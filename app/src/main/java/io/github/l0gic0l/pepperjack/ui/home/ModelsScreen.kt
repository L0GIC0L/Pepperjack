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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import io.github.l0gic0l.pepperjack.spice.SpiceModels
import io.github.l0gic0l.pepperjack.ui.common.clickableRow
import io.github.l0gic0l.pepperjack.ui.common.displayName
import io.github.l0gic0l.pepperjack.ui.common.readBytes
import io.github.l0gic0l.pepperjack.util.Si
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelsScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files by container.library.files.collectAsStateWithLifecycle()
    var viewing by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(Unit) { container.library.refresh() }

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
                                "Add .lib, .mod, .sub or .inc files from TI, Analog Devices, onsemi and others, " +
                                    "then reference them in a netlist or schematic directive with " +
                                    ".include FILE or .lib FILE. PSpice and LTspice model syntax both work.",
                            )
                        },
                        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    )
                }
            }
            if (files.isNotEmpty()) {
                item { SectionHeader("Your files") }
            }
            items(files, key = { it.name }) { file ->
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Description, null) },
                    headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val models = if (file.modelNames.isEmpty()) "no models found" else file.modelNames.take(6).joinToString()
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
                    modifier = Modifier.clickableRow {
                        scope.launch { viewing = file.name to container.library.read(file.name) }
                    },
                )
            }
            item { SectionHeader("Built in") }
            item {
                Text(
                    "Generic models the schematic parts use. Type any of these names as a part's model.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    SpiceModels.names.forEach { name ->
                        AssistChip(onClick = {
                            viewing = name to (SpiceModels.find(name)?.card ?: "")
                        }, label = { Text(name) })
                    }
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
