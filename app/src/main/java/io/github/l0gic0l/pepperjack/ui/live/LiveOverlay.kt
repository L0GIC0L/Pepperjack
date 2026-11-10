package io.github.l0gic0l.pepperjack.ui.live

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.pepperjack.model.MeterMode
import androidx.compose.ui.text.font.FontFamily
import io.github.l0gic0l.pepperjack.model.Part
import io.github.l0gic0l.pepperjack.ui.schematic.GridTransform
import io.github.l0gic0l.pepperjack.model.PartKind
import io.github.l0gic0l.pepperjack.model.LCD_SCREEN
import io.github.l0gic0l.pepperjack.model.isAmmeter
import io.github.l0gic0l.pepperjack.model.isLcd
import io.github.l0gic0l.pepperjack.model.isMeter
import io.github.l0gic0l.pepperjack.model.Probe
import io.github.l0gic0l.pepperjack.model.ProbeType
import io.github.l0gic0l.pepperjack.model.ProbeValue
import io.github.l0gic0l.pepperjack.model.Schematic
import io.github.l0gic0l.pepperjack.model.transformPoint
import io.github.l0gic0l.pepperjack.sim.LiveReadout
import io.github.l0gic0l.pepperjack.sim.LiveView
import io.github.l0gic0l.pepperjack.ui.schematic.SchematicViewport
import io.github.l0gic0l.pepperjack.util.Si
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** What the wires' colours show; [short] is the label where there is no room for [label]. */
enum class LiveColorMode(val label: String, val short: String) { VOLTAGE("Voltage", "V"), CURRENT("Current", "I") }

/**
 * Plays a [LiveView] back: a transient loops in slow motion (the whole run takes
 * [LOOP_SECONDS] at 1×), and current dots advance at a speed set by the current.
 */
@Stable
class LiveAnimator(val view: LiveView, previous: LiveAnimator? = null) {
    // A re-simulation after an edit carries on from where the previous playback was.
    var playing: Boolean by mutableStateOf(previous?.playing ?: true)
    var speed: Float by mutableFloatStateOf(previous?.speed ?: 1f)

    /** Simulation time being shown, in seconds from the start of the run. */
    var simTime: Double by mutableDoubleStateOf(previous?.simTime?.takeIf { t: Double -> t <= view.duration } ?: 0.0)
        private set

    /** Bumped every frame so the overlay redraws without recomposing anything else. */
    internal var frame by mutableLongStateOf(0L)
        private set

    internal val phase = FloatArray(view.segments.size)

    val position: Double get() = view.position((view.time?.firstOrNull() ?: 0.0) + simTime)

    fun currentAt(segment: Int): Double = LiveView.sample(view.segments[segment].current, position)

    fun advance(seconds: Float) {
        if (playing && view.duration > 0) {
            simTime = (simTime + seconds * speed * view.duration / LOOP_SECONDS) % view.duration
        }
        val p = position
        view.segments.forEachIndexed { i, segment ->
            val velocity = dotVelocity(LiveView.sample(segment.current, p), view.maxCurrent)
            phase[i] = (phase[i] + velocity * seconds * (if (playing || view.duration == 0.0) 1f else 0f)) % DOT_SPACING
        }
        frame++
    }

    fun restart() {
        simTime = 0.0
    }

    companion object {
        const val LOOP_SECONDS = 5.0
        const val DOT_SPACING = 1.5f
        private const val MAX_DOT_SPEED = 6f

        /** Grid units per second along a segment: log-scaled so small currents still visibly move. */
        fun dotVelocity(current: Double, maxCurrent: Double): Float {
            val magnitude = abs(current)
            if (maxCurrent < 1e-9 || magnitude < 1e-9) return 0f
            val ratio = magnitude / maxCurrent
            if (ratio < 1e-4) return 0f
            val speed = 0.15f + 0.85f * ((log10(ratio).toFloat() + 4f) / 4f)
            return MAX_DOT_SPEED * speed.coerceIn(0f, 1f) * if (current > 0) 1f else -1f
        }
    }
}

