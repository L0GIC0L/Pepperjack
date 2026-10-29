package io.github.l0gic0l.pepperjack.ui.schematic

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.pepperjack.model.GridPoint
import io.github.l0gic0l.pepperjack.model.Part
import io.github.l0gic0l.pepperjack.model.PartKind
import io.github.l0gic0l.pepperjack.model.PartLibrary
import io.github.l0gic0l.pepperjack.model.Schematic
import io.github.l0gic0l.pepperjack.model.Shape
import io.github.l0gic0l.pepperjack.model.bounds
import io.github.l0gic0l.pepperjack.model.transformPoint
import io.github.l0gic0l.pepperjack.ui.theme.CircuitColors

/** Maps grid units to screen pixels: screen = offset + grid * scale. */
data class GridTransform(val scale: Float, val offset: Offset) {
    fun toScreen(x: Float, y: Float) = Offset(offset.x + x * scale, offset.y + y * scale)
    fun toScreen(point: GridPoint) = toScreen(point.x.toFloat(), point.y.toFloat())
    fun toGrid(screen: Offset) = Offset((screen.x - offset.x) / scale, (screen.y - offset.y) / scale)

    companion object {
        /** Fits [schematic] into [size] with a margin, never zooming past [maxScale] px per grid unit. */
        fun fit(schematic: Schematic, size: Size, maxScale: Float, minScale: Float): GridTransform {
            val b = schematic.bounds() ?: floatArrayOf(-8f, -6f, 8f, 6f)
            val width = (b[2] - b[0]).coerceAtLeast(4f) + 4f
            val height = (b[3] - b[1]).coerceAtLeast(4f) + 4f
            val scale = minOf(size.width / width, size.height / height).coerceIn(minScale, maxScale)
            val cx = (b[0] + b[2]) / 2f
            val cy = (b[1] + b[3]) / 2f
            return GridTransform(scale, Offset(size.width / 2f - cx * scale, size.height / 2f - cy * scale))
        }
    }
}

fun Schematic.bounds(): FloatArray? {
    if (parts.isEmpty() && wires.isEmpty()) return null
    var left = Float.MAX_VALUE
    var top = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE
    parts.forEach {
        val b = it.bounds()
        left = minOf(left, b[0]); top = minOf(top, b[1]); right = maxOf(right, b[2]); bottom = maxOf(bottom, b[3])
    }
    wires.forEach {
        left = minOf(left, it.x1.toFloat(), it.x2.toFloat()); right = maxOf(right, it.x1.toFloat(), it.x2.toFloat())
        top = minOf(top, it.y1.toFloat(), it.y2.toFloat()); bottom = maxOf(bottom, it.y1.toFloat(), it.y2.toFloat())
    }
    return floatArrayOf(left, top, right, bottom)
}

