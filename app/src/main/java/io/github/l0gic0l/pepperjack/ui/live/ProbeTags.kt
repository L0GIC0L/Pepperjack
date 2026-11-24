package io.github.l0gic0l.pepperjack.ui.live

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.pepperjack.data.TagDetail
import io.github.l0gic0l.pepperjack.model.Part
import io.github.l0gic0l.pepperjack.model.PartKind
import io.github.l0gic0l.pepperjack.model.Probe
import io.github.l0gic0l.pepperjack.model.ProbeReading
import io.github.l0gic0l.pepperjack.model.ProbeType
import io.github.l0gic0l.pepperjack.model.ProbeValue
import io.github.l0gic0l.pepperjack.model.Schematic
import io.github.l0gic0l.pepperjack.model.bounds
import io.github.l0gic0l.pepperjack.model.transformPoint
import io.github.l0gic0l.pepperjack.sim.PlotKind
import io.github.l0gic0l.pepperjack.sim.SimulationResult
import io.github.l0gic0l.pepperjack.sim.WaveStats
import io.github.l0gic0l.pepperjack.spice.NetlistResult
import io.github.l0gic0l.pepperjack.ui.schematic.GridTransform
import io.github.l0gic0l.pepperjack.ui.schematic.SchematicViewport
import io.github.l0gic0l.pepperjack.util.Si
import kotlin.math.abs

/** One quantity a probe reads: its value now (while live) and its measurements over the run. */
class ProbeQuantity(val now: Double?, val stats: WaveStats?, val direction: String? = null) {
    /**
     * A current that never reaches a nanoamp, such as the leakage along a wire into a source or a
     * gate: a probe there reads the voltage, and the current line would only be noise.
     */
    val negligible: Boolean
        get() = maxOf(abs(now ?: 0.0), abs(stats?.min ?: 0.0), abs(stats?.max ?: 0.0)) < 1e-9
}

/**
 * A probe's compact tag: one line of its readings now (while live), or, after a run, the first
 * measurement it was set to show, for the voltage and then the current.
 */
fun probeCompactLine(readings: Set<ProbeReading>, voltage: ProbeQuantity?, current: ProbeQuantity?, transient: Boolean): String {
    fun cell(unit: String, q: ProbeQuantity): String {
        q.now?.let { return Si.formatFixed(it, unit) }
        val s = q.stats ?: return "—"
        return when {
            !transient -> Si.formatFixed(s.mean, unit)
            ProbeReading.PEAK_TO_PEAK in readings -> "p-p " + Si.formatFixed(s.peakToPeak, unit)
            ProbeReading.RMS in readings -> "rms " + Si.formatFixed(s.rms, unit)
            else -> "dc " + Si.formatFixed(s.mean, unit)
        }
    }
    return buildList {
        if (ProbeReading.VOLTAGE in readings && voltage != null) add(cell("V", voltage))
        if (ProbeReading.CURRENT in readings && current != null && !current.negligible) add(cell("A", current))
    }.joinToString("  ")
}

/**
 * The lines a measurement probe shows, like a Multisim probe: voltage and current now, with
 * the peak to peak, RMS and DC values and the frequency it was set to show. An operating
 * point has no waveform, so it shows just the values.
 */
