package io.github.l0gic0l.pepperjack.ui.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.pepperjack.model.MeterMode
import io.github.l0gic0l.pepperjack.model.Part
import io.github.l0gic0l.pepperjack.model.PotValue
import io.github.l0gic0l.pepperjack.util.Si
import kotlin.math.roundToInt

private val speeds = listOf(0.1f, 0.25f, 0.5f, 1f, 2f, 4f)

/**
 * The live panel's bar: the run's playback (pause, restart, the time being shown, the speed),
 * what the wires' colours stand for, and [trailing] (the fold button, when the panel is
 * docked). It is one row: on a narrow screen it shortens its labels and drops the colour scale.
 */
@Composable
fun LiveBar(
    animator: LiveAnimator?,
    running: Boolean,
    error: String?,
    mode: LiveColorMode,
    onMode: (LiveColorMode) -> Unit,
    modifier: Modifier = Modifier,
    /** A grip at the top edge, for a bar the panel is dragged by. */
    grip: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    val palette = rememberLivePalette()
    val numbers = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier,
    ) {
        Column {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val roomy = maxWidth >= 520.dp
                val scale = maxWidth >= 400.dp
                val view = animator?.view
                Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (animator != null && view != null && view.duration > 0) {
                        IconButton(onClick = { animator.playing = !animator.playing }, modifier = Modifier.size(40.dp)) {
                            Icon(
                                if (animator.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                contentDescription = if (animator.playing) "Pause playback" else "Play",
                            )
                        }
                        IconButton(onClick = animator::restart, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Outlined.Replay, contentDescription = "Restart playback")
                        }
                        val now = Si.formatFixed(animator.simTime, "s")
                        val total = Si.formatFixed(view.duration, "s")
                        if (roomy) {
                            Text(
                                "$now / $total",
                                style = numbers,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                modifier = Modifier.width(textWidth("-88.8 ms / -88.8 ms", numbers)),
                            )
                        } else {
                            // Two short lines take half the width of one long one.
                            val small = numbers.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize)
                            Column(Modifier.width(textWidth("of -88.8 ms", small)), horizontalAlignment = Alignment.End) {
                                Text(now, style = numbers, maxLines = 1)
                                Text("of $total", style = small, maxLines = 1, color = LocalContentColor.current.copy(alpha = 0.7f))
                            }
                        }
                        TextButton(
                            onClick = {
                                val next = speeds.indexOfFirst { it > animator.speed + 1e-3f }
                                animator.speed = speeds[if (next < 0) 0 else next]
                            },
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            modifier = Modifier.width(44.dp).semantics { contentDescription = "Playback speed" },
                        ) { Text(speedLabel(animator.speed), style = numbers) }
                    } else {
                        Text(
                            when {
                                view != null -> "Operating point"
                                running -> "Simulating…"
                                else -> "Live"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 12.dp, end = 8.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (view != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            LiveColorMode.entries.forEach { m ->
                                FilterChip(
                                    selected = mode == m,
                                    onClick = { onMode(m) },
                                    label = { Text(if (roomy) m.label else m.short) },
                                    modifier = Modifier.semantics { contentDescription = "Colour the wires by ${m.label.lowercase()}" },
                                )
                            }
                            if (scale) Legend(palette, mode, view.maxVoltage, view.maxCurrent, numbers, if (roomy) 96.dp else 64.dp)
                        }
                    }
                    trailing()
                }
                if (grip) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 3.dp)
                            .size(width = 32.dp, height = 4.dp)
                            .background(LocalContentColor.current.copy(alpha = 0.35f), CircleShape),
                    )
                }
            }
            // Always this tall, so the bar doesn't shift as runs start and finish.
            if (running) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().height(2.dp),
                    color = MaterialTheme.colorScheme.tertiary,
                    trackColor = Color.Transparent,
                )
            } else {
                Spacer(Modifier.height(2.dp))
            }
            if (error != null) {
                Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = "Live simulation failed", Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(error, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** The width [template] takes in [style]: a fixed slot for text whose length changes. */
@Composable
fun textWidth(template: String, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(template, style, density) { with(density) { measurer.measure(template, style).size.width.toDp() } }
}

private fun speedLabel(speed: Float): String =
    if (speed >= 1f) "${speed.roundToInt()}×" else "${(speed * 100).roundToInt() / 100.0}×"

/** Colour scale of the wires: −max…0…+max for voltage, 0…max for current. */
@Composable
private fun Legend(palette: LivePalette, mode: LiveColorMode, maxVoltage: Double, maxCurrent: Double, style: TextStyle, width: Dp) {
    Column(Modifier.padding(horizontal = 4.dp).width(width)) {
        Canvas(Modifier.width(width).height(8.dp)) {
            val colors = when (mode) {
                LiveColorMode.VOLTAGE -> listOf(voltageColor(palette, -1.0), voltageColor(palette, -0.5), palette.neutral, voltageColor(palette, 0.5), voltageColor(palette, 1.0))
                LiveColorMode.CURRENT -> listOf(palette.neutral, currentColor(palette, 0.01), currentColor(palette, 0.1), currentColor(palette, 1.0))
            }
            drawRoundRect(Brush.horizontalGradient(colors, startX = 0f, endX = size.width), cornerRadius = CornerRadius(4.dp.toPx()))
        }
        Spacer(Modifier.height(2.dp))
        Text(
            when (mode) {
                LiveColorMode.VOLTAGE -> "±" + Si.formatFixed(maxVoltage, "V")
                LiveColorMode.CURRENT -> "0 … " + Si.formatFixed(maxCurrent, "A")
            },
            style = style.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
            maxLines = 1,
        )
    }
}

/** Knob for the selected potentiometer while live simulating. */
@Composable
fun PotControl(part: Part, onPosition: (Double) -> Unit, modifier: Modifier = Modifier) {
    val pot = PotValue.parse(part.value)
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 3.dp,
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            val style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
            Text(
                "${part.ref} ${(pot.position * 100).roundToInt()}%",
                style = style,
                maxLines = 1,
                modifier = Modifier.width(textWidth("${part.ref} 100%", style)),
            )
            Slider(
                value = pot.position.toFloat(),
                onValueChange = { onPosition((it * 100).roundToInt() / 100.0) },
                modifier = Modifier.width(220.dp),
            )
        }
    }
}

/**
 * Mode buttons for the selected meter while live simulating: the reading now, or its DC,
 * AC, RMS or peak-to-peak value over the run, like a multimeter's function switch.
 */
@Composable
fun MeterModeControl(part: Part, onMode: (MeterMode) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        // Not the selected button's own colour, so the selected mode stands out.
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 3.dp,
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(part.ref, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
            SingleChoiceSegmentedButtonRow {
                MeterMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = part.meter == m,
                        onClick = { onMode(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, MeterMode.entries.size),
                        // No check mark: it would widen the selected button and shift the others.
                        icon = {},
                        label = { Text(m.label, maxLines = 1) },
                    )
                }
            }
        }
    }
}
