package io.github.l0gic0l.pepperjack.data

import io.github.l0gic0l.pepperjack.model.AnalysisConfig
import io.github.l0gic0l.pepperjack.model.AnalysisType
import io.github.l0gic0l.pepperjack.model.Circuit
import io.github.l0gic0l.pepperjack.model.PartKind
import io.github.l0gic0l.pepperjack.model.PartKind.AMMETER
import io.github.l0gic0l.pepperjack.model.PartKind.CAPACITOR
import io.github.l0gic0l.pepperjack.model.PartKind.DIODE
import io.github.l0gic0l.pepperjack.model.PartKind.LCD_AMMETER
import io.github.l0gic0l.pepperjack.model.PartKind.LCD_VOLTMETER
import io.github.l0gic0l.pepperjack.model.PartKind.LED
import io.github.l0gic0l.pepperjack.model.PartKind.NPN
import io.github.l0gic0l.pepperjack.model.PartKind.PNP
import io.github.l0gic0l.pepperjack.model.PartKind.POT
import io.github.l0gic0l.pepperjack.model.PartKind.RELAY
import io.github.l0gic0l.pepperjack.model.PartKind.RESISTOR
import io.github.l0gic0l.pepperjack.model.PartKind.SCR
import io.github.l0gic0l.pepperjack.model.PartKind.SPST
import io.github.l0gic0l.pepperjack.model.PartKind.SWITCH
import io.github.l0gic0l.pepperjack.model.PartKind.TEXT
import io.github.l0gic0l.pepperjack.model.PartKind.TRANSFORMER
import io.github.l0gic0l.pepperjack.model.PartKind.UJT
import io.github.l0gic0l.pepperjack.model.PartKind.VOLTAGE
import io.github.l0gic0l.pepperjack.model.PartKind.ZENER

/** Rotations, as in [Sheet]: 2 runs bottom to top, 3 left to right, 1 right to left. */
private const val UP = 2
private const val RIGHT = 3
private const val LEFT = 1

/**
 * The System Power Supply of instrument manual E92-331 (Figure 11, with the component list
 * and performance specifications): 15 V at 0–12 A from 105–130 V AC. A bridge of 35 A stud
 * rectifiers into 28 000 µF; four paralleled 2N3771s with 0.499 Ω ballast, driven by a
 * Darlington (Q2, Q4) from a constant-current pull-up (Q1); a differential error amplifier
 * (Q9, Q10) on a 6.2 V reference (CR8), with Q3 as its level shifter; a foldback current
 * limit (Q11) on the shunt R13 ∥ R14; a UJT (Q12) firing an SCR crowbar (CR11) at 17.5 V;
 * and a relay (Q13, K1) that drops out below 12.5 V. The circled test points A–J are net
 * labels of the same names, and land on the manual's voltage table.
 *
 * The manual leaves out T1's ratings and K1's coil; T1 is set so the raw supply (A) matches
 * the table, and a 9 V relay stands in for K1's mercury-wetted HGS2MT-5705.
 */
