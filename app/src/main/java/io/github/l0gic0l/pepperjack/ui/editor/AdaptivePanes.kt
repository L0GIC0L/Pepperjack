package io.github.l0gic0l.pepperjack.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass

enum class PaneLayout { COMPACT, SIDE_BY_SIDE, TABLETOP }

/** Which arrangement [AdaptivePanes] will use for the current window and posture. */
@Composable
fun currentPaneLayout(): PaneLayout {
    val adaptive = currentWindowAdaptiveInfoV2()
    val hinge = adaptive.windowPosture.hingeList.firstOrNull { it.isSeparating }
    return when {
        adaptive.windowPosture.isTabletop && hinge != null && !hinge.isVertical -> PaneLayout.TABLETOP
        adaptive.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> PaneLayout.SIDE_BY_SIDE
        else -> PaneLayout.COMPACT
    }
}

/**
 * Lays out the editor and the results for the current window:
 *  - phones and folded foldables: one pane at a time ([compact] supplies the switcher);
 *  - unfolded foldables, tablets and desktop windows: side by side, split exactly at a
 *    vertical hinge when one separates the screen;
 *  - tabletop posture (half-folded, hinge horizontal): results on the upper half, the
 *    editor on the lower half where it is easy to touch.
 */
@Composable
fun AdaptivePanes(
    editor: @Composable () -> Unit,
    results: @Composable () -> Unit,
    compact: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val adaptive = currentWindowAdaptiveInfoV2()
    val posture = adaptive.windowPosture
    val sizeClass = adaptive.windowSizeClass
    val layout = currentPaneLayout()
    val expanded = sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val hinge = posture.hingeList.firstOrNull { it.isSeparating }
    val density = LocalDensity.current

    var origin by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        val minPane = 160.dp
        when {
            layout == PaneLayout.TABLETOP && hinge != null && maxHeight > minPane * 2 -> {
                val top: Dp = with(density) { (hinge.bounds.top - origin.y).toDp() }.coerceIn(minPane, maxHeight - minPane)
                val gap: Dp = with(density) { hinge.bounds.height.toDp() }
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(top)) { results() }
                    if (gap > 0.dp) Spacer(Modifier.height(gap)) else HorizontalDivider()
                    Box(Modifier.fillMaxWidth().weight(1f)) { editor() }
                }
            }
            layout != PaneLayout.COMPACT && maxWidth > minPane * 2 -> {
                val verticalHinge = hinge?.takeIf { it.isVertical }
                val split: Dp = if (verticalHinge != null) {
                    with(density) { (verticalHinge.bounds.left - origin.x).toDp() }
                } else {
                    maxWidth * if (expanded) 0.58f else 0.5f
                }.coerceIn(minPane, maxWidth - minPane)
                val gap: Dp = verticalHinge?.let { with(density) { it.bounds.width.toDp() } } ?: 0.dp
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(split).fillMaxHeight()) { editor() }
                    if (gap > 0.dp) Spacer(Modifier.width(gap)) else VerticalDivider()
                    Box(Modifier.weight(1f).fillMaxHeight()) { results() }
                }
            }
            else -> compact()
        }
    }
}
