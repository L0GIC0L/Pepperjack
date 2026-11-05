package io.github.l0gic0l.pepperjack.data

import io.github.l0gic0l.pepperjack.model.AnalysisConfig
import io.github.l0gic0l.pepperjack.model.AnalysisType
import io.github.l0gic0l.pepperjack.model.Circuit
import io.github.l0gic0l.pepperjack.model.MeterMode
import io.github.l0gic0l.pepperjack.model.PartKind.AMMETER
import io.github.l0gic0l.pepperjack.model.PartKind.CAPACITOR
import io.github.l0gic0l.pepperjack.model.PartKind.COMPARATOR
import io.github.l0gic0l.pepperjack.model.PartKind.DFF
import io.github.l0gic0l.pepperjack.model.PartKind.DIODE
import io.github.l0gic0l.pepperjack.model.PartKind.INDUCTOR
import io.github.l0gic0l.pepperjack.model.PartKind.LED
import io.github.l0gic0l.pepperjack.model.PartKind.NAND
import io.github.l0gic0l.pepperjack.model.PartKind.NMOS
import io.github.l0gic0l.pepperjack.model.PartKind.NPN
import io.github.l0gic0l.pepperjack.model.PartKind.OPAMP5
import io.github.l0gic0l.pepperjack.model.PartKind.PNP
import io.github.l0gic0l.pepperjack.model.PartKind.POT
import io.github.l0gic0l.pepperjack.model.PartKind.REGULATOR_ADJ
import io.github.l0gic0l.pepperjack.model.PartKind.RESISTOR
import io.github.l0gic0l.pepperjack.model.PartKind.SCHOTTKY
import io.github.l0gic0l.pepperjack.model.PartKind.TIMER555
import io.github.l0gic0l.pepperjack.model.PartKind.TRANSFORMER
import io.github.l0gic0l.pepperjack.model.PartKind.VOLTAGE
import io.github.l0gic0l.pepperjack.model.PartKind.VOLTMETER
import io.github.l0gic0l.pepperjack.model.PartKind.ZENER

/*
 * More example schematics. Rotations of a two-terminal part: 0 runs down from (x, y) to
 * (x, y + 4), H runs right to (x + 4, y), L runs left to (x − 4, y), U runs up to (x, y − 4).
 * A diode's first pin is its anode, a source's its + terminal.
 */
private const val H = 3
private const val L = 1
private const val U = 2

private fun tran(stop: String, step: String, maxStep: String = "") =
    AnalysisConfig(AnalysisType.TRAN, tranStop = stop, tranStep = step, tranMaxStep = maxStep)

private val op = AnalysisConfig(AnalysisType.OP)

private fun ac(start: String, stop: String, points: Int = 100) =
    AnalysisConfig(AnalysisType.AC, acPoints = points, acStart = start, acStop = stop)

// ---------------------------------------------------------------- basics

internal fun voltageDivider(): Circuit = Sheet().run {
    supply("V1", 0, 0, "DC 9")
    w(0, 0, 0, -4, 4, -4)
    part(AMMETER, "AM1", 4, -4, H)
    w(8, -4, 10, -4)
    part(RESISTOR, "R1", 10, -4, value = "10k")
    part(RESISTOR, "R2", 10, 0, value = "4.7k")
    w(10, 4, 0, 4)
    label(10, 0, "out")
    w(10, 0, 16, 0)
    part(VOLTMETER, "VM1", 16, 0)
    w(16, 4, 10, 4)
    circuit("Voltage divider", op)
}

internal fun wheatstoneBridge(): Circuit = Sheet().run {
    supply("V1", 0, 0, "DC 10")
    w(0, 0, 0, -6, 16, -6)
    part(RESISTOR, "R1", 8, -6, value = "1k")
    part(RESISTOR, "R2", 8, -2, value = "1k")
    part(RESISTOR, "R3", 16, -6, value = "1k")
    part(POT, "RV1", 16, -2, value = "2k 40%")
    w(18, 0, 18, 2, 16, 2)
    w(0, 4, 16, 4)
    w(8, 2, 8, 4)
    w(16, 2, 16, 4)
    w(8, -2, 10, -2)
    part(VOLTMETER, "VM1", 10, -2, H)
    w(14, -2, 16, -2)
    circuit("Wheatstone bridge", op)
}

