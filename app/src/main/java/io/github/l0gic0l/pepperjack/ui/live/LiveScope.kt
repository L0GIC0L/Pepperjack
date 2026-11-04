package io.github.l0gic0l.pepperjack.ui.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import io.github.l0gic0l.pepperjack.sim.Measure
import io.github.l0gic0l.pepperjack.sim.WaveStats
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
 *
 * Like a bench scope it measures each channel (mean, RMS, peak to peak, frequency and so on)
 * and has two time cursors, A and B, to drag; with the cursors on, measurements cover only
 * the time between them.
 */
@Composable
fun LiveScope(
    animator: LiveAnimator?,
    history: ScopeHistory,
    colors: Map<Probe, Color>,
    onRemove: (Probe) -> Unit,
    modifier: Modifier = Modifier,
    controls: (@Composable () -> Unit)? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    // A sheet with knobs or switches opens on them; the scope is a tap away.
    var showControls by rememberSaveable { mutableStateOf(controls != null) }
    val onControls = controls != null && showControls
    var measureMask by rememberSaveable { mutableIntStateOf(0) }
    var cursorsOn by rememberSaveable { mutableStateOf(false) }
    var cursorA by rememberSaveable { mutableFloatStateOf(0.25f) }
    var cursorB by rememberSaveable { mutableFloatStateOf(0.75f) }
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

    val numbers = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
        modifier = modifier.testTag(LIVE_SCOPE),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (controls == null) {
                    Text("Scope", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp, end = 4.dp))
                } else {
                    Row(Modifier.padding(start = 4.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(selected = !showControls, onClick = { showControls = false; expanded = true }, label = { Text("Scope") })
                        FilterChip(selected = showControls, onClick = { showControls = true; expanded = true }, label = { Text("Controls") })
                    }
                }
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!onControls && traces.isEmpty()) {
                        Text(
                            "Probe a wire or a part to see its waveform",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // The traces' chips belong to the scope.
                    if (!onControls) traces.forEach { trace ->
                        val value = if (animator != null) LiveView.sample(trace.values, animator.position) else 0.0
                        val unit = trace.quantity.unit
                        InputChip(
                            selected = false,
                            onClick = { onRemove(trace.probe) },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(trace.label, maxLines = 1)
                                    Spacer(Modifier.width(6.dp))
                                    // The reading has a fixed slot, so the chips beside it don't shift as it changes.
                                    Text(
                                        Si.formatFixed(value, unit),
                                        style = numbers,
                                        textAlign = TextAlign.End,
                                        maxLines = 1,
                                        modifier = Modifier.width(textWidth("-888 m$unit", numbers)),
                                    )
                                }
                            },
                            leadingIcon = {
                                Box(Modifier.size(10.dp).background(colors[trace.probe] ?: MaterialTheme.colorScheme.primary, CircleShape))
                            },
                            trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Remove ${trace.label}", Modifier.size(16.dp)) },
                        )
                    }
                }
                if (traces.isNotEmpty() || onControls) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                            contentDescription = when {
                                onControls -> if (expanded) "Hide the controls" else "Show the controls"
                                else -> if (expanded) "Hide the scope" else "Show the scope"
                            },
                        )
                    }
                }
            }
            if (expanded && onControls) controls()
            if (expanded && !onControls && animator != null && traces.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(start = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MeasureMenu(measureMask) { measureMask = it }
                    FilterChip(
                        selected = cursorsOn,
                        onClick = { cursorsOn = !cursorsOn },
                        label = { Text("Cursors") },
                        modifier = Modifier.testTag(SCOPE_CURSORS),
                    )
                }
                // A transient's window is fixed; a rolling one moves on with every sample.
                val revision = if (rolling) history.revision else 0L
                val window = remember(view, rolling, revision) { ScopeWindow.of(animator, history, rolling) }
                val grid = MaterialTheme.colorScheme.outlineVariant
                val label = MaterialTheme.colorScheme.onSurfaceVariant
                val cursorColor = MaterialTheme.colorScheme.tertiary
                val textMeasurer = rememberTextMeasurer()
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .padding(bottom = 4.dp)
                        .clipToBounds()
                        .then(
                            if (cursorsOn) {
                                Modifier.pointerInput(Unit) {
                                    var dragging = 0
                                    fun nearest(x: Float): Int =
                                        if (abs(x / size.width - cursorA) <= abs(x / size.width - cursorB)) 0 else 1
                                    fun move(which: Int, x: Float) {
                                        val f = (x / size.width).coerceIn(0f, 1f)
                                        if (which == 0) cursorA = f else cursorB = f
                                    }
                                    detectDragGestures(
                                        onDragStart = { dragging = nearest(it.x); move(dragging, it.x) },
                                        onDrag = { change, _ -> change.consume(); move(dragging, change.position.x) },
                                    )
                                }.pointerInput(Unit) {
                                    detectTapGestures { p ->
                                        val f = (p.x / size.width).coerceIn(0f, 1f)
                                        if (abs(f - cursorA) <= abs(f - cursorB)) cursorA = f else cursorB = f
                                    }
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    animator.frame // redraw with the playback
                    history.revision
                    drawGrid(grid)
                    if (rolling) drawRolling(history, traces, colors, label, textMeasurer) else drawSweep(animator, traces, colors, label, textMeasurer)
                    if (cursorsOn) drawCursors(cursorA, cursorB, cursorColor, textMeasurer)
                }
                val measures = Measure.fromMask(measureMask)
                if (measures.isNotEmpty() || cursorsOn) {
                    MeasurementTable(
                        window = window,
                        traces = traces,
                        colors = colors,
                        measures = measures,
                        cursors = if (cursorsOn) cursorA to cursorB else null,
                        style = numbers,
                    )
                }
            }
        }
    }
}

