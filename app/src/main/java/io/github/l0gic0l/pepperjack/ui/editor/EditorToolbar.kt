package io.github.l0gic0l.pepperjack.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.pepperjack.model.Part

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

/** Context bar for the selected part. */
@Composable
fun SelectionBar(
    part: Part,
    onEdit: () -> Unit,
    onRotate: () -> Unit,
    onMirror: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ContextSurface(modifier) {
        Text(
            listOf(part.ref, part.value).filter { it.isNotBlank() }.distinct().joinToString("  "),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp).widthIn(max = 160.dp),
        )
        ActionButton(Icons.Outlined.Edit, "Edit properties", onClick = onEdit)
        ActionButton(Icons.AutoMirrored.Outlined.RotateRight, "Rotate", onClick = onRotate)
        ActionButton(Icons.Outlined.Flip, "Mirror", onClick = onMirror)
        ActionButton(Icons.Outlined.ContentCopy, "Duplicate", onClick = onDuplicate)
        ActionButton(Icons.Outlined.Delete, "Delete", onClick = onDelete)
    }
}

/** Context bar for a selected wire or a hint for the active tool. */
@Composable
fun HintBar(text: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: () -> Unit = {}) {
    ContextSurface(modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp).widthIn(max = 320.dp),
        )
        if (actionLabel != null) {
            androidx.compose.material3.TextButton(onClick = onAction) { Text(actionLabel) }
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun ContextSurface(modifier: Modifier, content: @Composable () -> Unit) {
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
