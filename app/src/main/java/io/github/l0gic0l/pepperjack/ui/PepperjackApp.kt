package io.github.l0gic0l.pepperjack.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withStarted
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.l0gic0l.pepperjack.AppContainer
import io.github.l0gic0l.pepperjack.data.CrashReporter
import io.github.l0gic0l.pepperjack.data.Settings
import io.github.l0gic0l.pepperjack.ui.common.CrashReportDialog
import io.github.l0gic0l.pepperjack.ui.common.importNetlist
import io.github.l0gic0l.pepperjack.ui.editor.EditorScreen
import io.github.l0gic0l.pepperjack.ui.home.HomeScreen
import io.github.l0gic0l.pepperjack.ui.symbols.SymbolEditorScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data class EditorRoute(val circuitId: String)

/** Edits [symbolId], or makes a new symbol for [subckt] from library [file]. */
@Serializable
data class SymbolEditorRoute(val symbolId: String? = null, val file: String? = null, val subckt: String? = null)

@Composable
fun PepperjackApp(container: AppContainer, settings: Settings, pendingImport: MutableStateFlow<Uri?>) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val incoming by pendingImport.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()

    // Screens open from async work (saving a new circuit, an import). Navigating while the
    // activity is stopped leaves the new destination uncomposed, and destroying the activity
    // then crashes inside Navigation, so wait until it is started (or drop it if destroyed).
    fun open(route: Any) {
        scope.launch {
            runCatching { lifecycle.withStarted { navController.navigate(route) } }
        }
    }

    LaunchedEffect(incoming) {
        val uri = incoming ?: return@LaunchedEffect
        pendingImport.value = null
        importNetlist(context, container, uri)
            .onSuccess { open(EditorRoute(it)) }
            .onFailure { Toast.makeText(context, it.message ?: "Import failed", Toast.LENGTH_LONG).show() }
    }

    var crashReport by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        crashReport = withContext(Dispatchers.IO) { CrashReporter.pendingReport(context) }
    }
    crashReport?.let { report -> CrashReportDialog(report) { crashReport = null } }

    NavHost(navController = navController, startDestination = HomeRoute) {
        composable<HomeRoute> {
            HomeScreen(
                container = container,
                settings = settings,
                onOpenCircuit = { open(EditorRoute(it)) },
                onEditSymbol = { id, file, subckt -> open(SymbolEditorRoute(id, file, subckt)) },
            )
        }
        composable<SymbolEditorRoute> { entry ->
            val route = entry.toRoute<SymbolEditorRoute>()
            SymbolEditorScreen(
                container = container,
                symbolId = route.symbolId,
                file = route.file,
                subckt = route.subckt,
                onBack = { navController.popBackStack() },
            )
        }
        composable<EditorRoute> { entry ->
            EditorScreen(
                container = container,
                settings = settings,
                circuitId = entry.toRoute<EditorRoute>().circuitId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
