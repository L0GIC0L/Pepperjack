package io.github.l0gic0l.pepperjack.data

import io.github.l0gic0l.pepperjack.model.AnalysisConfig
import io.github.l0gic0l.pepperjack.model.AnalysisType
import io.github.l0gic0l.pepperjack.model.Circuit
import io.github.l0gic0l.pepperjack.model.CircuitKind
import io.github.l0gic0l.pepperjack.model.GridPoint
import io.github.l0gic0l.pepperjack.model.PartKind
import io.github.l0gic0l.pepperjack.model.SpiceDialect

enum class ExampleCategory(val title: String) {
    BASICS("Basics"),
    FILTERS("Filters and AC"),
    DIODES("Diodes and rectifiers"),
    TRANSISTORS("Transistors"),
    OPAMPS("Op-amps"),
    OSCILLATORS("Oscillators and timers"),
    POWER("Power supplies"),
    DIGITAL("Digital logic"),
    INSTRUMENTATION("Instrumentation"),
    NETLISTS("SPICE netlists"),
}

/** A starter circuit; [live] marks ones made for live mode (switches, knobs, meters). */
data class Example(
    val title: String,
    val summary: String,
    val category: ExampleCategory,
    val live: Boolean = false,
    val build: () -> Circuit,
)

/**
 * Starter circuits. Rotation 3 lays a two-terminal part left-to-right with its
 * first pin at the origin; rotation 0 runs it top-to-bottom.
 */
object Examples {
    private val B = ExampleCategory.BASICS
    private val F = ExampleCategory.FILTERS
    private val D = ExampleCategory.DIODES
    private val T = ExampleCategory.TRANSISTORS
    private val O = ExampleCategory.OPAMPS
    private val X = ExampleCategory.OSCILLATORS
    private val P = ExampleCategory.POWER
    private val G = ExampleCategory.DIGITAL
    private val I = ExampleCategory.INSTRUMENTATION
    private val N = ExampleCategory.NETLISTS

