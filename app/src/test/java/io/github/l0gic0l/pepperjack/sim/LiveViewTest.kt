package io.github.l0gic0l.pepperjack.sim

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
import io.github.l0gic0l.pepperjack.spice.Netlister
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveViewTest {
    // V1 (+ at 0,0) -> R1 (0,-2 to 4,-2) -> R2 (8,-2 to 8,2) -> back along the bottom to ground at (0,4).
    private val schematic = Schematic(
        parts = listOf(
            Part(1, PartKind.VOLTAGE, "V1", 0, 0, value = "DC 3"),
            Part(2, PartKind.RESISTOR, "R1", 0, -2, rotation = 3, value = "1k"),
            Part(3, PartKind.RESISTOR, "R2", 8, -2, value = "2k"),
            Part(4, PartKind.GROUND, "GND", 0, 4),
        ),
        wires = listOf(
            Wire(10, 0, 0, 0, -2),
            Wire(11, 4, -2, 8, -2),
            Wire(12, 8, 2, 8, 4),
            Wire(13, 8, 4, 0, 4),
        ),
    )
    private val circuit = Circuit("t", "loop", CircuitKind.SCHEMATIC, schematic, analysis = AnalysisConfig(type = AnalysisType.OP))

    @Test
    fun wireCurrentsFollowKirchhoff() {
        val netlist = Netlister.netlist(circuit, live = true)
        assertEquals(AnalysisType.OP, netlist.liveAnalysis)
        // 1 mA leaves V1's + pin and returns into its - pin; the resistors see it in at pin 1, out at pin 2.
        val currents = mapOf(1L to listOf(-1e-3, 1e-3), 2L to listOf(1e-3, -1e-3), 3L to listOf(1e-3, -1e-3))
        val vectors = netlist.pinCurrents.flatMap { (id, names) ->
            names.mapIndexed { pin, name -> Vector(name.lowercase(), Quantity.CURRENT, doubleArrayOf(currents.getValue(id)[pin])) }
        } + netlist.pinNets.values.flatten().distinct().filter { it != "0" }.map { net ->
            Vector(net.lowercase(), Quantity.VOLTAGE, doubleArrayOf(if (net == netlist.pinNets.getValue(1L)[0]) 3.0 else 2.0))
        }
        val result = SimulationResult(listOf(Plot("op1", "Operating point", PlotKind.OP, null, vectors)), emptyList(), emptyList(), null)

        val view = checkNotNull(LiveView.build(schematic, netlist, result))
        assertEquals(1, view.samples)
        assertEquals(4, view.segments.size)
        for (segment in view.segments) {
            assertEquals("wire ${segment.wireId}", 1e-3, segment.current[0], 1e-12)
        }
        val top = view.segments.first { it.wireId == 10L }
        assertEquals(GridPoint(0, 0), top.a)
        assertEquals(3.0, top.voltage!![0], 0.0)
        assertEquals(0.0, view.segments.first { it.wireId == 13L }.voltage!![0], 0.0)
        assertEquals(1e-3, view.maxCurrent, 1e-12)
        assertEquals(3.0, view.maxVoltage, 0.0)
    }

    @Test
    fun probesBecomeScopeTraces() {
        val probed = circuit.copy(
            schematic = schematic.copy(probes = listOf(Probe(ProbeType.VOLTAGE, 4, -2), Probe(ProbeType.CURRENT, partId = 3))),
        )
        val netlist = Netlister.netlist(probed, live = true)
        val samples = 3
        val currentProbe = netlist.probeVectors.first { it.probe.type == ProbeType.CURRENT }.vector
        val vectors = (netlist.pinCurrents.values.flatten() + netlist.probeVectors.map { it.vector } + netlist.pinNets.values.flatten())
            .distinct().filter { it != "0" }
            .map { name -> Vector(name.lowercase(), Quantity.VOLTAGE, DoubleArray(samples) { it + if (name == currentProbe) 10.0 else 0.0 }) }
        val time = Vector("time", Quantity.TIME, doubleArrayOf(0.0, 1e-3, 2e-3))
        val result = SimulationResult(listOf(Plot("tran1", "Transient", PlotKind.TRAN, time, vectors)), emptyList(), emptyList(), null)

        val view = checkNotNull(LiveView.build(probed.schematic, netlist, result))
        assertEquals(listOf(ProbeType.VOLTAGE, ProbeType.CURRENT), view.traces.map { it.probe.type })
        assertEquals(listOf(Quantity.VOLTAGE, Quantity.CURRENT), view.traces.map { it.quantity })
        assertEquals("I(R2)", view.traces[1].label)
        assertEquals(12.0, view.traces[1].values[2], 0.0)
        assertEquals(2e-3, view.elapsed(2), 1e-15)
    }

    @Test
    fun playbackPositionInterpolates() {
        val view = LiveView(doubleArrayOf(0.0, 1.0, 3.0), emptyList(), emptyList(), 0.0, 0.0)
        assertEquals(0.0, view.position(-1.0), 0.0)
        assertEquals(0.5, view.position(0.5), 1e-12)
        assertEquals(1.5, view.position(2.0), 1e-12)
        assertEquals(2.0, view.position(9.0), 0.0)
        assertEquals(15.0, LiveView.sample(doubleArrayOf(10.0, 20.0), 0.5), 1e-12)
    }

    /** A probe part reads its net's voltage and the current along the wire under its tip. */
    @Test
    fun probePartsReadVoltageAndWireCurrent() {
        val probed = circuit.copy(
            schematic = schematic.copy(
                parts = schematic.parts + listOf(
                    Part(20, PartKind.PROBE, "PR1", 6, -2, value = "V I"),
                    Part(21, PartKind.PROBE, "PR2", 8, 3, value = "V I"),
                    Part(22, PartKind.PROBE, "PR3", 4, 4, value = "V I"),
                ),
            ),
        )
        val netlist = Netlister.netlist(probed, live = true)
        assertEquals(emptyList<String>(), netlist.warnings)
        // PR2 and PR3 sit on the ground net, which has no voltage vector to plot.
        assertEquals(1, netlist.probeVectors.count { it.probe.type == ProbeType.VOLTAGE })
        val currents = mapOf(1L to listOf(-1e-3, 1e-3), 2L to listOf(1e-3, -1e-3), 3L to listOf(1e-3, -1e-3))
        val vectors = netlist.pinCurrents.flatMap { (id, names) ->
            names.mapIndexed { pin, name -> Vector(name.lowercase(), Quantity.CURRENT, doubleArrayOf(currents.getValue(id)[pin])) }
        } + netlist.pinNets.values.flatten().distinct().filter { it != "0" }.map { net ->
            Vector(net.lowercase(), Quantity.VOLTAGE, doubleArrayOf(if (net == netlist.pinNets.getValue(1L)[0]) 3.0 else 2.0))
        }
        val result = SimulationResult(listOf(Plot("op1", "Operating point", PlotKind.OP, null, vectors)), emptyList(), emptyList(), null)
        val view = checkNotNull(LiveView.build(probed.schematic, netlist, result))
        val byId = view.probes.associateBy { it.partId }
        // Between R1 and R2 the current runs rightward; down through the wire under R2; leftward back along the bottom.
        assertEquals(2.0, byId.getValue(20).voltage!![0], 0.0)
        assertEquals(1e-3, byId.getValue(20).current!![0], 1e-12)
        assertEquals("→", byId.getValue(20).direction)
        assertEquals(1e-3, byId.getValue(21).current!![0], 1e-12)
        assertEquals("↓", byId.getValue(21).direction)
        assertEquals(0.0, byId.getValue(22).voltage!![0], 0.0)
        assertEquals(-1e-3, byId.getValue(22).current!![0], 1e-12)
    }

    @Test
    fun probesNeedSomethingToTouch() {
        val stray = circuit.copy(schematic = schematic.copy(parts = schematic.parts + Part(30, PartKind.PROBE, "PR9", 20, 20)))
        assertEquals(listOf("PR9 is not on a wire or a pin."), Netlister.netlist(stray).warnings)
        // A probe is not a connection: a pin that only a probe touches is still open.
        val open = circuit.copy(
            schematic = schematic.copy(
                parts = schematic.parts + Part(31, PartKind.RESISTOR, "R9", 30, 0, value = "1k") + Part(32, PartKind.PROBE, "PR1", 30, 0),
            ),
        )
        val warnings = Netlister.netlist(open).warnings
        assert(warnings.any { it.startsWith("R9 pin 1") }) { warnings.toString() }
    }

    @Test
    fun oldVoltageProbesBecomeProbeParts() {
        val old = schematic.copy(probes = listOf(Probe(ProbeType.VOLTAGE, 4, -2), Probe(ProbeType.CURRENT, partId = 3)))
        val migrated = old.withProbeParts()
        val probe = migrated.parts.single { it.kind == PartKind.PROBE }
        assertEquals("PR1" to GridPoint(4, -2), probe.ref to probe.origin)
        assertEquals(listOf(Probe(ProbeType.CURRENT, partId = 3)), migrated.probes)
        assertEquals(old.allProbes.toSet(), migrated.allProbes.toSet())
    }
}
