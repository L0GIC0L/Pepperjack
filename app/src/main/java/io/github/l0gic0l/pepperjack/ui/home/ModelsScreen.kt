package io.github.l0gic0l.pepperjack.ui.home

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.l0gic0l.pepperjack.AppContainer
import io.github.l0gic0l.pepperjack.data.LibraryFile
import io.github.l0gic0l.pepperjack.spice.ModelFamily
import io.github.l0gic0l.pepperjack.spice.SpiceModels
import io.github.l0gic0l.pepperjack.ui.common.clickableRow
import io.github.l0gic0l.pepperjack.ui.common.displayName
import io.github.l0gic0l.pepperjack.ui.common.readBytes
import io.github.l0gic0l.pepperjack.ui.symbols.SymbolThumbnail
import io.github.l0gic0l.pepperjack.util.Si
import kotlinx.coroutines.launch

/** Opens the symbol editor for an existing symbol, or for a new one made from [subckt] in [file]. */
fun interface SymbolEditorLauncher {
    fun open(symbolId: String?, file: String?, subckt: String?)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelsScreen(container: AppContainer, onEditSymbol: SymbolEditorLauncher) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files by container.library.files.collectAsStateWithLifecycle()
    val symbols by container.symbols.symbols.collectAsStateWithLifecycle()
    var viewing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var openFile by remember { mutableStateOf<LibraryFile?>(null) }

    LaunchedEffect(Unit) {
        container.library.refresh()
        container.symbols.load()
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            var imported = 0
            for (uri in uris) {
                runCatching { container.library.add(displayName(context, uri), readBytes(context, uri)) }
                    .onSuccess { imported++ }
                    .onFailure { Toast.makeText(context, it.message ?: "Import failed", Toast.LENGTH_LONG).show() }
            }
            if (imported > 0) Toast.makeText(context, "Added $imported file(s)", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Model library") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Outlined.UploadFile, null) },
                text = { Text("Add model files") },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 96.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    ListItem(
                        leadingContent = { Icon(Icons.Outlined.Info, null) },
                        headlineContent = { Text("Use vendor models") },
                        supportingContent = {
                            Text(
                                "Add .lib, .mod, .sub or .inc files from TI, Analog Devices, onsemi and others. " +
                                    "Type a model's name as a part's model and it is included automatically. " +
                                    "Open a file to make a symbol for any of its subcircuits.",
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    )
                }
            }
            if (symbols.isNotEmpty()) {
                item { SectionHeader("Your symbols") }
                items(symbols, key = { "symbol-" + it.id }) { symbol ->
                    ListItem(
                        leadingContent = { SymbolThumbnail(symbol) },
                        headlineContent = { Text(symbol.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                listOf(symbol.subckt, symbol.library).filter { it.isNotEmpty() }.distinct().joinToString(" · ") +
                                    " · ${symbol.pins.size} pins",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { scope.launch { container.symbols.delete(symbol.id) } }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Delete the ${symbol.title} symbol")
                            }
                        },
                        modifier = Modifier.clickableRow { onEditSymbol.open(symbol.id, null, null) },
                    )
                }
            }
            if (files.isNotEmpty()) {
                item { SectionHeader("Your files") }
            }
            items(files, key = { "file-" + it.name }) { file ->
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Description, null) },
                    headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val models = if (file.models.isEmpty()) "no models found" else file.modelNames.take(6).joinToString()
                        Text(
                            "${Si.format(file.sizeBytes.toDouble(), "B")} · $models",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { scope.launch { container.library.delete(file.name) } }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Remove ${file.name}")
                        }
                    },
                    modifier = Modifier.clickableRow { openFile = file },
                )
            }
            item { SectionHeader("Built in") }
            item {
                Text(
                    "Models the schematic parts offer, with datasheet-typical parameters. ICs are behavioural " +
                        "macro-models of the datasheet headline figures. Tap one to see its definition.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            ModelFamily.entries.forEach { family ->
                val models = SpiceModels.family(family)
                if (models.isEmpty()) return@forEach
                item(key = "family-" + family.name) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(family.title, style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            models.forEach { model ->
                                AssistChip(
                                    onClick = { viewing = model.name to "* ${model.description}\n${model.text}" },
                                    label = { Text(model.name) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    openFile?.let { file ->
        ModalBottomSheet(onDismissRequest = { openFile = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
                Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(file.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 2)
                    OutlinedButton(onClick = {
                        scope.launch { viewing = file.name to container.library.read(file.name) }
                    }) { Text("View source") }
                }
                if (file.models.isEmpty()) {
                    Text(
                        "No .model or .subckt definitions were found in this file.",
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                file.models.forEach { model ->
                    ListItem(
                        headlineContent = { Text(model.name, fontFamily = FontFamily.Monospace) },
                        supportingContent = {
                            Text(
                                if (model.isSubckt) "Subcircuit: ${model.pins.joinToString(" ")}" else "${model.type} model",
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = if (model.isSubckt) {
                            {
                                FilledTonalButton(onClick = {
                                    openFile = null
                                    onEditSymbol.open(null, file.name, model.name)
                                }) { Text("Make symbol") }
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    viewing?.let { (title, text) ->
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text(title) },
            text = {
                SelectionContainer {
                    Column(
                        Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        Text(text.take(200_000), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { viewing = null }) { Text("Close") } },
        )
    }
}

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}