internal fun kirchhoff(): Circuit = Sheet().run {
    supply("V1", 0, 0, "DC 12")
    w(0, 0, 0, -6, 2, -6)
    part(AMMETER, "AM1", 2, -6, H)
    w(6, -6, 22, -6)
    listOf("1k", "2.2k", "4.7k").forEachIndexed { i, r ->
        val x = 10 + 6 * i
        part(AMMETER, "AM${i + 2}", x, -6)
        part(RESISTOR, "R${i + 1}", x, -2, value = r)
        w(x, 2, x, 4)
    }
    w(0, 4, 22, 4)
    circuit("Kirchhoff's current law", op)
}

internal fun rlStep(): Circuit = Sheet().run {
    supply("V1", 0, 0, "PULSE(0 5 0 1u 1u 0.5m 1m)")
    w(0, 0, 0, -2)
    part(RESISTOR, "R1", 0, -2, H, "100")
    w(4, -2, 8, -2)
    val l1 = part(INDUCTOR, "L1", 8, -2, value = "10m")
    w(8, 2, 8, 4, 0, 4)
    label(0, -2, "in")
    label(8, -2, "out")
    probe(8, -2)
    probeCurrent(l1)
    circuit("RL step response", tran("2m", "2u"))
}

// ---------------------------------------------------------------- filters

internal fun rcHighPass(): Circuit = Sheet().run {
    supply("V1", 0, 0, "DC 0 AC 1")
    w(0, 0, 0, -2)
    part(CAPACITOR, "C1", 0, -2, H, "10n")
    w(4, -2, 8, -2)
    part(RESISTOR, "R1", 8, -2, value = "10k")
    ground(8, 2)
    label(0, -2, "in")
    label(8, -2, "out")
    probe(8, -2)
    circuit("RC high-pass filter", ac("10", "1Meg"))
}

internal fun sallenKey(): Circuit = Sheet().run {
    splitSupply(32, -10)
    supply("V1", 0, 1, "DC 0 AC 1")
    w(0, 1, 2, 1)
    part(RESISTOR, "R1", 2, 1, H, "10k")
    w(6, 1, 8, 1)
    part(RESISTOR, "R2", 8, 1, H, "10k")
    w(12, 1, 17, 1)
    part(CAPACITOR, "C2", 14, 1, value = "10n")
    ground(14, 5)
    amp(OPAMP5, "U1", 20, 0, "TL072")
    // C1 from the R1–R2 junction back to the output, and the unity-gain loop.
    w(6, 1, 6, -8, 8, -8)
    part(CAPACITOR, "C1", 8, -8, H, "22n")
    w(12, -8, 26, -8, 26, 0)
    w(17, -1, 16, -1, 16, -5, 25, -5, 25, 0)
    w(23, 0, 28, 0)
    label(0, 1, "in")
    label(28, 0, "out")
    probe(28, 0)
    circuit("Sallen-Key low-pass", ac("10", "100k"))
}

internal fun twinTNotch(): Circuit = Sheet().run {
    supply("V1", -6, -2, "DC 0 AC 1")
    w(-6, -2, -6, -6, 2, -6)
    w(0, -6, 0, 4, 2, 4)
    part(RESISTOR, "R1", 2, -6, H, "15.9k")
    w(6, -6, 8, -6)
    part(RESISTOR, "R2", 8, -6, H, "15.9k")
    part(CAPACITOR, "C3", 7, -6, value = "20n")
    ground(7, -2)
    part(CAPACITOR, "C1", 2, 4, H, "10n")
    w(6, 4, 8, 4)
    part(CAPACITOR, "C2", 8, 4, H, "10n")
    part(RESISTOR, "R3", 7, 4, value = "7.95k")
    ground(7, 8)
    w(12, -6, 14, -6)
    w(12, 4, 14, 4, 14, -6, 18, -6)
    label(-6, -6, "in")
    label(18, -6, "out")
    probe(18, -6)
    circuit("Twin-T notch filter", ac("10", "100k"))
}

// ---------------------------------------------------------------- diodes

