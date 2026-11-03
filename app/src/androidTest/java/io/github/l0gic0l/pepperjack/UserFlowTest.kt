package io.github.l0gic0l.pepperjack

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.l0gic0l.pepperjack.ui.home.NEW_CIRCUIT_LIST
import io.github.l0gic0l.pepperjack.ui.editor.PARTS_TRAY
import io.github.l0gic0l.pepperjack.ui.editor.PART_TOOLBAR
import io.github.l0gic0l.pepperjack.ui.home.ONLINE_SEARCH
import io.github.l0gic0l.pepperjack.ui.live.LIVE_SCOPE
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The main user flows, starting with the two that crashed on a Pixel Fold: opening Settings, and running a simulation. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UserFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsShowsTheEngine() {
        rule.onNodeWithText("Settings").performClick()
        rule.waitUntilDoesNotExist(hasText("Checking ngspice…"), timeoutMillis = 30_000)
        rule.onNodeWithText("Simulation unavailable").assertDoesNotExist()
    }

    @Test
    fun runningAnExampleShowsResults() {
        rule.onRoot(useUnmergedTree = true).printToLog("PepperjackTest")
        // The extended FAB's label is only in the unmerged tree; a tap on it lands on the FAB.
        rule.onNodeWithText("New circuit", useUnmergedTree = true).performClick()
        rule.waitUntilAtLeastOneExists(hasText("RC low-pass filter"), timeoutMillis = 10_000)
        rule.onNodeWithText("RC low-pass filter").performScrollTo().performClick()
        rule.waitUntilAtLeastOneExists(hasContentDescription("Run simulation"), timeoutMillis = 10_000)
        rule.onNodeWithContentDescription("Run simulation").performClick()
        rule.waitUntilAtLeastOneExists(hasText("AC Analysis"), timeoutMillis = 60_000)
    }

    @Test
    fun liveModeRunsAnExample() {
        rule.onNodeWithText("New circuit", useUnmergedTree = true).performClick()
        rule.waitUntilAtLeastOneExists(hasText("Examples"), timeoutMillis = 10_000)
        // The examples are a lazy list; scroll the sheet until the item is composed.
        rule.onNodeWithTag(NEW_CIRCUIT_LIST).performScrollToNode(hasText("Light switch (try live mode)"))
        rule.onNodeWithText("Light switch (try live mode)").performClick()
        rule.waitUntilAtLeastOneExists(hasContentDescription("Live simulation"), timeoutMillis = 10_000)
        // The live view animates continuously, so Compose never idles: drive the frame clock by hand.
        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("Live simulation").performClick()
        waitWithFrames { rule.onAllNodes(hasContentDescription("Stop live simulation")).fetchSemanticsNodes().isNotEmpty() }
        waitWithFrames { rule.onAllNodes(hasText("Operating point")).fetchSemanticsNodes().isNotEmpty() }
        rule.onRoot(useUnmergedTree = true).printToLog("PepperjackTest")
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
        rule.onRoot(useUnmergedTree = true).printToLog("PepperjackTest")
    }

    private fun waitWithFrames(timeoutMillis: Long = 60_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for the live view" }
            rule.mainClock.advanceTimeBy(100)
            Thread.sleep(20)
        }
    }
}
