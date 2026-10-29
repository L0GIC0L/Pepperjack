package io.github.l0gic0l.pepperjack.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.l0gic0l.pepperjack.AppContainer
import io.github.l0gic0l.pepperjack.data.Settings
import io.github.l0gic0l.pepperjack.ui.common.importNetlist
import io.github.l0gic0l.pepperjack.ui.editor.EditorScreen
import io.github.l0gic0l.pepperjack.ui.home.HomeScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data class EditorRoute(val circuitId: String)

@Composable
fun PepperjackApp(container: AppContainer, settings: Settings, pendingImport: MutableStateFlow<Uri?>) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val incoming by pendingImport.collectAsStateWithLifecycle()

    LaunchedEffect(incoming) {
        val uri = incoming ?: return@LaunchedEffect
        pendingImport.value = null
        importNetlist(context, container, uri)
            .onSuccess { navController.navigate(EditorRoute(it)) }
            .onFailure { Toast.makeText(context, it.message ?: "Import failed", Toast.LENGTH_LONG).show() }
    }

    NavHost(navController = navController, startDestination = HomeRoute) {
        composable<HomeRoute> {
            HomeScreen(
                container = container,
                settings = settings,
                onOpenCircuit = { navController.navigate(EditorRoute(it)) },
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
