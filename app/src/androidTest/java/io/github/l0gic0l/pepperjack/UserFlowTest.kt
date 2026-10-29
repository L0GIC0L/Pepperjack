package io.github.l0gic0l.pepperjack

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        rule.onNodeWithText("New circuit").performClick()
        rule.waitUntilAtLeastOneExists(hasText("RC low-pass filter"), timeoutMillis = 10_000)
        rule.onNodeWithText("RC low-pass filter").performClick()
        rule.waitUntilAtLeastOneExists(hasContentDescription("Run simulation"), timeoutMillis = 10_000)
        rule.onNodeWithContentDescription("Run simulation").performClick()
        rule.waitUntilAtLeastOneExists(hasText("AC Analysis"), timeoutMillis = 60_000)
    }
}
