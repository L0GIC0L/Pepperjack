package io.github.l0gic0l.pepperjack.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceSpecTest {
    @Test
    fun roundTripsCommonSourceForms() {
        listOf(
            "DC 5",
            "DC 0 AC 1",
            "SIN(0 1 1k)",
            "SIN(0 100m 1k) AC 1",
            "PULSE(0 5 0 1u 1u 5m 10m)",
        ).forEach { value -> assertEquals(value, SourceSpec.parse(value).toSpice()) }
    }

    @Test
    fun recognisesTypes() {
        assertEquals(SourceType.DC, SourceSpec.parse("12").type)
        assertEquals("DC 12", SourceSpec.parse("12").toSpice())
        assertEquals(SourceType.AC, SourceSpec.parse("AC 1").type)
        assertEquals(SourceType.SINE, SourceSpec.parse("sin(0 1 50)").type)
        assertEquals(SourceType.CUSTOM, SourceSpec.parse("PWL(0 0 1m 5)").type)
        assertEquals("PWL(0 0 1m 5)", SourceSpec.parse("PWL(0 0 1m 5)").toSpice())
    }
}
