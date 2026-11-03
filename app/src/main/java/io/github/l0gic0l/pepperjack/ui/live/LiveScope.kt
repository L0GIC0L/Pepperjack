package io.github.l0gic0l.pepperjack.ui.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.pepperjack.model.Probe
import io.github.l0gic0l.pepperjack.sim.LiveTrace
import io.github.l0gic0l.pepperjack.sim.LiveView
import io.github.l0gic0l.pepperjack.sim.Quantity
import io.github.l0gic0l.pepperjack.util.Si
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

const val LIVE_SCOPE = "live-scope"

/**
 * What an operating point's probes read over the last [WINDOW_SECONDS] of wall time, so the
 * scope can roll like a real one while switches flip and knobs turn. Outlives each
 * re-simulation; a probe's history starts when it is first seen.
 */
@Stable
class ScopeHistory {
    internal val times = ArrayDeque<Double>()
    internal val values = HashMap<Probe, ArrayDeque<Double>>()
    internal var clock = 0.0
        private set

    /** Bumped on every sample so the scope redraws. */
    internal var revision by mutableLongStateOf(0L)
        private set

    fun record(traces: List<LiveTrace>, seconds: Double) {
        clock += seconds
        times.addLast(clock)
        val live = traces.associateBy { it.probe }
        // Keep every column the same length: a probe with no reading this frame repeats NaN.
        for ((probe, column) in values) column.addLast(live[probe]?.values?.firstOrNull() ?: Double.NaN)
        for (trace in traces) {
            if (trace.probe !in values) {
                values[trace.probe] = ArrayDeque<Double>().apply {
                    repeat(times.size - 1) { addLast(Double.NaN) }
                    addLast(trace.values.firstOrNull() ?: Double.NaN)
                }
            }
        }
        while (times.isNotEmpty() && times.first() < clock - WINDOW_SECONDS) {
            times.removeFirst()
            values.values.forEach { it.removeFirst() }
        }
        values.entries.removeAll { (probe, column) -> probe !in live && column.all { it.isNaN() } }
        revision++
    }

    companion object {
        const val WINDOW_SECONDS = 10.0
        const val SAMPLE_SECONDS = 1 / 30.0
    }
}

/** A plot range that includes zero and leaves a margin, so a flat trace sits clear of the edges. */
fun scopeRange(low: Double, high: Double): Pair<Double, Double> {
    if (low.isNaN() || high.isNaN()) return -1.0 to 1.0
    var lo = min(low, 0.0)
    var hi = max(high, 0.0)
    if (hi - lo < 1e-15) {
        val pad = max(abs(hi), 1e-12)
        lo -= pad
        hi += pad
    }
    val margin = (hi - lo) * 0.1
    return (lo - margin) to (hi + margin)
}

/**
 * The live oscilloscope: the schematic's probes, plotted while live simulating. A transient
 * is swept in step with the playback, with a cursor at the time being shown; an operating
 * point rolls along over the last ten seconds. Voltages share the left scale, currents the
 * right, so channels of one kind compare directly.
 */
@Composable
fun LiveScope(
    animator: LiveAnimator?,
    history: ScopeHistory,
    colors: Map<Probe, Color>,
    onRemove: (Probe) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val view = animator?.view
    val traces = view?.traces.orEmpty()
    val rolling = view != null && view.duration == 0.0

    LaunchedEffect(animator, rolling) {
        if (animator == null || !rolling) return@LaunchedEffect
        var last = 0L
        var pending = 0.0
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) pending += (now - last) / 1e9
                last = now
                if (pending >= ScopeHistory.SAMPLE_SECONDS) {
                    history.record(animator.view.traces, pending.coerceAtMost(0.25))
                    pending = 0.0
                }
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
        modifier = modifier.testTag(LIVE_SCOPE),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Scope", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp, end = 4.dp))
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (traces.isEmpty()) {
                        Text(
                            "Probe a wire or a part to see its waveform",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    traces.forEach { trace ->
                        val value = if (animator != null) LiveView.sample(trace.values, animator.position) else 0.0
                        InputChip(
                            selected = false,
                            onClick = { onRemove(trace.probe) },
                            label = { Text("${trace.label}  ${Si.format(value, trace.quantity.unit)}") },
                            leadingIcon = {
                                Box(Modifier.size(10.dp).background(colors[trace.probe] ?: MaterialTheme.colorScheme.primary, CircleShape))
                            },
                            trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Remove ${trace.label}", Modifier.size(16.dp)) },
                        )
                    }
                }
                if (traces.isNotEmpty()) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                            contentDescription = if (expanded) "Hide the scope" else "Show the scope",
                        )
                    }
                }
            }
            if (expanded && animator != null && traces.isNotEmpty()) {
                val grid = MaterialTheme.colorScheme.outlineVariant
                val label = MaterialTheme.colorScheme.onSurfaceVariant
                val textMeasurer = rememberTextMeasurer()
                Canvas(Modifier.fillMaxWidth().height(120.dp).padding(bottom = 4.dp).clipToBounds()) {
                    animator.frame // redraw with the playback
                    history.revision
                    drawGrid(grid)
                    if (rolling) drawRolling(history, traces, colors, label, textMeasurer) else drawSweep(animator, traces, colors, label, textMeasurer)
                }
            }
        }
    }
}

