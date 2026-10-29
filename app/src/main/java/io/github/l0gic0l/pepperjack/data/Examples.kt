package io.github.l0gic0l.pepperjack.data

import io.github.l0gic0l.pepperjack.model.AnalysisConfig
import io.github.l0gic0l.pepperjack.model.AnalysisType
import io.github.l0gic0l.pepperjack.model.Circuit
import io.github.l0gic0l.pepperjack.model.CircuitKind
import io.github.l0gic0l.pepperjack.model.Part
import io.github.l0gic0l.pepperjack.model.PartKind
import io.github.l0gic0l.pepperjack.model.PartLibrary
import io.github.l0gic0l.pepperjack.model.Probe
import io.github.l0gic0l.pepperjack.model.ProbeType
import io.github.l0gic0l.pepperjack.model.Schematic
import io.github.l0gic0l.pepperjack.model.SpiceDialect
import io.github.l0gic0l.pepperjack.model.Wire

data class Example(val title: String, val summary: String, val build: () -> Circuit)

/**
 * Starter circuits. Rotation 3 lays a two-terminal part left-to-right with its
 * first pin at the origin; rotation 0 runs it top-to-bottom.
 */
object Examples {
    val all: List<Example> = listOf(
        Example("RC low-pass filter", "AC sweep of a 1.6 kHz first-order filter") { rcFilter(ac = true) },
        Example("RC step response", "Transient charge and discharge, τ = 1 ms") { rcFilter(ac = false) },
        Example("Half-wave rectifier", "1N4007 with a 100 µF reservoir capacitor") { rectifier() },
        Example("Common-emitter amplifier", "2N3904 with divider bias, gain ≈ −3") { commonEmitter() },
        Example("Inverting op-amp", "Ideal op-amp with a gain of −10") { invertingOpAmp() },
        Example("Op-amp clipping", "Non-inverting gain of 2 hitting ±12 V rails") { opAmpClipping() },
        Example("Series RLC resonance", "Peaks at 5.03 kHz") { seriesRlc() },
        Example("MOSFET LED driver", "DC sweep of a 2N7000 gate") { mosfetLed() },
        Example("Diode I-V curve (netlist)", "Hand-written SPICE netlist with a DC sweep") { diodeNetlist() },
        Example("Buck converter (LTspice syntax)", "Rser, .param and switch models in LTspice dialect") { buckNetlist() },
        Example("Stepped RC filter (LTspice .step)", "Three capacitor values overlaid in one Bode plot") { steppedNetlist() },
        Example("XSPICE limiter (Multisim style)", "Code-model A-device as used by Multisim") { xspiceNetlist() },
    )

    private class Sheet {
        private var nextId = 1L
        val parts = mutableListOf<Part>()
        val wires = mutableListOf<Wire>()
        val probes = mutableListOf<Probe>()

        fun part(kind: PartKind, ref: String, x: Int, y: Int, rotation: Int = 0, value: String? = null): Part {
            val part = Part(nextId++, kind, ref, x, y, rotation, value = value ?: PartLibrary[kind].defaultValue)
            parts += part
            return part
        }

        fun ground(x: Int, y: Int) = part(PartKind.GROUND, "GND", x, y)
        fun label(x: Int, y: Int, name: String) = part(PartKind.LABEL, name, x, y, value = name)
        fun wire(x1: Int, y1: Int, x2: Int, y2: Int) {
            wires += Wire(nextId++, x1, y1, x2, y2)
        }

        fun probe(x: Int, y: Int) {
            probes += Probe(ProbeType.VOLTAGE, x, y)
        }

        fun probeCurrent(part: Part) {
            probes += Probe(ProbeType.CURRENT, partId = part.id)
        }

        fun circuit(name: String, analysis: AnalysisConfig, directives: String = "") = Circuit(
            id = "",
            name = name,
            kind = CircuitKind.SCHEMATIC,
            schematic = Schematic(parts.toList(), wires.toList(), directives, probes.toList()),
            analysis = analysis,
        )
    }

    private fun rcFilter(ac: Boolean): Circuit = Sheet().run {
        part(PartKind.VOLTAGE, "V1", 0, 0, value = if (ac) "DC 0 AC 1" else "PULSE(0 5 0 1u 1u 5m 10m)")
        wire(0, 0, 0, -2)
        part(PartKind.RESISTOR, "R1", 0, -2, rotation = 3, value = "1k")
        wire(4, -2, 8, -2)
        part(PartKind.CAPACITOR, "C1", 8, -2, value = if (ac) "100n" else "1u")
        wire(8, 2, 8, 4)
        wire(8, 4, 0, 4)
        ground(0, 4)
        label(0, -2, "in")
        label(8, -2, "out")
        if (!ac) probe(0, -2)
        probe(8, -2)
        if (ac) {
            circuit("RC low-pass filter", AnalysisConfig(AnalysisType.AC, acPoints = 100, acStart = "10", acStop = "1Meg"))
        } else {
            circuit("RC step response", AnalysisConfig(AnalysisType.TRAN, tranStop = "20m", tranStep = "10u"))
        }
    }

