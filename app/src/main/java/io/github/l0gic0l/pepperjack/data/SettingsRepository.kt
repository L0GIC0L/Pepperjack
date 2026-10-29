package io.github.l0gic0l.pepperjack.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.l0gic0l.pepperjack.model.SpiceDialect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val dialect: SpiceDialect = SpiceDialect.Default,
    val showGrid: Boolean = true,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme")
    private val dynamicKey = booleanPreferencesKey("dynamic_color")
    private val dialectKey = stringPreferencesKey("dialect")
    private val gridKey = booleanPreferencesKey("show_grid")

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            themeMode = prefs[themeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[dynamicKey] ?: true,
            dialect = prefs[dialectKey]?.let { runCatching { SpiceDialect.valueOf(it) }.getOrNull() }
                ?: SpiceDialect.Default,
            showGrid = prefs[gridKey] ?: true,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[themeKey] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = context.dataStore.edit { it[dynamicKey] = enabled }
    suspend fun setDialect(dialect: SpiceDialect) = context.dataStore.edit { it[dialectKey] = dialect.name }
    suspend fun setShowGrid(enabled: Boolean) = context.dataStore.edit { it[gridKey] = enabled }
}