@Immutable
data class LivePalette(
    val positive: Color,
    val negative: Color,
    val neutral: Color,
    val hot: Color,
    val dot: Color,
    val dotOutline: Color,
    val readoutBackground: Color,
    val readoutText: Color,
)

@Composable
fun rememberLivePalette(): LivePalette {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    return LivePalette(
        positive = if (dark) Color(0xFF81C784) else Color(0xFF2E7D32),
        negative = if (dark) Color(0xFFE57373) else Color(0xFFC62828),
        neutral = scheme.outline,
        hot = if (dark) Color(0xFFFFB74D) else Color(0xFFE65100),
        dot = Color(0xFFFFD600),
        dotOutline = Color(0x99000000),
        readoutBackground = scheme.inverseSurface,
        readoutText = scheme.inverseOnSurface,
    )
}

/** Wire colour for a voltage as a fraction of the largest in the circuit (−1..1). */
fun voltageColor(palette: LivePalette, fraction: Double): Color {
    val f = abs(fraction).coerceIn(0.0, 1.0).pow(0.7).toFloat()
    return lerp(palette.neutral, if (fraction >= 0) palette.positive else palette.negative, f)
}

fun currentColor(palette: LivePalette, fraction: Double): Color {
    if (fraction <= 1e-4) return palette.neutral
    val f = ((log10(fraction) + 4) / 4).coerceIn(0.0, 1.0).toFloat()
    return lerp(palette.neutral, palette.hot, f)
}

/**
 * Draws the live view over the schematic: coloured wires, moving current dots, glowing
 * LEDs, meter readings and the inspected wire's values. Draws only; touches pass through.
 */
@Composable
fun LiveOverlay(
    animator: LiveAnimator,
    schematic: Schematic,
    viewport: SchematicViewport,
    mode: LiveColorMode,
    inspectWireId: Long?,
    modifier: Modifier = Modifier,
    /** Trace colours, for the probes' tags. */
    probeColors: Map<Probe, Color> = emptyMap(),
) {
    val palette = rememberLivePalette()
    val textMeasurer = rememberTextMeasurer()
    LaunchedEffect(animator) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) animator.advance(((now - last) / 1e9f).coerceAtMost(0.1f))
                last = now
            }
        }
    }
    Canvas(modifier.clipToBounds()) {
        animator.frame // redraw every frame
        if (viewport.scale == 0f) return@Canvas
        drawLive(animator, schematic, viewport, mode, inspectWireId, palette, textMeasurer, probeColors)
    }
}

