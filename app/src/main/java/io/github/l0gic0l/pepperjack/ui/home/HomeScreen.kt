package io.github.l0gic0l.pepperjack.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.l0gic0l.pepperjack.AppContainer
import io.github.l0gic0l.pepperjack.data.Settings

private enum class HomeTab(val label: String, val icon: ImageVector) {
    CIRCUITS("Circuits", Icons.Outlined.DeveloperBoard),
    MODELS("Models", Icons.AutoMirrored.Outlined.LibraryBooks),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

/** Top-level destinations; the navigation suite switches between a bar, rail and drawer by window size. */
@Composable
fun HomeScreen(container: AppContainer, settings: Settings, onOpenCircuit: (String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.CIRCUITS) }
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            HomeTab.entries.forEach { item ->
                item(
                    selected = tab == item,
                    onClick = { tab = item },
                    icon = { Icon(item.icon, contentDescription = null) },
                    label = { Text(item.label) },
                )
            }
        },
    ) {
        when (tab) {
            HomeTab.CIRCUITS -> CircuitsScreen(container, onOpenCircuit)
            HomeTab.MODELS -> ModelsScreen(container)
            HomeTab.SETTINGS -> SettingsScreen(container, settings)
        }
    }
}
