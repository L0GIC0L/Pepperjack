package io.github.l0gic0l.pepperjack.ui.schematic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import io.github.l0gic0l.pepperjack.model.GridPoint
import io.github.l0gic0l.pepperjack.model.Part
import io.github.l0gic0l.pepperjack.model.PartKind
import io.github.l0gic0l.pepperjack.model.Probe
import io.github.l0gic0l.pepperjack.model.ProbeType
import io.github.l0gic0l.pepperjack.model.Schematic
import io.github.l0gic0l.pepperjack.model.Wire
import io.github.l0gic0l.pepperjack.model.bounds
import io.github.l0gic0l.pepperjack.model.pinPositions
import io.github.l0gic0l.pepperjack.spice.Connectivity
import io.github.l0gic0l.pepperjack.spice.junctions
import io.github.l0gic0l.pepperjack.ui.editor.EditorState
import io.github.l0gic0l.pepperjack.ui.editor.Tool
import io.github.l0gic0l.pepperjack.ui.theme.LocalCircuitColors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Pan and zoom of the schematic view; survives recomposition, refits on request. */
@Stable
class SchematicViewport {
    var scale by mutableFloatStateOf(0f)
    var offset by mutableStateOf(Offset.Zero)
    var fitRequests by mutableIntStateOf(0)
        private set
    internal var fittedFor = -1

    /**
     * The view is as it was last fitted: nothing has panned or zoomed it since. While it is,
     * it is fitted again whenever the sheet's room changes (a panel opens, the screen turns).
     */
    internal var untouched = true

    val transform: GridTransform get() = GridTransform(scale, offset)

    fun requestFit() {
        fitRequests++
    }

    /** Pans the view by [delta] pixels. */
    fun panBy(delta: Offset) {
        offset += delta
        untouched = false
    }

    fun zoomBy(factor: Float, focus: Offset, minScale: Float, maxScale: Float) {
        untouched = false
        val newScale = (scale * factor).coerceIn(minScale, maxScale)
        val applied = newScale / scale
        offset = focus - (focus - offset) * applied
        scale = newScale
    }
}

private enum class DragMode { NONE, PAN, MOVE_PART, MOVE_GROUP, AREA, WIRE, PLACE, TRANSFORM }

