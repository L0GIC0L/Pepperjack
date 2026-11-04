package io.github.l0gic0l.pepperjack.data

import io.github.l0gic0l.pepperjack.model.AnalysisConfig
import io.github.l0gic0l.pepperjack.model.AnalysisType
import io.github.l0gic0l.pepperjack.model.Circuit
import io.github.l0gic0l.pepperjack.model.PartKind.AMMETER
import io.github.l0gic0l.pepperjack.model.PartKind.BEHAVIORAL
import io.github.l0gic0l.pepperjack.model.PartKind.CAPACITOR
import io.github.l0gic0l.pepperjack.model.PartKind.COMPARATOR
import io.github.l0gic0l.pepperjack.model.PartKind.DIODE
import io.github.l0gic0l.pepperjack.model.PartKind.LED
import io.github.l0gic0l.pepperjack.model.PartKind.LCD_VOLTMETER
import io.github.l0gic0l.pepperjack.model.PartKind.LCD_AMMETER
import io.github.l0gic0l.pepperjack.model.PartKind.NMOS
import io.github.l0gic0l.pepperjack.model.PartKind.NPN
import io.github.l0gic0l.pepperjack.model.PartKind.OPAMP5
import io.github.l0gic0l.pepperjack.model.PartKind.PNP
import io.github.l0gic0l.pepperjack.model.PartKind.POT
import io.github.l0gic0l.pepperjack.model.PartKind.RESISTOR
import io.github.l0gic0l.pepperjack.model.PartKind.SCHOTTKY
import io.github.l0gic0l.pepperjack.model.PartKind.SPST
import io.github.l0gic0l.pepperjack.model.PartKind.TEXT
import io.github.l0gic0l.pepperjack.model.PartKind.VOLTAGE
import io.github.l0gic0l.pepperjack.model.PartKind.ZENER

/**
 * One output channel of the bench supply: an isolated DC-DC module, then a linear CV/CC
 * regulator (LM358 error amps diode-ORed onto a TIP120 emitter follower), with a latching
 * LM393 OVP comparator that pulls the drive down and fires a MOSFET crowbar.
 */
private class PsuChannel(
    val n: Int,
    /** The module's output voltage. */
    val raw: Int,
    /** Feedback divider: Vout = 2.495 V × (1 + rt / rb) at full scale. */
    val rt: String,
    val rb: String,
    /** OVP divider, a little above the output's full scale. */
    val rot: String,
    val rob: String,
    /** Current shunt; full-scale limit = 0.156 V / rs. */
    val rs: String,
    val rref: String,
    /** The LM393's pull-up, and the OVP LED's resistor. */
    val rpu: String,
    val rled: String,
    /** Knob positions, as the pots' value text: voltage, current limit, OVP. */
    val vSet: String,
    val iSet: String,
    val ovpSet: String,
    val load: String,
    /** The negative channel: the + terminal is its common and the − terminal its output. */
    val negative: Boolean,
)

private const val H = 3
private const val L = 1
private const val U = 2
private const val PITCH = 54

