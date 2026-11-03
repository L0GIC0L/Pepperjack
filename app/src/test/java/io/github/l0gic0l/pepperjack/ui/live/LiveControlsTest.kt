package io.github.l0gic0l.pepperjack.ui.live

import io.github.l0gic0l.pepperjack.model.Part
import io.github.l0gic0l.pepperjack.model.PartKind
import io.github.l0gic0l.pepperjack.model.Probe
import io.github.l0gic0l.pepperjack.model.ProbeType
import io.github.l0gic0l.pepperjack.sim.LiveTrace
import io.github.l0gic0l.pepperjack.sim.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveControlsTest {
    private fun part(kind: PartKind, value: String) = Part(1, kind, "X1", 0, 0, value = value)

    @Test
    fun resistorKnobSweepsADecadeEachWay() {
        val knob = checkNotNull(LiveKnob.of(part(PartKind.RESISTOR, "1k")))
        assertEquals(100.0, knob.low, 1e-9)
        assertEquals(10_000.0, knob.high, 1e-9)
        assertEquals(0.5f, knob.fraction(1000.0), 1e-6f)
        assertEquals("1k", knob.text(knob.value(0.5f)))
        assertEquals("10k", knob.text(knob.value(1f)))
        // Two significant figures, as standard values have: 10^(0.75 × 2) × 100 n = 3.16 µ → 3.2 µ.
        val cap = checkNotNull(LiveKnob.of(part(PartKind.CAPACITOR, "1u")))
        assertEquals("3.2u", cap.text(cap.value(0.75f)))
    }

    @Test
    fun sourceKnobSweepsFromZeroToTwiceTheLevel() {
        val knob = checkNotNull(LiveKnob.of(part(PartKind.VOLTAGE, "DC 9")))
        assertEquals(0.0, knob.low, 0.0)
        assertEquals(18.0, knob.high, 0.0)
        assertEquals(0.5f, knob.fraction(9.0), 1e-6f)
        assertEquals("DC 12", knob.text(12.0))
        assertEquals("V", knob.unit)
        assertEquals(9.0, LiveKnob.read(part(PartKind.VOLTAGE, "DC 9"))!!, 0.0)
    }

    @Test
    fun partsWithoutOneValueGetNoKnob() {
        assertNull(LiveKnob.of(part(PartKind.VOLTAGE, "SIN(0 1 1k)")))
        assertNull(LiveKnob.of(part(PartKind.RESISTOR, "{rload}")))
        assertNull(LiveKnob.of(part(PartKind.DIODE, "1N4148")))
    }

    @Test
    fun scopeRangeIncludesZeroWithAMargin() {
        val (lo, hi) = scopeRange(2.0, 5.0)
        assertEquals(-0.5, lo, 1e-12)
        assertEquals(5.5, hi, 1e-12)
        val (flatLo, flatHi) = scopeRange(0.0, 0.0)
        assertTrue(flatLo < 0 && flatHi > 0)
    }

    @Test
    fun scopeHistoryRollsOverItsWindow() {
        val a = Probe(ProbeType.VOLTAGE, 1, 1)
        val b = Probe(ProbeType.CURRENT, partId = 2)
        fun trace(p: Probe, v: Double) = LiveTrace(p, "t", Quantity.VOLTAGE, doubleArrayOf(v))
        val history = ScopeHistory()
        history.record(listOf(trace(a, 1.0)), 1.0)
        history.record(listOf(trace(a, 2.0), trace(b, 5.0)), 1.0)
        assertEquals(listOf(1.0, 2.0), history.values.getValue(a).toList())
        // A probe added later is padded so every column lines up with the time axis.
        assertEquals(2, history.values.getValue(b).size)
        assertTrue(history.values.getValue(b).first().isNaN())
        repeat(20) { history.record(listOf(trace(a, 3.0)), 1.0) }
        assertTrue(history.times.first() >= history.clock - ScopeHistory.WINDOW_SECONDS)
        assertEquals(history.times.size, history.values.getValue(a).size)
        // Once a removed probe's readings have rolled out of the window, it is forgotten.
        assertTrue(b !in history.values)
    }
}
