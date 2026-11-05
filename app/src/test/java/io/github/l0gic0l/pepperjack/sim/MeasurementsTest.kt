package io.github.l0gic0l.pepperjack.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class MeasurementsTest {
    private fun sampled(cycles: Int, perCycle: Int, f: (Double) -> Double): Pair<DoubleArray, DoubleArray> {
        val n = cycles * perCycle + 1
        val t = DoubleArray(n) { it * 1e-3 / perCycle }
        return t to DoubleArray(n) { f(t[it]) }
    }

    @Test
    fun sineWithOffset() {
        val (t, v) = sampled(10, 400) { 2 + sin(2 * PI * 1000 * it) }
        val s = WaveStats.of(t, v)!!
        assertEquals(2.0, s.mean, 1e-6)
        assertEquals(sqrt(4.5), s.rms, 1e-5)
        assertEquals(1 / sqrt(2.0), s.acRms, 1e-4)
        assertEquals(2.0, s.peakToPeak, 1e-4)
        assertEquals(1000.0, s.frequency!!, 0.5)
        assertEquals(1e-3, s[Measure.PERIOD]!!, 1e-6)
    }

    @Test
    fun windowInterpolatesAtItsEdges() {
        val t = doubleArrayOf(0.0, 1.0)
        val v = doubleArrayOf(0.0, 1.0)
        val s = WaveStats.of(t, v, from = 0.75, to = 0.25)!!
        assertEquals(0.25, s.min, 1e-12)
        assertEquals(0.75, s.max, 1e-12)
        assertEquals(0.5, s.mean, 1e-12)
        assertNull(s.frequency)
    }

    @Test
    fun meanIsWeightedByTimeNotBySample() {
        // Five samples crowd into the last tenth of the run, as SPICE does at an edge.
        val t = doubleArrayOf(0.0, 0.9, 0.92, 0.94, 0.96, 0.98, 1.0)
        val v = doubleArrayOf(1.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val s = WaveStats.of(t, v)!!
        assertEquals(0.91, s.mean, 1e-9)
    }

    @Test
    fun operatingPointIsAFlatLine() {
        val s = WaveStats.of(null, doubleArrayOf(-3.0))!!
        assertEquals(-3.0, s.mean, 0.0)
        assertEquals(3.0, s.rms, 0.0)
        assertEquals(0.0, s.peakToPeak, 0.0)
        assertNull(s.frequency)
    }

    @Test
    fun noiseNearTheMidLevelCountsOnce() {
        // A square wave with a little ringing on each edge still reads its true rate.
        val (t, v) = sampled(20, 200) { time ->
            val phase = (time * 500) % 1.0
            (if (phase < 0.5) 0.0 else 5.0) + 0.3 * sin(2 * PI * 20_000 * time)
        }
        assertEquals(500.0, WaveStats.of(t, v)!!.frequency!!, 1.0)
    }

    @Test
    fun maskToggles() {
        var mask = 0
        mask = Measure.toggle(mask, Measure.RMS)
        mask = Measure.toggle(mask, Measure.FREQUENCY)
        assertEquals(listOf(Measure.RMS, Measure.FREQUENCY), Measure.fromMask(mask))
        mask = Measure.toggle(mask, Measure.RMS)
        assertEquals(listOf(Measure.FREQUENCY), Measure.fromMask(mask))
    }
}