internal fun bridgeRectifier(): Circuit = Sheet().run {
    // 120 V rms mains through a 10:1 transformer into a bridge and a reservoir capacitor.
    supply("V1", -6, 0, "SIN(0 170 60)")
    w(-6, 0, -4, 0)
    part(RESISTOR, "RP", -4, 0, H, "1")
    w(0, 0, 4, 0)
    w(-6, 4, 4, 4)
    part(TRANSFORMER, "T1", 6, 0, value = "10 10:1")
    w(8, 0, 10, 0, 10, 2, 12, 2)
    w(8, 4, 9, 4, 9, 10, 18, 10, 18, 2, 16, 2)
    part(DIODE, "D1", 12, 2, U, "1N4007")
    part(DIODE, "D3", 12, 6, U, "1N4007")
    part(DIODE, "D2", 16, 2, U, "1N4007")
    part(DIODE, "D4", 16, 6, U, "1N4007")
    w(12, -2, 12, -4, 26, -4)
    w(16, -2, 16, -4)
    w(12, 6, 12, 8, 16, 8, 16, 6)
    ground(14, 8)
    part(CAPACITOR, "C1", 22, -4, value = "470u")
    ground(22, 0)
    part(RESISTOR, "R1", 26, -4, value = "1k")
    ground(26, 0)
    label(26, -4, "out")
    label(8, 0, "a")
    label(8, 4, "b")
    // Meters set like a multimeter's switch: DC on the output, AC (rms) on the secondary.
    w(26, -4, 30, -4)
    part(VOLTMETER, "VM1", 30, -4)
    ground(30, 0)
    part(VOLTMETER, "VM2", 34, 0)
    label(34, 0, "a")
    label(34, 4, "b")
    probe(26, -4)
    circuit("Bridge rectifier", tran("100m", "50u"))
        .withMeterMode("VM1", MeterMode.DC)
        .withMeterMode("VM2", MeterMode.AC)
}

internal fun zenerRegulator(): Circuit = Sheet().run {
    supply("V1", 0, 0, "DC 12")
    w(0, 0, 0, -4, 2, -4)
    part(RESISTOR, "R1", 2, -4, H, "220")
    w(6, -4, 20, -4)
    part(ZENER, "D1", 10, 0, U, "1N4733A")
    ground(10, 0)
    part(RESISTOR, "R2", 16, -4, value = "1k")
    ground(16, 0)
    part(VOLTMETER, "VM1", 20, -4)
    ground(20, 0)
    label(16, -4, "out")
    circuit("Zener regulator", op)
}

internal fun diodeClipper(): Circuit = Sheet().run {
    supply("V1", 0, 0, "SIN(0 5 1k)")
    w(0, 0, 0, -4, 2, -4)
    part(RESISTOR, "R1", 2, -4, H, "1k")
    w(6, -4, 18, -4)
    part(DIODE, "D1", 9, -4, value = "1N4148")
    ground(9, 0)
    part(DIODE, "D2", 15, 0, U, "1N4148")
    ground(15, 0)
    label(0, -4, "in")
    label(18, -4, "out")
    probe(0, -4)
    probe(18, -4)
    circuit("Diode clipper", tran("3m", "5u"))
}

internal fun diodeClamp(): Circuit = Sheet().run {
    supply("V1", 0, 0, "SIN(0 5 1k)")
    w(0, 0, 0, -4, 2, -4)
    part(CAPACITOR, "C1", 2, -4, H, "1u")
    w(6, -4, 14, -4)
    part(DIODE, "D1", 9, 0, U, "1N4148")
    ground(9, 0)
    part(RESISTOR, "R1", 14, -4, value = "100k")
    ground(14, 0)
    label(0, -4, "in")
    label(14, -4, "out")
    probe(0, -4)
    probe(14, -4)
    circuit("Diode clamp", tran("5m", "5u"))
}

internal fun voltageDoubler(): Circuit = Sheet().run {
    supply("V1", 0, 0, "SIN(0 10 1k)")
    w(0, 0, 0, -4, 2, -4)
    part(CAPACITOR, "C1", 2, -4, H, "10u")
    w(6, -4, 8, -4)
    part(DIODE, "D1", 8, 0, U, "1N4148")
    ground(8, 0)
    part(DIODE, "D2", 8, -4, H, "1N4148")
    w(12, -4, 18, -4)
    part(CAPACITOR, "C2", 14, -4, value = "10u")
    ground(14, 0)
    part(RESISTOR, "R1", 18, -4, value = "10k")
    ground(18, 0)
    label(0, -4, "in")
    label(18, -4, "out")
    probe(0, -4)
    probe(18, -4)
    circuit("Voltage doubler", tran("30m", "10u"))
}

// ---------------------------------------------------------------- transistors

internal fun emitterFollower(): Circuit = Sheet().run {
    supply("VCC", 26, -10, "DC 12", "vcc")
    part(RESISTOR, "R1", 6, -10, value = "10k")
    label(6, -10, "vcc")
    w(6, -6, 6, -2)
    part(RESISTOR, "R2", 6, -2, value = "10k")
    ground(6, 2)
    part(NPN, "Q1", 11, -2, value = "2N3904")
    w(12, -4, 12, -10)
    label(12, -10, "vcc")
    part(RESISTOR, "RE", 12, 0, value = "1k")
    ground(12, 4)
    supply("V1", 0, 0, "SIN(0 1 1k)")
    w(0, 0, 0, -2)
    part(CAPACITOR, "C1", 0, -2, H, "10u")
    w(4, -2, 9, -2)
    w(12, 0, 14, 0)
    part(CAPACITOR, "C2", 14, 0, H, "10u")
    w(18, 0, 20, 0)
    part(RESISTOR, "RL", 20, 0, value = "10k")
    ground(20, 4)
    label(0, -2, "in")
    label(20, 0, "out")
    probe(0, -2)
    probe(20, 0)
    circuit("Emitter follower", tran("3m", "5u"))
}

