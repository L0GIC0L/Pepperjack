package io.github.l0gic0l.pepperjack.ui.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.l0gic0l.pepperjack.AppContainer
import io.github.l0gic0l.pepperjack.data.OnlineModel
import io.github.l0gic0l.pepperjack.data.OnlineModelIndex
import io.github.l0gic0l.pepperjack.model.PartKind
import io.github.l0gic0l.pepperjack.model.PartLibrary
import io.github.l0gic0l.pepperjack.ui.editor.accepts
import io.github.l0gic0l.pepperjack.util.Si
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

const val ONLINE_SEARCH = "online-search"
const val ONLINE_RESULTS = "online-results"

private enum class TypeFilter(val label: String, val types: Set<String>) {
    ALL("All", emptySet()),
    SUBCKT("Subcircuits", setOf("SUBCKT")),
    DIODE("Diodes", setOf("D")),
    BJT("BJTs", setOf("NPN", "PNP")),
    MOSFET("MOSFETs", setOf("NMOS", "PMOS", "VDMOS")),
    JFET("JFETs", setOf("NJF", "PJF")),
    OTHER("Other", emptySet()),
    ;

    fun matches(type: String) = when (this) {
        ALL -> true
        OTHER -> entries.none { type in it.types }
        else -> type in types
    }
}

private val examples = listOf("LM358", "TL072", "NE555", "IRF540", "2N3904", "BC547", "1N4148")

private fun describe(model: OnlineModel) = when (model.type) {
    "SUBCKT" -> "Subcircuit, ${model.pins} pins"
    "D" -> "Diode"
    "NPN", "PNP" -> "${model.type} BJT"
    "NJF", "PJF" -> (if (model.type == "NJF") "N" else "P") + "-channel JFET"
    "NMOS", "PMOS" -> "${model.type} MOSFET"
    "VDMOS" -> "Power MOSFET (VDMOS)"
    else -> "${model.type} model"
}

/**
 * Searches the online model library and downloads files into the user's library.
 * Opened from a part's model picker, [partKind] adds a filter for models that fit it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OnlineModelsScreen(container: AppContainer, partKind: String?, onBack: () -> Unit, onEditSymbol: SymbolEditorLauncher) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val focus = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    val part = remember(partKind) { partKind?.let { kind -> PartKind.entries.firstOrNull { it.name == kind } }?.let { PartLibrary[it] } }
    val files by container.library.files.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(TypeFilter.ALL) }
    var fitsPart by rememberSaveable { mutableStateOf(part != null) }
    val downloading = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(Unit) { container.library.refresh() }
    val index by produceState<OnlineModelIndex?>(null) { value = container.onlineModels.index() }
    val results by produceState(OnlineModelIndex.Results(emptyList(), 0), index, query, filter, fitsPart) {
        val loaded = index ?: return@produceState
        if (query.isBlank() && filter == TypeFilter.ALL && !fitsPart) {
            value = OnlineModelIndex.Results(emptyList(), 0)
            return@produceState
        }
        delay(150)
        value = withContext(Dispatchers.Default) {
            loaded.search(query) { model ->
                filter.matches(model.type) && (!fitsPart || part == null || part.accepts(model.type, model.pins))
            }
        }
    }

    fun add(loaded: OnlineModelIndex, model: OnlineModel) {
        val file = model.file
        downloading[file.path] = true
        // The download runs in the app scope, so it still finishes if the user leaves this screen.
        val download = container.appScope.async { container.onlineModels.add(loaded, file) }
        scope.launch {
            runCatching { download.await() }
                .onSuccess { name ->
                    downloading.remove(file.path)
                    val result = snackbar.showSnackbar(
                        message = "Added $name. Type ${model.name} as a part's model to use it.",
                        actionLabel = if (model.isSubckt) "Make symbol" else null,
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) onEditSymbol.open(null, name, model.name)
                }
                .onFailure {
                    downloading.remove(file.path)
                    snackbar.showSnackbar("Couldn't download ${file.name}: ${it.message ?: it.javaClass.simpleName}")
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Online models") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { index?.let { uriHandler.openUri(it.pageUrl) } }) {
                        Icon(Icons.Outlined.Public, contentDescription = "Open the library on GitHub")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Part number, maker or family") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Clear, contentDescription = "Clear the search") }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(ONLINE_SEARCH),
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (part != null) {
                    FilterChip(selected = fitsPart, onClick = { fitsPart = !fitsPart }, label = { Text("Fits ${part.title}") })
                }
                TypeFilter.entries.forEach { type ->
                    FilterChip(selected = filter == type, onClick = { filter = type }, label = { Text(type.label) })
                }
            }
            val loaded = index
            if (loaded == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            LazyColumn(
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 16.dp),
                modifier = Modifier.fillMaxSize().testTag(ONLINE_RESULTS),
            ) {
                if (results.models.isEmpty()) {
                    item(key = "intro") {
                        Intro(loaded, searching = query.isNotBlank() || filter != TypeFilter.ALL || fitsPart) { query = it }
                    }
                }
                items(results.models, key = { it.file.path + "/" + it.name }) { model ->
                    val copy = loaded.copyOf(model.file, files)
                    ListItem(
                        headlineContent = { Text(model.name, fontFamily = FontFamily.Monospace) },
                        supportingContent = {
                            Column {
                                Text(
                                    "${describe(model)} · ${model.file.folder}/${model.file.name} · " +
                                        Si.format(model.file.sizeBytes.toDouble(), "B"),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!model.loads) {
                                    Text(
                                        "ngspice reported errors loading this model when it was tested",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            when {
                                downloading[model.file.path] == true -> CircularProgressIndicator(Modifier.size(24.dp))
                                copy != null && model.isSubckt -> TextButton(onClick = { onEditSymbol.open(null, copy.name, model.name) }) {
                                    Text("Make symbol")
                                }
                                copy != null -> Icon(Icons.Outlined.CheckCircle, contentDescription = "In your library")
                                else -> FilledTonalButton(onClick = { add(loaded, model) }) {
                                    Icon(Icons.Outlined.CloudDownload, null, Modifier.size(18.dp))
                                    Text("Add", Modifier.padding(start = 8.dp))
                                }
                            }
                        },
                    )
                }
                if (results.total > results.models.size) {
                    item(key = "more") {
                        Text(
                            "Showing ${results.models.size} of ${NumberFormat.getIntegerInstance().format(results.total)}. " +
                                "Type more of the name to narrow it down.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Intro(index: OnlineModelIndex, searching: Boolean, onExample: (String) -> Unit) {
    val count = NumberFormat.getIntegerInstance()
    val loading = remember(index) { index.models.count { it.loads } * 100 / index.models.size.coerceAtLeast(1) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (searching) {
            Text("No models match.", style = MaterialTheme.typography.bodyLarge)
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            ListItem(
                headlineContent = { Text("KiCad-Spice-Library") },
                supportingContent = {
                    Text(
                        "${count.format(index.models.size)} models in ${count.format(index.files.size)} files, collected by the " +
                            "KiCad community from Texas Instruments, Analog Devices, National, Maxim and many other makers. " +
                            "Adding a model downloads its file from GitHub into your model library, with the maker's notice " +
                            "kept, and each file stays under its maker's terms. In testing, ngspice loaded $loading% of " +
                            "them; the rest are marked.",
                    )
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            )
        }
        Text("Try", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            examples.forEach { name -> AssistChip(onClick = { onExample(name) }, label = { Text(name) }) }
        }
    }
}
