package io.github.l0gic0l.pepperjack.data

import io.github.l0gic0l.pepperjack.model.AnalysisConfig
import io.github.l0gic0l.pepperjack.model.AnalysisType
import io.github.l0gic0l.pepperjack.model.Circuit
import io.github.l0gic0l.pepperjack.model.PartKind.AMMETER
import io.github.l0gic0l.pepperjack.model.PartKind.CAPACITOR
import io.github.l0gic0l.pepperjack.model.PartKind.DIODE
import io.github.l0gic0l.pepperjack.model.PartKind.ELOAD
import io.github.l0gic0l.pepperjack.model.PartKind.LCD_AMMETER
import io.github.l0gic0l.pepperjack.model.PartKind.LCD_VOLTMETER
import io.github.l0gic0l.pepperjack.model.PartKind.LED
import io.github.l0gic0l.pepperjack.model.PartKind.PROBE
import io.github.l0gic0l.pepperjack.model.PartKind.REGULATOR_ADJ
import io.github.l0gic0l.pepperjack.model.PartKind.RELAY
import io.github.l0gic0l.pepperjack.model.PartKind.RESISTOR
import io.github.l0gic0l.pepperjack.model.PartKind.SCR
import io.github.l0gic0l.pepperjack.model.PartKind.SWITCH
import io.github.l0gic0l.pepperjack.model.PartKind.TEXT
import io.github.l0gic0l.pepperjack.model.PartKind.UJT

/*
 * Examples for the bench parts: thyristors, unijunctions, relays, the electronic load, and XY
 * plots. Rotations of a two-terminal part, as in MoreExamples: 0 runs down from (x, y) to
 * (x, y + 4), H runs right to (x + 4, y), U runs up to (x, y − 4).
 */
private const val H = 3
private const val U = 2

/**
 * A transient; [uic] starts it from zero (capacitors empty) rather than from an operating point,
 * which a thyristor or unijunction could take in either of its states.
 */
private fun tran(stop: String, step: String, maxStep: String = "", uic: Boolean = false) =
    AnalysisConfig(AnalysisType.TRAN, tranStop = stop, tranStep = step, tranMaxStep = maxStep, tranUic = uic)

/**
 * A 2N2646 relaxation oscillator: C1 charges through R1 until the emitter reaches the peak
 * point, then dumps through B1, about 200 times a second. Turn R1's knob while live.
 */
internal fun ujtOscillator(): Circuit = Sheet().run {
    supply("VCC", 0, 0, "DC 12", "vcc")
    label(8, -6, "vcc")
    part(RESISTOR, "R1", 8, -6, value = "47k")
    w(8, -2, 8, 2)
    part(CAPACITOR, "C1", 8, 2, value = "100n")
    ground(8, 6)
    // Pins: E (10, 0), B2 (13, -3), B1 (13, 1).
    part(UJT, "Q1", 12, -1, value = "2N2646")
    w(8, 0, 10, 0)
    label(8, 0, "emitter")
    label(13, -7, "vcc")
    part(RESISTOR, "R2", 13, -7, value = "470")
    part(RESISTOR, "R3", 13, 1, value = "47")
    ground(13, 5)
    label(13, 1, "b1")
    probe(8, 1)
    // Its head down and to the left, clear of the UJT.
    part(PROBE, "PR2", 13, 1, rotation = 3)
    part(
        TEXT, "TXT1", 0, 9,
        value = "C1 charges through R1 to about 0.6 of 12 V, then the UJT fires\n" +
            "and empties it through B1: a sawtooth on the emitter and a\n" +
            "spike across R3, about 200 Hz. Turn R1's knob while live.",
    )
    circuit("UJT relaxation oscillator", tran("20m", "10u", "10u", uic = true))
}

/**
 * An SCR holds itself on: a gate pulse at 10 ms lights the lamp, and it stays lit after the
 * pulse ends, until RESET breaks the anode current at 25 ms; the next pulse, at 40 ms, lights it again.
 */
