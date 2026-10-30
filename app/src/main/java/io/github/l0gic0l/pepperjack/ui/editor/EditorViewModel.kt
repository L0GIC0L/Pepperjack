package io.github.l0gic0l.pepperjack.ui.editor

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

enum class Tool { SELECT, WIRE, PROBE, DELETE }

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
    /** Start of the wire being drawn with taps (LTspice-style click, click). */
    val wireStart: GridPoint? = null,
    /** Part whose properties sheet is open. */
    val editingPartId: Long? = null,
    val running: Boolean = false,
    /** Counts runs started with the Run button, so the UI can switch to the results for those only. */
    val runCount: Int = 0,
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
        if (_state.value.live) scheduleLive()
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
                val wire = if (hitPart == null) hitWire?.id else null
                it.copy(selectedPartId = hitPart?.id, selectedWireId = wire, inspectWireId = if (it.live) wire else null)
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

    private fun place(kind: PartKind, symbol: CustomSymbol?, point: GridPoint) {
        val state = _state.value
        val schematic = state.circuit?.schematic ?: return
        val def = if (symbol != null) CustomSymbols.def(symbol) else PartLibrary[kind]
        val part = Part(
            id = schematic.nextId,
            kind = kind,
            ref = nextReference(schematic, def.refPrefix, kind),
            x = point.x,
            y = point.y,
            rotation = state.placingRotation,
            mirrored = state.placingMirrored,
            value = if (kind == PartKind.LABEL) nextLabel(schematic) else def.defaultValue,
            symbol = symbol,
        )
        edit { it.copy(parts = it.parts + part) }
        if (kind == PartKind.LABEL) _state.update { it.copy(placing = null, editingPartId = part.id, selectedPartId = part.id) }
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

    private fun toggleProbe(point: GridPoint, hitPart: Part?, hitWire: Wire?) {
        val schematic = _state.value.circuit?.schematic ?: return
        val pinHit = hitPart?.pinPositions()?.firstOrNull { it == point }
        if (hitPart != null && pinHit == null && Netlister.canProbeCurrent(hitPart)) {
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
                val result = container.engine.run(netlist.text, dialect, netlist.probeVectors, netlist.warnings)
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
        const val LIVE_DEBOUNCE_MS = 300L
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