@Composable
fun SchematicCanvas(
    schematic: Schematic,
    state: EditorState,
    viewport: SchematicViewport,
    showGrid: Boolean,
    probeColors: Map<Probe, Color>,
    onTap: (GridPoint, Part?, Wire?) -> Unit,
    onDoubleTap: (Part) -> Unit,
    /** A part starts being dragged; it becomes the selection. */
    onDragPart: (Part) -> Unit,
    onMovePart: (Long, Int, Int) -> Unit,
    onDrawWire: (GridPoint, GridPoint) -> Unit,
    modifier: Modifier = Modifier,
    /** A part being dragged in from the parts tray, drawn where it would land. */
    dropGhost: Part? = null,
    /** A part drag starts (true) or ends (false). */
    onPartDragging: (Boolean) -> Unit = {},
    /** The area tool's box was dragged out from one corner to the other. */
    onSelectArea: (GridPoint, GridPoint) -> Unit = { _, _ -> },
    /** The multi-selection was dragged by whole grid steps. */
    onMoveGroup: (Int, Int) -> Unit = { _, _ -> },
    /** A strip along the bottom that fitting keeps the sheet clear of: the toolbar floats there. */
    fitClearBottom: Dp = 0.dp,
) {
    val colors = LocalCircuitColors.current
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val minScale = with(density) { 4.dp.toPx() }
    val maxScale = with(density) { 96.dp.toPx() }
    val touchTolerance = with(density) { 14.dp.toPx() }
    val labelBackground = androidx.compose.material3.MaterialTheme.colorScheme.tertiaryContainer
    val labelText = androidx.compose.material3.MaterialTheme.colorScheme.onTertiaryContainer

    val currentSchematic by rememberUpdatedState(schematic)
    val currentState by rememberUpdatedState(state)
    val tap by rememberUpdatedState(onTap)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val dragPart by rememberUpdatedState(onDragPart)
    val movePart by rememberUpdatedState(onMovePart)
    val drawWire by rememberUpdatedState(onDrawWire)
    val partDragging by rememberUpdatedState(onPartDragging)
    val selectArea by rememberUpdatedState(onSelectArea)
    val moveGroup by rememberUpdatedState(onMoveGroup)

    // What a drag is moving: whole parts and wires, and the wire ends that stretch after them.
    var drag by remember { mutableStateOf<DragSet?>(null) }
    var dragDelta by remember { mutableStateOf(GridPoint(0, 0)) }
    var area by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    var wirePreview by remember { mutableStateOf<Pair<GridPoint, GridPoint>?>(null) }
    var ghost by remember { mutableStateOf<GridPoint?>(null) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    val analysis = remember(schematic) { analyze(schematic) }
    LaunchedEffect(state.placing) { ghost = null }
    LaunchedEffect(canvasSize, viewport.fitRequests) {
        if (canvasSize.width > 0f && (viewport.scale == 0f || viewport.fittedFor != viewport.fitRequests || viewport.untouched)) {
            // Not more than a third of the sheet's room, however short that is.
            val clear = minOf(fitClearBottom.value * density.density, canvasSize.height / 3f)
            val room = Size(canvasSize.width, canvasSize.height - clear)
            val fitted = GridTransform.fit(currentSchematic, room, maxScale = 28 * density.density, minScale = minScale)
            viewport.scale = fitted.scale
            viewport.offset = fitted.offset
            viewport.fittedFor = viewport.fitRequests
            viewport.untouched = true
        }
    }

    fun snap(screen: Offset): GridPoint {
        val g = viewport.transform.toGrid(screen)
        return GridPoint(g.x.roundToInt(), g.y.roundToInt())
    }

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { canvasSize = it.toSize() }
            .semantics { contentDescription = "Schematic with ${schematic.parts.size} parts and ${schematic.wires.size} wires" }
            .pointerInput(Unit) {
                // Mouse wheel zoom and hover ghost for desktop-class devices (DeX, Chromebooks, tablets).
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        when (event.type) {
                            PointerEventType.Scroll -> {
                                val factor = if (change.scrollDelta.y > 0) 0.9f else 1.1f
                                viewport.zoomBy(factor, change.position, minScale, maxScale)
                                change.consume()
                            }
                            PointerEventType.Move -> if (!change.pressed && currentState.placing != null) {
                                ghost = snap(change.position)
                            }
                            else -> Unit
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                var lastTapTime = 0L
                var lastTapPosition = Offset.Zero
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val schematicNow = currentSchematic
                    val stateNow = currentState
                    val transform = viewport.transform
                    val downGrid = transform.toGrid(down.position)
                    val tolerance = touchTolerance / transform.scale
                    val startPoint = GridPoint(downGrid.x.roundToInt(), downGrid.y.roundToInt())
                    val hitPart = hitPart(schematicNow, downGrid, tolerance)
                    val hitWireAtDown = if (hitPart == null) hitWire(schematicNow, downGrid, tolerance) else null
                    val onGroup = (hitPart != null && hitPart.id in stateNow.group) || (hitWireAtDown != null && hitWireAtDown.id in stateNow.groupWires)
                    var mode = DragMode.NONE
                    if (stateNow.placing != null) {
                        mode = DragMode.PLACE
                        ghost = startPoint
                    }
                    var moved = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            if (mode != DragMode.TRANSFORM) {
                                if (mode == DragMode.MOVE_PART || mode == DragMode.MOVE_GROUP) partDragging(false)
                                drag = null
                                dragDelta = GridPoint(0, 0)
                                wirePreview = null
                                area = null
                            }
                            mode = DragMode.TRANSFORM
                            moved = true
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            if (pan != Offset.Zero) viewport.panBy(pan)
                            if (zoom != 1f) viewport.zoomBy(zoom, centroid, minScale, maxScale)
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (mode == DragMode.TRANSFORM) continue
                        val change = pressed.first()
                        if (!moved && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            moved = true
                            if (mode == DragMode.NONE) {
                                val selecting = stateNow.tool == Tool.SELECT || stateNow.tool == Tool.AREA
                                mode = when {
                                    stateNow.tool == Tool.WIRE -> DragMode.WIRE
                                    selecting && onGroup -> DragMode.MOVE_GROUP
                                    stateNow.tool == Tool.AREA -> DragMode.AREA
                                    stateNow.tool == Tool.SELECT && hitPart != null -> DragMode.MOVE_PART
                                    else -> DragMode.PAN
                                }
                                when (mode) {
                                    DragMode.MOVE_PART -> {
                                        drag = DragSet.of(schematicNow, setOf(hitPart!!.id), emptySet())
                                        dragPart(hitPart)
                                        partDragging(true)
                                    }
                                    DragMode.MOVE_GROUP -> {
                                        drag = DragSet.of(schematicNow, stateNow.group, stateNow.groupWires)
                                        partDragging(true)
                                    }
                                    DragMode.AREA -> area = downGrid to downGrid
                                    else -> Unit
                                }
                            }
                        }
                        if (moved) {
                            when (mode) {
                                DragMode.PAN -> viewport.panBy(change.position - change.previousPosition)
                                DragMode.MOVE_PART, DragMode.MOVE_GROUP -> {
                                    val g = viewport.transform.toGrid(change.position)
                                    dragDelta = GridPoint((g.x - downGrid.x).roundToInt(), (g.y - downGrid.y).roundToInt())
                                }
                                DragMode.AREA -> area = downGrid to viewport.transform.toGrid(change.position)
                                DragMode.WIRE -> wirePreview = startPoint to snap(change.position)
                                DragMode.PLACE -> ghost = snap(change.position)
                                else -> Unit
                            }
                            change.consume()
                        }
                    }

                    when (mode) {
                        DragMode.MOVE_PART -> {
                            drag?.parts?.firstOrNull()?.let { movePart(it, dragDelta.x, dragDelta.y) }
                            drag = null
                            dragDelta = GridPoint(0, 0)
                            partDragging(false)
                        }
                        DragMode.MOVE_GROUP -> {
                            moveGroup(dragDelta.x, dragDelta.y)
                            drag = null
                            dragDelta = GridPoint(0, 0)
                            partDragging(false)
                        }
                        DragMode.AREA -> {
                            area?.let { (a, b) ->
                                selectArea(GridPoint(a.x.roundToInt(), a.y.roundToInt()), GridPoint(b.x.roundToInt(), b.y.roundToInt()))
                            }
                            area = null
                        }
                        DragMode.WIRE -> {
                            wirePreview?.let { (a, b) -> drawWire(a, b) }
                            wirePreview = null
                        }
                        DragMode.PLACE -> ghost?.let { tap(it, null, null) }
                        DragMode.NONE -> {
                            val now = down.uptimeMillis
                            val isDouble = now - lastTapTime < 300 && (down.position - lastTapPosition).getDistance() < touchTolerance * 2
                            lastTapTime = now
                            lastTapPosition = down.position
                            val hitWire = if (hitPart == null || stateNow.tool == Tool.WIRE) hitWireAtDown ?: hitWire(schematicNow, downGrid, tolerance) else null
                            tap(startPoint, if (stateNow.tool == Tool.WIRE) null else hitPart, hitWire)
                            if (isDouble && hitPart != null && stateNow.tool == Tool.SELECT) doubleTap(hitPart)
                        }
                        else -> Unit
                    }
                }
            },
    ) {
        if (viewport.scale == 0f) return@Canvas
        val transform = viewport.transform
        val stroke = 2.dp.toPx()

        if (showGrid) drawGrid(transform, colors.grid)

        val moving = drag
        fun shown(p: GridPoint): GridPoint = moving?.let { if (p in it.points) GridPoint(p.x + dragDelta.x, p.y + dragDelta.y) else p } ?: p
        schematic.wires.forEach { wire ->
            val selected = wire.id == state.selectedWireId || wire.id in state.groupWires
            drawLine(
                if (selected) colors.selection else colors.wire,
                transform.toScreen(shown(wire.start)),
                transform.toScreen(shown(wire.end)),
                if (selected) stroke * 1.8f else stroke,
                StrokeCap.Round,
            )
        }
        analysis.junctions.forEach { drawCircle(colors.wire, radius = 3.5.dp.toPx(), center = transform.toScreen(it)) }

        schematic.parts.forEach { part ->
            val follows = moving != null && (part.id in moving.parts || (part.kind == PartKind.PROBE && part.origin in moving.points))
            val dx = if (follows) dragDelta.x.toFloat() else 0f
            val dy = if (follows) dragDelta.y.toFloat() else 0f
            val color = when {
                part.id == state.selectedPartId || part.id in state.group -> colors.selection
                // A probe takes the colour of its trace, as in LTspice.
                part.kind == PartKind.PROBE -> probeColors[Probe(ProbeType.VOLTAGE, part.x, part.y)] ?: colors.part
                else -> colors.part
            }
            drawPart(part, transform, color, stroke, textMeasurer, dx, dy)
            drawPartLabels(part, transform, textMeasurer, colors.part.copy(alpha = 0.85f), labelBackground, labelText, dx, dy)
        }

        val pinBox = 3.dp.toPx()
        analysis.openPins.forEach { pin ->
            val c = transform.toScreen(pin)
            drawRect(colors.openPin, topLeft = c - Offset(pinBox, pinBox), size = Size(pinBox * 2, pinBox * 2), style = Stroke(1.5.dp.toPx()))
        }

        drawProbes(schematic, transform, probeColors)

        area?.let { (a, b) ->
            val p = transform.toScreen(minOf(a.x, b.x), minOf(a.y, b.y))
            val q = transform.toScreen(maxOf(a.x, b.x), maxOf(a.y, b.y))
            drawRect(colors.selection.copy(alpha = 0.12f), topLeft = p, size = Size(q.x - p.x, q.y - p.y))
            drawRect(
                colors.selection,
                topLeft = p,
                size = Size(q.x - p.x, q.y - p.y),
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
            )
        }

        state.wireStart?.let { start ->
            val c = transform.toScreen(start)
            drawCircle(colors.selection, radius = 6.dp.toPx(), center = c, style = Stroke(2.dp.toPx()))
            drawCircle(colors.selection, radius = 2.5.dp.toPx(), center = c)
        }
        wirePreview?.let { (a, b) ->
            val corner = GridPoint(b.x, a.y)
            val path = Path().apply {
                transform.toScreen(a).let { moveTo(it.x, it.y) }
                transform.toScreen(corner).let { lineTo(it.x, it.y) }
                transform.toScreen(b).let { lineTo(it.x, it.y) }
            }
            drawPath(
                path,
                colors.selection,
                style = Stroke(stroke, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
            )
        }
        state.placing?.let { kind ->
            val at = ghost ?: snap(Offset(size.width / 2f, size.height / 2f))
            val preview = Part(
                -1, kind, "", at.x, at.y, state.placingRotation, state.placingMirrored,
                value = state.placingSymbol?.params ?: io.github.l0gic0l.pepperjack.model.PartLibrary[kind].defaultValue,
                symbol = state.placingSymbol,
            )
            drawPart(preview, transform, colors.selection.copy(alpha = 0.6f), stroke, textMeasurer)
        }
        dropGhost?.let { drawPart(it, transform, colors.selection.copy(alpha = 0.6f), stroke, textMeasurer) }
    }
}

