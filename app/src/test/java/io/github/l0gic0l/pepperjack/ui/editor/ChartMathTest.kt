package io.github.l0gic0l.pepperjack.ui.editor

import io.github.l0gic0l.pepperjack.ui.results.interpolate
import io.github.l0gic0l.pepperjack.ui.results.logTicks
import io.github.l0gic0l.pepperjack.ui.results.niceTicks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChartMathTest {
    @Test
    fun niceTicksUseOneTwoFiveSteps() {
        assertEquals(listOf(0.0, 0.002, 0.004, 0.006, 0.008, 0.01), niceTicks(0.0, 0.01, 5))
        assertEquals(listOf(-10.0, -5.0, 0.0, 5.0, 10.0), niceTicks(-12.0, 12.0, 4))
    }

    @Test
    fun logTicksLandOnDecades() {
        assertEquals(listOf(10.0, 100.0, 1000.0, 10000.0, 100000.0, 1000000.0), logTicks(10.0, 1e6))
    }

    @Test
    fun interpolatesBetweenSamples() {
        val x = doubleArrayOf(0.0, 1.0, 2.0)
        val y = doubleArrayOf(0.0, 10.0, 0.0)
        assertEquals(5.0, interpolate(x, y, 0.5)!!, 1e-12)
        assertEquals(10.0, interpolate(x, y, 1.0)!!, 1e-12)
        assertNull(interpolate(x, y, 3.0))
    }
}
