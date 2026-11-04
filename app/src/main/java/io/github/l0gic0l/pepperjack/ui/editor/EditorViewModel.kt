package io.github.l0gic0l.pepperjack.ui.editor

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.l0gic0l.pepperjack.AppContainer
import io.github.l0gic0l.pepperjack.model.AnalysisConfig
import io.github.l0gic0l.pepperjack.model.AnalysisType
import io.github.l0gic0l.pepperjack.model.Circuit
import io.github.l0gic0l.pepperjack.model.CircuitKind
import io.github.l0gic0l.pepperjack.model.CustomSymbol
import io.github.l0gic0l.pepperjack.model.CustomSymbols
import io.github.l0gic0l.pepperjack.model.GridPoint
import io.github.l0gic0l.pepperjack.model.MeterMode
import io.github.l0gic0l.pepperjack.model.Part
import io.github.l0gic0l.pepperjack.model.PartKind
import io.github.l0gic0l.pepperjack.model.PartLibrary
import io.github.l0gic0l.pepperjack.model.PotValue
import io.github.l0gic0l.pepperjack.model.Probe
import io.github.l0gic0l.pepperjack.model.ProbeType
import io.github.l0gic0l.pepperjack.model.Schematic
import io.github.l0gic0l.pepperjack.model.SpiceDialect
import io.github.l0gic0l.pepperjack.model.SwitchValue
import io.github.l0gic0l.pepperjack.model.Wire
import io.github.l0gic0l.pepperjack.model.bounds
import io.github.l0gic0l.pepperjack.model.def
import io.github.l0gic0l.pepperjack.model.pinPositions
import io.github.l0gic0l.pepperjack.sim.LiveView
import io.github.l0gic0l.pepperjack.sim.PlotKind
import io.github.l0gic0l.pepperjack.sim.Quantity
import io.github.l0gic0l.pepperjack.sim.SimulationResult
import io.github.l0gic0l.pepperjack.spice.Connectivity
import io.github.l0gic0l.pepperjack.spice.Netlister
import io.github.l0gic0l.pepperjack.ui.live.LiveColorMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tool { SELECT, AREA, WIRE, PROBE, DELETE }

