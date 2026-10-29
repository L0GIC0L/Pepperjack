package io.github.l0gic0l.pepperjack.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.l0gic0l.pepperjack.AppContainer
import io.github.l0gic0l.pepperjack.model.AnalysisConfig
import io.github.l0gic0l.pepperjack.model.Circuit
import io.github.l0gic0l.pepperjack.model.CircuitKind
import io.github.l0gic0l.pepperjack.model.GridPoint
import io.github.l0gic0l.pepperjack.model.Part
import io.github.l0gic0l.pepperjack.model.PartKind
import io.github.l0gic0l.pepperjack.model.PartLibrary
import io.github.l0gic0l.pepperjack.model.Probe
import io.github.l0gic0l.pepperjack.model.ProbeType
import io.github.l0gic0l.pepperjack.model.Schematic
import io.github.l0gic0l.pepperjack.model.SpiceDialect
import io.github.l0gic0l.pepperjack.model.Wire
import io.github.l0gic0l.pepperjack.model.pinPositions
import io.github.l0gic0l.pepperjack.sim.PlotKind
import io.github.l0gic0l.pepperjack.sim.Quantity
import io.github.l0gic0l.pepperjack.sim.SimulationResult
import io.github.l0gic0l.pepperjack.spice.Connectivity
import io.github.l0gic0l.pepperjack.spice.Netlister
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Tool { SELECT, WIRE, PROBE, DELETE }