internal fun currentMirror(): Circuit = Sheet().run {
    supply("VCC", 0, -10, "DC 12", "vcc")
    // Each collector is fed along a wire, so live mode shows the two currents flowing.
    part(RESISTOR, "RREF", 7, -10, value = "10k")
    label(7, -10, "vcc")
    w(7, -6, 7, 0)
    part(NPN, "Q1", 8, 2, value = "2N3904", mirrored = true)
    part(NPN, "Q2", 14, 2, value = "2N3904")
    w(10, 2, 12, 2)
    w(10, 2, 10, 0, 7, 0)
    ground(7, 4)
    ground(15, 4)
    part(RESISTOR, "RL", 15, -14, value = "2.2k")
    label(15, -14, "vcc")
    part(AMMETER, "AM1", 15, -10)
    w(15, -6, 15, 0)
    circuit("Current mirror", op)
}

internal fun differentialPair(): Circuit = Sheet().run {
    splitSupply(28, -12)
    part(NPN, "Q1", 8, 0, value = "2N3904")
    part(NPN, "Q2", 16, 0, value = "2N3904", mirrored = true)
    part(RESISTOR, "RC1", 9, -6, value = "10k")
    label(9, -6, "vcc")
    part(RESISTOR, "RC2", 15, -6, value = "10k")
    label(15, -6, "vcc")
    w(9, 2, 9, 4, 15, 4, 15, 2)
    part(RESISTOR, "RE", 12, 4, value = "10k")
    label(12, 8, "vee")
    part(VOLTAGE, "V1", 4, 0, value = "DC 0")
    ground(4, 4)
    w(4, 0, 6, 0)
    w(18, 0, 20, 0, 20, 2)
    ground(20, 2)
    label(9, -2, "c1")
    label(15, -2, "c2")
    probe(9, -2)
    probe(15, -2)
    circuit(
        "Differential pair",
        AnalysisConfig(AnalysisType.DC, dcSource = "V1", dcStart = "-0.2", dcStop = "0.2", dcStep = "2m"),
    )
}

internal fun flybackDiode(): Circuit = Sheet().run {
    supply("VCC", 24, -12, "DC 12", "vcc")
    label(12, -12, "vcc")
    part(RESISTOR, "RCOIL", 12, -12, value = "50")
    val coil = part(INDUCTOR, "LCOIL", 12, -8, value = "100m")
    w(12, -4, 12, 0)
    w(12, -4, 18, -4)
    part(DIODE, "D1", 18, -4, U, "1N4007")
    w(18, -8, 18, -12, 12, -12)
    part(NPN, "Q1", 11, 2, value = "TIP120")
    ground(12, 4)
    supply("V1", 0, 2, "PULSE(0 5 1m 1u 1u 10m 20m)")
    w(0, 2, 2, 2)
    part(RESISTOR, "RB", 2, 2, H, "1k")
    w(6, 2, 9, 2)
    label(12, -4, "coil")
    probe(12, -4)
    probeCurrent(coil)
    circuit("Relay coil with flyback diode", tran("25m", "10u"))
}

internal fun pushPull(): Circuit = Sheet().run {
    splitSupply(20, -10)
    part(NPN, "Q1", 8, -3, value = "BD139")
    part(PNP, "Q2", 8, 3, value = "BD140")
    label(9, -5, "vcc")
    label(9, 5, "vee")
    w(9, -1, 9, 1)
    w(9, 0, 14, 0)
    part(RESISTOR, "RL", 14, 0, value = "100")
    ground(14, 4)
    w(6, -3, 4, -3, 4, 3, 6, 3)
    supply("V1", 0, 0, "SIN(0 5 1k)")
    w(0, 0, 4, 0)
    label(0, 0, "in")
    label(14, 0, "out")
    probe(0, 0)
    probe(14, 0)
    circuit("Class-B push-pull output", tran("3m", "5u"))
}

// ---------------------------------------------------------------- op-amps