fun probeLines(readings: Set<ProbeReading>, voltage: ProbeQuantity?, current: ProbeQuantity?, transient: Boolean): List<String> {
    val current = current?.takeUnless { it.negligible }
    fun line(symbol: String, unit: String, q: ProbeQuantity): String? {
        val cells = buildList {
            q.now?.let { add(Si.formatFixed(it, unit)) }
            if (transient) {
                val s = q.stats
                if (ProbeReading.PEAK_TO_PEAK in readings) add("p-p " + Si.formatFixed(s?.peakToPeak ?: Double.NaN, unit))
                if (ProbeReading.RMS in readings) add("rms " + Si.formatFixed(s?.rms ?: Double.NaN, unit))
                if (ProbeReading.DC in readings || (q.now == null && isEmpty())) add("dc " + Si.formatFixed(s?.mean ?: Double.NaN, unit))
            } else if (q.now == null) {
                add(Si.formatFixed(q.stats?.mean ?: Double.NaN, unit))
            }
        }
        if (cells.isEmpty()) return null
        return (symbol + (q.direction?.let { " $it" } ?: "")).padEnd(3) + " " + cells.joinToString("  ")
    }
    return buildList {
        if (ProbeReading.VOLTAGE in readings && voltage != null) line("V", "V", voltage)?.let(::add)
        if (ProbeReading.CURRENT in readings && current != null) line("I", "A", current)?.let(::add)
        if (ProbeReading.FREQUENCY in readings && transient && voltage != null) {
            add("f   " + Si.formatFixed(voltage.stats?.frequency ?: Double.NaN, "Hz"))
        }
    }
}

/** Where a probe's tag sits: beside the probe's head, which points up-left from its tip at rotation 0. */
internal fun probeHead(part: Part, transform: GridTransform): Offset {
    val (hx, hy) = transformPoint(-1.6f, -1.6f, part.rotation, part.mirrored)
    return transform.toScreen(part.x + hx, part.y + hy)
}

/** Where the sheet's parts are on screen, for tags to keep clear of. */
internal fun partRects(schematic: Schematic, transform: GridTransform): List<Rect> =
    schematic.parts.filter { it.kind != PartKind.PROBE && it.kind != PartKind.TEXT }.map { part ->
        val b = part.bounds()
        Rect(transform.toScreen(b[0], b[1]), transform.toScreen(b[2], b[3]))
    }

/** The tags for each probe at [detail]: nothing when hidden; the selected probe shows everything. */
internal fun tagDetailFor(part: Part, detail: TagDetail, selectedPartId: Long?): TagDetail =
    if (part.id == selectedPartId) TagDetail.FULL else detail

/**
 * A probe's tag: its name, then its readings, with a bar in the probe's trace colour and a
 * leader to the probe. A [compact] tag is one row, the name then the readings. Tags already drawn
 * this frame are in [occupied]; a new tag tries each corner around its probe, preferring one
 * clear of other tags and then of the [obstacles] (the parts), and stacks above when all four
 * are taken, so tags don't cover one another or, where there's room, the circuit.
 */
internal fun DrawScope.drawProbeTag(
    textMeasurer: TextMeasurer,
    title: String,
    lines: List<String>,
    accent: Color,
    palette: LivePalette,
    head: Offset,
    occupied: MutableList<Rect>,
    compact: Boolean = false,
    obstacles: List<Rect> = emptyList(),
) {
    val base = TextStyle(color = palette.readoutText, fontSize = (if (compact) 10 else 11).dp.toSp(), fontFeatureSettings = "tnum")
    val titleLayout = textMeasurer.measure(title, base.copy(fontWeight = FontWeight.Bold))
    val body = if (lines.isEmpty()) null else textMeasurer.measure(lines.joinToString("\n"), base)
    val pad = (if (compact) 4 else 5).dp.toPx()
    val bar = 3.dp.toPx()
    val inlineGap = 6.dp.toPx()
    val width = if (compact) {
        titleLayout.size.width + (body?.let { it.size.width + inlineGap } ?: 0f) + pad * 2 + bar
    } else {
        maxOf(titleLayout.size.width, body?.size?.width ?: 0) + pad * 2 + bar
    }
    val height = if (compact) maxOf(titleLayout.size.height, body?.size?.height ?: 0) + pad else titleLayout.size.height + (body?.size?.height ?: 0) + pad
    val margin = 4.dp.toPx()
    val gap = 10.dp.toPx()
    fun clamped(x: Float, y: Float) = Rect(
        Offset(x.coerceIn(margin, maxOf(margin, size.width - width - margin)), y.coerceIn(margin, maxOf(margin, size.height - height - margin))),
        Size(width, height),
    )
    val candidates = listOf(
        clamped(head.x - width - gap, head.y - height - gap / 2),
        clamped(head.x + gap, head.y - height - gap / 2),
        clamped(head.x - width - gap, head.y + gap / 2),
        clamped(head.x + gap, head.y + gap / 2),
    )
    fun covered(c: Rect) = obstacles.sumOf { o -> if (o.overlaps(c)) (o.intersect(c).width * o.intersect(c).height).toDouble() else 0.0 }
    val spot = candidates.filter { c -> occupied.none { it.overlaps(c) } }.minByOrNull(::covered) ?: run {
        val first = candidates.first()
        val top = occupied.filter { it.overlaps(first) }.minOf { it.top }
        clamped(first.left, top - height - margin)
    }
    occupied += spot
    val anchor = Offset(head.x.coerceIn(spot.left, spot.right), head.y.coerceIn(spot.top, spot.bottom))
    drawLine(accent, head, anchor, 1.dp.toPx())
    drawRoundRect(palette.readoutBackground, spot.topLeft, spot.size, CornerRadius(6.dp.toPx()))
    drawRoundRect(accent, spot.topLeft, Size(bar, height), CornerRadius(6.dp.toPx()))
    drawText(titleLayout, topLeft = spot.topLeft + Offset(bar + pad, pad / 2))
    body?.let {
        val at = if (compact) Offset(bar + pad + titleLayout.size.width + inlineGap, pad / 2) else Offset(bar + pad, pad / 2 + titleLayout.size.height)
        drawText(it, topLeft = spot.topLeft + at)
    }
}

