package io.github.l0gic0l.pepperjack.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.pepperjack.model.CustomSymbol
import io.github.l0gic0l.pepperjack.model.Part
import io.github.l0gic0l.pepperjack.model.PartCategory
import io.github.l0gic0l.pepperjack.model.PartKind
import io.github.l0gic0l.pepperjack.model.PartLibrary
import io.github.l0gic0l.pepperjack.model.Schematic
import io.github.l0gic0l.pepperjack.ui.schematic.drawSchematicPreview
import io.github.l0gic0l.pepperjack.ui.theme.LocalCircuitColors
import kotlin.math.abs

const val PARTS_TRAY = "parts-tray"

/** Receives a part being dragged out of the tray, in window coordinates. */
interface PartDragHandler {
    fun start(choice: PartChoice, window: Offset)
    fun move(window: Offset)
    fun drop(window: Offset)
    fun cancel()
}

/**
 * Parts docked above the toolbar, a category at a time. Tap a part to place it with a tap,
 * or drag it up out of the tray straight onto the sheet; sideways drags scroll the row.
 */
@Composable
fun PartsTray(
    symbols: List<CustomSymbol>,
    onPick: (PartChoice) -> Unit,
    drag: PartDragHandler,
    onSearch: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val categories = PartCategory.entries.filter { c ->
        if (c == PartCategory.CUSTOM) symbols.isNotEmpty() else PartLibrary.all.any { it.category == c }
    }
    var picked by rememberSaveable { mutableStateOf(PartCategory.PASSIVE) }
    val category = if (picked in categories) picked else PartCategory.PASSIVE
    val choices = remember(category, symbols) {
        if (category == PartCategory.CUSTOM) {
            symbols.map { PartChoice(PartKind.CUSTOM, it) to it.title }
        } else {
            PartLibrary.all.filter { it.category == category }.map { PartChoice(it.kind) to it.title }
        }
    }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = modifier.testTag(PARTS_TRAY),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AssistChip(onClick = onSearch, label = { Text("Search") }, leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp)) })
                    categories.forEach { c ->
                        FilterChip(selected = c == category, onClick = { picked = c }, label = { Text(c.title) })
                    }
                }
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close the parts tray") }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(choices, key = { (choice, _) -> choice.symbol?.id ?: choice.kind.name }) { (choice, title) ->
                    TrayTile(choice, title, onTap = { onPick(choice) }, drag = drag)
                }
            }
        }
    }
}

@Composable
private fun TrayTile(choice: PartChoice, title: String, onTap: () -> Unit, drag: PartDragHandler) {
    val colors = LocalCircuitColors.current
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val def = PartLibrary[choice.kind]
    val sheet = remember(choice) {
        Schematic(parts = listOf(Part(0, choice.kind, "", 0, 0, value = choice.symbol?.params ?: def.defaultValue, symbol = choice.symbol)))
    }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val tap by rememberUpdatedState(onTap)
    val handler by rememberUpdatedState(drag)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(76.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(16.dp))
            .onGloballyPositioned { coordinates = it }
            .semantics {
                contentDescription = "Add $title"
                onClick { tap(); true }
            }
            .pointerInput(choice) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun window(local: Offset) = coordinates?.localToWindow(local) ?: local
                    var dragging = false
                    var tapping = true
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            if (dragging) handler.cancel()
                            break
                        }
                        if (!change.pressed) {
                            when {
                                dragging -> handler.drop(window(change.position))
                                tapping && !change.isConsumed -> tap()
                            }
                            break
                        }
                        val delta = change.position - down.position
                        if (!dragging && tapping && delta.getDistance() > viewConfiguration.touchSlop) {
                            // Up and out of the tray drags the part; sideways leaves it to the row to scroll.
                            if (-delta.y > abs(delta.x)) {
                                dragging = true
                                handler.start(choice, window(change.position))
                            } else {
                                tapping = false
                            }
                        }
                        if (dragging) {
                            change.consume()
                            handler.move(window(change.position))
                        } else if (change.isConsumed) {
                            tapping = false
                        }
                    }
                }
            }
            .padding(6.dp),
    ) {
        Canvas(Modifier.size(44.dp)) { drawSchematicPreview(sheet, colors, textMeasurer) }
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
