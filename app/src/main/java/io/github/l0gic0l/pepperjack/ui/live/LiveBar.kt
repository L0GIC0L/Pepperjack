package io.github.l0gic0l.pepperjack.ui.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.pepperjack.model.Part
import io.github.l0gic0l.pepperjack.model.PotValue
import io.github.l0gic0l.pepperjack.util.Si
import kotlin.math.roundToInt

private val speeds = listOf(0.1f, 0.25f, 0.5f, 1f, 2f, 4f)

/** Status and controls for live simulation, shown above the editing toolbar. */
@Composable
fun LiveBar(
    animator: LiveAnimator?,
    running: Boolean,
    error: String?,
    mode: LiveColorMode,
    onMode: (LiveColorMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = rememberLivePalette()
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shadowElevation = 3.dp,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StatusIcon {
                when {
                    running -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    error != null -> Icon(Icons.Outlined.ErrorOutline, contentDescription = "Live simulation failed")
                    else -> Icon(Icons.Outlined.Bolt, contentDescription = "Live simulation")
                }
            }
            if (error != null) {
                Text(error, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(max = 260.dp))
            }
            val view = animator?.view
            if (animator != null && view != null) {
                if (view.duration > 0) {
                    IconButton(onClick = { animator.playing = !animator.playing }) {
                        Icon(
                            if (animator.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = if (animator.playing) "Pause playback" else "Play",
                        )
                    }
                    IconButton(onClick = animator::restart) { Icon(Icons.Outlined.Replay, contentDescription = "Restart playback") }
                    Text(
                        "t = ${Si.format(animator.simTime, "s")} of ${Si.format(view.duration, "s")}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.widthIn(min = 120.dp),
                    )
                    TextButton(onClick = {
                        val next = speeds.indexOfFirst { it > animator.speed + 1e-3f }
                        animator.speed = speeds[if (next < 0) 0 else next]
                    }) { Text(speedLabel(animator.speed)) }
                } else {
                    Text("Operating point", style = MaterialTheme.typography.labelMedium)
                }
                LiveColorMode.entries.forEach { m ->
                    FilterChip(selected = mode == m, onClick = { onMode(m) }, label = { Text(m.label) })
                }
                Legend(palette, mode, view.maxVoltage, view.maxCurrent)
            }
        }
    }
}

@Composable
private fun StatusIcon(content: @Composable () -> Unit) {
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { content() }
}

private fun speedLabel(speed: Float): String =
    if (speed >= 1f) "${speed.roundToInt()}×" else "${(speed * 100).roundToInt() / 100.0}×"

/** Colour scale of the wires: −max…0…+max for voltage, 0…max for current. */
@Composable
private fun Legend(palette: LivePalette, mode: LiveColorMode, maxVoltage: Double, maxCurrent: Double) {
    Column(Modifier.padding(horizontal = 4.dp)) {
        Canvas(Modifier.width(96.dp).height(8.dp)) {
            val colors = when (mode) {
                LiveColorMode.VOLTAGE -> listOf(voltageColor(palette, -1.0), voltageColor(palette, -0.5), palette.neutral, voltageColor(palette, 0.5), voltageColor(palette, 1.0))
                LiveColorMode.CURRENT -> listOf(palette.neutral, currentColor(palette, 0.01), currentColor(palette, 0.1), currentColor(palette, 1.0))
            }
            drawRoundRect(Brush.horizontalGradient(colors, startX = 0f, endX = size.width), cornerRadius = CornerRadius(4.dp.toPx()))
        }
        Spacer(Modifier.height(2.dp))
        Text(
            when (mode) {
                LiveColorMode.VOLTAGE -> "±" + Si.format(maxVoltage, "V")
                LiveColorMode.CURRENT -> "0 … " + Si.format(maxCurrent, "A")
            },
            style = MaterialTheme.typography.labelSmall,
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
            Text("${part.ref} ${(pot.position * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(min = 72.dp))
            Slider(
                value = pot.position.toFloat(),
                onValueChange = { onPosition((it * 100).roundToInt() / 100.0) },
                modifier = Modifier.width(220.dp),
            )
        }
    }
}