    val all: List<Example> = listOf(
        // Basics
        Example("Light switch (try live mode)", "Tap the switch while live: meters, LED and current flow update", B, live = true) { lightSwitch() },
        Example("Voltage divider", "9 V across 10 k and 4.7 k: 2.88 V out; turn R2's knob while live", B, live = true) { voltageDivider() },
        Example("Kirchhoff's current law", "Three branch currents add up to the 20 mA the source supplies", B, live = true) { kirchhoff() },
        Example("Wheatstone bridge", "Turn RV1 to 50 % while live to null the meter", B, live = true) { wheatstoneBridge() },
        Example("RC step response", "Transient charge and discharge, τ = 1 ms", B) { rcFilter(ac = false) },
        Example("RL step response", "Current builds up in 10 mH through 100 Ω, τ = 100 µs", B) { rlStep() },
        // Filters and AC
        Example("RC low-pass filter", "AC sweep of a 1.6 kHz first-order filter", F) { rcFilter(ac = true) },
        Example("RC high-pass filter", "AC sweep of a 1.6 kHz first-order high-pass", F) { rcHighPass() },
        Example("Series RLC resonance", "Peaks at 5.03 kHz", F) { seriesRlc() },
        Example("Sallen-Key low-pass", "Second-order, unity gain, about 1.1 kHz, Q ≈ 0.74 (TL072)", F) { sallenKey() },
        Example("Twin-T notch filter", "Passive notch, over 60 dB deep at 1 kHz", F) { twinTNotch() },
        // Diodes and rectifiers
        Example("Half-wave rectifier", "1N4007 with a 100 µF reservoir capacitor", D) { rectifier() },
        Example("Bridge rectifier", "120 V, 60 Hz through a 10:1 transformer; meters on DC and AC", D) { bridgeRectifier() },
        Example("Zener regulator", "1N4733A holds about 5 V; turn the source's knob while live", D, live = true) { zenerRegulator() },
        Example("Diode clipper", "Back-to-back 1N4148s clip a 5 V sine at ±0.7 V", D) { diodeClipper() },
        Example("Diode clamp", "A 10 V peak-to-peak sine lifted to sit above 0 V", D) { diodeClamp() },
        Example("Voltage doubler", "Two diodes and two capacitors make about 18 V from a 10 V peak", D) { voltageDoubler() },
        // Transistors
        Example("Common-emitter amplifier", "2N3904 with divider bias, gain ≈ −3", T) { commonEmitter() },
        Example("Emitter follower", "Unity-gain buffer: the output follows the 2 V p-p input", T) { emitterFollower() },
        Example("Current mirror", "Q2 copies the 1.1 mA set by RREF; turn RL's knob while live", T, live = true) { currentMirror() },
        Example("Differential pair", "DC sweep: the tail current steers between Q1 and Q2", T) { differentialPair() },
        Example("Class-B push-pull output", "BD139/BD140 into 100 Ω, with crossover distortion", T) { pushPull() },
        Example("Relay coil with flyback diode", "TIP120 switching 100 mH; D1 catches the turn-off spike", T) { flybackDiode() },
        Example("MOSFET LED driver", "DC sweep of a 2N7000 gate", T) { mosfetLed() },
        Example("Potentiometer dimmer (live)", "Turn the pot while live to dim an LED through a 2N2222", T, live = true) { potDimmer() },
        // Op-amps
        Example("Inverting op-amp", "Ideal op-amp with a gain of −10", O) { invertingOpAmp() },
        Example("Op-amp clipping", "Non-inverting gain of 2 hitting ±12 V rails", O) { opAmpClipping() },
        Example("Summing amplifier", "−(1 V + 0.5 V sine) from two inputs", O) { summingAmp() },
        Example("Op-amp integrator", "A ±1 V square wave in, a ±0.25 V triangle out", O) { integrator() },
        Example("Instrumentation amplifier", "Three op-amps, gain 21: 10 mV in, 2 V common mode rejected", O) { instrumentationAmp() },
        Example("Schmitt trigger", "Hysteresis of ±0.9 V ignores the noise on a slow sine", O) { schmittTrigger() },
        Example("Precision rectifier", "Rectifies 200 mV without a diode drop", O) { precisionRectifier() },
        // Oscillators and timers
        Example("555 LED blinker", "NE555 astable at about 1.5 Hz; watch it in live mode", X, live = true) { blinker555() },
        Example("555 one-shot", "A trigger pulse makes one 1.1 ms output pulse", X) { monostable555() },
        Example("Two-transistor astable", "Two LEDs take turns at about 1.7 Hz; watch it live", X, live = true) { astableMultivibrator() },
        Example("Op-amp relaxation oscillator", "Square wave at about 450 Hz from one op-amp and an RC", X) { relaxationOscillator() },
        Example("Wien-bridge oscillator", "1 kHz sine; diodes hold the amplitude steady", X) { wienBridge() },
        // Power supplies
        Example("7805 regulator", "12 V with 2 V of ripple in, a steady 5 V out", P) { regulator7805() },
        Example("LM317 adjustable supply", "Vout = 1.25 V × (1 + RV1 / R1); turn RV1 while live", P, live = true) { lm317Supply() },
        Example("Boost converter", "5 V to about 10 V at 100 kHz with a logic-level MOSFET", P) { boostConverter() },
        // Digital logic
        Example("Half adder (logic gates)", "XOR and AND gates light the sum and carry LEDs", G) { halfAdder() },
        Example("SR latch (NAND gates)", "Set at 0 and 4 ms, reset at 2 ms; the latch holds in between", G) { srLatch() },
        Example("Ripple counter (divide by 4)", "Two D flip-flops count clock pulses in binary", G) { rippleCounter() },
        // Instrumentation
        Example("4–20 mA current loop", "Set point to loop current; 1–5 V across the 250 Ω receiver", I, live = true) { currentLoop() },
        Example("Foxboro SPEC 200 2AI-P2V (RTD to 0–10 V)", "Chopper-stabilized, transformer-isolated RTD converter, channel A", I) { spec200() },
        // Netlists
        Example("Diode I-V curve (netlist)", "Hand-written SPICE netlist with a DC sweep", N) { diodeNetlist() },
        Example("Buck converter (LTspice syntax)", "Rser, .param and switch models in LTspice dialect", N) { buckNetlist() },
        Example("Stepped RC filter (LTspice .step)", "Three capacitor values overlaid in one Bode plot", N) { steppedNetlist() },
        Example("XSPICE limiter (Multisim style)", "Code-model A-device as used by Multisim", N) { xspiceNetlist() },
    )

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