data class EditorState(
    val loaded: Boolean = false,
    val circuit: Circuit? = null,
    val tool: Tool = Tool.SELECT,
    val placing: PartKind? = null,
    /** The symbol being placed when [placing] is [PartKind.CUSTOM]. */
    val placingSymbol: CustomSymbol? = null,
    val placingRotation: Int = 0,
    val placingMirrored: Boolean = false,
    val selectedPartId: Long? = null,
    val selectedWireId: Long? = null,
    /** Where on the selected wire it was tapped, snapped to the grid. */
    val selectedWirePoint: GridPoint? = null,
    /** A multi-selection, made with the area tool: these parts and wires move together. */
    val group: Set<Long> = emptySet(),
    val groupWires: Set<Long> = emptySet(),
    /** Start of the wire being drawn with taps (LTspice-style click, click). */
    val wireStart: GridPoint? = null,
    /** Part whose properties sheet is open. */
    val editingPartId: Long? = null,
    val running: Boolean = false,
    /** Counts runs started with the Run button, so the UI can switch to the results for those only. */
    val runCount: Int = 0,
    /** Bumped when a new trace should be looked at in the results. */
    val resultsRequests: Int = 0,
    val result: SimulationResult? = null,
    /** Visible traces per analysis, by ngspice vector name. */
    val traces: Map<PlotKind, Set<String>> = emptyMap(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Live simulation: re-simulate on every edit and draw voltages and currents on the schematic. */
    val live: Boolean = false,
    val liveRunning: Boolean = false,
    val liveView: LiveView? = null,
    val liveError: String? = null,
    val liveColorMode: LiveColorMode = LiveColorMode.VOLTAGE,
    /** Wire whose voltage and current the live view labels. */
    val inspectWireId: Long? = null,
) {
    val selectedPart: Part? get() = circuit?.schematic?.parts?.firstOrNull { it.id == selectedPartId }
    val hasGroup: Boolean get() = group.isNotEmpty() || groupWires.isNotEmpty()
}

class EditorViewModel(private val container: AppContainer, private val circuitId: String) : ViewModel() {
    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()
    val progress: StateFlow<String?> = container.engine.progress

    private val undoStack = ArrayDeque<Schematic>()
    private val redoStack = ArrayDeque<Schematic>()
    private var saveJob: Job? = null
    private var runJob: Job? = null
    private var liveJob: Job? = null
    private var liveGeneration = 0
    private var liveDialect = SpiceDialect.Default

    init {
        viewModelScope.launch {
            // Voltage probes from older files become probe parts, which can be selected and moved.
            val circuit = container.circuits.get(circuitId)?.let { it.copy(schematic = it.schematic.withProbeParts()) }
            _state.update { it.copy(loaded = true, circuit = circuit) }
        }
    }

    // ---- persistence ----

    private fun mutate(recordUndo: Boolean, resimulate: Boolean = true, transform: (Circuit) -> Circuit) {
        val current = _state.value.circuit ?: return
        val next = transform(current)
        if (next == current) return
        if (recordUndo && next.schematic != current.schematic) {
            undoStack.addLast(current.schematic)
            if (undoStack.size > 100) undoStack.removeFirst()
            redoStack.clear()
        }
        _state.update { it.copy(circuit = next, canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty()) }
        if (_state.value.live && resimulate) scheduleLive()
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(500)
            container.circuits.save(next)
        }
    }

    private fun edit(transform: (Schematic) -> Schematic) = mutate(true) { it.copy(schematic = transform(it.schematic)) }

    fun flush() {
        val circuit = _state.value.circuit ?: return
        saveJob?.cancel()
        container.appScope.launch { container.circuits.save(circuit) }
    }

    override fun onCleared() {
        flush()
    }

    fun undo() {
        val circuit = _state.value.circuit ?: return
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(circuit.schematic)
        mutate(false) { it.copy(schematic = previous) }
        _state.update { it.copy(selectedPartId = null, selectedWireId = null) }
    }

    fun redo() {
        val circuit = _state.value.circuit ?: return
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(circuit.schematic)
        mutate(false) { it.copy(schematic = next) }
    }

    fun rename(name: String) = mutate(false) { it.copy(name = name) }
    fun setNetlist(text: String) = mutate(false) { it.copy(netlist = text) }
    fun setDirectives(text: String) = mutate(false) { it.copy(schematic = it.schematic.copy(directives = text)) }
    fun setAnalysis(config: AnalysisConfig) = mutate(false) { it.copy(analysis = config) }
    fun setDialect(dialect: SpiceDialect?) = mutate(false) { it.copy(dialect = dialect) }

    // ---- tools ----

    fun setTool(tool: Tool) = _state.update {
        it.copy(
            tool = tool, placing = null, wireStart = null, selectedPartId = null, selectedWireId = null,
            group = emptySet(), groupWires = emptySet(),
        )
    }

    fun startPlacing(choice: PartChoice) = _state.update {
        it.copy(
            placing = choice.kind, placingSymbol = choice.symbol, placingRotation = 0, placingMirrored = false,
            tool = Tool.SELECT, selectedPartId = null, selectedWireId = null,
        )
    }

    fun stopPlacing() = _state.update { it.copy(placing = null, placingSymbol = null) }
    fun rotatePlacing() = _state.update { it.copy(placingRotation = (it.placingRotation + 1) % 4) }
    fun mirrorPlacing() = _state.update { it.copy(placingMirrored = !it.placingMirrored) }

    fun openEditor(partId: Long?) = _state.update { it.copy(editingPartId = partId) }

    fun tap(point: GridPoint, hitPart: Part?, hitWire: Wire?) {
        val state = _state.value
        val schematic = state.circuit?.schematic ?: return
        when {
            state.placing != null -> place(state.placing, state.placingSymbol, point)
            // While live, tapping a switch flips it, like the real thing.
            state.live && state.tool == Tool.SELECT && hitPart != null &&
                (hitPart.kind == PartKind.SPST || hitPart.kind == PartKind.SPDT) -> toggleSwitch(hitPart)
            state.tool == Tool.SELECT -> _state.update {
                val wire = if (hitPart == null) hitWire else null
                it.copy(
                    selectedPartId = hitPart?.id, selectedWireId = wire?.id, selectedWirePoint = wire?.nearestGridPoint(point),
                    inspectWireId = if (it.live) wire?.id else null, group = emptySet(), groupWires = emptySet(),
                )
            }
            // The area tool adds and removes single items from the selection with a tap.
            state.tool == Tool.AREA -> _state.update {
                when {
                    hitPart != null -> it.copy(group = if (hitPart.id in it.group) it.group - hitPart.id else it.group + hitPart.id)
                    hitWire != null -> it.copy(groupWires = if (hitWire.id in it.groupWires) it.groupWires - hitWire.id else it.groupWires + hitWire.id)
                    else -> it.copy(group = emptySet(), groupWires = emptySet())
                }
            }
            state.tool == Tool.WIRE -> {
                val start = state.wireStart
                if (start == null) {
                    _state.update { it.copy(wireStart = point) }
                } else {
                    addWire(start, point)
                    // Finish the chain when it lands on something it can connect to.
                    val endsOnConnection = schematic.parts.any { part -> point in part.pinPositions() } ||
                        schematic.wires.any { it.start == point || it.end == point }
                    _state.update { it.copy(wireStart = if (endsOnConnection || point == start) null else point) }
                }
            }
            state.tool == Tool.PROBE -> toggleProbe(point, hitPart, hitWire)
            state.tool == Tool.DELETE -> when {
                hitPart != null -> deletePart(hitPart.id)
                hitWire != null -> edit { s -> s.copy(wires = s.wires.filter { it.id != hitWire.id }) }
            }
        }
    }

    fun cancelWire() = _state.update { it.copy(wireStart = null) }

    fun select(partId: Long) = _state.update { it.copy(selectedPartId = partId, selectedWireId = null, inspectWireId = null) }

    fun toggleSwitch(part: Part) {
        val value = if (part.kind == PartKind.SPST) SwitchValue.toggleSpst(part.value) else SwitchValue.toggleSpdt(part.value)
        edit { s -> s.copy(parts = s.parts.map { if (it.id == part.id) it.copy(value = value) else it }) }
    }

    /** Turns a potentiometer's knob; not recorded for undo, like adjusting a real one. */
    fun setPotPosition(id: Long, position: Double) = mutate(false) { c ->
        c.copy(
            schematic = c.schematic.copy(
                parts = c.schematic.parts.map { if (it.id == id) it.copy(value = PotValue.parse(it.value).copy(position = position).format()) else it },
            ),
        )
    }

    /** Turns a part's value knob while live; the first value of a turn records one undo step. */
    fun tweakValue(id: Long, value: String, record: Boolean) = mutate(record) { c ->
        c.copy(schematic = c.schematic.copy(parts = c.schematic.parts.map { if (it.id == id) it.copy(value = value) else it }))
    }

    /** Switches a meter between reading the value now and a measurement over the run; no re-simulation needed. */
    fun setMeterMode(id: Long, mode: MeterMode) = mutate(recordUndo = true, resimulate = false) { c ->
        c.copy(schematic = c.schematic.copy(parts = c.schematic.parts.map { if (it.id == id) it.copy(meter = mode) else it }))
    }

    /** Places a part dragged in from the parts tray, and selects it. */
    fun dropPart(choice: PartChoice, point: GridPoint) {
        val part = place(choice.kind, choice.symbol, point, rotation = 0, mirrored = false) ?: return
        if (part.kind != PartKind.LABEL) _state.update { it.copy(tool = Tool.SELECT, selectedPartId = part.id, selectedWireId = null) }
    }

    private fun place(
        kind: PartKind,
        symbol: CustomSymbol?,
        point: GridPoint,
        rotation: Int = _state.value.placingRotation,
        mirrored: Boolean = _state.value.placingMirrored,
    ): Part? {
        val schematic = _state.value.circuit?.schematic ?: return null
        val def = if (symbol != null) CustomSymbols.def(symbol) else PartLibrary[kind]
        val part = Part(
            id = schematic.nextId,
            kind = kind,
            ref = nextReference(schematic, def.refPrefix, kind),
            x = point.x,
            y = point.y,
            // Text stays upright, so a text box doesn't turn.
            rotation = if (kind == PartKind.TEXT) 0 else rotation,
            mirrored = mirrored && kind != PartKind.TEXT,
            value = if (kind == PartKind.LABEL) nextLabel(schematic) else def.defaultValue,
            symbol = symbol,
        )
        edit { it.copy(parts = it.parts + part) }
        // A label or text box is placed for its words: ask for them straight away.
        if (kind == PartKind.LABEL || kind == PartKind.TEXT) _state.update { it.copy(placing = null, editingPartId = part.id, selectedPartId = part.id) }
        return part
    }

    private fun nextReference(schematic: Schematic, prefix: String, kind: PartKind): String {
        if (kind == PartKind.GROUND) return prefix
        val used = schematic.parts.filter { it.def.refPrefix == prefix }
            .mapNotNull { it.ref.removePrefix(prefix).toIntOrNull() }
        return prefix + ((used.maxOrNull() ?: 0) + 1)
    }

    private fun nextLabel(schematic: Schematic): String {
        val used = schematic.parts.filter { it.kind == PartKind.LABEL }.map { it.value }.toSet()
        if ("out" !in used) return "out"
        return generateSequence(1) { it + 1 }.map { "net$it" }.first { it !in used }
    }

    /** Draws an L-shaped connection: horizontal first, then vertical. */
    fun addWire(from: GridPoint, to: GridPoint) {
        if (from == to) return
        edit { s ->
            var id = s.nextId
            val segments = buildList {
                val corner = GridPoint(to.x, from.y)
                if (corner != from) add(Wire(id++, from.x, from.y, corner.x, corner.y))
                if (corner != to) add(Wire(id++, corner.x, corner.y, to.x, to.y))
            }.filterNot { new -> s.wires.any { it.sameAs(new) } }
            s.copy(wires = s.wires + segments)
        }
    }

    private fun Wire.sameAs(other: Wire) =
        (start == other.start && end == other.end) || (start == other.end && end == other.start)

    /** Moves a part by whole grid steps, dragging attached wire ends along (rubber-banding). */
    fun movePart(id: Long, dx: Int, dy: Int) = moveItems(setOf(id), emptySet(), dx, dy)

    /** Moves the multi-selection by whole grid steps. */
    fun moveGroup(dx: Int, dy: Int) {
        val state = _state.value
        moveItems(state.group, state.groupWires, dx, dy)
    }

    /**
     * Moves [partIds] and [wireIds] together. Wire ends attached to a moved pin or a moved
     * wire's end come along (rubber-banding), and so do probes sitting on them; a probe moved
     * on its own leaves the wires where they are.
     */
    private fun moveItems(partIds: Set<Long>, wireIds: Set<Long>, dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        edit { s ->
            val map = movingPoints(s, partIds, wireIds).associateWith { GridPoint(it.x + dx, it.y + dy) }
            s.moved(partIds, wireIds, map) { it.copy(x = it.x + dx, y = it.y + dy) }
        }
    }

    private fun movingPoints(s: Schematic, partIds: Set<Long>, wireIds: Set<Long>): Set<GridPoint> = buildSet {
        s.parts.filter { it.id in partIds && it.kind != PartKind.PROBE }.forEach { addAll(it.pinPositions()) }
        s.wires.filter { it.id in wireIds }.forEach { add(it.start); add(it.end) }
    }

    /** Applies [transform] to [partIds], moves [wireIds] and wire ends by [map], and keeps probes on their points. */
    private fun Schematic.moved(partIds: Set<Long>, wireIds: Set<Long>, map: Map<GridPoint, GridPoint>, transform: (Part) -> Part): Schematic {
        fun at(p: GridPoint) = map[p] ?: p
        return copy(
            parts = parts.map { p ->
                when {
                    p.id in partIds -> transform(p)
                    p.kind == PartKind.PROBE && p.origin in map -> at(p.origin).let { q -> p.copy(x = q.x, y = q.y) }
                    else -> p
                }
            },
            wires = wires.map { w ->
                val a = at(w.start)
                val b = at(w.end)
                w.copy(x1 = a.x, y1 = a.y, x2 = b.x, y2 = b.y)
            },
            probes = probes.map { p ->
                if (p.type == ProbeType.VOLTAGE) at(GridPoint(p.x, p.y)).let { q -> p.copy(x = q.x, y = q.y) } else p
            },
        )
    }

    /** Selects every part whose middle and every wire whose ends lie in the box from [a] to [b]. */
    fun selectArea(a: GridPoint, b: GridPoint) {
        val schematic = _state.value.circuit?.schematic ?: return
        val xs = minOf(a.x, b.x)..maxOf(a.x, b.x)
        val ys = minOf(a.y, b.y)..maxOf(a.y, b.y)
        fun inside(x: Float, y: Float) = x >= xs.first && x <= xs.last && y >= ys.first && y <= ys.last
        val parts = schematic.parts.filter { p -> p.bounds().let { inside((it[0] + it[2]) / 2, (it[1] + it[3]) / 2) } }.map { it.id }
        val wires = schematic.wires.filter { w -> inside(w.x1.toFloat(), w.y1.toFloat()) && inside(w.x2.toFloat(), w.y2.toFloat()) }.map { it.id }
        _state.update { it.copy(group = parts.toSet(), groupWires = wires.toSet(), selectedPartId = null, selectedWireId = null) }
    }

    fun clearGroup() = _state.update { it.copy(group = emptySet(), groupWires = emptySet()) }

    /** Turns the multi-selection a quarter turn clockwise about its middle. */
    fun rotateGroup() {
        val state = _state.value
        val schematic = state.circuit?.schematic ?: return
        val parts = schematic.parts.filter { it.id in state.group }
        val wires = schematic.wires.filter { it.id in state.groupWires }
        val points = parts.map { it.origin } + wires.flatMap { listOf(it.start, it.end) }
        if (points.isEmpty()) return
        val c = GridPoint((points.minOf { it.x } + points.maxOf { it.x }) / 2, (points.minOf { it.y } + points.maxOf { it.y }) / 2)
        fun turn(p: GridPoint) = GridPoint(c.x - (p.y - c.y), c.y + (p.x - c.x))
        fun turned(p: Part) = turn(p.origin).let { o -> p.copy(x = o.x, y = o.y, rotation = if (p.kind == PartKind.TEXT) 0 else (p.rotation + 1) % 4) }
        val map = HashMap<GridPoint, GridPoint>()
        parts.filter { it.kind != PartKind.PROBE }.forEach { p -> p.pinPositions().zip(turned(p).pinPositions()).forEach { (from, to) -> map[from] = to } }
        wires.forEach { w -> map[w.start] = turn(w.start); map[w.end] = turn(w.end) }
        edit { s -> s.moved(state.group, state.groupWires, map, ::turned) }
    }

    /** Copies the multi-selection beside itself and selects the copy, ready to drag into place. */
    fun duplicateGroup() {
        val state = _state.value
        var schematic = state.circuit?.schematic ?: return
        val parts = schematic.parts.filter { it.id in state.group }
        val wires = schematic.wires.filter { it.id in state.groupWires }
        val xs = parts.flatMap { it.bounds().let { b -> listOf(b[0], b[2]) } } + wires.flatMap { listOf(it.x1.toFloat(), it.x2.toFloat()) }
        if (xs.isEmpty()) return
        val dx = (xs.max() - xs.min()).toInt() + 4
        val newParts = ArrayList<Part>()
        val newWires = ArrayList<Wire>()
        var id = schematic.nextId
        for (part in parts) {
            val ref = when (part.kind) {
                PartKind.LABEL, PartKind.GROUND -> part.ref
                else -> nextReference(schematic, part.def.refPrefix, part.kind)
            }
            val copy = part.copy(id = id++, ref = ref, x = part.x + dx)
            newParts += copy
            schematic = schematic.copy(parts = schematic.parts + copy)
        }
        wires.forEach { newWires += it.copy(id = id++, x1 = it.x1 + dx, x2 = it.x2 + dx) }
        edit { it.copy(parts = it.parts + newParts, wires = it.wires + newWires) }
        _state.update { it.copy(group = newParts.map { p -> p.id }.toSet(), groupWires = newWires.map { w -> w.id }.toSet()) }
    }

    fun deleteGroup() {
        val state = _state.value
        edit { s ->
            s.copy(
                parts = s.parts.filter { it.id !in state.group },
                wires = s.wires.filter { it.id !in state.groupWires },
                probes = s.probes.filterNot { it.type == ProbeType.CURRENT && it.partId in state.group },
            )
        }
        clearGroup()
    }

    fun rotateSelected() = updateSelected { if (it.kind == PartKind.TEXT) it else it.copy(rotation = (it.rotation + 1) % 4) }
    fun mirrorSelected() = updateSelected { if (it.kind == PartKind.TEXT) it else it.copy(mirrored = !it.mirrored) }

    private fun updateSelected(transform: (Part) -> Part) {
        val id = _state.value.selectedPartId ?: return
        edit { s -> s.copy(parts = s.parts.map { if (it.id == id) transform(it) else it }) }
    }

    fun duplicateSelected() {
        val part = _state.value.selectedPart ?: return
        val schematic = _state.value.circuit?.schematic ?: return
        val copy = part.copy(
            id = schematic.nextId,
            ref = if (part.kind == PartKind.LABEL) part.ref else nextReference(schematic, part.def.refPrefix, part.kind),
            x = part.x + 2,
            y = part.y + 2,
        )
        edit { it.copy(parts = it.parts + copy) }
        _state.update { it.copy(selectedPartId = copy.id) }
    }

    fun deleteSelected() {
        val state = _state.value
        state.selectedPartId?.let { deletePart(it) }
        state.selectedWireId?.let { id -> edit { s -> s.copy(wires = s.wires.filter { it.id != id }) } }
        _state.update { it.copy(selectedPartId = null, selectedWireId = null) }
    }

    private fun deletePart(id: Long) = edit { s ->
        s.copy(parts = s.parts.filter { it.id != id }, probes = s.probes.filterNot { it.type == ProbeType.CURRENT && it.partId == id })
    }

    fun updatePart(id: Long, ref: String, value: String) = edit { s ->
        s.copy(parts = s.parts.map { if (it.id == id) it.copy(ref = ref.trim().ifEmpty { it.ref }, value = value.trim()) else it })
    }

    /** Adds or removes the current probe on [partId]. */
    fun toggleCurrentProbe(partId: Long) = edit { s ->
        val existing = s.probes.firstOrNull { it.type == ProbeType.CURRENT && it.partId == partId }
        s.copy(probes = if (existing != null) s.probes - existing else s.probes + Probe(ProbeType.CURRENT, partId = partId))
    }

    /** Removes a probe: a probe part is deleted, a listed probe dropped. */
    fun removeProbe(probe: Probe) {
        val part = _state.value.circuit?.schematic?.probePart(probe)
        if (part != null) deletePart(part.id) else edit { s -> s.copy(probes = s.probes - probe) }
    }

    /**
     * Puts a probe on the tapped wire and shows its waveform: while live it joins the scope;
     * otherwise its trace is added to the results, simulating first if there are none.
     */
    fun plotSelectedWire(defaultDialect: SpiceDialect) {
        val state = _state.value
        val schematic = state.circuit?.schematic ?: return
        val wire = schematic.wires.firstOrNull { it.id == state.selectedWireId } ?: return
        val point = state.selectedWirePoint ?: wire.start
        val probe = schematic.parts.firstOrNull { it.kind == PartKind.PROBE && it.origin == point }
            ?: place(PartKind.PROBE, null, point, rotation = 0, mirrored = false)
            ?: return
        _state.update { it.copy(selectedWireId = null, selectedPartId = probe.id, inspectWireId = null) }
        if (!state.live) showProbeTrace(probe, defaultDialect)
    }

    private fun showProbeTrace(probe: Part, defaultDialect: SpiceDialect) {
        val circuit = _state.value.circuit ?: return
        val vector = Netlister.netlist(circuit, container.library.index()).probeVectors
            .firstOrNull { it.probe == Probe(ProbeType.VOLTAGE, probe.x, probe.y) }?.vector
        val plot = _state.value.result?.plots?.firstOrNull { p -> p.kind != PlotKind.OP && p.vectors.any { it.name.equals(vector, true) } }
        val name = plot?.vectors?.firstOrNull { it.name.equals(vector, true) }?.name
        if (plot != null && name != null) {
            _state.update { it.copy(traces = it.traces + (plot.kind to it.traces[plot.kind].orEmpty() + name), resultsRequests = it.resultsRequests + 1) }
        } else {
            run(defaultDialect)
        }
    }

    /**
     * The probe tool: tapping a part's body probes its current; tapping a wire or a pin puts a
     * measurement probe there; tapping a probe removes it.
     */
    private fun toggleProbe(point: GridPoint, hitPart: Part?, hitWire: Wire?) {
        val schematic = _state.value.circuit?.schematic ?: return
        if (hitPart?.kind == PartKind.PROBE) {
            deletePart(hitPart.id)
            return
        }
        val pinHit = hitPart?.pinPositions()?.firstOrNull { it == point }
        if (hitPart != null && pinHit == null && Netlister.canProbeCurrent(hitPart)) {
            toggleCurrentProbe(hitPart.id)
            return
        }
        val target = pinHit ?: hitWire?.nearestGridPoint(point) ?: point.takeIf { schematic.parts.any { p -> point in p.pinPositions() } } ?: return
        val existing = schematic.parts.firstOrNull { it.kind == PartKind.PROBE && it.origin == target }
        if (existing != null) deletePart(existing.id) else place(PartKind.PROBE, null, target, rotation = 0, mirrored = false)
    }

    // ---- simulation ----

    fun run(defaultDialect: SpiceDialect) {
        val circuit = _state.value.circuit ?: return
        if (_state.value.running) return
        runJob = viewModelScope.launch {
            _state.update { it.copy(running = true, runCount = it.runCount + 1) }
            try {
                val dialect = circuit.dialect ?: defaultDialect
                val result = if (circuit.kind == CircuitKind.SCHEMATIC) {
                    val netlist = Netlister.netlist(circuit, container.library.index())
                    container.engine.run(netlist.text, dialect, netlist.probeVectors, netlist.warnings)
                } else {
                    container.engine.run(circuit.netlist, dialect)
                }
                _state.update { it.copy(result = result, traces = defaultTraces(result, it.traces)) }
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    fun stop() {
        runJob?.cancel()
    }

    // ---- live simulation ----

    fun setLive(enabled: Boolean, defaultDialect: SpiceDialect) {
        liveDialect = defaultDialect
        _state.update {
            it.copy(live = enabled, liveView = if (enabled) it.liveView else null, liveError = null, inspectWireId = null, liveRunning = false)
        }
        if (enabled) scheduleLive(immediate = true) else liveJob?.cancel()
    }

    fun setLiveColorMode(mode: LiveColorMode) = _state.update { it.copy(liveColorMode = mode) }

    private val stepDirective = Regex("""(?im)^\s*\.step\b""")

    /** Re-simulates shortly after the last edit; a newer edit cancels a run still in progress. */
    private fun scheduleLive(immediate: Boolean = false) {
        liveJob?.cancel()
        val generation = ++liveGeneration
        liveJob = viewModelScope.launch {
            if (!immediate) delay(LIVE_DEBOUNCE_MS)
            val circuit = _state.value.circuit ?: return@launch
            _state.update { it.copy(liveRunning = true) }
            try {
                val dialect = circuit.dialect ?: liveDialect
                if (circuit.kind == CircuitKind.NETLIST) {
                    val result = container.engine.run(circuit.netlist, dialect)
                    _state.update { it.copy(result = result, traces = defaultTraces(result, it.traces), liveError = result.error) }
                    return@launch
                }
                val netlist = Netlister.netlist(circuit, container.library.index(), live = true)
                Log.i(TAG, "Live run of ${circuit.name}: ${netlist.text.length} characters")
                val result = container.engine.run(netlist.text, dialect, netlist.probeVectors, netlist.warnings)
                Log.i(
                    TAG,
                    "Live run of ${circuit.name} took ${result.elapsedMs} ms, native heap " +
                        "${android.os.Debug.getNativeHeapAllocatedSize() shr 20} MB" + (result.error?.let { ": $it" } ?: ""),
                )
                val view = if (result.succeeded) withContext(Dispatchers.Default) { LiveView.build(circuit.schematic, netlist, result) } else null
                // The live run doubles as the normal one when it is the same analysis.
                val publish = result.succeeded && !stepDirective.containsMatchIn(circuit.schematic.directives) &&
                    (netlist.liveAnalysis == AnalysisType.TRAN || circuit.analysis.type == AnalysisType.OP)
                _state.update {
                    it.copy(
                        liveView = view,
                        liveError = result.error ?: if (view == null) "Nothing to show yet: add parts and wires." else null,
                        result = if (publish) result else it.result,
                        traces = if (publish) defaultTraces(result, it.traces) else it.traces,
                    )
                }
            } finally {
                if (generation == liveGeneration) _state.update { it.copy(liveRunning = false) }
            }
        }
    }

    fun toggleTrace(kind: PlotKind, vector: String) = _state.update {
        val current = it.traces[kind].orEmpty()
        val next = if (vector in current) current - vector else current + vector
        it.copy(traces = it.traces + (kind to next))
    }

    private companion object {
        const val TAG = "Pepperjack"
        const val LIVE_DEBOUNCE_MS = 300L
    }

    private fun defaultTraces(result: SimulationResult, previous: Map<PlotKind, Set<String>>): Map<PlotKind, Set<String>> =
        result.plots.groupBy { it.kind }.mapValues { (kind, plots) ->
            val available = plots.flatMap { it.vectors }.map { it.name }.toSet()
            // Probes are always plotted, as in LTspice; the rest keeps what was picked before.
            val probed = result.probes.map { it.vector }.filter { it in available }
            previous[kind]?.filter { it in available }?.toSet()?.takeIf { it.isNotEmpty() }?.let { return@mapValues it + probed }
            if (probed.isNotEmpty()) return@mapValues probed.toSet()
            val vectors = plots.first().vectors.filterNot { it.isInternal }
            val voltages = vectors.filter { it.quantity == Quantity.VOLTAGE }
                .sortedBy { if (Regex("""n\d{3}""").matches(it.name)) 1 else 0 }
                .take(3)
            val currents = if (kind == PlotKind.DC || voltages.isEmpty()) {
                vectors.filter { it.quantity == Quantity.CURRENT }.take(1)
            } else {
                emptyList()
            }
            (voltages + currents).map { it.name }.toSet()
        }
}
