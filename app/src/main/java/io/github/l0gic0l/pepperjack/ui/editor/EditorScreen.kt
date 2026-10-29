package io.github.l0gic0l.pepperjack.ui.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.l0gic0l.pepperjack.AppContainer
import io.github.l0gic0l.pepperjack.data.Settings
import io.github.l0gic0l.pepperjack.model.Circuit
import io.github.l0gic0l.pepperjack.model.CircuitKind
import io.github.l0gic0l.pepperjack.model.PartKind
import io.github.l0gic0l.pepperjack.model.PartLibrary
import io.github.l0gic0l.pepperjack.model.Probe
import io.github.l0gic0l.pepperjack.sim.Plot
import io.github.l0gic0l.pepperjack.sim.PlotKind
import io.github.l0gic0l.pepperjack.spice.NetlistResult
import io.github.l0gic0l.pepperjack.spice.Netlister
import io.github.l0gic0l.pepperjack.ui.common.writeText
import io.github.l0gic0l.pepperjack.ui.results.ResultsPane
import io.github.l0gic0l.pepperjack.ui.results.plotsToCsv
import io.github.l0gic0l.pepperjack.ui.live.LiveAnimator
import io.github.l0gic0l.pepperjack.ui.live.LiveBar
import io.github.l0gic0l.pepperjack.ui.live.LiveOverlay
import io.github.l0gic0l.pepperjack.ui.live.PotControl
import io.github.l0gic0l.pepperjack.ui.results.traceColor
import io.github.l0gic0l.pepperjack.ui.schematic.SchematicCanvas
import io.github.l0gic0l.pepperjack.ui.schematic.SchematicViewport
import io.github.l0gic0l.pepperjack.ui.theme.LocalCircuitColors
import kotlinx.coroutines.launch

