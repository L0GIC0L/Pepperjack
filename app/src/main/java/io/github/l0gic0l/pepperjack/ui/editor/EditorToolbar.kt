package io.github.l0gic0l.pepperjack.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.HighlightAlt
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.pepperjack.model.Part
import io.github.l0gic0l.pepperjack.model.bounds
import io.github.l0gic0l.pepperjack.ui.schematic.SchematicViewport
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WithTooltip(text: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
    ) { content() }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, checked: Boolean, onClick: () -> Unit) {
    WithTooltip(label) {
        IconToggleButton(
            checked = checked,
            onCheckedChange = { onClick() },
            colors = IconButtonDefaults.iconToggleButtonColors(
                checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) { Icon(icon, contentDescription = label) }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    WithTooltip(label) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = label) }
    }
}

/** Floating tool palette at the bottom of the schematic, in the spirit of M3 floating toolbars. */
@Composable
fun EditorToolbar(
    tool: Tool,
    canUndo: Boolean,
    canRedo: Boolean,
    onTool: (Tool) -> Unit,
    onParts: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 4.dp).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(Icons.Outlined.NearMe, "Select and move", tool == Tool.SELECT) { onTool(Tool.SELECT) }
            ToolButton(Icons.Outlined.HighlightAlt, "Select several", tool == Tool.AREA) { onTool(Tool.AREA) }
            ToolButton(Icons.Outlined.Timeline, "Draw wires", tool == Tool.WIRE) { onTool(Tool.WIRE) }
            WithTooltip("Add part") {
                FilledIconButton(onClick = onParts) { Icon(Icons.Outlined.Add, contentDescription = "Add part") }
            }
            ToolButton(Icons.AutoMirrored.Outlined.ShowChart, "Probe voltages and currents", tool == Tool.PROBE) { onTool(Tool.PROBE) }
            ToolButton(Icons.Outlined.ContentCut, "Delete", tool == Tool.DELETE) { onTool(Tool.DELETE) }
            VerticalDivider(Modifier.padding(horizontal = 4.dp).height(24.dp))
            ActionButton(Icons.AutoMirrored.Outlined.Undo, "Undo", canUndo, onUndo)
            ActionButton(Icons.AutoMirrored.Outlined.Redo, "Redo", canRedo, onRedo)
        }
    }
}

/** Context bar for the part being placed. */
@Composable
fun PlacingBar(title: String, onRotate: () -> Unit, onMirror: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    ContextSurface(modifier) {
        Text(
            "Placing ${title.replaceFirstChar { it.lowercase() }} — tap or drag to drop",
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp).widthIn(max = 260.dp),
        )
        ActionButton(Icons.AutoMirrored.Outlined.RotateRight, "Rotate", onClick = onRotate)
        ActionButton(Icons.Outlined.Flip, "Mirror", onClick = onMirror)
        ActionButton(Icons.Outlined.Close, "Stop placing", onClick = onDone)
    }
}

/**
 * Small toolbar beside the tapped part, kept above it (or below, near the top of the sheet)
 * as the view pans and zooms.
 */
@Composable
fun FloatingPartToolbar(
    part: Part,
    viewport: SchematicViewport,
    probed: Boolean,
    canProbe: Boolean,
    onEdit: () -> Unit,
    onRotate: () -> Unit,
    onMirror: () -> Unit,
    onDuplicate: () -> Unit,
    onProbe: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gap = 12.dp
    Layout(
        modifier = modifier.fillMaxSize(),
        content = {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shadowElevation = 4.dp,
                modifier = Modifier.testTag(PART_TOOLBAR),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp)) {
                    ActionButton(Icons.Outlined.Edit, "Edit ${part.ref}", onClick = onEdit)
                    ActionButton(Icons.AutoMirrored.Outlined.RotateRight, "Rotate", onClick = onRotate)
                    ActionButton(Icons.Outlined.Flip, "Mirror", onClick = onMirror)
                    ActionButton(Icons.Outlined.ContentCopy, "Duplicate", onClick = onDuplicate)
                    if (canProbe) {
                        WithTooltip(if (probed) "Remove the current probe" else "Probe the current") {
                            IconToggleButton(
                                checked = probed,
                                onCheckedChange = { onProbe() },
                                colors = IconButtonDefaults.iconToggleButtonColors(
                                    checkedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    checkedContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                ),
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.ShowChart, contentDescription = if (probed) "Remove the current probe" else "Probe the current")
                            }
                        }
                    }
                    ActionButton(Icons.Outlined.Delete, "Delete", onClick = onDelete)
                }
            }
        },
    ) { measurables, constraints ->
        val bar = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val transform = viewport.transform
            val b = part.bounds()
            val a = transform.toScreen(b[0], b[1])
            val c = transform.toScreen(b[2], b[3])
            val margin = 8.dp.roundToPx()
            val space = gap.roundToPx()
            val top = minOf(a.y, c.y).roundToInt()
            val bottom = maxOf(a.y, c.y).roundToInt()
            val above = top - space - bar.height
            val y = if (above >= margin) above else bottom + space
            val x = ((a.x + c.x) / 2 - bar.width / 2f).roundToInt()
            bar.place(
                x.coerceIn(margin, maxOf(margin, constraints.maxWidth - bar.width - margin)),
                y.coerceIn(margin, maxOf(margin, constraints.maxHeight - bar.height - margin)),
            )
        }
    }
}

const val PART_TOOLBAR = "part-toolbar"

/** An action on a [SelectionBar]. */
class BarAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/** Context bar for a selection: what is selected, and what can be done with it. */
@Composable
fun SelectionBar(text: String, actions: List<BarAction>, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    ContextSurface(modifier.testTag(SELECTION_BAR)) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // On a narrow screen the text gives way, so the buttons always fit.
            modifier = Modifier.padding(start = 12.dp, end = 4.dp).widthIn(max = 200.dp).weight(1f, fill = false),
        )
        actions.forEach { action -> ActionButton(action.icon, action.label, onClick = action.onClick) }
        if (onClose != null) ActionButton(Icons.Outlined.Close, "Clear the selection", onClick = onClose)
    }
}

const val SELECTION_BAR = "selection-bar"

/** Context bar for a selected wire or a hint for the active tool. */
@Composable
fun HintBar(text: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: () -> Unit = {}) {
    ContextSurface(modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp).widthIn(max = 320.dp).weight(1f, fill = false),
        )
        if (actionLabel != null) {
            androidx.compose.material3.TextButton(onClick = onAction) { Text(actionLabel) }
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun ContextSurface(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 3.dp,
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) { content() }
    }
}