private fun DrawScope.drawLive(
    animator: LiveAnimator,
    schematic: Schematic,
    viewport: SchematicViewport,
    mode: LiveColorMode,
    inspectWireId: Long?,
    palette: LivePalette,
    textMeasurer: TextMeasurer,
    probeColors: Map<Probe, Color>,
) {
    val view = animator.view
    val transform = viewport.transform
    val position = animator.position
    val stroke = 3.5.dp.toPx()
    val dotRadius = 2.6.dp.toPx()

    // LEDs glow with their current, brightest at 20 mA.
    view.readouts.filter { it.kind == PartKind.LED }.forEach { readout ->
        val part = schematic.parts.firstOrNull { it.id == readout.partId } ?: return@forEach
        val current = LiveView.sample(readout.values, position)
        if (current <= 1e-5) return@forEach
        val brightness = sqrt((current / 20e-3).coerceIn(0.0, 1.0)).toFloat()
        val (cx, cy) = transformPoint(0f, 2f, part.rotation, part.mirrored)
        val center = transform.toScreen(part.x + cx, part.y + cy)
        val color = ledColor(part.value)
        val radius = transform.scale * 2.2f
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = 0.85f * brightness), color.copy(alpha = 0f)), center, radius),
            radius = radius,
            center = center,
        )
    }

    view.segments.forEachIndexed { i, segment ->
        val a = transform.toScreen(segment.a)
        val b = transform.toScreen(segment.b)
        val current = LiveView.sample(segment.current, position)
        val color = when (mode) {
            LiveColorMode.VOLTAGE -> segment.voltage?.let {
                voltageColor(palette, if (view.maxVoltage > 0) LiveView.sample(it, position) / view.maxVoltage else 0.0)
            } ?: palette.neutral
            LiveColorMode.CURRENT -> currentColor(palette, if (view.maxCurrent > 0) abs(current) / view.maxCurrent else 0.0)
        }
        drawLine(color, a, b, stroke, StrokeCap.Round)

        if (LiveAnimator.dotVelocity(current, view.maxCurrent) == 0f) return@forEachIndexed
        val length = hypot(b.x - a.x, b.y - a.y)
        if (length < 1f) return@forEachIndexed
        val spacing = LiveAnimator.DOT_SPACING * transform.scale
        var offset = ((animator.phase[i] % LiveAnimator.DOT_SPACING) + LiveAnimator.DOT_SPACING) % LiveAnimator.DOT_SPACING * transform.scale
        while (offset <= length) {
            val t = offset / length
            val c = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            drawCircle(palette.dot, dotRadius, c)
            drawCircle(palette.dotOutline, dotRadius, c, style = Stroke(1.dp.toPx()))
            offset += spacing
        }
    }

    // Meters show their reading on their face, like a real one. Digits keep their width so a
    // changing reading doesn't make the tag jitter.
    val style = TextStyle(
        color = palette.readoutText,
        fontSize = 12.dp.toSp(),
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    )
    view.readouts.filter { it.kind.isMeter }.forEach { readout ->
        val part = schematic.parts.firstOrNull { it.id == readout.partId } ?: return@forEach
        val text = meterText(part.meter, readout, view, position)
        if (part.kind.isLcd) {
            drawLcd(textMeasurer, text, part, transform)
        } else {
            val (fx, fy) = transformPoint(0f, 2f, part.rotation, part.mirrored)
            drawTag(textMeasurer, text, style, palette, transform.toScreen(part.x + fx, part.y + fy), centered = true)
        }
    }

    // Measurement probes show their readings in a tag, as Multisim's probes do.
    val transient = view.duration > 0
    val occupied = ArrayList<androidx.compose.ui.geometry.Rect>()
    view.probes.forEach { probe ->
        val part = schematic.parts.firstOrNull { it.id == probe.partId } ?: return@forEach
        val voltage = probe.voltage?.let { ProbeQuantity(LiveView.sample(it, position), if (transient) view.stats(probe, false) else null) }
        val current = probe.current?.let {
            ProbeQuantity(LiveView.sample(it, position), if (transient) view.stats(probe, true) else null, probe.direction)
        }
        val lines = probeLines(ProbeValue.parse(part.value), voltage, current, transient)
        val accent = probeColors[Probe(ProbeType.VOLTAGE, part.x, part.y)] ?: palette.hot
        drawProbeTag(textMeasurer, part.ref, lines, accent, palette, probeHead(part, transform), occupied)
    }

    inspectWireId?.let { id ->
        val segment = view.segments.firstOrNull { it.wireId == id } ?: return@let
        val index = view.segments.indexOf(segment)
        val parts = buildList {
            segment.net?.let { net -> add(if (net == "0") "Ground" else net) }
            segment.voltage?.let { add(Si.formatFixed(LiveView.sample(it, position), "V")) }
            add(Si.formatFixed(abs(animator.currentAt(index)), "A"))
        }
        val mid = transform.toScreen((segment.a.x + segment.b.x) / 2f, (segment.a.y + segment.b.y) / 2f)
        drawTag(textMeasurer, parts.joinToString("  ·  "), style, palette, mid + Offset(8.dp.toPx(), -30.dp.toPx()))
    }
}

