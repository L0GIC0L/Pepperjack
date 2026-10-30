package io.github.l0gic0l.pepperjack.ui.results

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.l0gic0l.pepperjack.util.Si
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

class Trace(val label: String, val color: Color, val x: DoubleArray, val y: DoubleArray)

/** Cursor positions in data x units, shared by charts that plot the same x axis. */
@Stable
class Cursors {
    var a by mutableStateOf<Double?>(null)
    var b by mutableStateOf<Double?>(null)

    fun clear() {
        a = null
        b = null
    }

    fun place(x: Double) {
        val currentA = a
        val currentB = b
        when {
            currentA == null -> a = x
            currentB == null -> b = x
            abs(x - currentA) <= abs(x - currentB) -> a = x
            else -> b = x
        }
    }
}

/** Visible window in data units; null fields mean "fit to data". */
@Stable
class ChartWindow {
    var x by mutableStateOf<ClosedFloatingPointRange<Double>?>(null)
    var y by mutableStateOf<ClosedFloatingPointRange<Double>?>(null)

    fun reset() {
        x = null
        y = null
    }
}

data class ChartColors(val grid: Color, val axis: Color, val label: Color, val cursor: Color)

@Composable
fun WaveformChart(
    traces: List<Trace>,
    xUnit: String,
    yUnit: String,
    logX: Boolean,
    cursors: Cursors,
    window: ChartWindow,
    colors: ChartColors,
    modifier: Modifier = Modifier,
    /** Shares x-axis zoom with a sibling chart (Bode magnitude and phase). */
    sharedX: ChartWindow? = null,
) {
    val textMeasurer = rememberTextMeasurer()
    val currentTraces by rememberUpdatedState(traces)
    val xWindow = sharedX ?: window

    Canvas(
        modifier
            .semantics { contentDescription = "Plot of ${traces.joinToString { it.label }}" }
            .pointerInput(logX) {
                var lastTap = 0L
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val layout = ChartLayout.of(size.width.toFloat(), size.height.toFloat(), density)
                    var moved = false
                    var multi = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        val bounds = Bounds.of(currentTraces, xWindow.x, window.y, logX)
                        if (pressed.size >= 2) {
                            multi = true
                            moved = true
                            val (p1, p2) = pressed
                            val prevSpanX = abs(p1.previousPosition.x - p2.previousPosition.x)
                            val prevSpanY = abs(p1.previousPosition.y - p2.previousPosition.y)
                            val spanX = abs(p1.position.x - p2.position.x)
                            val spanY = abs(p1.position.y - p2.position.y)
                            val centroid = (p1.position + p2.position) / 2f
                            val threshold = 40.dp.toPx()
                            if (prevSpanX > threshold && spanX > threshold) {
                                xWindow.x = zoomRange(bounds.xRange(logX), prevSpanX / spanX, layout.fractionX(centroid.x), logX)
                            }
                            if (prevSpanY > threshold && spanY > threshold) {
                                window.y = zoomRange(bounds.y, prevSpanY / spanY, 1f - layout.fractionY(centroid.y), false)
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (multi) continue
                        val change = pressed.first()
                        if (!moved && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        if (moved) {
                            val delta = change.position - change.previousPosition
                            xWindow.x = panRange(bounds.xRange(logX), -delta.x / layout.width, logX)
                            window.y = panRange(bounds.y, delta.y / layout.height, false)
                            change.consume()
                        }
                    }
                    if (!moved) {
                        val now = down.uptimeMillis
                        if (now - lastTap < 300) {
                            window.reset()
                            sharedX?.reset()
                            cursors.clear()
                        } else {
                            val bounds = Bounds.of(currentTraces, xWindow.x, window.y, logX)
                            val fraction = layout.fractionX(down.position.x).coerceIn(0f, 1f)
                            cursors.place(bounds.xAt(fraction, logX))
                        }
                        lastTap = now
                    }
                }
            },
    ) {
        val layout = ChartLayout.of(size.width, size.height, density)
        val bounds = Bounds.of(traces, xWindow.x, window.y, logX)
        val labelStyle = TextStyle(color = colors.label, fontSize = 11.sp)

        // Grid and tick labels
        val xTicks = if (logX) logTicks(bounds.xMin, bounds.xMax) else niceTicks(bounds.xMin, bounds.xMax, 5)
        xTicks.forEach { value ->
            val px = layout.left + bounds.fractionX(value, logX) * layout.width
            if (px < layout.left - 1 || px > layout.right + 1) return@forEach
            drawLine(colors.grid, Offset(px, layout.top), Offset(px, layout.bottom), 1.dp.toPx())
            val text = textMeasurer.measure(Si.format(value, xUnit, 3, space = false), labelStyle)
            drawText(text, topLeft = Offset(px - text.size.width / 2f, layout.bottom + 4.dp.toPx()))
        }
        niceTicks(bounds.yMin, bounds.yMax, 4).forEach { value ->
            val py = layout.bottom - bounds.fractionY(value) * layout.height
            if (py < layout.top - 1 || py > layout.bottom + 1) return@forEach
            drawLine(colors.grid, Offset(layout.left, py), Offset(layout.right, py), 1.dp.toPx())
            val text = textMeasurer.measure(Si.format(value, yUnit, 3, space = false), labelStyle)
            drawText(text, topLeft = Offset(layout.left - text.size.width - 6.dp.toPx(), py - text.size.height / 2f))
        }
        drawRect(colors.axis, topLeft = Offset(layout.left, layout.top), size = androidx.compose.ui.geometry.Size(layout.width, layout.height), style = Stroke(1.dp.toPx()))

        clipRect(layout.left, layout.top, layout.right, layout.bottom) {
            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            traces.forEach { trace ->
                val path = decimatedPath(trace, bounds, layout, logX)
                drawPath(path, trace.color, style = stroke)
            }
            listOfNotNull(cursors.a?.let { "A" to it }, cursors.b?.let { "B" to it }).forEach { (name, x) ->
                val px = layout.left + bounds.fractionX(x, logX) * layout.width
                drawLine(
                    colors.cursor,
                    Offset(px, layout.top),
                    Offset(px, layout.bottom),
                    1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                )
                val label = textMeasurer.measure(name, labelStyle.copy(color = colors.cursor))
                drawText(label, topLeft = Offset(px + 4.dp.toPx(), layout.top + 2.dp.toPx()))
            }
        }
    }
}

internal class ChartLayout(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun fractionX(px: Float) = (px - left) / width
    fun fractionY(py: Float) = (py - top) / height

    companion object {
        fun of(width: Float, height: Float, density: Float) =
            ChartLayout(left = 56 * density, top = 8 * density, right = width - 12 * density, bottom = height - 24 * density)
    }
}

/** Data window, already resolved from the user's zoom or the data extent. */
internal class Bounds(val xMin: Double, val xMax: Double, val yMin: Double, val yMax: Double) {
    val y get() = yMin..yMax
    fun xRange(log: Boolean) = xMin..xMax

    fun fractionX(x: Double, log: Boolean): Float = if (log) {
        ((log10(x) - log10(xMin)) / (log10(xMax) - log10(xMin))).toFloat()
    } else {
        ((x - xMin) / (xMax - xMin)).toFloat()
    }

    fun fractionY(y: Double): Float = ((y - yMin) / (yMax - yMin)).toFloat()

    fun xAt(fraction: Float, log: Boolean): Double = if (log) {
        10.0.pow(log10(xMin) + fraction * (log10(xMax) - log10(xMin)))
    } else {
        xMin + fraction * (xMax - xMin)
    }

    companion object {
        fun of(traces: List<Trace>, x: ClosedFloatingPointRange<Double>?, y: ClosedFloatingPointRange<Double>?, logX: Boolean): Bounds {
            var xMin = Double.POSITIVE_INFINITY
            var xMax = Double.NEGATIVE_INFINITY
            traces.forEach { t ->
                t.x.forEach { v -> if (v.isFinite() && (!logX || v > 0)) { if (v < xMin) xMin = v; if (v > xMax) xMax = v } }
            }
            if (!xMin.isFinite()) { xMin = if (logX) 1.0 else 0.0; xMax = if (logX) 10.0 else 1.0 }
            if (xMax <= xMin) xMax = if (logX) xMin * 10 else xMin + 1
            val xr = x ?: (xMin..xMax)

            val yr = y ?: run {
                var lo = Double.POSITIVE_INFINITY
                var hi = Double.NEGATIVE_INFINITY
                traces.forEach { t ->
                    for (i in t.y.indices) {
                        val xv = t.x[i]
                        if (xv < xr.start || xv > xr.endInclusive) continue
                        val v = t.y[i]
                        if (v.isFinite()) { if (v < lo) lo = v; if (v > hi) hi = v }
                    }
                }
                if (!lo.isFinite()) { lo = -1.0; hi = 1.0 }
                if (hi - lo < 1e-15 * maxOf(1.0, abs(hi))) { val pad = maxOf(abs(hi) * 0.1, 1e-12); lo -= pad; hi += pad }
                val pad = (hi - lo) * 0.06
                (lo - pad)..(hi + pad)
            }
            return Bounds(xr.start, xr.endInclusive, yr.start, yr.endInclusive)
        }
    }
}

private fun zoomRange(range: ClosedFloatingPointRange<Double>, factor: Float, focus: Float, log: Boolean): ClosedFloatingPointRange<Double> {
    val f = focus.coerceIn(0f, 1f).toDouble()
    return if (log) {
        val lo = log10(range.start)
        val hi = log10(range.endInclusive)
        val center = lo + f * (hi - lo)
        val newLo = center - (center - lo) * factor
        val newHi = center + (hi - center) * factor
        10.0.pow(newLo)..10.0.pow(newHi)
    } else {
        val center = range.start + f * (range.endInclusive - range.start)
        (center - (center - range.start) * factor)..(center + (range.endInclusive - center) * factor)
    }
}

private fun panRange(range: ClosedFloatingPointRange<Double>, fraction: Float, log: Boolean): ClosedFloatingPointRange<Double> =
    if (log) {
        val lo = log10(range.start)
        val hi = log10(range.endInclusive)
        val shift = (hi - lo) * fraction
        10.0.pow(lo + shift)..10.0.pow(hi + shift)
    } else {
        val shift = (range.endInclusive - range.start) * fraction
        (range.start + shift)..(range.endInclusive + shift)
    }

/** Min/max decimation per pixel column so 10⁵-point transients stay smooth. */
private fun decimatedPath(trace: Trace, bounds: Bounds, layout: ChartLayout, logX: Boolean): Path {
    val path = Path()
    val n = minOf(trace.x.size, trace.y.size)
    if (n == 0) return path
    var started = false
    var column = Int.MIN_VALUE
    var colMin = 0f
    var colMax = 0f
    var colFirst = 0f
    var colLast = 0f
    fun flush() {
        if (column == Int.MIN_VALUE) return
        val px = column.toFloat()
        if (!started) { path.moveTo(px, colFirst); started = true } else path.lineTo(px, colFirst)
        if (colMin != colFirst) path.lineTo(px, colMin)
        if (colMax != colMin) path.lineTo(px, colMax)
        path.lineTo(px, colLast)
    }
    // Include one point beyond each edge so lines run to the border.
    var startIndex = 0
    while (startIndex < n - 1 && trace.x[startIndex + 1] < bounds.xMin) startIndex++
    for (i in startIndex until n) {
        val xv = trace.x[i]
        if (logX && xv <= 0) continue
        val px = layout.left + bounds.fractionX(xv, logX) * layout.width
        val py = layout.bottom - bounds.fractionY(trace.y[i]) * layout.height
        if (!py.isFinite()) continue
        val col = px.toInt()
        if (col != column) {
            flush()
            column = col
            colMin = py; colMax = py; colFirst = py; colLast = py
        } else {
            if (py < colMin) colMin = py
            if (py > colMax) colMax = py
            colLast = py
        }
        if (xv > bounds.xMax) break
    }
    flush()
    return path
}

fun niceTicks(min: Double, max: Double, target: Int): List<Double> {
    val span = max - min
    if (!span.isFinite() || span <= 0) return listOf(min)
    val raw = span / target
    val magnitude = 10.0.pow(floor(log10(raw)))
    val normalized = raw / magnitude
    val step = magnitude * when {
        normalized < 1.5 -> 1.0
        normalized < 3 -> 2.0
        normalized < 7 -> 5.0
        else -> 10.0
    }
    val first = ceil(min / step) * step
    return generateSequence(first) { it + step }
        .takeWhile { it <= max + step * 1e-9 }
        .map { if (abs(it) < step * 1e-9) 0.0 else it }
        .take(50)
        .toList()
}

fun logTicks(min: Double, max: Double): List<Double> {
    if (min <= 0 || max <= min) return listOf(min)
    val lo = ceil(log10(min) - 1e-9).toInt()
    val hi = floor(log10(max) + 1e-9).toInt()
    val decades = (lo..hi).map { 10.0.pow(it) }
    if (decades.size >= 2) {
        val stride = (decades.size + 5) / 6
        return decades.filterIndexed { i, _ -> i % stride == 0 }
    }
    return niceTicks(min, max, 4)
}

/** Linear interpolation of [y] at [x]; [x] values must be ascending. */
fun interpolate(xs: DoubleArray, ys: DoubleArray, x: Double): Double? {
    val n = minOf(xs.size, ys.size)
    if (n == 0 || x < xs[0] || x > xs[n - 1]) return null
    var lo = 0
    var hi = n - 1
    while (hi - lo > 1) {
        val mid = (lo + hi) / 2
        if (xs[mid] <= x) lo = mid else hi = mid
    }
    val dx = xs[hi] - xs[lo]
    if (dx == 0.0) return ys[lo]
    return ys[lo] + (ys[hi] - ys[lo]) * (x - xs[lo]) / dx
}