internal fun summingAmp(): Circuit = Sheet().run {
    splitSupply(26, -10)
    supply("V1", 0, -9, "DC 1")
    w(0, -9, 2, -9)
    part(RESISTOR, "R1", 2, -9, H, "10k")
    w(6, -9, 8, -9, 8, -1)
    supply("V2", -4, 1, "SIN(0 0.5 1k)")
    w(-4, 1, -4, -1, 2, -1)
    part(RESISTOR, "R2", 2, -1, H, "10k")
    w(6, -1, 11, -1)
    amp(OPAMP5, "U1", 14, 0, "TL072")
    w(11, 1, 11, 3)
    ground(11, 3)
    w(10, -1, 10, -5)
    part(RESISTOR, "RF", 10, -5, H, "10k")
    w(14, -5, 18, -5, 18, 0)
    w(17, 0, 20, 0)
    label(0, -9, "a")
    label(-4, -1, "b")
    label(20, 0, "out")
    probe(-4, -1)
    probe(20, 0)
    circuit("Summing amplifier", tran("3m", "5u"))
}

internal fun integrator(): Circuit = Sheet().run {
    splitSupply(24, -10)
    supply("V1", 0, 1, "PULSE(-1 1 0.25m 1u 1u 0.5m 1m)")
    w(0, 1, 0, -1, 2, -1)
    part(RESISTOR, "R1", 2, -1, H, "10k")
    w(6, -1, 9, -1)
    amp(OPAMP5, "U1", 12, 0, "TL072")
    w(9, 1, 9, 3)
    ground(9, 3)
    w(8, -1, 8, -8, 10, -8)
    part(CAPACITOR, "C1", 8, -5, H, "100n")
    part(RESISTOR, "RF", 10, -8, H, "1Meg")
    w(14, -8, 16, -8, 16, 0)
    w(12, -5, 16, -5)
    w(15, 0, 19, 0)
    label(0, -1, "in")
    label(19, 0, "out")
    probe(0, -1)
    probe(19, 0)
    // Start from rest (UIC): an operating point with the input at −1 V would saturate the output.
    circuit("Op-amp integrator", tran("5m", "5u").copy(tranUic = true))
}

internal fun instrumentationAmp(): Circuit = Sheet().run {
    splitSupply(44, -8)
    // Inputs: a 10 mV difference riding on 2 V of common mode.
    supply("VCM", 0, 4, "DC 2")
    part(VOLTAGE, "VD", 0, 0, value = "SIN(0 10m 1k)")
    label(0, 0, "inp")
    label(0, 4, "inn")
    // U1 is drawn flipped so its inverting input faces the gain resistor.
    amp(OPAMP5, "U1", 14, -8, "TL072", mirrored = true, rotation = 2)
    amp(OPAMP5, "U2", 14, 8, "TL072")
    // U1 buffers the − input and U2 the + input, so the difference amplifier's output is + 21 × (inp − inn).
    w(11, -9, 4, -9)
    label(4, -9, "inn")
    w(11, 9, 4, 9)
    label(4, 9, "inp")
    w(11, -7, 9, -7, 9, -2)
    part(RESISTOR, "RG", 9, -2, value = "1k")
    w(9, 2, 9, 7, 11, 7)
    w(9, -3, 11, -3)
    part(RESISTOR, "R1", 11, -3, H, "10k")
    w(15, -3, 19, -3, 19, -8)
    w(9, 3, 11, 3)
    part(RESISTOR, "R2", 11, 3, H, "10k")
    w(15, 3, 19, 3, 19, 8)
    w(17, -8, 22, -8)
    w(17, 8, 22, 8)
    // U3: unity-gain difference amplifier.
    part(RESISTOR, "R3", 22, -8, H, "10k")
    w(26, -8, 28, -8, 28, -1, 31, -1)
    part(RESISTOR, "R5", 22, 8, H, "10k")
    w(26, 8, 28, 8, 28, 1, 31, 1)
    part(RESISTOR, "R6", 30, 1, value = "10k")
    ground(30, 5)
    amp(OPAMP5, "U3", 34, 0, "TL072")
    w(29, -1, 29, -5, 31, -5)
    part(RESISTOR, "R4", 31, -5, H, "10k")
    w(35, -5, 39, -5, 39, 0)
    w(37, 0, 41, 0)
    label(41, 0, "out")
    probe(22, 8)
    probe(41, 0)
    circuit("Instrumentation amplifier", tran("3m", "5u"))
}

