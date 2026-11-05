package io.github.l0gic0l.pepperjack

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.l0gic0l.pepperjack.ui.home.NEW_CIRCUIT_LIST
import io.github.l0gic0l.pepperjack.ui.live.SCOPE_CURSORS
import io.github.l0gic0l.pepperjack.ui.live.SCOPE_MEASURE
import io.github.l0gic0l.pepperjack.ui.editor.PARTS_TRAY
import io.github.l0gic0l.pepperjack.ui.editor.PART_TOOLBAR
import io.github.l0gic0l.pepperjack.ui.home.ONLINE_SEARCH
import io.github.l0gic0l.pepperjack.ui.live.LIVE_CONTROLS
import io.github.l0gic0l.pepperjack.ui.live.LIVE_SCOPE
import org.junit.Rule
import org.junit.rules.Timeout
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** The main user flows, starting with the two that crashed on a Pixel Fold: opening Settings, and running a simulation. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UserFlowTest {
    // A stuck test fails with its name and the stuck thread's stack, rather than holding up the
    // rest of the run.
    @get:Rule(order = 0)
    val timeout: Timeout = Timeout.builder().withTimeout(5, TimeUnit.MINUTES).withLookingForStuckThread(true).build()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsShowsTheEngine() {
        rule.onNodeWithText("Settings").performClick()
        rule.waitUntilDoesNotExist(hasText("Checking ngspice…"), timeoutMillis = 30_000)
        rule.onNodeWithText("Simulation unavailable").assertDoesNotExist()
    }

    @Test
    fun runningAnExampleShowsResults() {
        rule.onAllNodes(isRoot(), useUnmergedTree = true).printToLog("PepperjackTree")
        // The extended FAB's label is only in the unmerged tree; a tap on it lands on the FAB.
        rule.onNodeWithText("New circuit", useUnmergedTree = true).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag(NEW_CIRCUIT_LIST), timeoutMillis = 10_000)
        rule.onNodeWithTag(NEW_CIRCUIT_LIST).performScrollToNode(hasText("RC low-pass filter"))
        rule.onNodeWithText("RC low-pass filter").performClick()
        rule.waitUntilAtLeastOneExists(hasContentDescription("Run simulation"), timeoutMillis = 10_000)
        rule.onNodeWithContentDescription("Run simulation").performClick()
        rule.waitUntilAtLeastOneExists(hasText("AC Analysis"), timeoutMillis = 60_000)
    }

    @Test
    fun liveModeRunsAnExample() {
        rule.onNodeWithText("New circuit", useUnmergedTree = true).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag(NEW_CIRCUIT_LIST), timeoutMillis = 10_000)
        // The examples are a lazy list; scroll the sheet until the item is composed.
        rule.onNodeWithTag(NEW_CIRCUIT_LIST).performScrollToNode(hasText("Light switch (try live mode)"))
        rule.onNodeWithText("Light switch (try live mode)").performClick()
        rule.waitUntilAtLeastOneExists(hasContentDescription("Live simulation"), timeoutMillis = 10_000)
        // The live view animates continuously, so Compose never idles: drive the frame clock by hand.
        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("Live simulation").performClick()
        waitWithFrames { rule.onAllNodes(hasContentDescription("Stop live simulation")).fetchSemanticsNodes().isNotEmpty() }
        waitWithFrames { rule.onAllNodes(hasText("Operating point")).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(isRoot(), useUnmergedTree = true).printToLog("PepperjackTree")
    }

    @Test
    fun browsingOnlineModelsFindsAPart() {
        rule.onNodeWithText("Models").performClick()
        rule.onNodeWithText("Browse online models").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag(ONLINE_SEARCH), timeoutMillis = 10_000)
        rule.onNodeWithTag(ONLINE_SEARCH).performTextInput("LM358")
        rule.waitUntilAtLeastOneExists(hasText("Texas Instruments/lm358.mod", substring = true), timeoutMillis = 30_000)
    }

    /** The touch flow: drag a part in from the tray, use its toolbar, then turn its knob live. */
    @Test
    fun draggingAPartInThenTurningItsKnob() {
        rule.onNodeWithText("New circuit", useUnmergedTree = true).performClick()
        rule.waitUntilAtLeastOneExists(hasText("Blank schematic"), timeoutMillis = 10_000)
        rule.onNodeWithText("Blank schematic").performClick()
        rule.waitUntilAtLeastOneExists(hasContentDescription("Add part"), timeoutMillis = 10_000)
        rule.onNodeWithContentDescription("Add part").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag(PARTS_TRAY), timeoutMillis = 5_000)
        // Straight up out of the tray to a third of the way down the sheet, whatever the screen size.
        val sheet = rule.onNodeWithContentDescription("Schematic with", substring = true).fetchSemanticsNode().boundsInRoot
        val tile = rule.onNodeWithContentDescription("Add Resistor").fetchSemanticsNode().boundsInRoot
        val target = Offset(tile.center.x, sheet.top + sheet.height / 3) - tile.topLeft
        rule.onNodeWithContentDescription("Add Resistor").performTouchInput {
            down(center)
            for (step in 1..10) moveTo(center + (target - center) * (step / 10f))
            up()
        }
        rule.waitUntilAtLeastOneExists(hasContentDescription("Schematic with 1 parts", substring = true), timeoutMillis = 5_000)
        rule.onNodeWithTag(PART_TOOLBAR).assertExists()
        rule.onNodeWithContentDescription("Rotate").performClick()

        // Live: the selected resistor gets a knob, and the scope asks for a probe.
        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("Live simulation").performClick()
        waitWithFrames { rule.onAllNodes(hasText("R1 1 kΩ")).fetchSemanticsNodes().isNotEmpty() }
        waitWithFrames { rule.onAllNodes(hasTestTag(LIVE_SCOPE)).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(isRoot(), useUnmergedTree = true).printToLog("PepperjackTree")
    }

    /** Search the examples, run one live, and measure it on the scope with the cursors on. */
    @Test
    fun scopeMeasuresBetweenCursors() {
        rule.onNodeWithText("New circuit", useUnmergedTree = true).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag(NEW_CIRCUIT_LIST), timeoutMillis = 10_000)
        rule.onNode(hasSetTextAction()).performTextInput("step response")
        // The keyboard is up and the matches sit below the search box: scroll to them.
        rule.onNodeWithTag(NEW_CIRCUIT_LIST).performScrollToNode(hasText("RL step response"))
        rule.onNodeWithTag(NEW_CIRCUIT_LIST).performScrollToNode(hasText("RC step response"))
        // Only in the examples list: an earlier test may have saved one to the circuits behind it.
        rule.onNode(hasText("RC low-pass filter") and hasAnyAncestor(hasTestTag(NEW_CIRCUIT_LIST))).assertDoesNotExist()
        rule.onNodeWithText("RC step response").performClick()
        rule.waitUntilAtLeastOneExists(hasContentDescription("Live simulation"), timeoutMillis = 10_000)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("Live simulation").performClick()
        waitWithFrames { rule.onAllNodes(hasTestTag(SCOPE_MEASURE)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(SCOPE_MEASURE).performClick()
        waitWithFrames { rule.onAllNodes(hasText("Peak to peak")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Peak to peak").performClick()
        waitWithFrames { rule.onAllNodes(hasText("P-P")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Done").performClick()
        waitWithFrames { rule.onAllNodes(hasText("Peak to peak")).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag(SCOPE_CURSORS).performClick()
        waitWithFrames { rule.onAllNodes(hasText("at A")).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(isRoot(), useUnmergedTree = true).printToLog("PepperjackTree")
    }

    /** Box-select a whole example with the area tool, then delete it as one selection. */
    @Test
    fun boxSelectingAndDeletingTogether() {
        rule.onNodeWithText("New circuit", useUnmergedTree = true).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag(NEW_CIRCUIT_LIST), timeoutMillis = 10_000)
        rule.onNodeWithTag(NEW_CIRCUIT_LIST).performScrollToNode(hasText("Voltage divider"))
        rule.onNodeWithText("Voltage divider").performClick()
        rule.waitUntilAtLeastOneExists(hasContentDescription("Select several"), timeoutMillis = 10_000)
        rule.onNodeWithContentDescription("Select several").performClick()
        // Corner to corner across the sheet, which the example is fitted to. The drag starts in
        // the top-left corner, clear of the hint bar; where it ends doesn't matter.
        rule.onNodeWithContentDescription("Schematic with", substring = true).performTouchInput {
            down(Offset(width * 0.01f, height * 0.01f))
            for (step in 1..12) moveTo(Offset(width * (0.01f + 0.98f * step / 12), height * (0.01f + 0.98f * step / 12)))
            up()
        }
        rule.waitUntilAtLeastOneExists(hasContentDescription("Delete the selection"), timeoutMillis = 5_000)
        rule.onAllNodes(isRoot(), useUnmergedTree = true).printToLog("PepperjackTree")
        // The divider has 7 parts (V1, its ground, AM1, R1, R2, the out label, VM1) and 6 wires.
        rule.onNodeWithText("7 parts, 6 wires · drag to move").assertExists()
        rule.onNodeWithContentDescription("Delete the selection").performClick()
        rule.waitUntilAtLeastOneExists(hasContentDescription("Schematic with 0 parts and 0 wires"), timeoutMillis = 5_000)
    }

    /** A sheet's switches and knobs are worked live from the panel's Controls tab. */
    @Test
    fun controlsFlipASwitchLive() {
        rule.onNodeWithText("New circuit", useUnmergedTree = true).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag(NEW_CIRCUIT_LIST), timeoutMillis = 10_000)
        rule.onNodeWithTag(NEW_CIRCUIT_LIST).performScrollToNode(hasText("Light switch (try live mode)"))
        rule.onNodeWithText("Light switch (try live mode)").performClick()
        rule.waitUntilAtLeastOneExists(hasContentDescription("Live simulation"), timeoutMillis = 10_000)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("Live simulation").performClick()
        // A sheet with a switch opens on Controls; the switch starts closed.
        waitWithFrames { rule.onAllNodes(hasTestTag(LIVE_CONTROLS)).fetchSemanticsNodes().isNotEmpty() }
        waitWithFrames { rule.onAllNodes(hasText("Closed")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("SW1 switch").performClick()
        waitWithFrames { rule.onAllNodes(hasText("Open")).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(isRoot(), useUnmergedTree = true).printToLog("PepperjackTree")
    }

    private fun waitWithFrames(timeoutMillis: Long = 60_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for the live view" }
            // One frame at a time, at about a real screen's pace: the emulator draws every one in
            // software, and racing the clock ahead flooded it until it went down.
            rule.mainClock.advanceTimeBy(16)
            Thread.sleep(16)
        }
    }
}
