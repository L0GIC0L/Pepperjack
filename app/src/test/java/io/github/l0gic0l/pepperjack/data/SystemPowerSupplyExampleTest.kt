package io.github.l0gic0l.pepperjack.data

import io.github.l0gic0l.pepperjack.spice.Netlister
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The E92-331 example keeps the manual's parts list (p. 18) and its test-point nets. */
class SystemPowerSupplyExampleTest {
    private val circuit = systemPowerSupply()
    private val values = circuit.schematic.parts.associate { it.ref to it.value }

    @Test
    fun partsMatchTheManualsList() {
        val expected = mapOf(
            "C1" to "28000u", "C2" to "100n", "C4" to "220n", "C5" to "2u", "C7" to "10n",
            "R1" to "1.5k", "R2" to "301", "R3" to "100", "R4" to "1.18k", "R5" to "3.9k", "R6" to "24",
            "R7" to "2.2k", "R8" to "2.4k", "R9" to "0.499", "R10" to "0.499", "R11" to "0.499", "R12" to "0.499",
            "R13" to "0.1", "R14" to "0.1", "R16" to "1k", "R18" to "15", "R19" to "649", "R20" to "1k",
            "R21" to "2.55k", "R23" to "1.58k", "R24" to "15.5k", "R28" to "1.87k", "R30" to "698", "R31" to "390",
            "R32" to "1.3k", "R33" to "47", "R34" to "50", "R35" to "453", "R37" to "301", "R38" to "470",
            "R40" to "14.9k", "R41" to "100", "R42" to "50", "R43" to "1k",
            "CR1" to "1N1187RA", "CR2" to "1N1187RA", "CR3" to "1N1187A", "CR4" to "1N1187A", "CR5" to "1N747A",
            "CR6" to "1N4143", "CR7" to "1N959B", "CR8" to "1N825", "CR9" to "1N4143", "CR10" to "1N943",
            "CR11" to "2N681", "CR12" to "1N1187A", "CR13" to "1N3193", "CR14" to "1N751A",
            "Q1" to "2N4036", "Q2" to "2N2270", "Q3" to "2N2270", "Q4" to "2N3771", "Q5" to "2N3771",
            "Q6" to "2N3771", "Q7" to "2N3771", "Q8" to "2N3771", "Q9" to "2N2270", "Q10" to "2N2270",
            "Q11" to "2N2270", "Q12" to "2N2646", "Q13" to "2N2270",
        )
        expected.forEach { (ref, value) -> assertEquals(ref, value, values[ref]) }
        // C3 carries its ESR; the trimmers keep their full-scale values whatever the wiper says.
        assertEquals("1000u", values["C3"]?.substringBefore(' '))
        val trims = mapOf("R15" to "100", "R17" to "200", "R22" to "500", "R25" to "5k", "R29" to "1k", "R36" to "200")
        trims.forEach { (ref, value) -> assertEquals(ref, value, values[ref]?.substringBefore(' ')) }
    }

    @Test
    fun netlistsWithTheTestPointsAndLoadsOff() {
        val netlist = Netlister.netlist(circuit)
        assertTrue(netlist.warnings.toString(), netlist.warnings.isEmpty())
        val nodes = netlist.text.lines().flatMap { it.split(' ').drop(1) }.toSet()
        for (point in listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "out")) {
            assertTrue("test point $point", point in nodes)
        }
        // It opens at no load, so the first run reads the manual's no-load column.
        listOf("LOAD1", "LOAD2", "SHORT").forEach { assertEquals(it, "open", values[it]) }
        // The electronic load starts with its input off.
        assertEquals("CC 16 on=30m sweep off", values["EL1"])
        assertTrue(netlist.text, netlist.text.lines().any { it.startsWith("BEL1 ") && it.endsWith(" I=0") })
    }
}
