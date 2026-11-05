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
import io.github.l0gic0l.pepperjack.model.Part
import io.github.l0gic0l.pepperjack.model.PartKind
import io.github.l0gic0l.pepperjack.model.Probe
import io.github.l0gic0l.pepperjack.model.ProbeReading
import io.github.l0gic0l.pepperjack.model.ProbeType
import io.github.l0gic0l.pepperjack.model.ProbeValue
import io.github.l0gic0l.pepperjack.model.Schematic
import io.github.l0gic0l.pepperjack.model.transformPoint
import io.github.l0gic0l.pepperjack.sim.PlotKind
import io.github.l0gic0l.pepperjack.sim.SimulationResult
import io.github.l0gic0l.pepperjack.sim.WaveStats
import io.github.l0gic0l.pepperjack.spice.NetlistResult
import io.github.l0gic0l.pepperjack.ui.schematic.GridTransform
import io.github.l0gic0l.pepperjack.ui.schematic.SchematicViewport
import io.github.l0gic0l.pepperjack.util.Si

/** One quantity a probe reads: its value now (while live) and its measurements over the run. */
class ProbeQuantity(val now: Double?, val stats: WaveStats?, val direction: String? = null)

/**
 * The lines a measurement probe shows, like a Multisim probe: voltage and current now, with
 * the peak to peak, RMS and DC values and the frequency it was set to show. An operating
 * point has no waveform, so it shows just the values.
 */
fun probeLines(readings: Set<ProbeReading>, voltage: ProbeQuantity?, current: ProbeQuantity?, transient: Boolean): List<String> {
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

/**
 * A probe's tag: its name, then its readings, with a bar in the probe's trace colour and a
 * leader to the probe. Tags already drawn this frame are in [occupied]; a new tag tries each
 * corner around its probe, then stacks above, so tags don't cover one another.
 */
internal fun DrawScope.drawProbeTag(
    textMeasurer: TextMeasurer,
    title: String,
    lines: List<String>,
    accent: Color,
    palette: LivePalette,
    head: Offset,
    occupied: MutableList<Rect>,
) {
    val base = TextStyle(color = palette.readoutText, fontSize = 11.dp.toSp(), fontFeatureSettings = "tnum")
    val titleLayout = textMeasurer.measure(title, base.copy(fontWeight = FontWeight.Bold))
    val body = if (lines.isEmpty()) null else textMeasurer.measure(lines.joinToString("\n"), base)
    val pad = 5.dp.toPx()
    val bar = 3.dp.toPx()
    val width = maxOf(titleLayout.size.width, body?.size?.width ?: 0) + pad * 2 + bar
    val height = titleLayout.size.height + (body?.size?.height ?: 0) + pad
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
    val spot = candidates.firstOrNull { c -> occupied.none { it.overlaps(c) } } ?: run {
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
    body?.let { drawText(it, topLeft = spot.topLeft + Offset(bar + pad, pad / 2 + titleLayout.size.height)) }
}

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
            Triple(part, probe, probeLines(ProbeValue.parse(part.value), voltage, null, transient))
        }
    }
    Canvas(modifier.clipToBounds()) {
        if (viewport.scale == 0f) return@Canvas
        val occupied = ArrayList<Rect>()
        tags.forEach { (part, probe, lines) ->
            drawProbeTag(textMeasurer, part.ref, lines, colors[probe] ?: fallback, palette, probeHead(part, viewport.transform), occupied)
        }
    }
}