internal fun systemPowerSupply(): Circuit = Sheet().run {
    // Laid out on a grid, then spread apart after the bridge so the stud rectifiers' and the
    // reservoir's labels have room.
    fun sx(x: Int) = when {
        x >= 33 -> x + 11
        x >= 27 -> x + 6
        else -> x
    }
    fun p(kind: PartKind, ref: String, x: Int, y: Int, rotation: Int = 0, value: String? = null, mirrored: Boolean = false) =
        part(kind, ref, sx(x), y, rotation, value, mirrored)
    fun w(vararg xy: Int) = this.w(*IntArray(xy.size) { if (it % 2 == 0) sx(xy[it]) else xy[it] })
    fun label(x: Int, y: Int, name: String) = this.label(sx(x), y, name)
    fun ground(x: Int, y: Int) = this.ground(sx(x), y)

    // ---- AC input: TB1-1 (H) and TB1-3 (N); the circuit breaker CB1 is the on/off switch,
    // and DS1 the pilot lamp. The source's 2.5 Ω stands in for T1's winding resistance.
    p(VOLTAGE, "VAC", 0, 8, value = "SIN(0 166.9 60) Rser=2.5")
    ground(0, 12)
    w(0, 8, 0, 4, 4, 4)
    p(SPST, "CB1", 4, 4, RIGHT, "closed")
    w(8, 4, 14, 4)
    p(RESISTOR, "DS1", 11, 4, value = "4.8k")
    w(11, 8, 11, 12)
    p(TRANSFORMER, "T1", 16, 4, value = "2 118:25.3 0.999")
    w(14, 8, 14, 12, 0, 12)

    // ---- Bridge CR1–CR4 (35 A stud rectifiers, CR1 and CR2 reverse-polarity studs) and the
    // reservoir C1: test point A.
    w(18, 4, 26, 4)
    p(DIODE, "CR1", 26, 4, UP, "1N1187RA")
    p(DIODE, "CR3", 26, 8, UP, "1N1187A")
    p(DIODE, "CR2", 28, 4, UP, "1N1187RA")
    p(DIODE, "CR4", 28, 8, UP, "1N1187A")
    w(18, 8, 20, 8, 20, 12, 30, 12, 30, 4, 28, 4)
    w(26, 8, 28, 8)
    ground(27, 8)
    w(26, 0, 95, 0)
    label(32, 0, "A")
    p(CAPACITOR, "C1", 34, 0, value = "28000u")
    ground(34, 4)

    // ---- Q1: a constant-current pull-up of about 10 mA; CR5 holds its base (B) 3.6 V below A.
    p(ZENER, "CR5", 40, 4, UP, "1N747A")
    w(40, 4, 40, 8, 42, 8)
    label(40, 6, "B")
    p(RESISTOR, "R1", 40, 8, value = "1.5k")
    ground(40, 12)
    p(RESISTOR, "R2", 46, 0, value = "301")
    w(46, 4, 46, 6)
    w(42, 8, 43, 8)
    p(PNP, "Q1", 45, 8, value = "2N4036")

    // ---- C: Q1's collector, Q2's base and Q3's collector (and Q11's, by its label).
    w(46, 10, 46, 14, 48, 14)
    w(46, 14, 46, 20)
    label(46, 12, "C")
    p(NPN, "Q2", 50, 14, value = "2N2270")
    w(51, 12, 51, 0)
    // Q3 sinks Q1's current to regulate, its emitter held up by CR6 and CR7; C2 rolls the loop off.
    p(CAPACITOR, "C2", 40, 16, value = "100n")
    w(40, 16, 46, 16)
    w(40, 20, 40, 22, 43, 22)
    label(40, 22, "q3b")
    p(NPN, "Q3", 45, 22, value = "2N2270")
    p(DIODE, "CR6", 46, 24, value = "1N4143")
    p(ZENER, "CR7", 46, 32, UP, "1N959B")
    ground(46, 32)

    // ---- D and E: the Darlington Q2 → Q4 → Q5–Q8, with the bleeders R3 and R6.
    w(51, 16, 51, 22)
    w(51, 18, 54, 18)
    label(51, 20, "D")
    p(NPN, "Q4", 56, 18, value = "2N3771")
    w(57, 16, 57, 0)
    p(RESISTOR, "R3", 51, 22, RIGHT, "100")
    w(55, 22, 57, 22, 57, 20)
    w(57, 20, 92, 20)
    label(60, 20, "E")

    // ---- Q5–Q8: the pass transistors, emitters F, G, H and I, through the ballast R12–R9 to J.
    val pass = listOf(Triple("Q5", "R12", "F"), Triple("Q6", "R11", "G"), Triple("Q7", "R10", "H"), Triple("Q8", "R9", "I"))
    pass.forEachIndexed { k, (q, r, tp) ->
        val x = 64 + 10 * k
        p(NPN, q, x, 8, value = "2N3771")
        w(x + 1, 6, x + 1, 0)
        w(x - 2, 8, x - 2, 20)
        p(RESISTOR, r, x + 1, 10, value = "0.499")
        label(x + 1, 10, tp)
    }
    w(65, 14, 99, 14)
    label(68, 14, "J")
    p(RESISTOR, "R6", 92, 20, RIGHT, "24")
    w(96, 20, 97, 20, 97, 10, 95, 10)

    // ---- The shunt R13 ∥ R14, and M1 (DC AMPS: a 0–1 mA meter; 1 mA ≈ 15 A) across it.
    p(RESISTOR, "R13", 99, 14, RIGHT, "0.1")
    p(RESISTOR, "R14", 99, 18, RIGHT, "0.1")
    w(99, 14, 99, 18)
    w(103, 14, 103, 18)
    w(99, 14, 99, 10)
    p(AMMETER, "M1", 99, 10, RIGHT)
    p(POT, "R17", 103, 10, RIGHT, "200 50%")
    w(105, 8, 103, 8, 103, 10)
    p(RESISTOR, "R19", 107, 10, RIGHT, "649")
    w(111, 10, 111, 32)
    w(103, 18, 111, 18)

    // ---- Foldback current limit: R15 (CURRENT LIMIT) and R16 divide J; Q11 turns on, and pulls
    // C down, once the shunt's drop beats R15's share. About 14 A at 15 V, folding to 3 A.
    label(118, 6, "J")
    w(118, 6, 118, 8)
    p(POT, "R15", 118, 8, value = "100 55%", mirrored = true)
    p(RESISTOR, "R16", 118, 12, value = "1k")
    ground(118, 16)
    w(116, 10, 114, 10, 114, 20, 125, 20)
    p(DIODE, "CR9", 129, 20, LEFT, "1N4143")
    p(RESISTOR, "R20", 129, 20, UP, "1k")
    w(129, 16, 132, 16, 132, 18)
    label(130, 16, "C")
    p(NPN, "Q11", 131, 20, value = "2N2270")
    p(RESISTOR, "R18", 132, 22, value = "15")
    w(132, 26, 132, 32)

    // ---- The output (+S and +DC, jumpered at TB2 for local sensing) and the common (−S, −DC).
    w(48, 32, 248, 32)
    label(134, 32, "out")
    w(48, 56, 248, 56)
    ground(48, 56)

    // ---- Error amplifier: Q9 on the 6.2 V reference CR8, Q10 on the divider R21, R22 (OUTPUT)
    // and R23; Q9's collector drives Q3's base.
    p(RESISTOR, "R4", 50, 32, value = "1.18k")
    p(ZENER, "CR8", 50, 40, UP, "1N825")
    w(50, 40, 50, 56)
    w(50, 36, 52, 36, 52, 40, 54, 40)
    p(NPN, "Q9", 56, 40, value = "2N2270")
    p(RESISTOR, "R5", 57, 32, value = "3.9k")
    w(57, 36, 57, 38)
    label(57, 36, "q3b")
    p(NPN, "Q10", 64, 40, value = "2N2270", mirrored = true)
    p(RESISTOR, "R7", 63, 32, value = "2.2k")
    w(63, 36, 63, 38)
    w(57, 42, 57, 44, 63, 44, 63, 42)
    p(RESISTOR, "R8", 60, 44, value = "2.4k")
    w(60, 48, 60, 56)
    w(66, 40, 70, 40)
    p(RESISTOR, "R21", 70, 32, value = "2.55k")
    w(70, 36, 70, 40)
    p(POT, "R22", 70, 40, value = "500 57%")
    w(72, 42, 73, 42, 73, 40, 70, 40)
    p(RESISTOR, "R23", 70, 44, value = "1.58k")
    w(70, 48, 70, 56)
    // Output capacitors, and M2 (DC VOLTS: a 0–1 mA meter; 1 mA = 20 V).
    p(CAPACITOR, "C3", 78, 32, value = "1000u Rser=50m")
    w(78, 36, 78, 56)
    p(CAPACITOR, "C4", 88, 32, value = "220n")
    w(88, 36, 88, 56)
    p(AMMETER, "M2", 94, 32)
    p(RESISTOR, "R24", 94, 36, value = "15.5k")
    p(POT, "R25", 94, 40, value = "5k 90%")
    w(96, 42, 96, 44, 94, 44)
    w(94, 44, 94, 56)

    // ---- High limit (crowbar): R28, R29 (HIGH LIMIT) and R30 feed the UJT Q12's emitter
    // through C5; its bases run from CR10's 11.7 V. At 17.5 V it fires, and its B1 pulse fires
    // the SCR CR11 across the output; the current limit then holds it there.
    p(RESISTOR, "R28", 136, 32, value = "1.87k")
    p(POT, "R29", 136, 36, value = "1k 22%")
    p(RESISTOR, "R30", 136, 40, value = "698")
    w(136, 44, 136, 56)
    w(138, 38, 143, 38, 143, 39, 145, 39)
    p(CAPACITOR, "C5", 143, 40, value = "2u")
    w(143, 39, 143, 40)
    w(143, 44, 143, 56)
    p(UJT, "Q12", 147, 38, value = "2N2646")
    p(RESISTOR, "R31", 155, 32, value = "390")
    w(155, 36, 157, 36)
    p(ZENER, "CR10", 157, 40, UP, "1N943")
    w(157, 40, 157, 56)
    p(RESISTOR, "R32", 155, 36, LEFT, "1.3k")
    w(151, 36, 148, 36)
    w(148, 40, 148, 42)
    p(RESISTOR, "R33", 148, 42, value = "47")
    w(148, 46, 148, 56)
    p(SCR, "CR11", 163, 40, value = "2N681")
    w(163, 40, 163, 32)
    w(163, 44, 163, 56)
    w(161, 43, 159, 43, 159, 41, 148, 41)
    p(DIODE, "CR12", 167, 36, UP, "1N1187A")
    w(167, 36, 167, 56)

    // ---- Low limit: R35, R36 (LOW LIMIT) and R37 bias Q13 against CR14's 5.1 V; above
    // 12.5 V it holds K1 in.
    p(RESISTOR, "R35", 173, 32, value = "453")
    p(POT, "R36", 173, 36, value = "200 30%")
    p(RESISTOR, "R37", 173, 40, value = "301")
    w(173, 44, 173, 56)
    w(175, 38, 181, 38)
    p(NPN, "Q13", 183, 38, value = "2N2270")
    w(184, 40, 184, 46)
    p(RESISTOR, "R38", 188, 32, value = "470")
    w(188, 36, 188, 46, 184, 46)
    p(ZENER, "CR14", 184, 50, UP, "1N751A")
    w(184, 50, 184, 56)
    // K1's coil from the output to Q13 (by the "coil" labels), with CR13 across it.
    w(184, 36, 184, 34)
    label(184, 34, "coil")
    p(DIODE, "CR13", 191, 42, UP, "1N3193")
    w(191, 38, 198, 38)
    w(191, 42, 198, 42)
    w(191, 42, 191, 46)
    label(191, 46, "coil")
    p(RELAY, "K1", 200, 38, value = "G5LE-1-9")
    w(198, 38, 198, 32)
    // K1's contacts go out on J1; here a lamp shows each, lit through the common's R43.
    p(LED, "DS3", 201, 32, value = "LED_RED")
    w(201, 36, 201, 38)
    p(LED, "DS2", 209, 32, value = "LED_GREEN")
    w(209, 36, 209, 38, 203, 38)
    p(RESISTOR, "R43", 202, 42, value = "1k")
    w(202, 46, 202, 56)

    // ---- J2 (the front-panel output jack: 15.0 V through R34 and R42), and the 100 mV
    // monitor R40/R41 (with J1's jumpers per notes 3 and 4).
    p(RESISTOR, "R34", 218, 32, value = "50")
    label(218, 36, "J2")
    p(CAPACITOR, "C7", 218, 36, value = "10n")
    p(RESISTOR, "R42", 218, 40, value = "50")
    w(218, 44, 218, 56)
    p(RESISTOR, "R40", 223, 32, value = "14.9k")
    label(223, 36, "sig")
    p(RESISTOR, "R41", 223, 36, value = "100")
    w(223, 40, 223, 56)

    // ---- Added for the simulation: the panel meters, two 6 A loads (both on is the full
    // 12 A) and a short circuit to switch in.
    p(LCD_AMMETER, "IOUT", 230, 32)
    // Live mode starts every run from cold, so a load switched in at the start would never let
    // the output rise and K1 pull in. SAPPLY connects the loads 30 ms in (VAPPLY steps), once
    // the supply is up, as if they were switched on at the bench.
    w(230, 36, 230, 38)
    p(SWITCH, "SAPPLY", 230, 38, value = "APPLY", mirrored = true)
    w(232, 39, 232, 37, 237, 37)
    p(VOLTAGE, "VAPPLY", 237, 37, value = "PULSE(0 1 30m)")
    w(230, 42, 230, 44, 242, 44)
    w(232, 41, 232, 44)
    w(237, 41, 237, 44)
    p(SPST, "LOAD1", 230, 44, value = "open")
    p(RESISTOR, "RL1", 230, 48, value = "2.5")
    w(230, 52, 230, 56)
    p(SPST, "LOAD2", 236, 44, value = "open")
    p(RESISTOR, "RL2", 236, 48, value = "2.5")
    w(236, 52, 236, 56)
    p(SPST, "SHORT", 242, 44, value = "open")
    p(RESISTOR, "RS", 242, 48, value = "10m")
    w(242, 52, 242, 56)
    p(LCD_VOLTMETER, "VOUT", 248, 32)
    w(248, 36, 248, 56)

    p(
        TEXT, "TXT1", 0, -22,
        value = "System Power Supply, E92-331 Figure 11\n" +
            "15 V DC, 0–12 A, from 105–130 V AC\n" +
            "Over-voltage crowbar 17.5 V, under-voltage relay 12.5 V\n" +
            "Trims: R22 OUTPUT, R15 CURRENT LIMIT,\n" +
            "R29 HIGH LIMIT, R36 LOW LIMIT, R17 and R25 meters",
    )
    p(
        TEXT, "TXT2", 0, 40,
        value = "Test points (no load / 6 A), from the manual:\n" +
            "A 32–33 / 29–30    B 28–30 / 25–27\n" +
            "C 16.3–16.7 / 17.7–18.2    D 15.7–16.0 / 17.1–17.5\n" +
            "E 15.4–15.5 / 16.6–16.9    F–I 15.0 / 16.0–16.2\n" +
            "J 15.0 / 15.3–15.4\n" +
            "Tap a wire while live to read it.",
    )
    p(
        TEXT, "TXT3", 136, 60,
        value = "Added for the simulation: LOAD1 and LOAD2 (6 A each) and\n" +
            "SHORT, which SAPPLY connects 30 ms into each run, once the\n" +
            "supply is up and K1 is in; the IOUT and VOUT panel meters;\n" +
            "and lamps on K1's contacts: green DS2 above the low limit,\n" +
            "red DS3 below it.\n" +
            "Only the crowbar switches the output off. Below 12.5 V,\n" +
            "K1 drops out to tell the system on J1, and the current\n" +
            "limit folds back (Figure 10) to 3 A into a short.\n" +
            "Not drawn: K1's second pole (R39, C6), the test jacks\n" +
            "J3–J11 (R26, R27), and J1's jumpers.\n" +
            "K1 is a mercury-wetted HGS2MT-5705; a 9 V relay stands in.\n" +
            "T1's ratings aren't listed: 118 V : 25.3 V matches A.",
    )

    circuit(
        "System power supply (E92-331)",
        AnalysisConfig(AnalysisType.TRAN, tranStop = "100m", tranStep = "20u", tranMaxStep = "20u", tranUic = true),
        // Gear integration: trapezoidal rings from step to step in the current limit's
        // feedback, which takes the run ten times as long for no difference in the result.
        ".options abstol=1n method=gear\n.model APPLY SW(Ron=1m Roff=1G Vt=0.5 Vh=0.1)",
    )
}