internal fun scrLatch(): Circuit = Sheet().run {
    // The supply comes up over 1 ms, as a real one does: a step would fire the SCR through its capacitance.
    supply("V1", 0, 0, "PWL(0 0 1m 12)", "vcc")
    supply("VG", -8, 0, "PULSE(0 5 10m 10u 10u 1m 30m)", "trig")
    supply("VRST", -16, 0, "PULSE(5 0 25m 10u 10u 2m 100m)", "hold")
    // RESET: closed while "hold" is high, opened for 2 ms at 25 ms.
    label(8, -12, "vcc")
    part(SWITCH, "RESET", 8, -12, value = "PJ_SW")
    label(6, -11, "hold")
    ground(6, -9)
    part(RESISTOR, "R1", 8, -8, value = "470")
    val lamp = part(LED, "D1", 8, -4, value = "LED_RED")
    // Pins: A (8, 0), K (8, 4), G (6, 3).
    part(SCR, "CR1", 8, 0, value = "C106D")
    ground(8, 4)
    label(2, 3, "trig")
    part(RESISTOR, "RG", 2, 3, H, "1k")
    probe(8, 0)
    probeCurrent(lamp)
    part(
        TEXT, "TXT1", -16, 8,
        value = "A gate pulse at 10 ms fires CR1 and the lamp stays lit after it:\n" +
            "the SCR holds itself on while its anode current is above its\n" +
            "holding current. RESET breaks that current at 25 ms and the lamp\n" +
            "stays out until the next pulse, at 40 ms.",
    )
    circuit("SCR latch with reset", tran("60m", "20u", "20u", uic = true))
}

/**
 * Start/stop with a seal-in contact, as on a motor starter: START (normally open) pulls the
 * relay in, its NO contact holds it in after START is let go, and STOP (normally closed)
 * drops it out.
 */
internal fun relaySealIn(): Circuit = Sheet().run {
    supply("V1", 0, 0, "DC 12", "vcc")
    supply("VSTART", -8, 0, "PULSE(0 5 20m 10u 10u 10m 200m)", "start")
    supply("VSTOP", -16, 0, "PULSE(5 0 60m 10u 10u 15m 200m)", "stop")
    label(8, -14, "vcc")
    part(SWITCH, "STOP", 8, -14, value = "PJ_SW")
    label(6, -13, "stop")
    ground(6, -11)
    label(8, -10, "s1")
    part(SWITCH, "START", 8, -10, value = "PJ_SW")
    label(6, -9, "start")
    ground(6, -7)
    label(8, -6, "coil")
    // Pins: C1 (14, -2), C2 (14, 2), COM (18, 2), NO (19, -2), NC (17, -2).
    val relay = part(RELAY, "K1", 16, -2, value = "G5LE-1-12")
    label(14, -2, "coil")
    ground(14, 2)
    part(DIODE, "D1", 12, 2, U, "1N4148")
    w(12, -2, 14, -2)
    w(12, 2, 14, 2)
    // The seal-in contact: COM from s1, NO to the coil, in parallel with START.
    label(18, 2, "s1")
    w(19, -2, 19, -4, 23, -4)
    label(23, -4, "coil")
    // NC lights the red "stopped" lamp; the coil's own feed lights the green "running" one.
    w(17, -2, 17, -6, 27, -6)
    part(RESISTOR, "R4", 27, -6, value = "1k")
    part(LED, "DS1", 27, -2, value = "LED_RED")
    ground(27, 2)
    label(33, -6, "coil")
    part(RESISTOR, "R3", 33, -6, value = "1k")
    part(LED, "DS2", 33, -2, value = "LED_GREEN")
    ground(33, 2)
    probe(33, -6)
    probeCurrent(relay)
    part(
        TEXT, "TXT1", -16, 8,
        value = "START is pressed from 20 to 30 ms: K1 pulls in, and its NO contact,\n" +
            "in parallel with START, keeps the coil fed after START is let go.\n" +
            "STOP, pressed from 60 to 75 ms, breaks the feed and K1 drops out.\n" +
            "STOP must be held longer than K1 takes to drop out (D1 keeps the\n" +
            "coil current up for several ms), or the seal-in catches it again.\n" +
            "Watch K1's contact and the lamps while live.",
    )
    circuit("Relay start/stop (seal-in)", tran("100m", "20u", "20u"))
}