internal fun schmittTrigger(): Circuit = Sheet().run {
    splitSupply(26, -10, volts = 5)
    // A slow sine with fast noise on top, the case a Schmitt trigger is for.
    part(VOLTAGE, "VN", 0, -1, value = "SIN(0 0.4 3k)")
    supply("VS", 0, 3, "SIN(0 2 100)")
    w(0, -1, 9, -1)
    amp(COMPARATOR, "U1", 12, 0, "PJ_COMP")
    w(15, 0, 19, 0)
    w(17, 0, 17, 5)
    part(RESISTOR, "R1", 17, 5, L, "10k")
    w(13, 5, 7, 5, 7, 1, 9, 1)
    part(RESISTOR, "R2", 7, 5, value = "2.2k")
    ground(7, 9)
    label(0, -1, "in")
    label(19, 0, "out")
    probe(0, -1)
    probe(19, 0)
    circuit("Schmitt trigger", tran("20m", "5u"))
}

internal fun precisionRectifier(): Circuit = Sheet().run {
    splitSupply(30, -12)
    supply("V1", 0, 1, "SIN(0 0.2 1k)")
    w(0, 1, 0, -1, 2, -1)
    part(RESISTOR, "R1", 2, -1, H, "10k")
    w(6, -1, 11, -1)
    amp(OPAMP5, "U1", 14, 0, "TL072")
    w(11, 1, 11, 3)
    ground(11, 3)
    w(8, -1, 8, -8, 10, -8)
    w(8, -5, 10, -5)
    part(DIODE, "D1", 10, -5, H, "1N4148")
    w(14, -5, 19, -5, 19, 0)
    w(17, 0, 19, 0)
    part(DIODE, "D2", 19, 0, H, "1N4148")
    part(RESISTOR, "R2", 10, -8, H, "10k")
    w(14, -8, 25, -8, 25, 0)
    w(23, 0, 27, 0)
    part(RESISTOR, "RL", 27, 0, value = "10k")
    ground(27, 4)
    label(0, -1, "in")
    label(27, 0, "out")
    probe(0, -1)
    probe(27, 0)
    circuit("Precision rectifier", tran("3m", "2u"))
}

// ---------------------------------------------------------------- oscillators

internal fun wienBridge(): Circuit = Sheet().run {
    splitSupply(28, -12)
    amp(OPAMP5, "U1", 16, 0, "TL072")
    w(19, 0, 24, 0)
    // Positive feedback: series RC from the output, parallel RC to ground.
    w(21, 0, 21, 6, 20, 6)
    part(RESISTOR, "R1", 20, 6, L, "10k")
    part(CAPACITOR, "C1", 16, 6, L, "16n")
    w(13, 1, 12, 1, 12, 8, 8, 8)
    part(CAPACITOR, "C2", 12, 8, value = "16n")
    ground(12, 12)
    part(RESISTOR, "R2", 8, 8, value = "10k")
    ground(8, 12)
    // Negative feedback: gain 3.5 for small signals, eased toward 2.5 by the diodes.
    w(13, -1, 10, -1)
    part(RESISTOR, "RG", 10, -1, value = "10k")
    ground(10, 3)
    w(11, -1, 11, -6, 12, -6)
    part(RESISTOR, "RF1", 12, -6, H, "15k")
    part(RESISTOR, "RF2", 16, -6, H, "10k")
    w(20, -6, 22, -6, 22, 0)
    w(16, -6, 16, -11)
    w(20, -6, 20, -11)
    part(DIODE, "D1", 16, -9, H, "1N4148")
    part(DIODE, "D2", 20, -11, L, "1N4148")
    label(24, 0, "out")
    probe(24, 0)
    circuit("Wien-bridge oscillator", tran("30m", "5u", "5u"))
}

internal fun relaxationOscillator(): Circuit = Sheet().run {
    splitSupply(26, -12)
    amp(OPAMP5, "U1", 14, 0, "TL072")
    w(17, 0, 21, 0)
    w(19, 0, 19, -6, 16, -6)
    part(RESISTOR, "R", 16, -6, L, "10k")
    w(12, -6, 9, -6, 9, -1)
    w(11, -1, 7, -1)
    part(CAPACITOR, "C", 7, -1, value = "100n")
    ground(7, 3)
    w(11, 1, 10, 1, 10, 6, 12, 6)
    part(RESISTOR, "R2", 10, 6, value = "10k")
    ground(10, 10)
    part(RESISTOR, "R1", 12, 6, H, "10k")
    w(16, 6, 19, 6, 19, 0)
    label(9, -1, "cap")
    label(21, 0, "out")
    probe(9, -1)
    probe(21, 0)
    circuit("Op-amp relaxation oscillator", tran("10m", "5u"))
}

