package io.github.l0gic0l.pepperjack.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import io.github.l0gic0l.pepperjack.data.Settings
import io.github.l0gic0l.pepperjack.data.ThemeMode

// Fallback palette for devices without Material You: a warm "pepper jack" amber
// with a chili-red tertiary, built from Material 3 tonal steps.
private val LightColors = lightColorScheme(
    primary = Color(0xFF7B5800),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDEA6),
    onPrimaryContainer = Color(0xFF271900),
    secondary = Color(0xFF6B5D3F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF5E0BB),
    onSecondaryContainer = Color(0xFF241A04),
    tertiary = Color(0xFFA23F2F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDAD3),
    onTertiaryContainer = Color(0xFF3E0500),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F2),
    onBackground = Color(0xFF1F1B13),
    surface = Color(0xFFFFF8F2),
    onSurface = Color(0xFF1F1B13),
    surfaceVariant = Color(0xFFEDE1CF),
    onSurfaceVariant = Color(0xFF4D4639),
    outline = Color(0xFF7F7667),
    outlineVariant = Color(0xFFD0C5B4),
    inverseSurface = Color(0xFF353027),
    inverseOnSurface = Color(0xFFF8EFE2),
    inversePrimary = Color(0xFFF7BD48),
    surfaceDim = Color(0xFFE2D9CC),
    surfaceBright = Color(0xFFFFF8F2),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCF2E5),
    surfaceContainer = Color(0xFFF6EDDF),
    surfaceContainerHigh = Color(0xFFF0E7D9),
    surfaceContainerHighest = Color(0xFFEBE1D4),
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF7BD48),
    onPrimary = Color(0xFF412D00),
    primaryContainer = Color(0xFF5D4200),
    onPrimaryContainer = Color(0xFFFFDEA6),
    secondary = Color(0xFFD8C4A0),
    onSecondary = Color(0xFF3A2F15),
    secondaryContainer = Color(0xFF52452A),
    onSecondaryContainer = Color(0xFFF5E0BB),
    tertiary = Color(0xFFFFB4A6),
    onTertiary = Color(0xFF5F150A),
    tertiaryContainer = Color(0xFF81281A),
    onTertiaryContainer = Color(0xFFFFDAD3),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF17130B),
    onBackground = Color(0xFFEBE1D4),
    surface = Color(0xFF17130B),
    onSurface = Color(0xFFEBE1D4),
    surfaceVariant = Color(0xFF4D4639),
    onSurfaceVariant = Color(0xFFD0C5B4),
    outline = Color(0xFF998F80),
    outlineVariant = Color(0xFF4D4639),
    inverseSurface = Color(0xFFEBE1D4),
    inverseOnSurface = Color(0xFF353027),
    inversePrimary = Color(0xFF7B5800),
    surfaceDim = Color(0xFF17130B),
    surfaceBright = Color(0xFF3E382F),
    surfaceContainerLowest = Color(0xFF110E07),
    surfaceContainerLow = Color(0xFF1F1B13),
    surfaceContainer = Color(0xFF231F17),
    surfaceContainerHigh = Color(0xFF2E2921),
    surfaceContainerHighest = Color(0xFF39342B),
    scrim = Color(0xFF000000),
)

/** Colors the schematic and plots need beyond the Material roles. */
@Immutable
data class CircuitColors(
    val wire: Color,
    val part: Color,
    val selection: Color,
    val openPin: Color,
    val grid: Color,
    val traces: List<Color>,
)

val LocalCircuitColors = staticCompositionLocalOf {
    CircuitColors(Color.Blue, Color.Black, Color.Red, Color.Red, Color.Gray, listOf(Color.Blue))
}

// Trace hues picked to stay distinguishable on both light and dark surfaces.
private val LightTraces = listOf(
    0xFF1565C0, 0xFFC62828, 0xFF2E7D32, 0xFFEF6C00, 0xFF6A1B9A, 0xFF00838F, 0xFF8D6E63, 0xFFAD1457,
).map(::Color)
private val DarkTraces = listOf(
    0xFF64B5F6, 0xFFEF9A9A, 0xFF81C784, 0xFFFFB74D, 0xFFCE93D8, 0xFF4DD0E1, 0xFFBCAAA4, 0xFFF48FB1,
).map(::Color)

private fun circuitColors(scheme: ColorScheme): CircuitColors {
    val dark = scheme.surface.luminance() < 0.5f
    return CircuitColors(
        wire = scheme.primary,
        part = scheme.onSurface,
        selection = scheme.tertiary,
        openPin = scheme.error,
        grid = scheme.outlineVariant,
        traces = if (dark) DarkTraces else LightTraces,
    )
}

@Composable
fun PepperjackTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalCircuitColors provides circuitColors(scheme)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
