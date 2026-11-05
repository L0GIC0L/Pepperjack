package io.github.l0gic0l.pepperjack.ui.home

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.pepperjack.AppContainer
import io.github.l0gic0l.pepperjack.BuildConfig
import io.github.l0gic0l.pepperjack.data.Settings
import io.github.l0gic0l.pepperjack.data.ThemeMode
import io.github.l0gic0l.pepperjack.model.SpiceDialect
import io.github.l0gic0l.pepperjack.sim.EngineStatus
import io.github.l0gic0l.pepperjack.ui.common.clickableRow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, settings: Settings) {
    val scope = rememberCoroutineScope()
    val repo = container.settings
    val engine by produceState<EngineStatus?>(null) { value = container.engine.status() }
    val uriHandler = LocalUriHandler.current

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        ) {
            SectionHeader("Appearance")
            ListItem(
                leadingContent = { Icon(Icons.Outlined.Palette, null) },
                headlineContent = { Text("Theme") },
                supportingContent = {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        ThemeMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = settings.themeMode == mode,
                                onClick = { scope.launch { repo.setThemeMode(mode) } },
                                shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                            ) { Text(mode.label) }
                        }
                    }
                },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ListItem(
                    headlineContent = { Text("Dynamic color") },
                    supportingContent = { Text("Use colors from your wallpaper (Material You)") },
                    trailingContent = {
                        Switch(checked = settings.dynamicColor, onCheckedChange = { scope.launch { repo.setDynamicColor(it) } })
                    },
                    modifier = Modifier.clickableRow { scope.launch { repo.setDynamicColor(!settings.dynamicColor) } },
                )
            }
            ListItem(
                leadingContent = { Icon(Icons.Outlined.GridOn, null) },
                headlineContent = { Text("Schematic grid") },
                supportingContent = { Text("Show grid dots behind the schematic") },
                trailingContent = {
                    Switch(checked = settings.showGrid, onCheckedChange = { scope.launch { repo.setShowGrid(it) } })
                },
                modifier = Modifier.clickableRow { scope.launch { repo.setShowGrid(!settings.showGrid) } },
            )

            SectionHeader("Default SPICE dialect")
            Text(
                "How netlists are interpreted unless a circuit picks its own dialect. " +
                    "ngspice translates each of these through its compatibility modes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Column(Modifier.selectableGroup()) {
                SpiceDialect.entries.forEach { dialect ->
                    ListItem(
                        headlineContent = { Text(dialect.label) },
                        supportingContent = { Text(dialect.description) },
                        leadingContent = { RadioButton(selected = settings.dialect == dialect, onClick = null) },
                        modifier = Modifier.selectable(
                            selected = settings.dialect == dialect,
                            role = Role.RadioButton,
                            onClick = { scope.launch { repo.setDialect(dialect) } },
                        ),
                    )
                }
            }

            SectionHeader("Simulation engine")
            when (val status = engine) {
                null -> ListItem(headlineContent = { Text("Checking ngspice…") })
                is EngineStatus.Ready -> ListItem(
                    leadingContent = { Icon(Icons.Outlined.Memory, null) },
                    headlineContent = { Text("ngspice ${status.version}") },
                    supportingContent = {
                        Text(
                            if (status.codeModels.isEmpty()) "No XSPICE code models loaded"
                            else "XSPICE code models: ${status.codeModels.joinToString()}",
                        )
                    },
                )
                is EngineStatus.Unavailable -> ListItem(
                    leadingContent = { Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
                    headlineContent = { Text("Simulation unavailable") },
                    supportingContent = { Text(status.reason) },
                )
            }

            SectionHeader("About")
            ListItem(
                leadingContent = { Icon(Icons.Outlined.Code, null) },
                headlineContent = { Text("Pepperjack ${BuildConfig.VERSION_NAME}") },
                supportingContent = {
                    Text(
                        "Open source (MIT) on GitHub. Simulation by ngspice 47, © the ngspice team: " +
                            "Modified BSD, with KLU and numparam under LGPLv2, OSDI under MPL 2.0 and " +
                            "XSPICE in the public domain. Source: sourceforge.net/projects/ngspice.",
                    )
                },
                modifier = Modifier.clickableRow { uriHandler.openUri("https://github.com/l0gic0l/pepperjack") },
            )
        }
    }
}
