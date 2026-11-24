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

/** How much a probe's tag on the sheet shows: every reading, one line, or nothing. */
enum class TagDetail(val label: String) {
    COMPACT("Compact"), FULL("Full"), OFF("Hidden");

    /** The next level, for the sheet's button that steps through them. */
    val next: TagDetail get() = entries[(ordinal + 1) % entries.size]
}

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val dialect: SpiceDialect = SpiceDialect.Default,
    val showGrid: Boolean = true,
    /** The hint over the sheet in live mode, until it has been read. */
    val liveHint: Boolean = true,
    val tagDetail: TagDetail = TagDetail.COMPACT,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme")
    private val dynamicKey = booleanPreferencesKey("dynamic_color")
    private val dialectKey = stringPreferencesKey("dialect")
    private val gridKey = booleanPreferencesKey("show_grid")
    private val liveHintKey = booleanPreferencesKey("live_hint")
    private val tagDetailKey = stringPreferencesKey("tag_detail")

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            themeMode = prefs[themeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[dynamicKey] ?: true,
            dialect = prefs[dialectKey]?.let { runCatching { SpiceDialect.valueOf(it) }.getOrNull() }
                ?: SpiceDialect.Default,
            showGrid = prefs[gridKey] ?: true,
            liveHint = prefs[liveHintKey] ?: true,
            tagDetail = prefs[tagDetailKey]?.let { runCatching { TagDetail.valueOf(it) }.getOrNull() } ?: TagDetail.COMPACT,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[themeKey] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = context.dataStore.edit { it[dynamicKey] = enabled }
    suspend fun setDialect(dialect: SpiceDialect) = context.dataStore.edit { it[dialectKey] = dialect.name }
    suspend fun setShowGrid(enabled: Boolean) = context.dataStore.edit { it[gridKey] = enabled }
    suspend fun setLiveHint(enabled: Boolean) = context.dataStore.edit { it[liveHintKey] = enabled }
    suspend fun setTagDetail(detail: TagDetail) = context.dataStore.edit { it[tagDetailKey] = detail.name }
}