    private fun lightSwitch(): Circuit = Sheet().run {
        part(PartKind.VOLTAGE, "V1", 0, 0, value = "DC 9")
        ground(0, 4)
        wire(0, 0, 0, -4)
        part(PartKind.SPST, "SW1", 0, -4, rotation = 3, value = "closed")
        wire(4, -4, 6, -4)
        part(PartKind.RESISTOR, "R1", 6, -4, rotation = 3, value = "470")
        wire(10, -4, 12, -4)
        part(PartKind.AMMETER, "AM1", 12, -4, rotation = 3)
        wire(16, -4, 18, -4)
        part(PartKind.LED, "D1", 18, -4, value = "LED_RED")
        wire(18, 0, 18, 4)
        wire(18, 4, 0, 4)
        wire(18, -4, 26, -4)
        part(PartKind.VOLTMETER, "VM1", 26, -4)
        wire(26, 0, 18, 0)
        circuit("Light switch", AnalysisConfig(AnalysisType.OP))
    }

    private fun potDimmer(): Circuit = Sheet().run {
        part(PartKind.VOLTAGE, "V1", 0, 0, value = "DC 9")
        ground(0, 4)
        label(0, 0, "vcc")
        part(PartKind.POT, "RV1", 4, -2, value = "10k 60%")
        label(4, -2, "vcc")
        ground(4, 2)
        wire(6, 0, 8, 0)
        part(PartKind.RESISTOR, "R1", 8, 0, rotation = 3, value = "10k")
        wire(12, 0, 13, 0)
        part(PartKind.NPN, "Q1", 15, 0, value = "2N2222")
        ground(16, 2)
        part(PartKind.RESISTOR, "R2", 16, -10, value = "330")
        label(16, -10, "vcc")
        part(PartKind.LED, "D1", 16, -6, value = "LED_GREEN")
        circuit("Potentiometer dimmer", AnalysisConfig(AnalysisType.OP))
    }

    private fun blinker555(): Circuit = Sheet().run {
        part(PartKind.VOLTAGE, "V1", -4, -2, value = "DC 9")
        label(-4, -2, "vcc")
        ground(-4, 2)
        // Pins: GND (9,4), TRIG (6,-2), OUT (14,0), RESET (11,-4), CTRL (11,4), THR (6,0), DIS (6,2), VCC (9,-4).
        part(PartKind.TIMER555, "U1", 10, 0, value = "NE555")
        wire(9, -4, 9, -6)
        wire(11, -4, 11, -6)
        wire(9, -6, 11, -6)
        label(9, -6, "vcc")
        // Timing: f = 1.44 / ((Ra + 2 Rb) C) = 1.5 Hz
        part(PartKind.RESISTOR, "R1", 2, -2, value = "1k")
        label(2, -2, "vcc")
        part(PartKind.RESISTOR, "R2", 2, 2, value = "47k")
        wire(2, 2, 6, 2)
        part(PartKind.CAPACITOR, "C1", 2, 6, value = "10u")
        ground(2, 10)
        label(2, 6, "timing")
        label(6, -2, "timing")
        label(6, 0, "timing")
        part(PartKind.CAPACITOR, "C2", 11, 4, value = "10n")
        ground(11, 8)
        wire(9, 4, 9, 6)
        ground(9, 6)
        // Output drives an LED
        wire(14, 0, 16, 0)
        part(PartKind.RESISTOR, "R3", 16, 0, rotation = 3, value = "470")
        wire(20, 0, 22, 0)
        part(PartKind.LED, "D1", 22, 0, value = "LED_RED")
        ground(22, 4)
        probe(2, 6)
        probe(14, 0)
        circuit("555 LED blinker", AnalysisConfig(AnalysisType.TRAN, tranStop = "3", tranStep = "1m"))
    }