/**
 * An LM317 at 5 V against an electronic load sweeping 0–2 A and back: it holds 5 V to its
 * 1.5 A limit, sags while the load asks for more, and recovers as soon as it asks for less.
 */
internal fun lm317ELoad(): Circuit = Sheet().run {
    supply("V1", 0, 0, "DC 12")
    w(0, 0, 0, -2, 3, -2)
    // Pins: IN (3, -2), ADJ (6, 0), OUT (9, -2). Vout = 1.25 V × (1 + R2 / R1) = 5 V.
    part(REGULATOR_ADJ, "U1", 6, -2, value = "LM317")
    w(9, -2, 33, -2)
    part(RESISTOR, "R1", 12, -2, value = "240")
    w(6, 0, 6, 2, 12, 2)
    part(RESISTOR, "R2", 12, 2, value = "720")
    ground(12, 6)
    part(LCD_AMMETER, "IOUT", 19, -2)
    part(ELOAD, "EL1", 19, 2, value = "CC 2 sweep")
    ground(19, 6)
    label(23, -2, "out")
    part(LCD_VOLTMETER, "VOUT", 27, -2)
    ground(27, 2)
    // The output capacitor the datasheet asks for.
    part(CAPACITOR, "C1", 33, -2, value = "10u")
    ground(33, 2)
    part(
        TEXT, "TXT1", 0, 9,
        value = "EL1 sweeps 0 → 2 A → 0. The LM317 holds 5 V up to its 1.5 A\n" +
            "limit; past it the load pulls the output down, and it comes straight\n" +
            "back as the load asks for less: a plain current limit, no foldback.\n" +
            "Run it, plot V(out) and set X to I(VIOUT) for the V–I curve.",
    )
    circuit("LM317 on an electronic load", tran("100m", "20u", "20u"))
}

/** Lissajous figures: 1 kHz against 1.5 kHz, and against itself through an RC network's 45°. */
internal fun lissajous(): Circuit = Sheet().run {
    supply("VX", 0, 0, "SIN(0 1 1k)", "x")
    supply("VY", 8, 0, "SIN(0 1 1.5k)", "y")
    label(16, -2, "x")
    part(RESISTOR, "R1", 16, -2, H, "1.59k")
    label(20, -2, "rc")
    part(CAPACITOR, "C1", 20, -2, value = "100n")
    ground(20, 2)
    part(
        TEXT, "TXT1", 0, 6,
        value = "Run, plot V(y) and V(rc), and set X to V(x). Y at 1.5 times the\n" +
            "frequency of X draws a 3:2 knot. The RC's corner is 1 kHz, so V(rc)\n" +
            "lags 45° at 0.71 of the amplitude: a tilted ellipse.",
    )
    circuit("Lissajous figures (XY)", tran("4m", "2u"))
}

/** A diode's I–V curve, swept by the source and plotted against the diode's own voltage. */
internal fun diodeCurveXY(): Circuit = Sheet().run {
    supply("V1", 0, 0, "DC 0")
    w(0, 0, 0, -2)
    part(RESISTOR, "R1", 0, -2, H, "100")
    w(4, -2, 8, -2)
    part(AMMETER, "AM1", 8, -2)
    part(DIODE, "D1", 8, 2, value = "1N4148")
    ground(8, 6)
    label(8, 2, "d")
    part(
        TEXT, "TXT1", 0, 9,
        value = "V1 sweeps 0–5 V. Run, plot I(VAM1) and set X to V(d): the\n" +
            "1N4148's I–V curve, flat until about 0.6 V and then steep.",
    )
    circuit("Diode I–V curve (XY)", AnalysisConfig(AnalysisType.DC, dcSource = "V1", dcStart = "0", dcStop = "5", dcStep = "10m"))
}