/** Draws one part symbol. [dx]/[dy] shift it in grid units (used while dragging). */
fun DrawScope.drawPart(
    part: Part,
    transform: GridTransform,
    color: Color,
    strokeWidth: Float,
    textMeasurer: TextMeasurer?,
    dx: Float = 0f,
    dy: Float = 0f,
) {
    val def = PartLibrary[part.kind]
    fun point(x: Float, y: Float): Offset {
        val (tx, ty) = transformPoint(x, y, part.rotation, part.mirrored)
        return transform.toScreen(part.x + dx + tx, part.y + dy + ty)
    }
    val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    for (shape in def.shapes) {
        when (shape) {
            is Shape.Line -> drawLine(color, point(shape.x1, shape.y1), point(shape.x2, shape.y2), strokeWidth, StrokeCap.Round)
            is Shape.Poly -> {
                val path = Path()
                shape.points.forEachIndexed { i, (x, y) ->
                    val p = point(x, y)
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                if (shape.closed) path.close()
                if (shape.filled) drawPath(path, color, style = Fill)
                drawPath(path, color, style = stroke)
            }
            is Shape.Circle -> drawCircle(
                color,
                radius = shape.r * transform.scale,
                center = point(shape.cx, shape.cy),
                style = if (shape.filled) Fill else stroke,
            )
            is Shape.Glyph -> if (textMeasurer != null) {
                val layout = textMeasurer.measure(
                    shape.text,
                    TextStyle(color = color, fontSize = (transform.scale * 0.9f).toSp(), fontWeight = FontWeight.Bold),
                )
                val center = point(shape.x, shape.y)
                drawText(layout, topLeft = center - Offset(layout.size.width / 2f, layout.size.height / 2f))
            }
        }
    }
}

/** Reference and value text placed beside the symbol, LTspice style. */
fun DrawScope.drawPartLabels(
    part: Part,
    transform: GridTransform,
    textMeasurer: TextMeasurer,
    color: Color,
    labelBackground: Color,
    labelText: Color,
    dx: Float = 0f,
    dy: Float = 0f,
) {
    if (transform.scale < 7.dp.toPx()) return
    val size = (transform.scale * 0.7f).coerceIn(9.dp.toPx(), 15.dp.toPx())
    val style = TextStyle(color = color, fontSize = size.toSp())
    val b = part.bounds()
    val left = b[0] + dx
    val top = b[1] + dy
    val right = b[2] + dx
    val bottom = b[3] + dy
    when (part.kind) {
        PartKind.GROUND -> return
        PartKind.LABEL -> {
            val layout = textMeasurer.measure(part.value, style.copy(color = labelText, fontWeight = FontWeight.Medium))
            val anchor = transform.toScreen(part.x + dx, part.y + dy)
            val pad = 3.dp.toPx()
            val topLeft = Offset(anchor.x + 4.dp.toPx(), anchor.y - layout.size.height - 4.dp.toPx())
            drawRoundRect(
                labelBackground,
                topLeft = topLeft - Offset(pad, pad / 2),
                size = Size(layout.size.width + 2 * pad, layout.size.height + pad),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
            drawText(layout, topLeft = topLeft)
            return
        }
        else -> Unit
    }
    val ref = textMeasurer.measure(part.ref, style.copy(fontWeight = FontWeight.Medium))
    val value = part.value.takeIf { it.isNotBlank() }?.let { textMeasurer.measure(it.take(40), style) }
    val vertical = (bottom - top) >= (right - left)
    if (vertical) {
        val x = transform.toScreen(right + 0.4f, 0f).x
        val cy = transform.toScreen(0f, (top + bottom) / 2f).y
        val totalHeight = ref.size.height + (value?.size?.height ?: 0)
        drawText(ref, topLeft = Offset(x, cy - totalHeight / 2f))
        value?.let { drawText(it, topLeft = Offset(x, cy - totalHeight / 2f + ref.size.height)) }
    } else {
        val cx = transform.toScreen((left + right) / 2f, 0f).x
        val above = transform.toScreen(0f, top - 0.2f).y
        drawText(ref, topLeft = Offset(cx - ref.size.width / 2f, above - ref.size.height))
        value?.let {
            val below = transform.toScreen(0f, bottom + 0.2f).y
            drawText(it, topLeft = Offset(cx - it.size.width / 2f, below))
        }
    }
}

/** A compact, label-free rendering used for thumbnails and the parts palette. */
fun DrawScope.drawSchematicPreview(schematic: Schematic, colors: CircuitColors, textMeasurer: TextMeasurer?) {
    val transform = GridTransform.fit(schematic, size, maxScale = 14.dp.toPx(), minScale = 1f)
    val stroke = (transform.scale * 0.12f).coerceIn(1f, 2.dp.toPx())
    schematic.wires.forEach {
        drawLine(colors.wire, transform.toScreen(it.start), transform.toScreen(it.end), stroke, StrokeCap.Round)
    }
    schematic.parts.forEach { drawPart(it, transform, colors.part, stroke, textMeasurer) }
}
