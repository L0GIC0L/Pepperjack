package io.github.l0gic0l.pepperjack.ui.home

import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChangeHistory
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ElectricalServices
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SettingsInputComponent
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material3.FilterChip
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.l0gic0l.pepperjack.data.ExampleCategory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Schema
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.l0gic0l.pepperjack.AppContainer
import io.github.l0gic0l.pepperjack.data.Examples
import io.github.l0gic0l.pepperjack.model.Circuit
import io.github.l0gic0l.pepperjack.model.CircuitKind
import io.github.l0gic0l.pepperjack.spice.Netlister
import io.github.l0gic0l.pepperjack.ui.common.clickableRow
import io.github.l0gic0l.pepperjack.ui.common.importNetlist
import io.github.l0gic0l.pepperjack.ui.common.writeText
import io.github.l0gic0l.pepperjack.ui.schematic.drawSchematicPreview
import io.github.l0gic0l.pepperjack.ui.theme.LocalCircuitColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CircuitsScreen(container: AppContainer, onOpenCircuit: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val circuits by container.circuits.circuits.collectAsStateWithLifecycle()
    var showNewSheet by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Circuit?>(null) }
    var deleting by remember { mutableStateOf<Circuit?>(null) }
    var exporting by remember { mutableStateOf<Circuit?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(Unit) { container.circuits.refresh() }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importNetlist(context, container, uri)
                .onSuccess(onOpenCircuit)
                .onFailure { Toast.makeText(context, it.message ?: "Import failed", Toast.LENGTH_LONG).show() }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val circuit = exporting
        exporting = null
        if (uri != null && circuit != null) scope.launch {
            runCatching { writeText(context, uri, netlistText(circuit)) }
                .onSuccess { Toast.makeText(context, "Netlist exported", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, it.message ?: "Export failed", Toast.LENGTH_LONG).show() }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Pepperjack") },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Outlined.UploadFile, contentDescription = "Import netlist")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewSheet = true },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("New circuit") },
            )
        },
    ) { padding ->
        if (circuits.isEmpty()) {
            EmptyCircuits(Modifier.padding(padding), onNew = { showNewSheet = true })
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 260.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(circuits, key = { it.id }) { circuit ->
                    CircuitCard(
                        circuit = circuit,
                        onOpen = { onOpenCircuit(circuit.id) },
                        onRename = { renaming = circuit },
                        onDuplicate = { scope.launch { container.circuits.duplicate(circuit) } },
                        onExport = {
                            exporting = circuit
                            exportLauncher.launch("${circuit.name}.cir")
                        },
                        onDelete = { deleting = circuit },
                    )
                }
            }
        }
    }

    if (showNewSheet) {
        NewCircuitSheet(
            onDismiss = { showNewSheet = false },
            onBlank = { kind ->
                showNewSheet = false
                scope.launch {
                    val name = if (kind == CircuitKind.SCHEMATIC) "Untitled schematic" else "Untitled netlist"
                    onOpenCircuit(container.circuits.create(name, kind).id)
                }
            },
            onImport = {
                showNewSheet = false
                importLauncher.launch(arrayOf("*/*"))
            },
            onExample = { index ->
                showNewSheet = false
                scope.launch { onOpenCircuit(container.circuits.save(Examples.all[index].build()).id) }
            },
        )
    }

    renaming?.let { circuit ->
        RenameDialog(
            initial = circuit.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                scope.launch { container.circuits.save(circuit.copy(name = name)) }
            },
        )
    }

    deleting?.let { circuit ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text("Delete “${circuit.name}”?") },
            text = { Text("This circuit will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { container.circuits.delete(circuit.id) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

internal fun netlistText(circuit: Circuit): String =
    if (circuit.kind == CircuitKind.SCHEMATIC) Netlister.netlist(circuit).text else circuit.netlist

@Composable
private fun EmptyCircuits(modifier: Modifier, onNew: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.DeveloperBoard,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("No circuits yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Draw a schematic, paste a SPICE netlist, or open one of the examples to see ngspice at work.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onNew) {
            Icon(Icons.Outlined.School, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Start from an example")
        }
    }
}

@Composable
private fun CircuitCard(
    circuit: Circuit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalCircuitColors.current
    Card(onClick = onOpen, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth().height(132.dp),
        ) {
            if (circuit.kind == CircuitKind.SCHEMATIC && circuit.schematic.parts.isNotEmpty()) {
                Canvas(Modifier.fillMaxSize().padding(8.dp)) { drawSchematicPreview(circuit.schematic, colors, null) }
            } else if (circuit.kind == CircuitKind.NETLIST) {
                Text(
                    circuit.netlist.lineSequence().take(8).joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Schema, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
            Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                Text(circuit.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val kind = if (circuit.kind == CircuitKind.SCHEMATIC) {
                    "Schematic · ${circuit.schematic.parts.size} parts"
                } else {
                    "Netlist · ${circuit.netlist.lines().size} lines"
                }
                Text(
                    "$kind · ${DateUtils.getRelativeTimeSpanString(circuit.modifiedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = { menu = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("Duplicate") },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                        onClick = { menu = false; onDuplicate() },
                    )
                    DropdownMenuItem(
                        text = { Text("Export netlist") },
                        leadingIcon = { Icon(Icons.Outlined.IosShare, null) },
                        onClick = { menu = false; onExport() },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

/** Test tag of the new-circuit sheet's list (blank circuits, import, examples). */
const val NEW_CIRCUIT_LIST = "new-circuit-list"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewCircuitSheet(
    onDismiss: () -> Unit,
    onBlank: (CircuitKind) -> Unit,
    onImport: () -> Unit,
    onExample: (Int) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<ExampleCategory?>(null) }
    var liveOnly by rememberSaveable { mutableStateOf(false) }
    val matches = remember(query, category, liveOnly) {
        val words = query.trim().lowercase().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        Examples.all.withIndex().filter { (_, e) ->
            (category == null || e.category == category) && (!liveOnly || e.live) &&
                words.all { w -> w in e.title.lowercase() || w in e.summary.lowercase() || w in e.category.title.lowercase() }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.testTag(NEW_CIRCUIT_LIST)) {
            item {
                ListItem(
                    headlineContent = { Text("Blank schematic") },
                    supportingContent = { Text("Place parts and wires, like Multisim or LTspice") },
                    leadingContent = { Icon(Icons.Outlined.Schema, null) },
                    modifier = Modifier.clickableRow { onBlank(CircuitKind.SCHEMATIC) },
                )
                ListItem(
                    headlineContent = { Text("Blank netlist") },
                    supportingContent = { Text("Type or paste SPICE directly") },
                    leadingContent = { Icon(Icons.AutoMirrored.Outlined.Notes, null) },
                    modifier = Modifier.clickableRow { onBlank(CircuitKind.NETLIST) },
                )
                ListItem(
                    headlineContent = { Text("Import a file") },
                    supportingContent = { Text("LTspice .asc schematics, or .cir, .net, .sp and .spice netlists") },
                    leadingContent = { Icon(Icons.Outlined.UploadFile, null) },
                    modifier = Modifier.clickableRow(onImport),
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    "Examples · ${Examples.all.size}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search examples, e.g. op-amp, 555, filter") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Clear, contentDescription = "Clear search") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = liveOnly,
                        onClick = { liveOnly = !liveOnly },
                        label = { Text("Live") },
                        leadingIcon = { Icon(Icons.Outlined.Bolt, null, Modifier.size(18.dp)) },
                    )
                    FilterChip(selected = category == null, onClick = { category = null }, label = { Text("All") })
                    ExampleCategory.entries.forEach { c ->
                        FilterChip(
                            selected = category == c,
                            onClick = { category = if (category == c) null else c },
                            label = { Text(c.title) },
                        )
                    }
                }
            }
            if (matches.isEmpty()) {
                item {
                    Text(
                        "No examples match.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            matches.groupBy { it.value.category }.forEach { (group, examples) ->
                item(key = "header-${group.name}") {
                    Text(
                        group.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(examples, key = { it.index }) { (index, example) ->
                    ListItem(
                        headlineContent = { Text(example.title) },
                        supportingContent = { Text(example.summary) },
                        leadingContent = { Icon(example.category.icon(), null) },
                        trailingContent = if (example.live) {
                            {
                                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer) {
                                    Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.Bolt, null, Modifier.size(14.dp))
                                        Text("Live", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        } else {
                            null
                        },
                        modifier = Modifier.clickableRow { onExample(index) },
                    )
                }
            }
        }
    }
}

private fun ExampleCategory.icon(): ImageVector = when (this) {
    ExampleCategory.BASICS -> Icons.Outlined.School
    ExampleCategory.FILTERS -> Icons.Outlined.GraphicEq
    ExampleCategory.DIODES -> Icons.Outlined.ElectricalServices
    ExampleCategory.TRANSISTORS -> Icons.Outlined.SettingsInputComponent
    ExampleCategory.OPAMPS -> Icons.Outlined.ChangeHistory
    ExampleCategory.OSCILLATORS -> Icons.Outlined.Waves
    ExampleCategory.POWER -> Icons.Outlined.Power
    ExampleCategory.DIGITAL -> Icons.Outlined.Memory
    ExampleCategory.INSTRUMENTATION -> Icons.Outlined.Speed
    ExampleCategory.NETLISTS -> Icons.Outlined.Code
}

@Composable
internal fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename circuit") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
