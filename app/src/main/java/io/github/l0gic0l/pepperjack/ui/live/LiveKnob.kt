package io.github.l0gic0l.pepperjack.ui.live

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.pepperjack.model.Part
import io.github.l0gic0l.pepperjack.model.PartKind
import io.github.l0gic0l.pepperjack.ui.editor.SourceSpec
import io.github.l0gic0l.pepperjack.ui.editor.SourceType
import io.github.l0gic0l.pepperjack.util.Si
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.ln
import kotlin.math.pow

/**
 * A slider over one part's value while live simulating. Resistance, capacitance and
 * inductance sweep logarithmically over a decade either side of where the knob started; a
 * DC source sweeps linearly from zero to twice its value. Values snap to two significant
 * figures, as standard part values do.
 */
class LiveKnob private constructor(
    val unit: String,
    val low: Double,
    val high: Double,
    private val logarithmic: Boolean,
    private val write: (Double) -> String,
) {
    fun fraction(value: Double): Float {
        val f = if (logarithmic) ln(value / low) / ln(high / low) else (value - low) / (high - low)
        return f.toFloat().coerceIn(0f, 1f)
    }

    fun value(fraction: Float): Double {
        val f = fraction.coerceIn(0f, 1f).toDouble()
        val raw = if (logarithmic) low * (high / low).pow(f) else low + (high - low) * f
        return if (raw == 0.0) 0.0 else BigDecimal(raw).round(MathContext(2)).toDouble()
    }

    /** The part value for [value], as it is written into the netlist. */
    fun text(value: Double): String = write(value)

    companion object {
        /** A knob centred on [part]'s present value, or null if the part has no single value to sweep. */
        fun of(part: Part): LiveKnob? {
            val value = read(part) ?: return null
            return when (part.kind) {
                PartKind.RESISTOR, PartKind.CAPACITOR, PartKind.INDUCTOR -> {
                    if (value <= 0) return null
                    val unit = when (part.kind) {
                        PartKind.RESISTOR -> "Ω"
                        PartKind.CAPACITOR -> "F"
                        else -> "H"
                    }
                    LiveKnob(unit, value / 10, value * 10, logarithmic = true) { Si.formatSpice(it) }
                }
                else -> {
                    val spec = SourceSpec.parse(part.value)
                    val (low, high) = when {
                        value > 0 -> 0.0 to 2 * value
                        value < 0 -> 2 * value to 0.0
                        else -> 0.0 to 10.0
                    }
                    LiveKnob(if (part.kind == PartKind.VOLTAGE) "V" else "A", low, high, logarithmic = false) {
                        spec.copy(dc = Si.formatSpice(it)).toSpice()
                    }
                }
            }
        }

        /** The value a knob would sweep: a plain R, C or L value, or a DC source's level. */
        fun read(part: Part): Double? = when (part.kind) {
            PartKind.RESISTOR, PartKind.CAPACITOR, PartKind.INDUCTOR -> Si.parse(part.value)
            PartKind.VOLTAGE, PartKind.CURRENT -> SourceSpec.parse(part.value).takeIf { it.type == SourceType.DC }?.let { Si.parse(it.dc) }
            else -> null
        }?.takeIf { it.isFinite() }
    }
}

/**
 * Knob for the selected part while live simulating. The range is fixed while you turn it,
 * and follows the part again if its value is edited some other way. [onValue] gets each new
 * value, with `first` true for the first one of a turn so that a turn is one undo step.
 */
@Composable
fun ValueKnob(part: Part, onValue: (value: String, first: Boolean) -> Unit, modifier: Modifier = Modifier) {
    var knob by remember(part.id) { mutableStateOf(LiveKnob.of(part)) }
    var written by remember(part.id) { mutableStateOf(part.value) }
    var turning by remember(part.id) { mutableStateOf(false) }
    LaunchedEffect(part.value) {
        if (part.value != written) {
            knob = LiveKnob.of(part)
            written = part.value
        }
    }
    val current = knob ?: return
    val value = LiveKnob.read(part) ?: return
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 3.dp,
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${part.ref} ${Si.format(value, current.unit)}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.widthIn(min = 96.dp),
            )
            Slider(
                value = current.fraction(value),
                onValueChange = { f ->
                    val text = current.text(current.value(f))
                    if (text != part.value) {
                        written = text
                        onValue(text, !turning)
                        turning = true
                    }
                },
                onValueChangeFinished = { turning = false },
                modifier = Modifier.width(220.dp),
            )
        }
    }
}