private fun Sheet.channel(ch: PsuChannel, ox: Int, oy: Int) {
    val n = ch.n
    val raw = "raw$n"
    val iso = "iso$n"
    val ref = "ref$n"
    val b = "base$n"
    // The output terminals' nets: + and −. The CC amp senses the − side of the shunt, and
    // "com" is the terminal the channel's voltage is measured from.
    val plus = if (ch.negative) "com$n" else "pos$n"
    val minus = if (ch.negative) "neg$n" else "com$n"
    fun p(kind: io.github.l0gic0l.pepperjack.model.PartKind, ref: String, x: Int, y: Int, rotation: Int = 0, value: String? = null, mirrored: Boolean = false) =
        part(kind, ref, ox + x, oy + y, rotation, value, mirrored)
    fun lb(x: Int, y: Int, name: String) = label(ox + x, oy + y, name)
    fun wr(vararg xy: Int) = w(*IntArray(xy.size) { xy[it] + if (it % 2 == 0) ox else oy })
    fun gnd(x: Int, y: Int) = ground(ox + x, oy + y)

    // Isolated module. Its input (on the bus) draws the output power at 88% efficiency plus
    // 0.3 W; its output comes up once the input is past about 9 V, its turn-on, with a soft
    // start of a few ms (centred on 7 ms, after the input has come up).
    wr(-38, 0, -34, 0)
    p(BEHAVIORAL, "B${n}I", -34, 0, value = "I=(V($raw,$iso)*i(VAM${n}M)+0.3)/(0.88*(2.5+sqrt((V(bus)-2.5)*(V(bus)-2.5)+1)))")
    gnd(-34, 4)
    val quarter = if (ch.raw % 4 == 0) "${ch.raw / 4}" else "${ch.raw / 4.0}"
    p(BEHAVIORAL, "B${n}M", -34, 8, value = "V=$quarter*(1+tanh((V(bus)-9)/0.7))*(1+tanh((time-7m)/1.5m))")
    lb(-34, 12, iso)
    wr(-34, 8, -2, 8, -2, 0, 0, 0)
    p(AMMETER, "AM${n}M", 0, 0, H)
    lb(4, 0, raw)
    wr(4, 0, 14, 0)
    p(CAPACITOR, "C${n}01", 5, 0, value = "220u")
    lb(5, 4, iso)

    // Pass transistor and output.
    p(NPN, "Q${n}01", 13, 2, value = "TIP120")
    wr(11, 2, 9, 2)
    lb(9, 2, b)
    wr(14, 4, 14, 6, 63, 6)
    p(RESISTOR, "R${n}01", 18, 6, value = ch.rt)
    lb(18, 10, "fb$n")
    p(RESISTOR, "R${n}02", 18, 10, value = ch.rb)
    lb(18, 14, iso)
    p(RESISTOR, "R${n}03", 22, 6, value = "4.7k")
    lb(22, 10, iso)
    // OVP divider, and the crowbar across the terminals: through 1 Ω, so it doesn't dump the
    // output capacitor in one spike, and through the shunt, so the current limit holds it.
    p(RESISTOR, "R${n}04", 34, 6, value = ch.rot)
    lb(34, 10, "ov$n")
    p(RESISTOR, "R${n}05", 34, 10, value = ch.rob)
    lb(34, 14, iso)
    p(NMOS, "M${n}01", 39, 10, value = "IRLZ44N")
    wr(40, 8, 40, 6)
    lb(37, 10, "cg$n")
    p(RESISTOR, "R${n}06", 40, 12, value = "1")
    lb(40, 16, minus)
    // Output capacitor across the terminals. Everything that leaves by the + terminal comes
    // back by the − one, through the IOUT meter and then the current shunt.
    p(CAPACITOR, "C${n}02", 48, 6, value = "47u")
    lb(48, 10, minus)
    wr(48, 10, 63, 10)
    // The panel's voltmeter across the terminals, then its ammeter, as on a bench supply.
    if (ch.negative) p(LCD_VOLTMETER, "VOUT$n", 63, 10, U) else p(LCD_VOLTMETER, "VOUT$n", 63, 6)
    p(LCD_AMMETER, "IOUT$n", 48, 10)
    p(RESISTOR, "R${n}07", 48, 14, value = ch.rs)
    lb(48, 18, iso)
    lb(60, 6, plus)
    // A load to try it on.
    p(RESISTOR, "RLOAD$n", 54, 6, value = ch.load)
    // The channel floats in the real build. SPICE needs every node referred to ground, so REFn
    // ties the channel's internal common (the shunt's far end) to it; no current flows in it,
    // as nothing else joins the channel to the input.
    p(RESISTOR, "REF$n", 48, 18, value = "1")
    gnd(48, 22)

    // Reference and the three knobs: voltage, current limit, OVP level.
    p(RESISTOR, "R${n}10", -32, 18, value = ch.rref)
    lb(-32, 18, raw)
    lb(-32, 22, ref)
    p(ZENER, "U${n}03", -32, 26, U, "TL431")
    lb(-32, 26, iso)
    // The knobs have pin 1 at the common, so turning one up (0% to 100%) turns its level up.
    p(POT, "VSET$n", -24, 22, U, ch.vSet)
    lb(-24, 18, ref)
    lb(-24, 22, iso)
    wr(-26, 20, -28, 20)
    lb(-28, 20, "vs$n")
    p(RESISTOR, "R${n}11", -16, 18, value = "150k")
    lb(-16, 18, ref)
    p(POT, "ISET$n", -16, 26, U, ch.iSet)
    lb(-16, 26, iso)
    wr(-18, 24, -20, 24)
    lb(-20, 24, "is$n")
    p(POT, "OVPSET$n", -6, 22, U, ch.ovpSet)
    lb(-6, 18, ref)
    lb(-6, 22, iso)
    wr(-8, 20, -10, 20)
    lb(-10, 20, "os$n")

    // Error amps: each pulls the base down through a Schottky, so the one wanting less wins.
    amp(OPAMP5, "U${n}01A", ox + 10, oy + 20, "LM358", raw, iso)
    wr(7, 19, 5, 19)
    lb(5, 19, "fb$n")
    wr(7, 21, 5, 21)
    lb(5, 21, "vs$n")
    wr(13, 20, 17, 20)
    p(SCHOTTKY, "D${n}01", 21, 20, L, "BAT54")
    amp(OPAMP5, "U${n}01B", ox + 10, oy + 28, "LM358", raw, iso)
    wr(7, 27, 5, 27)
    lb(5, 27, minus)
    wr(7, 29, 5, 29)
    lb(5, 29, "is$n")
    wr(13, 28, 17, 28)
    p(SCHOTTKY, "D${n}02", 21, 28, L, "BAT54")
    wr(21, 20, 23, 20, 23, 31, 34, 31, 34, 27)
    wr(21, 28, 23, 28)
    lb(30, 31, b)

    // 1 mA current-source pull-up on the base, so the amps can pull it right down for 0 V out.
    p(DIODE, "D${n}03", 26, 17, value = "1N4148")
    lb(26, 17, raw)
    p(DIODE, "D${n}04", 26, 21, value = "1N4148")
    p(RESISTOR, "R${n}12", 26, 25, value = "22k")
    lb(26, 29, iso)
    wr(26, 25, 31, 25)
    p(PNP, "Q${n}02", 33, 25, value = "2N3906")
    p(RESISTOR, "R${n}13", 34, 19, value = "680")
    lb(34, 19, raw)

    // OVP: the LM393's output is released above the OVP knob's level, and D05 and R15 then
    // hold its + input high, so it stays latched whatever the output does next. RESETn, a push
    // button, resets it.
    amp(COMPARATOR, "U${n}02", ox + 10, oy + 40, "LM393", raw, iso)
    wr(7, 39, 5, 39)
    lb(5, 39, "os$n")
    wr(7, 41, 1, 41)
    lb(5, 41, "ov$n")
    p(SPST, "RESET$n", 1, 41, value = "open")
    lb(1, 45, iso)
    p(RESISTOR, "R${n}14", 17, 36, value = ch.rpu)
    lb(17, 36, raw)
    wr(13, 40, 32, 40)
    lb(21, 40, "ovp$n")
    wr(20, 40, 20, 46)
    p(DIODE, "D${n}05", 20, 46, L, "1N4148")
    p(RESISTOR, "R${n}15", 16, 46, L, "1k")
    wr(12, 46, 5, 46, 5, 41)
    // Latched: the crowbar shorts the terminals, Q03 pulls the current limit's setting to
    // zero (so the CC amp turns the TIP120 off gently, without reverse-biasing its base), and
    // the LED lights.
    p(RESISTOR, "R${n}18", 24, 40, value = "47k")
    lb(24, 44, "cg$n")
    p(RESISTOR, "R${n}19", 24, 44, value = "47k")
    lb(24, 48, iso)
    p(RESISTOR, "R${n}20", 28, 40, value = ch.rled)
    p(LED, "D${n}06", 28, 44, value = "LED_RED")
    lb(28, 48, iso)
    p(RESISTOR, "R${n}16", 32, 40, H, "22k")
    wr(36, 40, 41, 40)
    p(RESISTOR, "R${n}17", 37, 40, value = "10k")
    lb(37, 44, iso)
    p(NPN, "Q${n}03", 43, 40, value = "2N3904")
    lb(44, 38, "is$n")
    wr(44, 42, 44, 44)
    lb(44, 44, iso)
}