private enum class EditorSheet { PARTS, ANALYSIS, DIRECTIVES, RENAME }
private enum class Pane(val label: String) { SCHEMATIC("Schematic"), NETLIST("Netlist"), RESULTS("Results") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(container: AppContainer, settings: Settings, circuitId: String, onBack: () -> Unit) {
    val viewModel: EditorViewModel = viewModel(key = circuitId) { EditorViewModel(container, circuitId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val circuit = state.circuit

    if (!state.loaded || circuit == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (!state.loaded) CircularProgressIndicator() else TextButton(onClick = onBack) { Text("Circuit not found — go back") }
        }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val viewport = remember { SchematicViewport() }
    var sheet by remember { mutableStateOf<EditorSheet?>(null) }
    val isSchematic = circuit.kind == CircuitKind.SCHEMATIC
    var compactPane by rememberSaveable { mutableStateOf(if (isSchematic) Pane.SCHEMATIC else Pane.NETLIST) }
    var editorPane by rememberSaveable { mutableStateOf(if (isSchematic) Pane.SCHEMATIC else Pane.NETLIST) }
    val layout = currentPaneLayout()
    val libraryFiles by container.library.files.collectAsStateWithLifecycle()
    val symbols by container.symbols.symbols.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        container.library.refresh()
        container.symbols.load()
    }
    val libraryIndex = remember(libraryFiles) { container.library.index() }
    val netlist: NetlistResult? = remember(circuit, libraryIndex) { if (isSchematic) Netlister.netlist(circuit, libraryIndex) else null }
    val run = { viewModel.run(settings.dialect) }

    // Only runs started with the Run button jump to the results; live updates stay on the schematic.
    LaunchedEffect(state.runCount) {
        if (state.runCount > 0 && layout == PaneLayout.COMPACT) compactPane = Pane.RESULTS
    }
    BackHandler(enabled = state.placing != null || state.wireStart != null || state.selectedPartId != null || state.selectedWireId != null) {
        when {
            state.placing != null -> viewModel.stopPlacing()
            state.wireStart != null -> viewModel.cancelWire()
            else -> viewModel.setTool(state.tool)
        }
    }

    var pendingCsv by remember { mutableStateOf<List<Plot>?>(null) }
    val netlistExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val text = netlist?.text ?: circuit.netlist
            runCatching { writeText(context, uri, text) }
                .onSuccess { snackbar.showSnackbar("Netlist exported") }
                .onFailure { snackbar.showSnackbar(it.message ?: "Export failed") }
        }
    }
    val csvExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val plots = pendingCsv
        pendingCsv = null
        if (uri != null && plots != null) scope.launch {
            runCatching { writeText(context, uri, plotsToCsv(plots)) }
                .onSuccess { snackbar.showSnackbar("Data exported") }
                .onFailure { snackbar.showSnackbar(it.message ?: "Export failed") }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.flush()
                        onBack()
                    }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                },
                title = {
                    Column(Modifier.clickable { sheet = EditorSheet.RENAME }) {
                        Text(circuit.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (circuit.dialect ?: settings.dialect).label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                actions = {
                    IconToggleButton(
                        checked = state.live,
                        onCheckedChange = { viewModel.setLive(it, settings.dialect) },
                        colors = IconButtonDefaults.iconToggleButtonColors(
                            checkedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            checkedContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                    ) {
                        Icon(Icons.Outlined.Bolt, contentDescription = if (state.live) "Stop live simulation" else "Live simulation")
                    }
                    IconButton(onClick = { sheet = EditorSheet.ANALYSIS }) {
                        Icon(Icons.Outlined.Tune, contentDescription = "Simulation settings")
                    }
                    if (state.running) {
                        FilledTonalIconButton(onClick = viewModel::stop) { Icon(Icons.Outlined.Stop, contentDescription = "Stop simulation") }
                    } else {
                        FilledIconButton(onClick = run) { Icon(Icons.Outlined.PlayArrow, contentDescription = "Run simulation") }
                    }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (isSchematic) {
                                DropdownMenuItem(
                                    text = { Text("SPICE directives") },
                                    leadingIcon = { Icon(Icons.Outlined.DataObject, null) },
                                    onClick = { menu = false; sheet = EditorSheet.DIRECTIVES },
                                )
                                DropdownMenuItem(
                                    text = { Text("Fit to screen") },
                                    leadingIcon = { Icon(Icons.Outlined.CenterFocusStrong, null) },
                                    onClick = { menu = false; viewport.requestFit() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Export netlist") },
                                leadingIcon = { Icon(Icons.Outlined.IosShare, null) },
                                onClick = { menu = false; netlistExport.launch("${circuit.name}.cir") },
                            )
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                onClick = { menu = false; sheet = EditorSheet.RENAME },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val probeColors = rememberProbeColors(circuit, netlist, state)
        val editorContent: @Composable (Pane) -> Unit = { pane ->
            when {
                pane == Pane.NETLIST && isSchematic -> NetlistViewer(netlist?.text ?: "", Modifier.fillMaxSize())
                pane == Pane.NETLIST -> NetlistEditor(circuit.netlist, viewModel::setNetlist, Modifier.fillMaxSize())
                else -> SchematicPane(
                    circuit = circuit,
                    state = state,
                    viewport = viewport,
                    showGrid = settings.showGrid,
                    probeColors = probeColors,
                    viewModel = viewModel,
                    onParts = { sheet = EditorSheet.PARTS },
                )
            }
        }
        val results: @Composable () -> Unit = {
            ResultsPane(
                result = state.result,
                running = state.running,
                progress = progress,
                traces = state.traces,
                onToggleTrace = viewModel::toggleTrace,
                onRun = run,
                onExport = { plots ->
                    pendingCsv = plots
                    csvExport.launch("${circuit.name}-${plots.first().name}.csv")
                },
            )
        }
        AdaptivePanes(
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            editor = {
                if (isSchematic) {
                    Column {
                        PaneTabs(listOf(Pane.SCHEMATIC, Pane.NETLIST), editorPane) { editorPane = it }
                        Box(Modifier.weight(1f)) { editorContent(editorPane) }
                    }
                } else {
                    editorContent(Pane.NETLIST)
                }
            },
            results = results,
            compact = {
                val panes = if (isSchematic) Pane.entries.toList() else listOf(Pane.NETLIST, Pane.RESULTS)
                Column {
                    PaneTabs(panes, compactPane) { compactPane = it }
                    Box(Modifier.weight(1f)) {
                        if (compactPane == Pane.RESULTS) results() else editorContent(compactPane)
                    }
                }
            },
        )
    }

    when (sheet) {
        EditorSheet.PARTS -> PartPickerSheet(symbols, onDismiss = { sheet = null }) { choice ->
            sheet = null
            if (layout == PaneLayout.COMPACT) compactPane = Pane.SCHEMATIC else editorPane = Pane.SCHEMATIC
            viewModel.startPlacing(choice)
        }
        EditorSheet.ANALYSIS -> AnalysisSheet(
            config = circuit.analysis,
            dialect = circuit.dialect,
            defaultDialect = settings.dialect,
            isSchematic = isSchematic,
            sourceNames = circuit.schematic.parts
                .filter { it.kind == PartKind.VOLTAGE || it.kind == PartKind.CURRENT }
                .map { Netlister.spiceName(it) },
            onDismiss = { sheet = null },
            onApply = { config, dialect ->
                sheet = null
                viewModel.setAnalysis(config)
                viewModel.setDialect(dialect)
            },
        )
        EditorSheet.DIRECTIVES -> DirectivesSheet(circuit.schematic.directives, onDismiss = { sheet = null }) {
            sheet = null
            viewModel.setDirectives(it)
        }
        EditorSheet.RENAME -> io.github.l0gic0l.pepperjack.ui.home.RenameDialog(
            initial = circuit.name,
            onDismiss = { sheet = null },
            onConfirm = {
                sheet = null
                viewModel.rename(it)
            },
        )
        null -> Unit
    }

    state.editingPartId?.let { id ->
        circuit.schematic.parts.firstOrNull { it.id == id }?.let { part ->
            PartEditorSheet(part, libraryFiles.flatMap { it.models }, onDismiss = { viewModel.openEditor(null) }) { ref, value ->
                viewModel.updatePart(part.id, ref, value)
                viewModel.openEditor(null)
            }
        }
    }
}

@Composable
private fun PaneTabs(panes: List<Pane>, selected: Pane, onSelect: (Pane) -> Unit) {
    PrimaryTabRow(selectedTabIndex = panes.indexOf(selected).coerceAtLeast(0)) {
        panes.forEach { pane ->
            Tab(selected = pane == selected, onClick = { onSelect(pane) }, text = { Text(pane.label) })
        }
    }
}

@Composable
private fun SchematicPane(
    circuit: Circuit,
    state: EditorState,
    viewport: SchematicViewport,
    showGrid: Boolean,
    probeColors: Map<Probe, Color>,
    viewModel: EditorViewModel,
    onParts: () -> Unit,
) {
    val liveView = state.liveView.takeIf { state.live }
    var lastAnimator by remember { mutableStateOf<LiveAnimator?>(null) }
    val animator = remember(liveView) { liveView?.let { LiveAnimator(it, lastAnimator) } }
    LaunchedEffect(animator) { if (animator != null) lastAnimator = animator }
    Box(Modifier.fillMaxSize()) {
        SchematicCanvas(
            schematic = circuit.schematic,
            state = state,
            viewport = viewport,
            showGrid = showGrid,
            probeColors = probeColors,
            onTap = viewModel::tap,
            onDoubleTap = { viewModel.openEditor(it.id) },
            onDragPart = { viewModel.select(it.id) },
            onMovePart = viewModel::movePart,
            onDrawWire = viewModel::addWire,
            modifier = Modifier.fillMaxSize(),
        )
        if (animator != null) {
            LiveOverlay(animator, circuit.schematic, viewport, state.liveColorMode, state.inspectWireId, Modifier.fillMaxSize())
        }
        val topBar = Modifier.align(Alignment.TopCenter).padding(top = 12.dp, start = 12.dp, end = 12.dp)
        val selected = state.selectedPart
        when {
            state.placing != null -> PlacingBar(
                state.placingSymbol?.title ?: PartLibrary[state.placing].title,
                onRotate = viewModel::rotatePlacing,
                onMirror = viewModel::mirrorPlacing,
                onDone = viewModel::stopPlacing,
                modifier = topBar,
            )
            selected != null -> SelectionBar(
                selected,
                onEdit = { viewModel.openEditor(selected.id) },
                onRotate = viewModel::rotateSelected,
                onMirror = viewModel::mirrorSelected,
                onDuplicate = viewModel::duplicateSelected,
                onDelete = viewModel::deleteSelected,
                modifier = topBar,
            )
            state.selectedWireId != null -> HintBar("Wire selected", topBar, "Delete", viewModel::deleteSelected)
            state.tool == Tool.WIRE -> HintBar(
                if (state.wireStart == null) "Drag to draw a wire, or tap two points" else "Tap the next point",
                topBar,
                if (state.wireStart != null) "Finish" else null,
                viewModel::cancelWire,
            )
            state.tool == Tool.PROBE -> HintBar("Tap a wire for its voltage or a part for its current", topBar)
            state.tool == Tool.DELETE -> HintBar("Tap a part or wire to delete it", topBar)
            state.live && circuit.schematic.parts.isNotEmpty() -> HintBar("Live: tap a switch to flip it, or a wire to read it", topBar)
            circuit.schematic.parts.isEmpty() -> HintBar("Tap + to place your first part", topBar)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp, start = 12.dp, end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.live) {
                selected?.takeIf { it.kind == PartKind.POT }?.let { pot ->
                    PotControl(pot, onPosition = { viewModel.setPotPosition(pot.id, it) })
                }
                LiveBar(animator, state.liveRunning, state.liveError, state.liveColorMode, viewModel::setLiveColorMode)
            }
            EditorToolbar(
                tool = state.tool,
                canUndo = state.canUndo,
                canRedo = state.canRedo,
                onTool = viewModel::setTool,
                onParts = onParts,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
            )
        }
    }
}

/** Probe markers take the color of their trace in the results, matching LTspice's behaviour. */
@Composable
private fun rememberProbeColors(circuit: Circuit, netlist: NetlistResult?, state: EditorState): Map<Probe, Color> {
    val palette = LocalCircuitColors.current.traces
    val outline = MaterialTheme.colorScheme.outline
    return remember(circuit.schematic.probes, netlist, state.result, state.traces, palette) {
        val probes = netlist?.probeVectors.orEmpty()
        val result = state.result
        val plot = result?.plots?.firstOrNull { it.kind != PlotKind.OP }
        if (plot == null) {
            probes.mapIndexed { i, p -> p.probe to palette[i % palette.size] }.toMap()
        } else {
            val selected = state.traces[plot.kind].orEmpty()
            val ordered = plot.vectors.filter { it.name in selected }
            probes.associate { p ->
                val index = ordered.indexOfFirst { it.name == p.vector }
                p.probe to if (index >= 0) traceColor(palette, index, ordered.size, 0) else outline
            }
        }
    }
}
