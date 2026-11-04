package io.github.l0gic0l.pepperjack.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.pepperjack.model.Part
import io.github.l0gic0l.pepperjack.model.PartKind
import io.github.l0gic0l.pepperjack.model.isLcd
import io.github.l0gic0l.pepperjack.model.isMeter
import io.github.l0gic0l.pepperjack.model.PotValue
import io.github.l0gic0l.pepperjack.model.Schematic
import io.github.l0gic0l.pepperjack.model.SwitchValue
import kotlin.math.roundToInt

/** The parts worked from the live panel's Controls tab: knobs and switches, in sheet order. */
fun controlParts(schematic: Schematic): List<Part> =
    schematic.parts.filter { it.kind == PartKind.POT || it.kind == PartKind.SPST || it.kind == PartKind.SPDT }

/** How many columns of controls fit across [width], each at least [min] wide. */
internal fun controlColumns(width: Dp, min: Dp = 260.dp): Int = (width / min).toInt().coerceAtLeast(1)

/**
 * A front panel for the sheet: every meter's reading, and every knob and switch, so a big
 * circuit can be worked live without hunting for its parts on the sheet. Knobs turn as they
 * do when selected, from 0% at pin 1 to 100% at pin 2.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveControls(
    schematic: Schematic,
    controls: List<Part>,
    animator: LiveAnimator?,
    onPot: (Long, Double) -> Unit,
    onSwitch: (Part) -> Unit,
    modifier: Modifier = Modifier,
) {
    val numbers = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
    val names = MaterialTheme.typography.labelLarge
    // Up to about two fifths of the window; it scrolls beyond that, and folds away.
    val window = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val maxHeight = (window * 0.4f).coerceIn(200.dp, 360.dp)
    val nameWidth = textWidth(controls.maxByOrNull { it.ref.length }?.ref ?: "RV1", names)
    Column(
        modifier.testTag(LIVE_CONTROLS).heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        MeterReadings(schematic, animator, numbers)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = controlColumns(maxWidth)
            val gap = 12.dp
            val itemWidth = (maxWidth - gap * (columns - 1)) / columns
            FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), maxItemsInEachRow = columns) {
                controls.forEach { part ->
                    val row = Modifier.width(itemWidth)
                    when (part.kind) {
                        PartKind.POT -> PotRow(part, nameWidth, names, numbers, onPot, row)
                        else -> SwitchRow(part, nameWidth, names, onSwitch, row)
                    }
                }
            }
        }
    }
}

/**
 * The meters' readings, in their modes, as on their faces. A sheet's LCD meters are its front
 * panel, so when it has some they alone show here, as backlit one-line tiles; otherwise every
 * meter shows as a small two-line tag, many to a row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MeterReadings(schematic: Schematic, animator: LiveAnimator?, style: TextStyle) {
    val view = animator?.view ?: return
    val all = view.readouts.filter { it.kind.isMeter }
        .mapNotNull { r -> schematic.parts.firstOrNull { it.id == r.partId }?.let { it to r } }
    val meters = all.filter { it.first.kind.isLcd }.ifEmpty { all }
    if (meters.isEmpty()) return
    val nameStyle = MaterialTheme.typography.labelSmall
    val lcdStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        animator.frame // follow the playback
        meters.forEach { (part, readout) ->
            val text = meterText(part.meter, readout, view, animator.position)
            if (part.kind.isLcd) {
                // A fixed slot, so the tiles don't shift as readings change.
                val slot = textWidth("-888.8 mA" + part.meter.suffix, lcdStyle)
                Surface(shape = RoundedCornerShape(8.dp), color = LcdBackground, contentColor = LcdDigits) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(part.ref, style = nameStyle, color = LcdDigits.copy(alpha = 0.7f), maxLines = 1)
                        Spacer(Modifier.width(8.dp))
                        Text(text, style = lcdStyle, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(slot))
                    }
                }
            } else {
                val slot = textWidth("-888 mA" + part.meter.suffix, style)
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text(part.ref, style = nameStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Text(text, style = style, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(slot))
                    }
                }
            }
        }
    }
}

@Composable
private fun PotRow(part: Part, nameWidth: Dp, names: TextStyle, numbers: TextStyle, onPot: (Long, Double) -> Unit, modifier: Modifier) {
    val pot = PotValue.parse(part.value)
    Row(modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(part.ref, style = names, maxLines = 1, modifier = Modifier.padding(start = 4.dp).width(nameWidth))
        Text(
            "${(pot.position * 100).roundToInt()}%",
            style = numbers,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(textWidth("100%", numbers) + 4.dp),
        )
        Slider(
            value = pot.position.toFloat(),
            onValueChange = { onPot(part.id, (it * 100).roundToInt() / 100.0) },
            modifier = Modifier.weight(1f).padding(start = 8.dp).semantics { contentDescription = "${part.ref} knob" },
        )
    }
}

@Composable
private fun SwitchRow(part: Part, nameWidth: Dp, names: TextStyle, onSwitch: (Part) -> Unit, modifier: Modifier) {
    Row(modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(part.ref, style = names, maxLines = 1, modifier = Modifier.padding(start = 4.dp).width(nameWidth))
        Spacer(Modifier.weight(1f))
        if (part.kind == PartKind.SPST) {
            val closed = SwitchValue.isClosed(part.value)
            Text(if (closed) "Closed" else "Open", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
            Switch(
                checked = closed,
                onCheckedChange = { onSwitch(part) },
                modifier = Modifier.semantics { contentDescription = "${part.ref} switch" },
            )
        } else {
            val b = SwitchValue.isB(part.value)
            SingleChoiceSegmentedButtonRow {
                listOf(false, true).forEachIndexed { i, toB ->
                    SegmentedButton(
                        selected = b == toB,
                        onClick = { if (b != toB) onSwitch(part) },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        icon = {},
                        label = { Text(if (toB) "B" else "A") },
                    )
                }
            }
        }
    }
}

const val LIVE_CONTROLS = "live-controls"