    private fun rectifier(): Circuit = Sheet().run {
        part(PartKind.VOLTAGE, "V1", 0, 0, value = "SIN(0 10 50)")
        wire(0, 0, 0, -2)
        part(PartKind.DIODE, "D1", 0, -2, rotation = 3, value = "1N4007")
        wire(4, -2, 12, -2)
        part(PartKind.CAPACITOR, "C1", 8, -2, value = "100u")
        part(PartKind.RESISTOR, "R1", 12, -2, value = "1k")
        wire(12, 2, 8, 2)
        wire(8, 2, 8, 4)
        wire(8, 4, 0, 4)
        ground(0, 4)
        label(0, -2, "in")
        label(12, -2, "out")
        probe(0, -2)
        probe(12, -2)
        circuit("Half-wave rectifier", AnalysisConfig(AnalysisType.TRAN, tranStop = "100m", tranStep = "50u"))
    }

    private fun commonEmitter(): Circuit = Sheet().run {
        // Supply
        part(PartKind.VOLTAGE, "V2", 26, -10, value = "DC 12")
        ground(26, -6)
        label(26, -10, "vcc")
        // Bias divider and base
        part(PartKind.RESISTOR, "R1", 6, -10, value = "47k")
        label(6, -10, "vcc")
        wire(6, -6, 6, -2)
        part(PartKind.RESISTOR, "R2", 6, -2, value = "10k")
        ground(6, 2)
        wire(6, -2, 9, -2)
        // Transistor with collector and emitter resistors
        part(PartKind.NPN, "Q1", 11, -2, value = "2N3904")
        part(PartKind.RESISTOR, "R3", 12, -10, value = "4.7k")
        label(12, -10, "vcc")
        wire(12, -6, 12, -4)
        part(PartKind.RESISTOR, "R4", 12, 0, value = "1k")
        ground(12, 4)
        // Input coupling
        part(PartKind.VOLTAGE, "V1", 0, 0, value = "SIN(0 100m 1k) AC 1")
        ground(0, 4)
        wire(0, 0, 0, -2)
        part(PartKind.CAPACITOR, "C1", 0, -2, rotation = 3, value = "10u")
        wire(4, -2, 6, -2)
        label(0, -2, "in")
        // Output coupling and load
        part(PartKind.CAPACITOR, "C2", 12, -6, rotation = 3, value = "10u")
        wire(16, -6, 20, -6)
        part(PartKind.RESISTOR, "R5", 20, -6, value = "10k")
        ground(20, -2)
        label(20, -6, "out")
        probe(0, -2)
        probe(20, -6)
        circuit("Common-emitter amplifier", AnalysisConfig(AnalysisType.TRAN, tranStop = "5m", tranStep = "5u"))
    }

    private fun invertingOpAmp(): Circuit = Sheet().run {
        part(PartKind.OPAMP, "U1", 10, 0)
        part(PartKind.VOLTAGE, "V1", 0, 1, value = "SIN(0 0.5 1k) AC 1")
        ground(0, 5)
        wire(0, 1, 0, -1)
        part(PartKind.RESISTOR, "R1", 0, -1, rotation = 3, value = "1k")
        wire(4, -1, 7, -1)
        wire(7, -1, 7, -4)
        part(PartKind.RESISTOR, "R2", 7, -4, rotation = 3, value = "10k")
        wire(11, -4, 15, -4)
        wire(15, -4, 15, 0)
        wire(13, 0, 17, 0)
        wire(7, 1, 7, 3)
        ground(7, 3)
        label(0, -1, "in")
        label(17, 0, "out")
        probe(0, -1)
        probe(17, 0)
        circuit("Inverting op-amp", AnalysisConfig(AnalysisType.TRAN, tranStop = "3m", tranStep = "5u"))
    }

    private fun opAmpClipping(): Circuit = Sheet().run {
        part(PartKind.OPAMP5, "U1", 10, 0)
        label(10, -3, "vp")
        label(10, 3, "vn")
        part(PartKind.VOLTAGE, "V2", 22, -8, value = "DC 12")
        label(22, -8, "vp")
        ground(22, -4)
        part(PartKind.VOLTAGE, "V3", 26, -8, value = "DC -12")
        label(26, -8, "vn")
        ground(26, -4)
        // Input on the non-inverting pin
        wire(7, 1, 7, 5)
        part(PartKind.VOLTAGE, "V1", 7, 5, value = "SIN(0 8 1k)")
        ground(7, 9)
        label(7, 5, "in")
        // Feedback divider: gain = 1 + R2/R1 = 2
        wire(7, -1, 4, -1)
        part(PartKind.RESISTOR, "R1", 4, -1, value = "10k")
        ground(4, 3)
        wire(4, -1, 4, -5)
        part(PartKind.RESISTOR, "R2", 4, -5, rotation = 3, value = "10k")
        wire(8, -5, 15, -5)
        wire(15, -5, 15, 0)
        wire(13, 0, 17, 0)
        label(17, 0, "out")
        probe(7, 5)
        probe(17, 0)
        circuit("Op-amp clipping", AnalysisConfig(AnalysisType.TRAN, tranStop = "3m", tranStep = "5u"))
    }

