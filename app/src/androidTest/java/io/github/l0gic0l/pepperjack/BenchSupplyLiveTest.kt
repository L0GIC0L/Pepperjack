package io.github.l0gic0l.pepperjack

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToLog
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.l0gic0l.pepperjack.ui.home.NEW_CIRCUIT_LIST
import io.github.l0gic0l.pepperjack.ui.live.LIVE_CONTROLS
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * The biggest sheet, live, in the app: the bench supply's front panel shows its LCD readings.
 * CI runs this on its own after the other device tests, as it is the heaviest thing the app
 * draws, and an emulator once went away during it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class BenchSupplyLiveTest {
    @get:Rule(order = 0)
    val timeout: Timeout = Timeout.builder().withTimeout(6, TimeUnit.MINUTES).withLookingForStuckThread(true).build()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun benchSupplyShowsItsPanelLive() {
        rule.onNodeWithText("New circuit", useUnmergedTree = true).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag(NEW_CIRCUIT_LIST), timeoutMillis = 10_000)
        rule.onNodeWithTag(NEW_CIRCUIT_LIST).performScrollToNode(hasText("Triple bench power supply"))
        rule.onNodeWithText("Triple bench power supply").performClick()
        rule.waitUntilAtLeastOneExists(hasContentDescription("Live simulation"), timeoutMillis = 10_000)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("Live simulation").performClick()
        waitWithFrames { rule.onAllNodes(hasTestTag(LIVE_CONTROLS)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("VSET1").assertExists()
        // The LCD tiles show once the run is done and its live view is built.
        waitWithFrames(timeoutMillis = 240_000) { rule.onAllNodes(hasText("VOUT1")).fetchSemanticsNodes().isNotEmpty() }
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