internal fun monostable555(): Circuit = Sheet().run {
    supply("VCC", -6, -10, "DC 9", "vcc")
    // Pins: GND (9,4), TRIG (6,-2), OUT (14,0), RESET (11,-4), CTRL (11,4), THR (6,0), DIS (6,2), VCC (9,-4).
    part(TIMER555, "U1", 10, 0, value = "NE555")
    w(9, -4, 9, -6, 11, -6, 11, -4)
    label(9, -6, "vcc")
    // Pulse width = 1.1 R1 C1 = 1.1 ms.
    part(RESISTOR, "R1", 3, -2, value = "10k")
    label(3, -2, "vcc")
    w(3, 2, 6, 2)
    w(6, 0, 6, 2)
    part(CAPACITOR, "C1", 3, 2, value = "100n")
    ground(3, 6)
    label(6, -2, "trig")
    supply("VT", -6, -2, "PULSE(9 0 1m 1u 1u 50u 5m)", "trig")
    part(CAPACITOR, "C2", 11, 4, value = "10n")
    ground(11, 8)
    w(9, 4, 9, 6)
    ground(9, 6)
    w(14, 0, 16, 0)
    part(RESISTOR, "R3", 16, 0, H, "470")
    w(20, 0, 22, 0)
    part(LED, "D1", 22, 0, value = "LED_RED")
    ground(22, 4)
    label(14, 0, "out")
    probe(-6, -2)
    probe(3, 2)
    probe(14, 0)
    circuit("555 one-shot", tran("8m", "2u"))
}

internal fun astableMultivibrator(): Circuit = Sheet().run {
    supply("V1", 26, -12, "DC 9", "vcc")
    for ((x, led, q, mirrored) in listOf(
        Quad(7, "LED_RED", "Q1", true),
        Quad(19, "LED_GREEN", "Q2", false),
    )) {
        label(x, -14, "vcc")
        part(RESISTOR, if (x == 7) "RC1" else "RC2", x, -14, value = "470")
        part(LED, if (x == 7) "D1" else "D2", x, -10, value = led)
        w(x, -6, x, 2)
        part(NPN, q, if (mirrored) x + 1 else x - 1, 4, value = "2N3904", mirrored = mirrored)
        ground(x, 6)
    }
    // Base resistors, and each collector's capacitor to the other transistor's base.
    part(RESISTOR, "RB1", 11, 0, value = "47k")
    label(11, 0, "vcc")
    w(11, 4, 10, 4)
    part(RESISTOR, "RB2", 15, 0, value = "47k")
    label(15, 0, "vcc")
    w(15, 4, 16, 4)
    part(CAPACITOR, "C1", 7, -3, H, "10u")
    label(11, -3, "b2")
    part(CAPACITOR, "C2", 19, -3, L, "10u")
    label(15, -3, "b1")
    label(11, 4, "b1")
    label(15, 4, "b2")
    probe(7, 2)
    probe(19, 2)
    circuit("Two-transistor astable", tran("3", "1m"))
}

private data class Quad(val x: Int, val led: String, val ref: String, val mirrored: Boolean)

// ---------------------------------------------------------------- power

internal fun lm317Supply(): Circuit = Sheet().run {
    supply("V1", 0, 0, "DC 15")
    w(0, 0, 0, -2, 3, -2)
    // Pins: IN (3,-2), ADJ (6,0), OUT (9,-2). Vout = 1.25 V × (1 + RV1 / R1).
    part(REGULATOR_ADJ, "U1", 6, -2, value = "LM317")
    w(9, -2, 22, -2)
    part(RESISTOR, "R1", 12, -2, value = "240")
    w(6, 0, 6, 2, 12, 2)
    part(POT, "RV1", 12, 2, value = "5k 40%")
    w(14, 4, 14, 6, 12, 6)
    ground(12, 6)
    part(RESISTOR, "RL", 18, -2, value = "1k")
    ground(18, 2)
    part(VOLTMETER, "VM1", 22, -2)
    ground(22, 2)
    label(18, -2, "out")
    circuit("LM317 adjustable supply", op)
}

internal fun boostConverter(): Circuit = Sheet().run {
    supply("V1", 0, 0, "DC 5")
    w(0, 0, 0, -4, 2, -4)
    val l1 = part(INDUCTOR, "L1", 2, -4, H, "100u")
    w(6, -4, 10, -4, 10, -2)
    part(NMOS, "M1", 9, 0, value = "IRLZ44N")
    ground(10, 2)
    supply("VG", 4, 0, "PULSE(0 5 0 20n 20n 4.5u 10u)")
    w(4, 0, 7, 0)
    part(SCHOTTKY, "D1", 10, -4, H, "1N5819")
    w(14, -4, 20, -4)
    part(CAPACITOR, "C1", 16, -4, value = "47u")
    ground(16, 0)
    part(RESISTOR, "RL", 20, -4, value = "100")
    ground(20, 0)
    label(6, -4, "sw")
    label(20, -4, "out")
    probe(20, -4)
    probeCurrent(l1)
    circuit("Boost converter", tran("2m", "100n", "50n"))
}