const val SCOPE_CURSORS = "scope-cursors"
const val SCOPE_MEASURE = "scope-measure"

/** The time span the scope shows, in simulation seconds (a transient) or wall-clock seconds (rolling). */
private class ScopeWindow(
    val start: Double,
    val length: Double,
    val rolling: Boolean,
    private val samples: (LiveTrace) -> Pair<DoubleArray?, DoubleArray>,
) {
    fun timeAt(fraction: Float): Double = start + fraction * length

    /** Label for a cursor: time since the start of the run, or seconds ago when rolling. */
    fun label(fraction: Float): String =
        if (rolling) Si.formatFixed((fraction - 1.0) * length, "s") else Si.formatFixed(fraction * length, "s")

    fun stats(trace: LiveTrace, from: Double, to: Double): WaveStats? {
        val (times, values) = samples(trace)
        return WaveStats.of(times, values, from, to)
    }

    fun valueAt(trace: LiveTrace, fraction: Float): Double {
        val (times, values) = samples(trace)
        if (times == null || times.isEmpty()) return values.firstOrNull() ?: Double.NaN
        val t = timeAt(fraction)
        val i = times.indexOfFirst { it >= t }
        return when {
            i < 0 -> values.last()
            i == 0 -> values.first()
            else -> {
                val span = times[i] - times[i - 1]
                values[i - 1] + (values[i] - values[i - 1]) * (if (span > 0) (t - times[i - 1]) / span else 0.0)
            }
        }
    }

    companion object {
        fun of(animator: LiveAnimator, history: ScopeHistory, rolling: Boolean): ScopeWindow {
            val view = animator.view
            return if (rolling) {
                val times = history.times.toDoubleArray()
                ScopeWindow(history.clock - ScopeHistory.WINDOW_SECONDS, ScopeHistory.WINDOW_SECONDS, true) { trace ->
                    times to (history.values[trace.probe]?.toDoubleArray() ?: trace.values)
                }
            } else {
                ScopeWindow(view.time?.firstOrNull() ?: 0.0, view.duration, false) { trace -> view.time to trace.values }
            }
        }
    }
}

