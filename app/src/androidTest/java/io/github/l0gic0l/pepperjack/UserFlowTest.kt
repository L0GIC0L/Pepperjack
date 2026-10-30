package io.github.l0gic0l.pepperjack

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToLog
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.l0gic0l.pepperjack.ui.home.NEW_CIRCUIT_LIST
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The two user flows that crashed on a Pixel Fold: opening Settings, and running a simulation. */
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
        rule.onNodeWithContentDescription("Live simulation").performClick()
        rule.waitUntilAtLeastOneExists(hasContentDescription("Stop live simulation"), timeoutMillis = 10_000)
        rule.waitUntilAtLeastOneExists(hasText("Operating point"), timeoutMillis = 60_000)
        rule.onRoot(useUnmergedTree = true).printToLog("PepperjackTest")
    }
}