private class ProbeTagText(val part: Part, val probe: Probe, val lines: List<String>, val compact: String)

/**
 * Probe tags from the last simulation run, when not live: the voltage measurements of each
 * probe's net over the run (a live netlist is needed for currents).
 */
@Composable
fun ProbeResultTags(
    schematic: Schematic,
    result: SimulationResult?,
    netlist: NetlistResult?,
    viewport: SchematicViewport,
    colors: Map<Probe, Color>,
    modifier: Modifier = Modifier,
    detail: TagDetail = TagDetail.FULL,
    selectedPartId: Long? = null,
) {
    val probes = schematic.parts.filter { it.kind == PartKind.PROBE }
    if (probes.isEmpty() || result == null || netlist == null) return
    val plot = result.plots.firstOrNull { it.kind == PlotKind.TRAN } ?: result.plots.firstOrNull { it.kind == PlotKind.OP } ?: return
    val transient = plot.kind == PlotKind.TRAN
    val palette = rememberLivePalette()
    val textMeasurer = rememberTextMeasurer()
    val fallback = MaterialTheme.colorScheme.primary
    val tags = remember(probes, plot, netlist) {
        probes.mapNotNull { part ->
            val probe = Probe(ProbeType.VOLTAGE, part.x, part.y)
            val vector = netlist.probeVectors.firstOrNull { it.probe == probe }?.vector ?: return@mapNotNull null
            val values = plot.vectors.firstOrNull { it.name.equals(vector, ignoreCase = true) }?.real ?: return@mapNotNull null
            val voltage = if (transient) {
                ProbeQuantity(null, WaveStats.of(plot.scale?.real, values))
            } else {
                ProbeQuantity(values.firstOrNull(), null)
            }
            val readings = ProbeValue.parse(part.value)
            ProbeTagText(part, probe, probeLines(readings, voltage, null, transient), probeCompactLine(readings, voltage, null, transient))
        }
    }
    Canvas(modifier.clipToBounds()) {
        if (viewport.scale == 0f) return@Canvas
        val occupied = ArrayList<Rect>()
        val obstacles = partRects(schematic, viewport.transform)
        tags.forEach { tag ->
            val level = tagDetailFor(tag.part, detail, selectedPartId)
            if (level == TagDetail.OFF) return@forEach
            val compact = level == TagDetail.COMPACT
            drawProbeTag(
                textMeasurer, tag.part.ref, if (compact) listOf(tag.compact) else tag.lines, colors[tag.probe] ?: fallback, palette,
                probeHead(tag.part, viewport.transform), occupied, compact, obstacles,
            )
        }
    }
}