/** Picks which measurements the table shows. */
@Composable
fun MeasureMenu(mask: Int, onMask: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = mask != 0,
            onClick = { open = true },
            label = { Text(if (mask == 0) "Measure" else "Measure · ${Measure.fromMask(mask).size}") },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, Modifier.size(18.dp)) },
            modifier = Modifier.testTag(SCOPE_MEASURE),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Measure.entries.forEach { m ->
                val on = mask and (1 shl m.ordinal) != 0
                DropdownMenuItem(
                    text = { Text(m.label) },
                    leadingIcon = { Checkbox(checked = on, onCheckedChange = null) },
                    onClick = { onMask(Measure.toggle(mask, m)) },
                )
            }
            HorizontalDivider()
            if (mask != 0) DropdownMenuItem(text = { Text("Clear all") }, onClick = { onMask(0); open = false })
            DropdownMenuItem(text = { Text("Done") }, onClick = { open = false })
        }
    }
}

/**
 * One row per channel: the chosen measurements, then the values at cursors A and B and their
 * difference. Columns have fixed widths and digits are tabular, so nothing moves as values change.
 */
@Composable
private fun MeasurementTable(
    window: ScopeWindow,
    traces: List<LiveTrace>,
    colors: Map<Probe, Color>,
    measures: List<Measure>,
    cursors: Pair<Float, Float>?,
    style: TextStyle,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val cell = textWidth("-888 mHz", style) + 8.dp
    val name = textWidth("MMMMMMMM", style)
    val (from, to) = cursors?.let { (a, b) -> window.timeAt(minOf(a, b)) to window.timeAt(maxOf(a, b)) }
        ?: (Double.NEGATIVE_INFINITY to Double.POSITIVE_INFINITY)
    Column(Modifier.padding(horizontal = 8.dp).padding(bottom = 6.dp)) {
        if (cursors != null) {
            val (a, b) = cursors
            val dt = abs(window.timeAt(b) - window.timeAt(a))
            Text(
                "A ${window.label(a)}   B ${window.label(b)}   Δt ${Si.formatFixed(dt, "s")}" +
                    if (dt > 0) "   1/Δt ${Si.formatFixed(1 / dt, "Hz")}" else "",
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (measures.isNotEmpty()) Text("Measured between the cursors", style = MaterialTheme.typography.labelSmall, color = muted)
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Column {
                // Cursor readings first: they follow the finger, so they should be in view.
                Row {
                    Spacer(Modifier.width(name + 14.dp))
                    if (cursors != null) listOf("at A", "at B", "B − A").forEach { HeaderCell(it, cell, style, muted) }
                    measures.forEach { m -> HeaderCell(m.short, cell, style, muted) }
                }
                traces.forEach { trace ->
                    val unit = trace.quantity.unit
                    // Measured once per window and cursor position, not on every playback frame.
                    val stats = remember(window, trace, from, to, measures.isNotEmpty()) {
                        if (measures.isNotEmpty()) window.stats(trace, from, to) else null
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(colors[trace.probe] ?: muted, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(trace.label, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(name))
                        if (cursors != null) {
                            val (va, vb) = remember(window, trace, cursors) {
                                window.valueAt(trace, cursors.first) to window.valueAt(trace, cursors.second)
                            }
                            ValueCell(Si.formatFixed(va, unit), cell, style)
                            ValueCell(Si.formatFixed(vb, unit), cell, style)
                            ValueCell(Si.formatFixed(vb - va, unit), cell, style)
                        }
                        measures.forEach { m -> ValueCell(Si.formatFixed(stats?.get(m) ?: Double.NaN, m.unit(unit)), cell, style) }
                    }
                }
            }
        }
    }
}

@Composable
fun HeaderCell(text: String, width: Dp, style: TextStyle, color: Color) =
    Text(text, style = style, color = color, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(width))

@Composable
fun ValueCell(text: String, width: Dp, style: TextStyle) =
    Text(text, style = style, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(width))

private fun DrawScope.drawCursors(a: Float, b: Float, color: Color, textMeasurer: TextMeasurer) {
    val style = TextStyle(color = color, fontSize = 10.dp.toSp())
    val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
    listOf("A" to a, "B" to b).forEach { (name, f) ->
        val x = f * size.width
        drawLine(color, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx(), pathEffect = dash)
        val layout = textMeasurer.measure(name, style)
        val left = (x + 3.dp.toPx()).coerceAtMost(size.width - layout.size.width - 2.dp.toPx())
        drawText(layout, topLeft = Offset(left, size.height / 2 - layout.size.height / 2))
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