private class SchematicAnalysis(val junctions: Set<GridPoint>, val openPins: List<GridPoint>)

private fun analyze(schematic: Schematic): SchematicAnalysis {
    val connectivity = Connectivity(schematic)
    val pins = schematic.parts.filter { it.kind != PartKind.PROBE }.flatMap { it.pinPositions() }
    val pinCounts = pins.groupingBy { connectivity.root(it) }.eachCount()
    val open = schematic.parts
        .filter { it.kind != PartKind.LABEL && it.kind != PartKind.PROBE }
        .flatMap { it.pinPositions() }
        .filter { (pinCounts[connectivity.root(it)] ?: 0) < 2 }
    return SchematicAnalysis(schematic.junctions(), open)
}

/**
 * What a drag moves, for the preview: the parts being dragged, and every point that moves
 * with them (their pins and the dragged wires' ends), which stretches the wires attached there.
 */
private class DragSet(val parts: Set<Long>, val points: Set<GridPoint>) {
    companion object {
        fun of(schematic: Schematic, partIds: Set<Long>, wireIds: Set<Long>) = DragSet(
            partIds,
            buildSet {
                schematic.parts.filter { it.id in partIds && it.kind != PartKind.PROBE }.forEach { addAll(it.pinPositions()) }
                schematic.wires.filter { it.id in wireIds }.forEach { add(it.start); add(it.end) }
            },
        )
    }
}

