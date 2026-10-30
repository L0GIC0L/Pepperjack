package io.github.l0gic0l.pepperjack.spice

import io.github.l0gic0l.pepperjack.model.AnalysisConfig
import io.github.l0gic0l.pepperjack.model.AnalysisType
import io.github.l0gic0l.pepperjack.model.Circuit
import io.github.l0gic0l.pepperjack.model.CircuitKind
import io.github.l0gic0l.pepperjack.model.GridPoint
import io.github.l0gic0l.pepperjack.model.Part
import io.github.l0gic0l.pepperjack.model.PartKind
import io.github.l0gic0l.pepperjack.model.Probe
import io.github.l0gic0l.pepperjack.model.ProbeType
import io.github.l0gic0l.pepperjack.model.Schematic
import io.github.l0gic0l.pepperjack.model.Wire
import io.github.l0gic0l.pepperjack.model.pinPositions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetlisterTest {
    private var id = 1L
    private fun part(kind: PartKind, ref: String, x: Int, y: Int, rotation: Int = 0, value: String = "") =
        Part(id++, kind, ref, x, y, rotation, value = value)

    private fun wire(x1: Int, y1: Int, x2: Int, y2: Int) = Wire(id++, x1, y1, x2, y2)

    private fun circuit(
        parts: List<Part>,
        wires: List<Wire> = emptyList(),
        directives: String = "",
        probes: List<Probe> = emptyList(),
        analysis: AnalysisConfig = AnalysisConfig(AnalysisType.OP),
    ) = Circuit("t", "Test", CircuitKind.SCHEMATIC, Schematic(parts, wires, directives, probes), analysis = analysis)

    private fun lines(result: NetlistResult) = result.text.lines()

    @Test
    fun rotationKeepsPinsOnTheGrid() {
        val r = part(PartKind.RESISTOR, "R1", 2, 3, rotation = 3)
        assertEquals(listOf(GridPoint(2, 3), GridPoint(6, 3)), r.pinPositions())
        val r90 = part(PartKind.RESISTOR, "R2", 2, 3, rotation = 1)
        assertEquals(listOf(GridPoint(2, 3), GridPoint(-2, 3)), r90.pinPositions())
    }

    @Test
    fun dividerWithLabelsAndGround() {
        val result = Netlister.netlist(
            circuit(
                listOf(
                    part(PartKind.VOLTAGE, "V1", 0, 0, value = "DC 10"),
                    part(PartKind.GROUND, "GND", 0, 4),
                    part(PartKind.RESISTOR, "R1", 0, -4, value = "1k"),
                    part(PartKind.RESISTOR, "R2", 4, -4, value = "1k"),
                    part(PartKind.LABEL, "out", 4, -4, value = "out"),
                    part(PartKind.GROUND, "GND", 4, 0),
                ),
                listOf(wire(0, -4, 4, -4)),
            ),
        )
        val text = lines(result)
        assertTrue(result.warnings.toString(), result.warnings.isEmpty())
        assertTrue(text.toString(), "V1 N001 0 DC 10" in text)
        assertTrue("R1 out N001 1k" in text)
        assertTrue("R2 out 0 1k" in text)
        assertTrue(".op" in text)
        assertEquals(".end", text.last { it.isNotBlank() })
    }

    @Test
    fun pinOnWireInteriorConnectsButCrossingWiresDoNot() {
        val schematic = Schematic(
            parts = listOf(part(PartKind.RESISTOR, "R1", 2, 0)),
            wires = listOf(wire(0, 0, 4, 0), wire(2, -2, 2, 2), wire(10, -2, 10, 2), wire(8, 0, 12, 0)),
        )
        val connectivity = Connectivity(schematic)
        assertTrue(connectivity.connected(GridPoint(0, 0), GridPoint(2, 0)))
        assertTrue(connectivity.connected(GridPoint(2, -2), GridPoint(4, 0)))
        assertFalse(connectivity.connected(GridPoint(2, 2), GridPoint(2, 4))) // across R1's body
        assertFalse(connectivity.connected(GridPoint(10, -2), GridPoint(8, 0)))
    }

    @Test
    fun warnsAboutFloatingPinsAndMissingGround() {
        val result = Netlister.netlist(circuit(listOf(part(PartKind.RESISTOR, "R1", 0, 0, value = "1k"))))
        assertTrue(result.warnings.any { "not connected" in it })
        assertTrue(result.warnings.any { "no ground" in it })
    }

    @Test
    fun prefixesReferencesThatLackTheElementLetter() {
        assertEquals("RLoad", Netlister.spiceName(part(PartKind.RESISTOR, "Load", 0, 0)))
        assertEquals("R5", Netlister.spiceName(part(PartKind.RESISTOR, "R5", 0, 0)))
        assertEquals("XU1", Netlister.spiceName(part(PartKind.OPAMP, "U1", 0, 0)))
    }

    @Test
    fun emitsModelsSubcircuitsAndMosfetNodeCounts() {
        val result = Netlister.netlist(
            circuit(
                listOf(
                    part(PartKind.NMOS, "M1", 0, 0, value = "2N7000"),
                    part(PartKind.NMOS, "M2", 10, 0, value = "PJ_NMOS"),
                    part(PartKind.OPAMP, "U1", 20, 0),
                ),
            ),
        )
        val text = lines(result)
        assertTrue(text.toString(), text.any { it.startsWith("M1 ") && it.split(" ").size == 5 })
        assertTrue(text.any { it.startsWith("M2 ") && it.split(" ").size == 6 })
        assertTrue(text.any { it.startsWith(".model 2N7000 VDMOS") })
        assertTrue(text.any { it.startsWith(".model PJ_NMOS NMOS") })
        assertTrue(".subckt PJ_OPAMP_IDEAL inp inn out" in text)
    }

    @Test
    fun userModelsOverrideBuiltInsAndDirectiveAnalysisWins() {
        val result = Netlister.netlist(
            circuit(
                listOf(part(PartKind.DIODE, "D1", 0, 0, value = "1N4148")),
                directives = ".model 1N4148 D(IS=1n)\n.tran 1u 1m",
                analysis = AnalysisConfig(AnalysisType.AC),
            ),
        )
        val text = lines(result)
        assertEquals(1, text.count { it.startsWith(".model 1N4148") })
        assertFalse(text.any { it.startsWith(".ac") })
        assertTrue(".tran 1u 1m" in text)
    }

    @Test
    fun currentProbesSaveDeviceCurrents() {
        val r1 = part(PartKind.RESISTOR, "R1", 0, 0, value = "1k")
        val v1 = part(PartKind.VOLTAGE, "V1", 4, 0, value = "1")
        val result = Netlister.netlist(
            circuit(
                listOf(r1, v1),
                probes = listOf(
                    Probe(ProbeType.CURRENT, partId = r1.id),
                    Probe(ProbeType.CURRENT, partId = v1.id),
                    Probe(ProbeType.VOLTAGE, 0, 0),
                ),
            ),
        )
        val text = lines(result)
        assertTrue(".save all" in text)
        assertTrue(".save @r1[i]" in text)
        assertEquals(listOf("@r1[i]", "v1#branch", "n001"), result.probeVectors.map { it.vector })
        assertEquals("I(R1)", result.probeVectors[0].label)
    }

    @Test
    fun transientLineIncludesOptionalFields() {
        assertEquals(".tran 10u 10m", AnalysisConfig(AnalysisType.TRAN).toSpice())
        assertEquals(
            ".tran 1u 5m 0 100n uic",
            AnalysisConfig(AnalysisType.TRAN, tranStep = "1u", tranStop = "5m", tranMaxStep = "100n", tranUic = true).toSpice(),
        )
        assertEquals(".dc V1 0 5 0.1", AnalysisConfig(AnalysisType.DC).toSpice())
    }
}