/** A backlit panel meter's colours, the same in light and dark themes. */
internal val LcdBackground = Color(0xFF102018)
internal val LcdDigits = Color(0xFF8CF5A8)

/**
 * An LCD meter's reading, filling its screen in digits as large as fit, so it can be read at
 * a glance even zoomed out, where other text is hidden.
 */
private fun DrawScope.drawLcd(textMeasurer: TextMeasurer, text: String, part: Part, transform: GridTransform) {
    val (ax, ay) = transformPoint(LCD_SCREEN[0], LCD_SCREEN[1], part.rotation, part.mirrored)
    val (bx, by) = transformPoint(LCD_SCREEN[2], LCD_SCREEN[3], part.rotation, part.mirrored)
    val a = transform.toScreen(part.x + ax, part.y + ay)
    val b = transform.toScreen(part.x + bx, part.y + by)
    val screen = androidx.compose.ui.geometry.Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
    if (screen.width < 2f || screen.height < 2f) return
    drawRoundRect(LcdBackground, screen.topLeft, screen.size, CornerRadius(screen.height * 0.12f))
    val base = TextStyle(color = LcdDigits, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
    var layout = textMeasurer.measure(text, base.copy(fontSize = (screen.height * 0.6f).toSp()))
    val fit = screen.width * 0.92f / layout.size.width
    if (fit < 1f) layout = textMeasurer.measure(text, base.copy(fontSize = (screen.height * 0.6f * fit).toSp()))
    drawText(layout, topLeft = screen.center - Offset(layout.size.width / 2f, layout.size.height / 2f))
}

/** A meter's reading in its [mode]: the value now, or a measurement over the whole run. */
fun meterText(mode: MeterMode, readout: LiveReadout, view: LiveView, position: Double): String {
    // A panel meter shows four digits, as a 3½-digit LCD does.
    val digits = if (readout.kind.isLcd) 4 else 3
    val unit = if (readout.kind.isAmmeter) "A" else "V"
    val value = when (mode) {
        MeterMode.NOW -> LiveView.sample(readout.values, position)
        MeterMode.DC -> view.stats(readout)?.mean
        MeterMode.AC -> view.stats(readout)?.acRms
        MeterMode.RMS -> view.stats(readout)?.rms
        MeterMode.PP -> view.stats(readout)?.peakToPeak
    } ?: Double.NaN
    return Si.formatFixed(value, unit, digits) + mode.suffix
}

private fun DrawScope.drawTag(
    textMeasurer: TextMeasurer,
    text: String,
    style: TextStyle,
    palette: LivePalette,
    at: Offset,
    centered: Boolean = false,
) {
    val layout = textMeasurer.measure(text, style)
    val pad = 6.dp.toPx()
    val size = Size(layout.size.width + pad * 2, layout.size.height + pad)
    val margin = 4.dp.toPx()
    val wanted = if (centered) at - Offset(size.width / 2, size.height / 2) else at
    // Keep the tag on screen.
    val topLeft = Offset(
        wanted.x.coerceIn(margin, maxOf(margin, this.size.width - size.width - margin)),
        wanted.y.coerceIn(margin, maxOf(margin, this.size.height - size.height - margin)),
    )
    drawRoundRect(palette.readoutBackground, topLeft = topLeft, size = size, cornerRadius = CornerRadius(8.dp.toPx()))
    drawText(layout, topLeft = topLeft + Offset(pad, pad / 2))
}

private fun ledColor(model: String): Color {
    val name = model.uppercase()
    return when {
        "GREEN" in name -> Color(0xFF00E676)
        "BLUE" in name -> Color(0xFF2979FF)
        "YELLOW" in name -> Color(0xFFFFEA00)
        "WHITE" in name -> Color(0xFFFFFFF0)
        "IR" in name -> Color(0xFF8E2424)
        else -> Color(0xFFFF1744)
    }
}