private fun DrawScope.drawGrid(transform: GridTransform, color: Color) {
    var step = 1
    while (transform.scale * step < 10.dp.toPx()) step *= 2
    val topLeft = transform.toGrid(Offset.Zero)
    val bottomRight = transform.toGrid(Offset(size.width, size.height))
    val x0 = floor(topLeft.x / step).toInt() * step
    val y0 = floor(topLeft.y / step).toInt() * step
    val x1 = ceil(bottomRight.x).toInt()
    val y1 = ceil(bottomRight.y).toInt()
    val points = ArrayList<Offset>()
    var x = x0
    while (x <= x1) {
        var y = y0
        while (y <= y1) {
            points += transform.toScreen(x.toFloat(), y.toFloat())
            y += step
        }
        x += step
    }
    drawPoints(points, PointMode.Points, color, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
}

private fun DrawScope.drawProbes(schematic: Schematic, transform: GridTransform, probeColors: Map<Probe, Color>) {
    schematic.probes.forEach { probe ->
        val color = probeColors[probe] ?: return@forEach
        when (probe.type) {
            ProbeType.VOLTAGE -> {
                val c = transform.toScreen(probe.x.toFloat(), probe.y.toFloat())
                val tip = c + Offset(-12.dp.toPx(), -14.dp.toPx()) // up-left, clear of net label text
                drawLine(color, c, tip, 2.dp.toPx(), StrokeCap.Round)
                drawCircle(color, radius = 5.dp.toPx(), center = tip)
                drawCircle(color, radius = 2.5.dp.toPx(), center = c)
            }
            ProbeType.CURRENT -> {
                val part = schematic.parts.firstOrNull { it.id == probe.partId } ?: return@forEach
                val b = part.bounds()
                val c = transform.toScreen((b[0] + b[2]) / 2f, (b[1] + b[3]) / 2f)
                drawCircle(color, radius = maxOf(transform.scale * 1.4f, 12.dp.toPx()), center = c, style = Stroke(2.5.dp.toPx()))
            }
        }
    }
}

internal fun hitPart(schematic: Schematic, grid: Offset, tolerance: Float): Part? = schematic.parts.lastOrNull { part ->
    val b = if (part.kind == PartKind.LABEL) {
        floatArrayOf(part.x.toFloat(), part.y - 1.4f, part.x + 0.7f * part.value.length + 0.8f, part.y + 0.3f)
    } else {
        part.bounds()
    }
    grid.x in (b[0] - tolerance)..(b[2] + tolerance) && grid.y in (b[1] - tolerance)..(b[3] + tolerance)
}

internal fun hitWire(schematic: Schematic, grid: Offset, tolerance: Float): Wire? =
    schematic.wires.map { it to distanceToSegment(grid, it) }.filter { it.second <= tolerance }.minByOrNull { it.second }?.first

private fun distanceToSegment(p: Offset, wire: Wire): Float {
    val ax = wire.x1.toFloat()
    val ay = wire.y1.toFloat()
    val bx = wire.x2.toFloat()
    val by = wire.y2.toFloat()
    val dx = bx - ax
    val dy = by - ay
    val lengthSquared = dx * dx + dy * dy
    val t = if (lengthSquared == 0f) 0f else (((p.x - ax) * dx + (p.y - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
    return hypot(p.x - (ax + t * dx), p.y - (ay + t * dy))
}
