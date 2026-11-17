package io.github.l0gic0l.pepperjack.ui.live

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.pepperjack.model.Probe

const val LIVE_PANEL = "live-panel"

/** A docked panel's content is never dragged shorter than this; shorter still, and it folds. */
private val MIN_CONTENT = 96.dp
private const val FOLD_BELOW_DP = 48f

/** A docked panel's scope starts out with a plot of 160 dp under its row of chips. */
private val SCOPE_CONTENT = 216.dp

/** How tall a docked panel's controls start out: about two fifths of the window. */
@Composable
private fun defaultControlsHeight(): Dp {
    val window = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    return (window * 0.4f).coerceIn(200.dp, 360.dp)
}

/**
 * Everything worked while live simulating, in one place: the playback bar, and under it the
 * Controls and Scope tabs.
 *
 * On a phone it is docked under the sheet ([dockLimit] is how tall its content may get), so
 * the sheet keeps the rest of the screen to itself and fits there. Its bar is a handle: drag
 * it up or down to give the panel more or less of the screen, right down to just the bar.
 * Where there is a second pane (an unfolded foldable, a tablet, landscape) it fills that
 * instead ([dockLimit] is null), and the sheet has the first pane whole.
 */
@Composable
fun LivePanel(
    panel: LivePanelState,
    animator: LiveAnimator?,
    history: ScopeHistory,
    colors: Map<Probe, Color>,
    onRemove: (Probe) -> Unit,
    running: Boolean,
    error: String?,
    mode: LiveColorMode,
    onMode: (LiveColorMode) -> Unit,
    modifier: Modifier = Modifier,
    dockLimit: Dp? = null,
    controls: (@Composable (Modifier) -> Unit)? = null,
) {
    if (dockLimit == null) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.testTag(LIVE_PANEL).fillMaxSize()) {
            Column {
                LiveBar(animator, running, error, mode, onMode)
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    // The tabs' row takes 48 dp of the pane.
                    LiveScope(panel, animator, history, colors, onRemove, cap = (maxHeight - 48.dp).coerceAtLeast(MIN_CONTENT), controls = controls)
                }
            }
        }
        return
    }

    val density = LocalDensity.current.density
    val limit = dockLimit.coerceAtLeast(MIN_CONTENT)
    // Each tab keeps the height it was dragged to: a list of knobs wants more room than a plot.
    val onControls = controls != null && panel.controlsTab
    val chosen = if (onControls) panel.controlsHeight else panel.scopeHeight
    val default = if (onControls) defaultControlsHeight() else SCOPE_CONTENT
    val cap = (if (chosen.isNaN()) default else chosen.dp).coerceIn(MIN_CONTENT, limit)
    var contentPx by remember { mutableIntStateOf(0) }
    // Where the drag has taken the content's height, in dp, before it is limited.
    var dragged by remember { mutableFloatStateOf(0f) }
    val drag = rememberDraggableState { delta ->
        dragged -= delta / density
        if (dragged < FOLD_BELOW_DP) {
            panel.open = false
        } else {
            panel.open = true
            val height = dragged.coerceIn(MIN_CONTENT.value, limit.value)
            if (onControls) panel.controlsHeight = height else panel.scopeHeight = height
        }
    }
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
        modifier = modifier.testTag(LIVE_PANEL).fillMaxWidth(),
    ) {
        Column {
            LiveBar(
                animator, running, error, mode, onMode,
                modifier = Modifier.draggable(
                    drag,
                    Orientation.Vertical,
                    // From the height it shows, which is less than its limit when the content is short.
                    onDragStarted = {
                        dragged = when {
                            !panel.open -> 0f
                            contentPx > 0 -> minOf(cap.value, contentPx / density)
                            else -> cap.value
                        }
                    },
                ),
                grip = true,
            ) {
                IconButton(onClick = { panel.open = !panel.open }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (panel.open) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                        contentDescription = if (panel.open) "Fold the live panel" else "Open the live panel",
                    )
                }
            }
            if (panel.open) {
                LiveScope(panel, animator, history, colors, onRemove, cap = cap, onContentHeight = { contentPx = it }, controls = controls)
            }
        }
    }
}