// ---------------------------------------------------------------- digital

internal fun srLatch(): Circuit = Sheet().run {
    // Active-low inputs: set from 0 to 0.2 ms and again from 4 ms, reset from 2 to 2.5 ms.
    supply("VS", 0, -5, "PULSE(0 5 0.2m 1u 1u 3.8m 10m)", "set_n")
    supply("VR", 0, 3, "PULSE(5 0 2m 1u 1u 0.5m 10m)", "reset_n")
    part(NAND, "U1", 12, -4)
    part(NAND, "U2", 12, 4)
    label(9, -5, "set_n")
    label(9, 5, "reset_n")
    // Each gate's output drives the other's spare input.
    w(15, -4, 18, -4)
    w(16, -4, 16, -1, 7, -1, 7, 3, 9, 3)
    w(15, 4, 20, 4)
    w(16, 4, 16, 1, 6, 1, 6, -3, 9, -3)
    part(RESISTOR, "R1", 18, -4, H, "330")
    part(LED, "D1", 22, -4, value = "LED_RED")
    ground(22, 0)
    label(18, -4, "q")
    label(20, 4, "q_n")
    probe(0, -5)
    probe(0, 3)
    probe(18, -4)
    circuit("SR latch (NAND gates)", tran("5m", "5u"))
}

internal fun rippleCounter(): Circuit = Sheet().run {
    supply("VCLK", -4, 1, "PULSE(0 5 0 1u 1u 0.5m 1m)", "clk")
    part(DFF, "U1", 8, 0)
    part(DFF, "U2", 20, 0)
    label(5, 1, "clk")
    // Each flip-flop's Q̄ feeds back to its D, so it toggles; it also clocks the next stage.
    w(11, 1, 17, 1)
    w(13, 1, 13, -4, 4, -4, 4, -1, 5, -1)
    w(23, 1, 25, 1, 25, -4, 16, -4, 16, -1, 17, -1)
    label(11, -1, "q0")
    label(23, -1, "q1")
    for ((i, net) in listOf("q0", "q1").withIndex()) {
        val x = 4 + 10 * i
        label(x, 8, net)
        part(RESISTOR, "R${i + 1}", x, 8, H, "330")
        part(LED, "D${i + 1}", x + 4, 8, value = "LED_GREEN")
        ground(x + 4, 12)
    }
    probe(-4, 1)
    probe(11, -1)
    probe(23, -1)
    circuit("Ripple counter (divide by 4)", tran("10m", "10u"))
}

// ---------------------------------------------------------------- instrumentation

internal fun currentLoop(): Circuit = Sheet().run {
    supply("VREF", 0, -10, "DC 5", "vref")
    supply("VLOOP", 32, -10, "DC 24", "vloop")
    // Set point: RV1's wiper spans 0.4–2.0 V, so the loop spans 4–20 mA through RS.
    label(4, -8, "vref")
    part(RESISTOR, "R1", 4, -8, value = "6.2k")
    part(POT, "RV1", 4, -4, value = "3.3k 50%")
    part(RESISTOR, "R2", 4, 0, value = "820")
    ground(4, 4)
    w(6, -2, 7, -2, 7, 1, 9, 1)
    amp(OPAMP5, "U1", 12, 0, "LM358", vp = "vloop", vn = "0")
    label(9, -1, "sense")
    part(RESISTOR, "RGATE", 15, 0, H, "1k")
    part(NMOS, "M1", 21, 0, value = "2N7000")
    w(22, 2, 22, 4)
    label(22, 4, "sense")
    part(RESISTOR, "RS", 22, 4, value = "100")
    ground(22, 8)
    // The loop: 24 V supply, the 250 Ω receiver (1–5 V) and the transmitter.
    label(22, -10, "vloop")
    part(RESISTOR, "RRX", 22, -10, value = "250")
    part(AMMETER, "AM1", 22, -6)
    w(22, -10, 26, -10)
    w(22, -6, 26, -6)
    part(VOLTMETER, "VM1", 26, -10)
    circuit("4–20 mA current loop", op)
}

/** Meter modes worth showing on a transient example. */
internal fun Circuit.withMeterMode(ref: String, mode: MeterMode): Circuit =
    copy(schematic = schematic.copy(parts = schematic.parts.map { if (it.ref == ref) it.copy(meter = mode) else it }))