    private fun seriesRlc(): Circuit = Sheet().run {
        val source = part(PartKind.VOLTAGE, "V1", 0, 0, value = "DC 0 AC 1")
        wire(0, 0, 0, -2)
        part(PartKind.RESISTOR, "R1", 0, -2, rotation = 3, value = "10")
        part(PartKind.INDUCTOR, "L1", 4, -2, rotation = 3, value = "10m")
        wire(8, -2, 10, -2)
        part(PartKind.CAPACITOR, "C1", 10, -2, value = "100n")
        wire(10, 2, 10, 4)
        wire(10, 4, 0, 4)
        ground(0, 4)
        label(10, -2, "out")
        probe(10, -2)
        probeCurrent(source)
        circuit(
            "Series RLC resonance",
            AnalysisConfig(AnalysisType.AC, acPoints = 200, acStart = "100", acStop = "100k"),
        )
    }

    private fun mosfetLed(): Circuit = Sheet().run {
        part(PartKind.NMOS, "M1", 10, 0, value = "2N7000")
        ground(11, 2)
        val led = part(PartKind.LED, "D1", 11, -6, value = "LED_RED")
        part(PartKind.RESISTOR, "R1", 11, -10, value = "150")
        label(11, -10, "vdd")
        part(PartKind.VOLTAGE, "V2", 18, -10, value = "DC 5")
        label(18, -10, "vdd")
        ground(18, -6)
        part(PartKind.VOLTAGE, "V1", 4, 0, value = "DC 0")
        ground(4, 4)
        wire(4, 0, 8, 0)
        label(4, 0, "gate")
        probeCurrent(led)
        circuit(
            "MOSFET LED driver",
            AnalysisConfig(AnalysisType.DC, dcSource = "V1", dcStart = "0", dcStop = "5", dcStep = "10m"),
        )
    }

    private fun netlistCircuit(name: String, text: String, dialect: SpiceDialect? = null) = Circuit(
        id = "",
        name = name,
        kind = CircuitKind.NETLIST,
        netlist = text.trimIndent() + "\n",
        dialect = dialect,
    )

    private fun diodeNetlist() = netlistCircuit(
        "Diode I-V curve",
        """
        * Diode I-V curve
        * Sweep a 1N4148 through a 10 ohm sense resistor and plot the current.
        V1 a 0 DC 0
        R1 a k 10
        D1 k 0 D1N4148
        .model D1N4148 D(IS=4.352n N=1.906 RS=0.6458 BV=110 IBV=100u CJO=0.7048p VJ=0.869 M=0.03 TT=3.48n)
        .dc V1 0 1.2 1m
        .end
        """,
    )

    private fun buckNetlist() = netlistCircuit(
        "Buck converter (LTspice syntax)",
        """
        * Open-loop buck converter written in LTspice syntax
        .param fsw=100k duty=0.42
        V1 in 0 12
        V2 g 0 PULSE(0 5 0 10n 10n {duty/fsw} {1/fsw})
        S1 in sw g 0 SW1
        D1 0 sw MBR340
        L1 sw out 22u Rser=20m
        C1 out 0 47u Rser=10m Lser=1n
        R1 out 0 5
        .model SW1 SW(Ron=50m Roff=1Meg Vt=2.5 Vh=0.1)
        .model MBR340 D(IS=1u RS=20m N=1.1 CJO=200p BV=40 IBV=1m)
        .tran 50n 2m
        .end
        """,
        SpiceDialect.LTSPICE,
    )

    private fun steppedNetlist() = netlistCircuit(
        "Stepped RC filter",
        """
        * Stepping a component value, LTspice style
        .param C=100n
        V1 in 0 AC 1
        R1 in out 1k
        C1 out 0 {C}
        .step param C list 10n 100n 1u
        .ac dec 50 10 1Meg
        .end
        """,
        SpiceDialect.LTSPICE,
    )

    private fun xspiceNetlist() = netlistCircuit(
        "XSPICE limiter (Multisim style)",
        """
        * XSPICE code-model limiter, the building block Multisim netlists use
        V1 in 0 SIN(0 1 1k)
        A1 in out lim
        .model lim limit(in_offset=0 gain=5 out_lower_limit=-2 out_upper_limit=2 limit_range=0.1)
        R1 out 0 1k
        .tran 5u 3m
        .end
        """,
        SpiceDialect.NGSPICE,
    )
}
