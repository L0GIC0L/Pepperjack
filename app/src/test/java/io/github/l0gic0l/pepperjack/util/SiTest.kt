package io.github.l0gic0l.pepperjack.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SiTest {
    private fun assertClose(expected: Double, actual: Double?) {
        requireNotNull(actual)
        assertEquals(expected, actual, kotlin.math.abs(expected) * 1e-12)
    }

    @Test
    fun parsesSpiceSuffixes() {
        assertClose(4700.0, Si.parse("4.7k"))
        assertClose(1e7, Si.parse("10Meg"))
        assertClose(1e-3, Si.parse("1m"))
        assertClose(1e-3, Si.parse("1M")) // SPICE: M is milli
        assertClose(2.2e-6, Si.parse("2.2u"))
        assertClose(2.2e-6, Si.parse("2.2µ"))
        assertClose(1e-7, Si.parse("100nF"))
        assertClose(1e-3, Si.parse("1e-3"))
        assertClose(5.0, Si.parse("5V"))
        assertClose(-0.5, Si.parse("-.5"))
        assertNull(Si.parse("abc"))
        assertNull(Si.parse(""))
    }

    @Test
    fun formatsWithSiPrefixes() {
        assertEquals("4.7 kΩ", Si.format(4700.0, "Ω"))
        assertEquals("1.23 mV", Si.format(0.0012345, "V"))
        assertEquals("1 MHz", Si.format(1e6, "Hz"))
        assertEquals("1k", Si.format(999.96))
        assertEquals("-15 µA", Si.format(-15e-6, "A"))
        assertEquals("0 V", Si.format(0.0, "V"))
    }

    @Test
    fun formatsForNetlists() {
        assertEquals("4.7k", Si.formatSpice(4700.0))
        assertEquals("10Meg", Si.formatSpice(1e7))
        assertEquals("2.2u", Si.formatSpice(2.2e-6))
        assertEquals("1", Si.formatSpice(1.0))
    }
}