    private fun regulator7805(): Circuit = Sheet().run {
        part(PartKind.VOLTAGE, "V1", 0, 0, value = "SIN(12 2 100)")
        ground(0, 4)
        wire(0, 0, 0, -2)
        wire(0, -2, 3, -2)
        label(0, -2, "in")
        part(PartKind.CAPACITOR, "C1", 2, -2, value = "330n")
        ground(2, 2)
        // Pins: IN (3,-2), GND (6,0), OUT (9,-2)
        part(PartKind.REGULATOR, "U1", 6, -2, value = "LM7805")
        ground(6, 0)
        wire(9, -2, 14, -2)
        part(PartKind.CAPACITOR, "C2", 12, -2, value = "100u")
        ground(12, 2)
        part(PartKind.RESISTOR, "R1", 14, -2, value = "50")
        ground(14, 2)
        label(14, -2, "out")
        probe(0, -2)
        probe(14, -2)
        circuit("7805 regulator", AnalysisConfig(AnalysisType.TRAN, tranStop = "50m", tranStep = "20u"))
    }

    private fun halfAdder(): Circuit = Sheet().run {
        part(PartKind.VOLTAGE, "V1", 0, 0, value = "PULSE(0 5 0 1u 1u 1m 2m)")
        label(0, 0, "a")
        ground(0, 4)
        part(PartKind.VOLTAGE, "V2", 4, 0, value = "PULSE(0 5 0 1u 1u 2m 4m)")
        label(4, 0, "b")
        ground(4, 4)
        // Sum = A xor B
        part(PartKind.XOR, "U1", 12, -4)
        label(9, -5, "a")
        label(9, -3, "b")
        wire(15, -4, 16, -4)
        label(15, -4, "sum")
        part(PartKind.RESISTOR, "R1", 16, -4, rotation = 3, value = "330")
        wire(20, -4, 22, -4)
        part(PartKind.LED, "D1", 22, -4, value = "LED_RED")
        ground(22, 0)
        // Carry = A and B
        part(PartKind.AND, "U2", 12, 4)
        label(9, 3, "a")
        label(9, 5, "b")
        wire(15, 4, 16, 4)
        label(15, 4, "carry")
        part(PartKind.RESISTOR, "R2", 16, 4, rotation = 3, value = "330")
        wire(20, 4, 22, 4)
        part(PartKind.LED, "D2", 22, 4, value = "LED_GREEN")
        ground(22, 8)
        probe(15, -4)
        probe(15, 4)
        circuit("Half adder", AnalysisConfig(AnalysisType.TRAN, tranStop = "4m", tranStep = "2u"))
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

    /**
     * Channel A of a Foxboro SPEC 200 2AI-P2V, after the MI 2AI-185 component diagram. A
     * 3-wire Pt100 bridge feeds a chopper-stabilized amplifier, Q6 chopper, U6 AC amplifier,
     * Q9 demodulator and U7 integrator. Its output is chopped by Q12 into T3; one winding is
     * demodulated by Q11 onto C29 and fed back through the span divider, the other by Q10 to the U8
     * output stage, so the 0–10 V output is isolated from the sensor. Clock, supplies and T3
     * are assumptions; the directives list them.
     */
    private fun spec200(): Circuit = Sheet().run {
        fun p(x: Int, y: Int) = GridPoint(x, y)
        val h = 3 // rotation that lays a two-terminal part left to right

        // Field supply (output side), isolated A2/B2 supply (input side) and the chopper clock.
        part(PartKind.VOLTAGE, "VP15", 0, 0, value = "DC 15").also { label(it.pin(0), "p15"); ground(it.pin(1)) }
        part(PartKind.VOLTAGE, "VN15", 5, 4, rotation = 2, value = "DC 15").also { label(it.pin(1), "n15"); ground(it.pin(0)) }
        part(PartKind.VOLTAGE, "VA2", 10, 0, value = "DC 15").also { label(it.pin(0), "a2"); ground(it.pin(1)) }
        part(PartKind.VOLTAGE, "VB2", 15, 4, rotation = 2, value = "DC 15").also { label(it.pin(1), "b2"); ground(it.pin(0)) }
        part(PartKind.VOLTAGE, "VCK", 20, 0, value = "PULSE(-6 0 0 2u 2u 498u 1m)").also { label(it.pin(0), "ck"); ground(it.pin(1)) }

        // Reference: U9 lifts the 6.6 V zener to about 8.1 V for the bridge.
        part(PartKind.OPAMP5, "U9", 10, 16, value = "LM301A").also { label(it.pin(2), "a2"); label(it.pin(3), "b2") }
        part(PartKind.RESISTOR, "R74", 1, 13, value = "15k").also { label(it.pin(0), "a2") }
        part(PartKind.ZENER, "CR12", 1, 21, rotation = 2, value = "1N4611A").also { ground(it.pin(0)) }
        wire(p(1, 17), p(7, 17))
        wire(p(7, 15), p(6, 15), p(6, 19))
        part(PartKind.RESISTOR, "R73", 6, 19, value = "33.2k").also { ground(it.pin(1)) }
        wire(p(7, 15), p(7, 11))
        part(PartKind.RESISTOR, "R39", 7, 11, rotation = h, value = "7.68k")
        wire(p(11, 11), p(17, 11), p(17, 16))
        part(PartKind.RESISTOR, "R75", 13, 16, rotation = h, value = "1k")
        part(PartKind.RESISTOR, "R89", 17, 16, rotation = h, value = "332")
        label(p(17, 16), "ref")

        // RTD bridge: 6.65 k arms, a 3-wire Pt100 with 5 Ω leads, and the zero resistors.
        wire(p(21, 16), p(32, 16))
        part(PartKind.RESISTOR, "R79", 24, 16, value = "6.65k")
        part(PartKind.RESISTOR, "R81", 32, 16, value = "6.65k")
        part(PartKind.RESISTOR, "RBLK", 24, 20, value = "5")
        part(PartKind.RESISTOR, "RTD", 24, 24, value = "{100*(1+3.9083m*tdeg-5.775e-7*tdeg*tdeg)}")
        part(PartKind.RESISTOR, "RGRN", 24, 28, value = "5").also { ground(it.pin(1)) }
        part(PartKind.RESISTOR, "R85", 32, 20, value = "32")
        part(PartKind.RESISTOR, "R86", 32, 24, value = "64")
        part(PartKind.POT, "R80", 32, 28, value = "10 38.7%")
        wire(p(34, 30), p(34, 32), p(32, 32))
        part(PartKind.RESISTOR, "RWHT", 32, 32, value = "5")
        wire(p(32, 36), p(28, 36), p(28, 28), p(24, 28))
        label(p(24, 20), "sp")
        label(p(32, 20), "sm")

        // Differential chopper: Q6 shorts the bridge signal (sp) to the feedback (fbx) every half cycle.
        label(p(42, 18), "sp")
        part(PartKind.RESISTOR, "R54", 42, 18, rotation = h, value = "2k")
        wire(p(46, 18), p(47, 18))
        label(p(42, 12), "fbx")
        part(PartKind.RESISTOR, "R55", 42, 12, rotation = h, value = "2k")
        wire(p(46, 12), p(47, 12), p(47, 14))
        wire(p(47, 12), p(52, 12))
        label(p(52, 12), "cm")
        part(PartKind.CAPACITOR, "C25", 50, 12, value = "1u").also { ground(it.pin(1)) }
        part(PartKind.NJFET, "Q6", 46, 16, value = "2N4393")
        label(p(40, 16), "ck")
        part(PartKind.RESISTOR, "R103", 40, 16, rotation = h, value = "33.2k")

        // U6: AC amplifier with a T-network feedback, gain about 1000.
        wire(p(47, 18), p(50, 18))
        part(PartKind.CAPACITOR, "C28", 50, 18, rotation = h, value = "0.1u")
        part(PartKind.RESISTOR, "R61", 54, 18, rotation = h, value = "10k")
        part(PartKind.OPAMP5, "U6", 61, 19, value = "LM308").also { label(it.pin(2), "a2"); label(it.pin(3), "b2") }
        label(p(50, 24), "cm")
        part(PartKind.CAPACITOR, "C27", 50, 24, rotation = h, value = "0.1u")
        part(PartKind.RESISTOR, "R62", 54, 24, rotation = h, value = "10k")
        wire(p(58, 24), p(58, 20))
        part(PartKind.RESISTOR, "R60", 58, 24, value = "100k").also { ground(it.pin(1)) }
        wire(p(58, 18), p(58, 10))
        part(PartKind.RESISTOR, "R59", 58, 10, rotation = h, value = "100k")
        part(PartKind.RESISTOR, "R64", 62, 10, rotation = h, value = "100k")
        part(PartKind.RESISTOR, "R63", 62, 10, value = "1k").also { ground(it.pin(1)) }
        wire(p(66, 10), p(66, 19), p(64, 19))

        // Q9 demodulates in step with Q6; U7 integrates the error.
        part(PartKind.CAPACITOR, "C32", 66, 19, rotation = h, value = "1u")
        part(PartKind.RESISTOR, "R65", 70, 19, rotation = h, value = "1k")
        part(PartKind.NJFET, "Q9", 73, 21, value = "2N4393").also { ground(it.pin(2)) }
        label(p(67, 21), "ck")
        part(PartKind.RESISTOR, "R112", 67, 21, rotation = h, value = "82k")
        part(PartKind.RESISTOR, "R67", 74, 19, rotation = h, value = "40.2k")
        wire(p(78, 19), p(81, 19))
        part(PartKind.OPAMP5, "U7", 84, 20, value = "LM301A").also { label(it.pin(2), "a2"); label(it.pin(3), "b2") }
        wire(p(81, 21), p(77, 21))
        part(PartKind.RESISTOR, "R68", 80, 21, value = "41.2k").also { ground(it.pin(1)) }
        part(PartKind.CAPACITOR, "C35", 77, 21, value = "0.1u").also { ground(it.pin(1)) }
        wire(p(81, 19), p(81, 12))
        part(PartKind.CAPACITOR, "C36", 81, 15, rotation = h, value = "2.2u")
        part(PartKind.DIODE, "CR22", 81, 12, rotation = h, value = "FD333")
        wire(p(85, 12), p(89, 12), p(89, 20), p(87, 20))
        wire(p(85, 15), p(89, 15))

        // Q12 chops U7's output into T3's 7–9 winding. T3's symbol is windings 7–9 and 1–3;
        // L46 beside it is the 4–6 winding, coupled to both by K2 and K3.
        part(PartKind.RESISTOR, "R105", 89, 20, rotation = h, value = "1k")
        wire(p(93, 20), p(95, 20))
        part(PartKind.NJFET, "Q12", 94, 22, value = "2N4393").also { ground(it.pin(2)) }
        label(p(88, 22), "ck")
        part(PartKind.RESISTOR, "R113", 88, 22, rotation = h, value = "39k")
        part(PartKind.CAPACITOR, "C41", 95, 20, rotation = h, value = "2.2u")
        wire(p(99, 20), p(103, 20))
        part(PartKind.CAPACITOR, "C31", 101, 20, value = "500p").also { ground(it.pin(1)) }
        part(PartKind.TRANSFORMER, "T3", 105, 20, value = "1 1:3 0.999").also { ground(it.pin(1)); ground(it.pin(3)) }
        part(PartKind.INDUCTOR, "L46", 107, 28, value = "9")
        wire(p(107, 28), p(105, 28))
        part(PartKind.CAPACITOR, "C45", 105, 28, value = "500p")
        wire(p(105, 32), p(107, 32))

        // Output side: Q10 demodulates T3's 1–3 winding, R70/C37 filter, U8 doubles it to 0–10 V.
        wire(p(107, 20), p(113, 20))
        part(PartKind.RESISTOR, "R69", 113, 20, rotation = h, value = "20.5k")
        part(PartKind.NJFET, "Q10", 116, 22, value = "2N4393").also { ground(it.pin(2)) }
        label(p(110, 22), "ck")
        part(PartKind.RESISTOR, "R49", 110, 22, rotation = h, value = "150k")
        part(PartKind.RESISTOR, "R70", 117, 20, rotation = h, value = "137k")
        part(PartKind.CAPACITOR, "C37", 121, 20, value = "1u").also { ground(it.pin(1)) }
        wire(p(121, 20), p(123, 20), p(123, 18))
        part(PartKind.OPAMP5, "U8", 126, 17, value = "LM301A").also { label(it.pin(2), "p15"); label(it.pin(3), "n15") }
        wire(p(123, 16), p(123, 9))
        part(PartKind.RESISTOR, "R71", 123, 12, rotation = h, value = "274k")
        part(PartKind.CAPACITOR, "C38", 123, 9, rotation = h, value = "1000p")
        wire(p(127, 12), p(131, 12), p(131, 17), p(129, 17))
        wire(p(127, 9), p(131, 9), p(131, 12))
        wire(p(123, 12), p(119, 12))
        part(PartKind.RESISTOR, "R72", 119, 12, value = "274k").also { ground(it.pin(1)) }
        wire(p(131, 17), p(134, 17))
        label(p(134, 17), "outa")

        // Feedback side, floating on the bridge's zero side (sm): Q11 closes T3's 4–6 winding onto
        // C29 every other half cycle, so C29 holds the winding's peak. The span divider, R76 over
        // R78 (coarse span jumper P5–P50) and R77 + R101, feeds a fraction of it back to the chopper
        // from R77's wiper.
        wire(p(107, 28), p(117, 28))
        part(PartKind.NJFET, "Q11", 116, 30, value = "2N4393").also { label(it.pin(2), "sm") }
        label(p(110, 30), "ck")
        part(PartKind.RESISTOR, "RG11", 110, 30, rotation = h, value = "100k")
        wire(p(107, 32), p(107, 36), p(113, 36))
        part(PartKind.CAPACITOR, "C29", 110, 36, value = "2.2u").also { label(it.pin(1), "sm") }
        part(PartKind.RESISTOR, "R76", 113, 36, rotation = h, value = "20k")
        part(PartKind.RESISTOR, "R78", 117, 36, value = "487").also { label(it.pin(1), "sm") }
        wire(p(117, 36), p(121, 36))
        part(PartKind.POT, "R77", 121, 36, value = "1k 93.3%")
        part(PartKind.RESISTOR, "R101", 121, 40, value = "205").also { label(it.pin(1), "sm") }
        wire(p(123, 38), p(126, 38))
        label(p(126, 38), "fbx")

        probe(134, 17)
        probe(87, 20)
        circuit(
            "Foxboro SPEC 200 2AI-P2V",
            AnalysisConfig(AnalysisType.TRAN, tranStop = "1.5", tranStep = "100u", tranMaxStep = "20u"),
            directives = """
                |* Foxboro SPEC 200 2AI-P2V (RTD in, 0-10 V out), channel A, after the MI 2AI-185 component diagram.
                |* Part names and values follow the drawing. Not on the drawing, so assumed here:
                |*  - VCK, a 1 kHz clock, stands in for the Q7/Q8 + T1 oscillator; VA2/VB2 for its isolated supply.
                |*  - T3: 1 H primary (7-9), secondaries 1-3 and 4-6 at 1:3, coupling 0.999. The T3 symbol
                |*    netlists as LT3_p (7-9) and LT3_s (1-3); L46 is the 4-6 winding.
                |*  - The isolated common is joined to the field common, and Q11 gets gate resistor RG11.
                |*  - Coarse span jumper P5-P50 is fitted, putting R78 across the span divider.
                |* Not modelled: the U10 lead-compensation stage and the zero/span jumpers unused for this range
                |* (R94 and R102 are the other coarse span positions).
                |* Calibrated for a 100-ohm platinum RTD, 0-100 C = 0-10 V: zero with R80, span with R77.
                |* Set the RTD temperature in degrees C:
                |.param tdeg=50
                |* Steps of 20 us resolve the clock; results are kept every 100 us. A 1 Tohm shunt on
                |* every node keeps the switching converging when live mode adds its current meters.
                |.options interp rshunt=1e12
                |K2 LT3_p L46 0.999
                |K3 LT3_s L46 0.999
            """.trimMargin(),
        )
    }

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
