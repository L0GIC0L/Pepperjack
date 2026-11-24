package io.github.l0gic0l.pepperjack.ui.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.pepperjack.model.ELoadValue
import io.github.l0gic0l.pepperjack.model.Part
import io.github.l0gic0l.pepperjack.model.PartKind
import io.github.l0gic0l.pepperjack.model.isLcd
import io.github.l0gic0l.pepperjack.model.isMeter
import io.github.l0gic0l.pepperjack.model.PotValue
import io.github.l0gic0l.pepperjack.model.Schematic
import io.github.l0gic0l.pepperjack.model.SwitchValue
import io.github.l0gic0l.pepperjack.sim.LiveView
import io.github.l0gic0l.pepperjack.util.Si
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The parts worked from the live panel's Controls tab: knobs, switches and electronic loads, in sheet order. */
fun controlParts(schematic: Schematic): List<Part> =
    schematic.parts.filter { it.kind == PartKind.POT || it.kind == PartKind.SPST || it.kind == PartKind.SPDT || it.kind == PartKind.ELOAD }

/** How many columns of controls fit across [width], each at least [min] wide. */
internal fun controlColumns(width: Dp, min: Dp = 260.dp): Int = (width / min).toInt().coerceAtLeast(1)

/**
 * A front panel for the sheet: every meter's reading, and every knob and switch, so a big
 * circuit can be worked live without hunting for its parts on the sheet. Knobs turn as they
 * do when selected, from 0% at pin 1 to 100% at pin 2. It is the live panel's Controls tab.
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
    /** A load's new setting, with `first` true for the first of a turn (one undo step). */
    onValue: (id: Long, value: String, first: Boolean) -> Unit = { _, _, _ -> },
) {
    val numbers = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
    val names = MaterialTheme.typography.labelLarge
    val nameWidth = textWidth(controls.maxByOrNull { it.ref.length }?.ref ?: "RV1", names)
    // The panel it sits in gives it its height and scrolls it.
    Column(modifier.testTag(LIVE_CONTROLS).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MeterReadings(schematic, animator, numbers)
        Lamps(schematic, animator)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = controlColumns(maxWidth)
            val gap = 12.dp
            val itemWidth = (maxWidth - gap * (columns - 1)) / columns
            FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), maxItemsInEachRow = columns) {
                controls.forEach { part ->
                    val row = Modifier.width(itemWidth)
                    when (part.kind) {
                        PartKind.POT -> PotRow(part, nameWidth, names, numbers, onPot, row)
                        PartKind.ELOAD -> LoadRow(part, animator, nameWidth, names, numbers, onValue, row)
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

/**
 * The sheet's LEDs as indicator lamps, lit as they are on the sheet, so status lamps can be
 * watched from the panel without hunting for them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Lamps(schematic: Schematic, animator: LiveAnimator?) {
    val view = animator?.view ?: return
    val lamps = view.readouts.filter { it.kind == PartKind.LED }
        .mapNotNull { r -> schematic.parts.firstOrNull { it.id == r.partId }?.let { it to r } }
    if (lamps.isEmpty()) return
    val outline = MaterialTheme.colorScheme.outline
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        animator.frame // follow the playback
        lamps.forEach { (part, readout) ->
            val current = LiveView.sample(readout.values, animator.position)
            // As on the sheet: brightest at 20 mA.
            val brightness = sqrt((current / 20e-3).coerceIn(0.0, 1.0)).toFloat()
            val color = ledColor(part.value)
            val lit = brightness > 0.05f
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics { contentDescription = "${part.ref} ${if (lit) "lit" else "off"}" },
            ) {
                Canvas(Modifier.size(14.dp)) {
                    if (lit) drawCircle(color.copy(alpha = 0.35f * brightness), radius = size.minDimension * 0.75f)
                    drawCircle(color.copy(alpha = 0.15f + 0.85f * brightness), radius = size.minDimension / 2)
                    drawCircle(outline, radius = size.minDimension / 2, style = Stroke(1.dp.toPx()))
                }
                Spacer(Modifier.width(6.dp))
                Text(part.ref, style = MaterialTheme.typography.labelMedium, maxLines = 1)
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

/**
 * An electronic load's front panel: its mode, a knob over its setting (as when it is selected),
 * and its display of the voltage across it and the current it draws.
 */
@Composable
private fun LoadRow(
    part: Part,
    animator: LiveAnimator?,
    nameWidth: Dp,
    names: TextStyle,
    numbers: TextStyle,
    onValue: (Long, String, Boolean) -> Unit,
    modifier: Modifier,
) {
    var knob by remember(part.id) { mutableStateOf(LiveKnob.of(part)) }
    var written by remember(part.id) { mutableStateOf(part.value) }
    var turning by remember(part.id) { mutableStateOf(false) }
    LaunchedEffect(part.value) {
        if (part.value != written) {
            knob = LiveKnob.of(part)
            written = part.value
        }
    }
    val load = ELoadValue.parse(part.value)
    val value = LiveKnob.read(part)
    Column(modifier) {
        Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(part.ref, style = names, maxLines = 1, modifier = Modifier.padding(start = 4.dp).width(nameWidth))
            Text(
                "${load.mode.token} ${value?.let { Si.format(it, load.mode.unit) } ?: load.setting}",
                style = numbers,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(textWidth("CC -888 mA", numbers) + 4.dp),
            )
            val current = knob
            if (current != null && value != null) {
                Slider(
                    value = current.fraction(value),
                    onValueChange = { f ->
                        val text = current.text(current.value(f))
                        if (text != part.value) {
                            written = text
                            onValue(part.id, text, !turning)
                            turning = true
                        }
                    },
                    onValueChangeFinished = { turning = false },
                    modifier = Modifier.weight(1f).padding(start = 8.dp).semantics { contentDescription = "${part.ref} setting" },
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        Row(Modifier.padding(start = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val reading = animator?.view?.loads?.firstOrNull { it.partId == part.id }
            if (reading != null) {
                animator.frame // follow the playback
                val volts = LiveView.sample(reading.voltage, animator.position)
                val amps = LiveView.sample(reading.current, animator.position)
                val lcd = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
                Surface(shape = RoundedCornerShape(8.dp), color = LcdBackground, contentColor = LcdDigits) {
                    Text(
                        "${Si.formatFixed(volts, "V", 4)} ${Si.formatFixed(amps, "A", 4)}",
                        style = lcd,
                        maxLines = 1,
                        // A fixed slot, so the display doesn't change width as the readings do.
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp).width(textWidth("-888.8 mV -888.8 mA", lcd)),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text("Input", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
            Switch(
                checked = load.input,
                onCheckedChange = { onValue(part.id, load.copy(input = it).format(), true) },
                modifier = Modifier.semantics { contentDescription = "${part.ref} input" },
            )
        }
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