private fun DrawScope.drawGrid(color: Color) {
    val stroke = 1.dp.toPx()
    for (i in 0..10) drawLine(color, Offset(size.width * i / 10, 0f), Offset(size.width * i / 10, size.height), stroke)
    for (i in 0..4) drawLine(color, Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4), stroke)
}

/** Shared scales: one for every voltage channel, one for every current channel. */
private class Scales(private val traces: List<LiveTrace>, private val values: (LiveTrace) -> Sequence<Double>) {
    private fun rangeOf(quantity: Quantity): Pair<Double, Double>? {
        val all = traces.filter { it.quantity == quantity }.flatMap { values(it).filterNot(Double::isNaN) }
        if (all.isEmpty()) return null
        return scopeRange(all.min(), all.max())
    }

    val voltage = rangeOf(Quantity.VOLTAGE)
    val current = rangeOf(Quantity.CURRENT)

    /** A trace with no finite readings (a failed run reads NaN) gets a nominal scale. */
    fun of(trace: LiveTrace) = (if (trace.quantity == Quantity.VOLTAGE) voltage else current) ?: (-1.0 to 1.0)
}

private fun DrawScope.y(value: Double, range: Pair<Double, Double>): Float =
    (size.height * (1 - (value - range.first) / (range.second - range.first))).toFloat()

private fun DrawScope.drawSweep(
    animator: LiveAnimator,
    traces: List<LiveTrace>,
    colors: Map<Probe, Color>,
    label: Color,
    textMeasurer: TextMeasurer,
) {
    val view = animator.view
    val scales = Scales(traces) { it.values.asSequence() }
    val duration = view.duration
    val cursor = (animator.simTime / duration * size.width).toFloat()
    val stroke = 2.dp.toPx()
    traces.forEach { trace ->
        val color = colors[trace.probe] ?: label
        val range = scales.of(trace)
        val path = Path()
        trace.values.forEachIndexed { i, v ->
            val x = (view.elapsed(i) / duration * size.width).toFloat()
            if (i == 0) path.moveTo(x, y(v, range)) else path.lineTo(x, y(v, range))
        }
        // The last sweep stays faintly; the part already swept this time round is bright.
        drawPath(path, color.copy(alpha = 0.3f), style = Stroke(stroke))
        clipRect(right = cursor) { drawPath(path, color, style = Stroke(stroke)) }
    }
    drawLine(label, Offset(cursor, 0f), Offset(cursor, size.height), 1.dp.toPx())
    drawScaleLabels(scales, Si.format(duration, "s"), label, textMeasurer)
}

private fun DrawScope.drawRolling(
    history: ScopeHistory,
    traces: List<LiveTrace>,
    colors: Map<Probe, Color>,
    label: Color,
    textMeasurer: TextMeasurer,
) {
    val times = history.times
    if (times.isEmpty()) return
    val scales = Scales(traces) { history.values[it.probe]?.asSequence() ?: it.values.asSequence() }
    val start = history.clock - ScopeHistory.WINDOW_SECONDS
    val stroke = 2.dp.toPx()
    traces.forEach { trace ->
        val column = history.values[trace.probe] ?: return@forEach
        val range = scales.of(trace)
        val path = Path()
        var drawing = false
        column.forEachIndexed { i, v ->
            if (v.isNaN()) {
                drawing = false
                return@forEachIndexed
            }
            val x = ((times[i] - start) / ScopeHistory.WINDOW_SECONDS * size.width).toFloat()
            if (drawing) path.lineTo(x, y(v, range)) else path.moveTo(x, y(v, range))
            drawing = true
        }
        drawPath(path, colors[trace.probe] ?: label, style = Stroke(stroke))
    }
    drawScaleLabels(scales, "${ScopeHistory.WINDOW_SECONDS.toInt()} s", label, textMeasurer)
}

private fun DrawScope.drawScaleLabels(scales: Scales, timebase: String, color: Color, textMeasurer: TextMeasurer) {
    val style = TextStyle(color = color, fontSize = 10.dp.toSp())
    val pad = 3.dp.toPx()
    fun text(s: String, x: Float, y: Float, alignRight: Boolean = false) {
        val layout = textMeasurer.measure(s, style)
        drawText(layout, topLeft = Offset(if (alignRight) x - layout.size.width else x, y))
    }
    fun bottom() = size.height - pad - textMeasurer.measure("0", style).size.height
    scales.voltage?.let { (lo, hi) ->
        text(Si.format(hi, "V"), pad, pad)
        text(Si.format(lo, "V"), pad, bottom())
    }
    scales.current?.let { (lo, hi) ->
        text(Si.format(hi, "A"), size.width - pad, pad, alignRight = true)
        text(Si.format(lo, "A"), size.width - pad, bottom(), alignRight = true)
    }
    text(timebase, size.width / 2, bottom())
}