/**
 * A triple bench supply from one 24 V input: +0–20 V and −0–20 V at up to 0.7 A, and 0–5 V
 * at up to 1 A, each galvanically isolated by its own DC-DC module, each with an adjustable
 * current limit and adjustable latching over-voltage protection. About 33 W out at most.
 */
internal fun triplePowerSupply(): Circuit = Sheet().run {
    // Single input: a 24 V adapter (or a USB-C PD trigger at 20 V), fuse, reverse-polarity
    // diode, then a bus down the left to the three modules.
    part(VOLTAGE, "VIN", -64, -8, value = "PWL(0 0 5m 24)")
    ground(-64, -4)
    w(-64, -8, -64, -12, -62, -12)
    part(RESISTOR, "F1", -62, -12, H, "50m")
    w(-58, -12, -56, -12)
    part(SCHOTTKY, "D1", -56, -12, H, "1N5822")
    w(-52, -12, -50, -12)
    part(AMMETER, "AMIN", -50, -12, H)
    w(-46, -12, -38, -12)
    label(-40, -12, "bus")
    part(CAPACITOR, "C1", -42, -12, value = "470u")
    ground(-42, -8)
    w(-38, -12, -38, 0)
    w(-38, 0, -38, PITCH)
    w(-38, PITCH, -38, 2 * PITCH)

    val channels = listOf(
        // +0–20 V, 0.7 A: set to 12 V, 0.5 A limit, OVP at 14 V, into 47 Ω.
        PsuChannel(1, 24, "82k", "11.5k", "91k", "11.5k", "0.22", "4.7k", "4.7k", "10k", "10k 59.15%", "10k 70.5%", "10k 63%", "47", negative = false),
        // −0–20 V, 0.7 A: set to 15 V with a 0.25 A limit, into 47 Ω, so it is current limiting.
        PsuChannel(2, 24, "82k", "11.5k", "91k", "11.5k", "0.22", "4.7k", "4.7k", "10k", "10k 73.95%", "10k 35.3%", "10k 80.94%", "47", negative = true),
        // 0–5 V, 1 A: set to 5.0 V, 0.8 A limit, OVP at 5.5 V, into 10 Ω.
        PsuChannel(3, 9, "10.5k", "10k", "14k", "10k", "0.15", "1.5k", "1.5k", "3.3k", "10k 97.75%", "10k 76.9%", "10k 91.85%", "10", negative = false),
    )
    channels.forEachIndexed { i, ch -> channel(ch, 0, PITCH * i) }
    // On each + terminal. They read against the channel's internal common, so they include the
    // shunt's drop, and CH2's reads its size; the VOUTn meters read the terminals.
    probe(60, 6)
    probe(60, PITCH + 6)
    probe(60, 2 * PITCH + 6)

    // Notes on the sheet: what each channel is, and how to work it.
    part(TEXT, "TXT1", -64, -20, value = "One input: a 24 V 2.5 A adapter,\nor a USB-C PD trigger at 20 V")
    listOf("CH1  +0–20 V, 0.7 A", "CH2  −0–20 V, 0.7 A  (its + terminal is the common)", "CH3  0–5 V, 1 A").forEachIndexed { i, title ->
        part(TEXT, "TXT${i + 2}", -34, PITCH * i - 6, value = title)
    }
    part(
        TEXT, "TXT5", -34, 30,
        value = "VSET: volts · ISET: current limit · OVPSET: trip level\nWhile live, turn them from Controls; RESET clears a trip",
    )

    circuit(
        "Triple bench power supply",
        AnalysisConfig(AnalysisType.TRAN, tranStop = "30m", tranStep = "20u", tranMaxStep = "20u", tranUic = true),
        directives = """
            |* Triple isolated bench supply from one 24 V input (a 24 V 2.5 A adapter, or a USB-C PD
            |* trigger board set to 20 V: the modules take 9-36 V).
            |* CH1 +0-20 V 0.7 A, CH2 -0-20 V 0.7 A, CH3 0-5 V 1 A; about 33 W out at most.
            |* Isolation: each channel has its own DC-DC module (CH1, CH2: Mornsun URB2424YMD-20WR3,
            |*   24 V 0.83 A; CH3: URB2409YMD-10WR3, 9 V 1.1 A; 1.5 kV isolation). The modules are
            |*   modelled by B1M-B3M (output) and B1I-B3I (input power). Since the channels float,
            |*   CH2 is the same circuit with its + terminal as common. REF1-REF3 only give SPICE a
            |*   reference for each floating channel; no current flows in them. The probes read
            |*   against that internal common (shunt drop included; CH2's shows its size); the
            |*   VOUTn and IOUTn panel meters read the terminals.
            |* Each channel: TL431 2.495 V reference; LM358 CV and CC amps diode-ORed (BAT54) onto a
            |*   TIP120 follower fed by a 1 mA current source; 0.22/0.15 ohm low-side shunt.
            |*   VSETn sets the voltage, ISETn the current limit (OCP), OVPSETn the OVP level. Above it
            |*   the LM393 trips and latches itself through Dn05/Rn15; it fires the IRLZ44N crowbar
            |*   across the terminals, pulls the current limit to about 0.1 A (Qn03) and lights
            |*   Dn06. RESETn (a push button) resets it.
            |* B1M-B3M start the outputs softly, around 7 ms, as real modules do.
            |* abstol: live mode senses the current in every pin; 1 nA (well under any current here)
            |*   lets those sense currents converge as an OVP latch snaps over.
            |.options abstol=1n
            |* Heat: a shorted 20 V channel at full current puts 17 W into its TIP120 - use a
            |*   heatsink of 3 C/W or better, or a fan.
        """.trimMargin(),
    )
}