data class EditorState(
    val loaded: Boolean = false,
    val circuit: Circuit? = null,
    val tool: Tool = Tool.SELECT,
    val placing: PartKind? = null,
    val placingRotation: Int = 0,
    val placingMirrored: Boolean = false,
    val selectedPartId: Long? = null,
    val selectedWireId: Long? = null,
    /** Start of the wire being drawn with taps (LTspice-style click, click). */
    val wireStart: GridPoint? = null,
    /** Part whose properties sheet is open. */
    val editingPartId: Long? = null,
    val running: Boolean = false,
    val result: SimulationResult? = null,
    /** Visible traces per analysis, by ngspice vector name. */
    val traces: Map<PlotKind, Set<String>> = emptyMap(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
) {
    val selectedPart: Part? get() = circuit?.schematic?.parts?.firstOrNull { it.id == selectedPartId }
}

class EditorViewModel(private val container: AppContainer, private val circuitId: String) : ViewModel() {
    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()
    val progress: StateFlow<String?> = container.engine.progress

    private val undoStack = ArrayDeque<Schematic>()
    private val redoStack = ArrayDeque<Schematic>()
    private var saveJob: Job? = null
    private var runJob: Job? = null

    init {
        viewModelScope.launch {
            _state.update { it.copy(loaded = true, circuit = container.circuits.get(circuitId)) }
        }
    }

    // ---- persistence ----

    private fun mutate(recordUndo: Boolean, transform: (Circuit) -> Circuit) {
        val current = _state.value.circuit ?: return
        val next = transform(current)
        if (next == current) return
        if (recordUndo && next.schematic != current.schematic) {
            undoStack.addLast(current.schematic)
            if (undoStack.size > 100) undoStack.removeFirst()
            redoStack.clear()
        }
        _state.update { it.copy(circuit = next, canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty()) }
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
        it.copy(tool = tool, placing = null, wireStart = null, selectedPartId = null, selectedWireId = null)
    }

    fun startPlacing(kind: PartKind) = _state.update {
        it.copy(placing = kind, placingRotation = 0, placingMirrored = false, tool = Tool.SELECT, selectedPartId = null, selectedWireId = null)
    }

    fun stopPlacing() = _state.update { it.copy(placing = null) }
    fun rotatePlacing() = _state.update { it.copy(placingRotation = (it.placingRotation + 1) % 4) }
    fun mirrorPlacing() = _state.update { it.copy(placingMirrored = !it.placingMirrored) }

    fun openEditor(partId: Long?) = _state.update { it.copy(editingPartId = partId) }

    fun tap(point: GridPoint, hitPart: Part?, hitWire: Wire?) {
        val state = _state.value
        val schematic = state.circuit?.schematic ?: return
        when {
            state.placing != null -> place(state.placing, point)
            state.tool == Tool.SELECT -> _state.update {
                it.copy(selectedPartId = hitPart?.id, selectedWireId = if (hitPart == null) hitWire?.id else null)
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

    private fun place(kind: PartKind, point: GridPoint) {
        val state = _state.value
        val schematic = state.circuit?.schematic ?: return
        val def = PartLibrary[kind]
        val part = Part(
            id = schematic.nextId,
            kind = kind,
            ref = nextReference(schematic, kind),
            x = point.x,
            y = point.y,
            rotation = state.placingRotation,
            mirrored = state.placingMirrored,
            value = if (kind == PartKind.LABEL) nextLabel(schematic) else def.defaultValue,
        )
        edit { it.copy(parts = it.parts + part) }
        if (kind == PartKind.LABEL) _state.update { it.copy(placing = null, editingPartId = part.id, selectedPartId = part.id) }
    }

    private fun nextReference(schematic: Schematic, kind: PartKind): String {
        val prefix = PartLibrary[kind].refPrefix
        if (kind == PartKind.GROUND) return prefix
        val used = schematic.parts.filter { PartLibrary[it.kind].refPrefix == prefix }
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
    fun movePart(id: Long, dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        edit { s ->
            val part = s.parts.firstOrNull { it.id == id } ?: return@edit s
            val pins = part.pinPositions().toSet()
            fun shift(p: GridPoint) = if (p in pins) GridPoint(p.x + dx, p.y + dy) else p
            s.copy(
                parts = s.parts.map { if (it.id == id) it.copy(x = it.x + dx, y = it.y + dy) else it },
                wires = s.wires.map { w ->
                    val a = shift(w.start)
                    val b = shift(w.end)
                    w.copy(x1 = a.x, y1 = a.y, x2 = b.x, y2 = b.y)
                },
                probes = s.probes.map { p ->
                    val moved = shift(GridPoint(p.x, p.y))
                    if (p.type == ProbeType.VOLTAGE) p.copy(x = moved.x, y = moved.y) else p
                },
            )
        }
    }

    fun rotateSelected() = updateSelected { it.copy(rotation = (it.rotation + 1) % 4) }
    fun mirrorSelected() = updateSelected { it.copy(mirrored = !it.mirrored) }

    private fun updateSelected(transform: (Part) -> Part) {
        val id = _state.value.selectedPartId ?: return
        edit { s -> s.copy(parts = s.parts.map { if (it.id == id) transform(it) else it }) }
    }

    fun duplicateSelected() {
        val part = _state.value.selectedPart ?: return
        val schematic = _state.value.circuit?.schematic ?: return
        val copy = part.copy(
            id = schematic.nextId,
            ref = if (part.kind == PartKind.LABEL) part.ref else nextReference(schematic, part.kind),
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

    private fun toggleProbe(point: GridPoint, hitPart: Part?, hitWire: Wire?) {
        val schematic = _state.value.circuit?.schematic ?: return
        val pinHit = hitPart?.pinPositions()?.firstOrNull { it == point }
        if (hitPart != null && pinHit == null && Netlister.currentVector(hitPart) != null) {
            val existing = schematic.probes.firstOrNull { it.type == ProbeType.CURRENT && it.partId == hitPart.id }
            edit { s ->
                s.copy(probes = if (existing != null) s.probes - existing else s.probes + Probe(ProbeType.CURRENT, partId = hitPart.id))
            }
            return
        }
        val target = pinHit ?: point.takeIf { hitWire != null || schematic.parts.any { p -> point in p.pinPositions() } } ?: return
        val connectivity = Connectivity(schematic)
        val existing = schematic.probes.firstOrNull {
            it.type == ProbeType.VOLTAGE && connectivity.connected(GridPoint(it.x, it.y), target)
        }
        edit { s ->
            s.copy(probes = if (existing != null) s.probes - existing else s.probes + Probe(ProbeType.VOLTAGE, target.x, target.y))
        }
    }

    // ---- simulation ----

    fun run(defaultDialect: SpiceDialect) {
        val circuit = _state.value.circuit ?: return
        if (_state.value.running) return
        runJob = viewModelScope.launch {
            _state.update { it.copy(running = true) }
            try {
                val dialect = circuit.dialect ?: defaultDialect
                val result = if (circuit.kind == CircuitKind.SCHEMATIC) {
                    val netlist = Netlister.netlist(circuit)
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

    fun toggleTrace(kind: PlotKind, vector: String) = _state.update {
        val current = it.traces[kind].orEmpty()
        val next = if (vector in current) current - vector else current + vector
        it.copy(traces = it.traces + (kind to next))
    }

    private fun defaultTraces(result: SimulationResult, previous: Map<PlotKind, Set<String>>): Map<PlotKind, Set<String>> =
        result.plots.groupBy { it.kind }.mapValues { (kind, plots) ->
            val available = plots.flatMap { it.vectors }.map { it.name }.toSet()
            previous[kind]?.filter { it in available }?.toSet()?.takeIf { it.isNotEmpty() }?.let { return@mapValues it }
            val probed = result.probes.map { it.vector }.filter { it in available }
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
