package io.github.l0gic0l.pepperjack.spice

import java.util.Locale
import kotlin.math.PI

/** Groups of interchangeable models; a part's model picker shows the families its symbol accepts. */
enum class ModelFamily(val title: String) {
    DIODE("Rectifier & signal diodes"),
    SCHOTTKY("Schottky & germanium diodes"),
    ZENER("Zener diodes"),
    LED("LEDs"),
    NPN("NPN transistors"),
    PNP("PNP transistors"),
    NJF("N-channel JFETs"),
    PJF("P-channel JFETs"),
    NMOS("N-channel MOSFETs"),
    PMOS("P-channel MOSFETs"),
    OPAMP("Op-amps"),
    COMPARATOR("Comparators"),
    REGULATOR("Voltage regulators"),
    REGULATOR_ADJ("Adjustable regulators"),
    TIMER("555 timers"),
    OPTO("Optocouplers"),
    CRYSTAL("Crystals"),
    SWITCH("Switch models"),
    LOGIC("Digital logic (XSPICE)"),
}

/** A built-in model: a `.model` card or a complete `.subckt … .ends` block. */
data class SpiceModel(val name: String, val family: ModelFamily, val description: String, val text: String) {
    val isSubckt: Boolean get() = text.trimStart().startsWith(".subckt", ignoreCase = true)

    /** The `.model` type (D, NPN, VDMOS, …), or SUBCKT. */
    val type: String
        get() = if (isSubckt) "SUBCKT" else {
            text.trim().split(Regex("""\s+"""), limit = 3).getOrNull(2)?.substringBefore('(')?.uppercase(Locale.ROOT) ?: ""
        }

    /** Subcircuit port names in order; empty for `.model` cards. */
    val pins: List<String> get() = if (isSubckt) SubcktHeader.parse(text)?.pins.orEmpty() else emptyList()
}

/** Name and ports of a `.subckt` line (continuation lines already joined). */
data class SubcktHeader(val name: String, val pins: List<String>) {
    companion object {
        private val header = Regex("""(?im)^[ \t]*\.subckt[ \t]+(\S+)(.*)$""")

        fun parse(text: String): SubcktHeader? {
            val joined = LtspiceCompat.joinContinuations(text.lines()).joinToString("\n")
            val match = header.find(joined) ?: return null
            val pins = match.groupValues[2].trim().split(Regex("""\s+"""))
                .takeWhile { it.isNotEmpty() && '=' !in it && !it.equals("params:", true) && !it.startsWith("params:", true) }
            return SubcktHeader(match.groupValues[1], pins)
        }

        fun parseAll(text: String): List<SubcktHeader> {
            val joined = LtspiceCompat.joinContinuations(text.lines()).joinToString("\n")
            return header.findAll(joined).mapNotNull { parse(it.value) }.toList()
        }
    }
}

/**
 * Models bundled with the schematic parts. Discrete devices use datasheet-typical
 * parameters; ICs are behavioural macro-models that capture the datasheet headline
 * figures (gain-bandwidth, slew rate, output swing, dropout, current limits,
 * propagation delay) rather than every second-order effect. Every model is checked
 * against those figures in desktop ngspice. Use a vendor model from the Model library
 * when exact behaviour matters.
 */
object SpiceModels {
    const val IDEAL_OPAMP = "PJ_OPAMP_IDEAL"
    const val OPAMP = "PJ_OPAMP"

    private val all: Map<String, SpiceModel> by lazy {
        (discretes() + opAmps() + comparators() + regulators() + timers() + optos() + crystals() + logic())
            .associateBy { it.name.uppercase(Locale.ROOT) }
    }

    fun find(name: String): SpiceModel? = all[name.trim().uppercase(Locale.ROOT)]

    val models: Collection<SpiceModel> get() = all.values

    val names: List<String> get() = all.values.map { it.name }

    fun family(family: ModelFamily): List<SpiceModel> = all.values.filter { it.family == family }

    val idealOpAmp = """
        .subckt PJ_OPAMP_IDEAL inp inn out
        * Ideal op-amp: open-loop gain 1e6, no bandwidth or supply limits
        E1 out 0 inp inn 1e6
        .ends PJ_OPAMP_IDEAL
    """.trimIndent()

    // ---- discretes ----

    private fun card(name: String, family: ModelFamily, description: String, type: String, params: String) =
        SpiceModel(name, family, description, ".model $name $type($params)")

    private fun discretes(): List<SpiceModel> = buildList {
        val d = ModelFamily.DIODE
        add(card("1N4148", d, "Fast signal diode, 100 V, 200 mA, 4 ns", "D", "IS=4.352n N=1.906 RS=0.6458 BV=110 IBV=100u CJO=0.7048p VJ=0.869 M=0.03 FC=0.5 TT=3.48n"))
        add(card("1N914", d, "Fast signal diode, 100 V, 200 mA", "D", "IS=2.52n N=1.752 RS=0.568 BV=100 IBV=100u CJO=4p VJ=0.75 M=0.4 TT=20n"))
        add(card("1N4001", d, "Rectifier, 50 V, 1 A", "D", "IS=7.02767n N=1.80803 RS=34.15m EG=1.05743 XTI=5 BV=50 IBV=5u CJO=10p VJ=0.7 M=0.5 FC=0.5 TT=100n"))
        add(card("1N4007", d, "Rectifier, 1000 V, 1 A", "D", "IS=7.02767n N=1.80803 RS=34.15m EG=1.05743 XTI=5 BV=1000 IBV=5u CJO=10p VJ=0.7 M=0.5 FC=0.5 TT=100n"))
        add(card("1N5408", d, "Rectifier, 1000 V, 3 A", "D", "IS=63n N=1.7 RS=14.1m BV=1000 IBV=10u CJO=53p VJ=0.34 M=0.45 TT=4.32u"))

        val s = ModelFamily.SCHOTTKY
        add(card("1N5817", s, "Schottky, 20 V, 1 A, 0.45 V at 1 A", "D", "IS=0.77u N=1.1 RS=50m BV=20 IBV=1m CJO=110p VJ=0.35 M=0.4 EG=0.69 XTI=2 TT=5n"))
        add(card("1N5819", s, "Schottky, 40 V, 1 A, 0.42 V at 1 A", "D", "IS=31.7u N=1.373 RS=51m EG=0.69 XTI=2 BV=40 IBV=1m CJO=110p VJ=0.33 M=0.35 TT=5n"))
        add(card("1N5822", s, "Schottky, 40 V, 3 A, 0.53 V at 3 A", "D", "IS=0.92u N=1.2 RS=20m BV=40 IBV=1m CJO=300p VJ=0.35 M=0.45 EG=0.69 XTI=2 TT=5n"))
        add(card("BAT54", s, "Small-signal Schottky, 30 V, 200 mA, 0.32 V at 1 mA", "D", "IS=97n N=1.34 RS=3.2 BV=30 IBV=2u CJO=10p VJ=0.3 M=0.3 EG=0.69 XTI=2 TT=1n"))
        add(card("1N5711", s, "RF Schottky, 70 V, 15 mA, 2 pF", "D", "IS=0.27n N=1.05 RS=34 BV=70 IBV=10u CJO=2p VJ=0.6 M=0.5 EG=0.69 XTI=2 TT=0.1n"))
        add(card("1N34A", s, "Germanium point-contact, 0.3 V at 1 mA", "D", "IS=0.2u N=1.3 RS=7 BV=60 IBV=10u CJO=1p VJ=0.2 M=0.3 EG=0.67 TT=1n"))

        // Zeners: breakdown placed so Vz is exact at the datasheet test current Izt.
        listOf(
            Triple("1N4728A", 3.3, 76e-3), Triple("1N4732A", 4.7, 53e-3), Triple("1N4733A", 5.1, 49e-3),
            Triple("1N4734A", 5.6, 45e-3), Triple("1N4735A", 6.2, 41e-3), Triple("1N4739A", 9.1, 28e-3),
            Triple("1N4740A", 10.0, 25e-3), Triple("1N4742A", 12.0, 21e-3), Triple("1N4744A", 15.0, 17e-3),
            Triple("1N4746A", 18.0, 14e-3), Triple("1N4750A", 27.0, 9.5e-3),
            Triple("1N5231B", 5.1, 20e-3), Triple("1N5242B", 12.0, 20e-3),
        ).forEach { (name, vz, izt) ->
            val zzt = zenerImpedance.getValue(name)
            val rs = 0.8 * zzt
            val watts = if (name.startsWith("1N52")) "0.5 W" else "1 W"
            val cjo = maxOf(20.0, 200.0 * 5.1 / vz)
            add(
                card(
                    name, ModelFamily.ZENER, "${fmt(vz)} V zener, $watts", "D",
                    "IS=1n N=1.5 RS=${fmt(rs)} BV=${fmt(vz - izt * rs)} IBV=${fmt(izt)} NBV=1.5 CJO=${fmt(cjo)}p VJ=0.75 M=0.33 TT=50n",
                ),
            )
        }
        // Back-compat names from the first release.
        add(card("1N4733", ModelFamily.ZENER, "5.1 V zener, 1 W", "D", "IS=1n N=1.5 RS=1 BV=5.1 IBV=49m CJO=200p VJ=0.75 M=0.33"))
        add(card("1N4742", ModelFamily.ZENER, "12 V zener, 1 W", "D", "IS=1n N=1.5 RS=2 BV=12 IBV=21m CJO=80p VJ=0.75 M=0.33"))

        val l = ModelFamily.LED
        add(card("LED_RED", l, "Red LED, 1.9 V at 20 mA", "D", "IS=1e-20 N=1.7 RS=2.5 BV=5 IBV=10u CJO=40p"))
        add(card("LED_YELLOW", l, "Yellow LED, 2.0 V at 20 mA", "D", "IS=1.3e-20 N=1.8 RS=2.5 BV=5 IBV=10u CJO=40p"))
        add(card("LED_GREEN", l, "Green LED (GaP), 2.1 V at 20 mA", "D", "IS=1.2e-19 N=2 RS=3 BV=5 IBV=10u CJO=40p"))
        add(card("LED_BLUE", l, "Blue LED, 3.0 V at 20 mA", "D", "IS=6.3e-22 N=2.5 RS=5 BV=5 IBV=10u CJO=40p"))
        add(card("LED_WHITE", l, "White LED, 3.1 V at 20 mA", "D", "IS=2.6e-20 N=2.8 RS=6 BV=5 IBV=10u CJO=40p"))
        add(card("LED_IR", l, "Infrared LED (940 nm), 1.25 V at 20 mA", "D", "IS=3.4e-16 N=1.5 RS=1 BV=5 IBV=10u CJO=30p"))

        val n = ModelFamily.NPN
        add(card("2N3904", n, "General purpose, 40 V, 200 mA, hFE 100–300", "NPN", "IS=6.734f XTI=3 EG=1.11 VAF=74.03 BF=416.4 NE=1.259 ISE=6.734f IKF=66.78m XTB=1.5 BR=0.7371 NC=2 ISC=0 IKR=0 RC=1 CJC=3.638p MJC=0.3085 VJC=0.75 FC=0.5 CJE=4.493p MJE=0.2593 VJE=0.75 TR=239.5n TF=301.2p ITF=0.4 VTF=4 XTF=2 RB=10"))
        add(card("2N2222", n, "General purpose, 40 V, 600 mA, hFE 100–300", "NPN", "IS=14.34f XTI=3 EG=1.11 VAF=74.03 BF=255.9 NE=1.307 ISE=14.34f IKF=0.2847 XTB=1.5 BR=6.092 NC=2 ISC=0 IKR=0 RC=1 CJC=7.306p MJC=0.3416 VJC=0.75 FC=0.5 CJE=22.01p MJE=0.377 VJE=0.75 TR=46.91n TF=411.1p ITF=0.6 VTF=1.7 XTF=3 RB=10"))
        add(card("BC547B", n, "Low noise, 45 V, 100 mA, hFE 200–450", "NPN", "IS=18f NF=0.9955 ISE=50f NE=1.46 BF=400 IKF=0.14 VAF=80 NR=1.005 ISC=172f NC=1.27 BR=35.5 IKR=0.03 VAR=12.5 RB=0.56 RE=0.6 RC=0.25 CJE=13p TF=0.64n CJC=4p VJC=0.54 MJC=0.33 TR=50.72n"))
        add(card("BC337", n, "45 V, 800 mA, hFE 160–400", "NPN", "IS=41f NF=1 BF=330 VAF=100 IKF=0.8 ISE=50f NE=1.5 BR=15 VAR=20 IKR=0.2 ISC=50f NC=1.3 RB=3 RE=0.15 RC=0.25 CJE=45p VJE=0.7 MJE=0.35 CJC=12p VJC=0.6 MJC=0.4 TF=1.2n TR=50n XTB=1.5"))
        add(card("2N4401", n, "Switching, 40 V, 600 mA, hFE 100–300", "NPN", "IS=26f NF=1 BF=300 VAF=90 IKF=0.45 ISE=26f NE=1.3 BR=4 NC=2 RB=10 RC=0.5 CJE=24p VJE=0.75 MJE=0.36 CJC=11p VJC=0.75 MJC=0.38 TF=0.45n TR=230n XTB=1.5"))
        add(card("2N5551", n, "High voltage, 160 V, 600 mA, hFE 80–250", "NPN", "IS=2.5f NF=1 BF=200 VAF=100 IKF=0.1 ISE=2.5f NE=1.4 BR=5 NC=2 RB=10 RC=1 CJE=20p VJE=0.75 MJE=0.36 CJC=4.5p VJC=0.75 MJC=0.4 TF=0.5n TR=500n XTB=1.5"))
        add(card("BD139", n, "Medium power, 80 V, 1.5 A, hFE 40–250", "NPN", "IS=0.1p NF=1 BF=200 VAF=100 IKF=0.8 ISE=0.1p NE=1.5 BR=10 NC=2 RB=1 RE=0.05 RC=0.1 CJE=100p VJE=0.75 MJE=0.4 CJC=20p VJC=0.7 MJC=0.4 TF=0.8n TR=100n XTB=1.5"))
        add(card("TIP31C", n, "Power, 100 V, 3 A, hFE 25+ at 1 A", "NPN", "IS=2p NF=1 BF=120 VAF=100 IKF=1.2 ISE=2p NE=1.5 BR=5 NC=2 RB=1 RE=0.05 RC=0.1 CJE=300p VJE=0.75 MJE=0.4 CJC=90p VJC=0.7 MJC=0.4 TF=25n TR=500n XTB=1.5"))
        add(card("TIP41C", n, "Power, 100 V, 6 A, hFE 15–75 at 3 A", "NPN", "IS=5p NF=1 BF=100 VAF=100 IKF=2.5 ISE=5p NE=1.5 BR=5 NC=2 RB=0.5 RE=0.03 RC=0.05 CJE=500p VJE=0.75 MJE=0.4 CJC=150p VJC=0.7 MJC=0.4 TF=30n TR=500n XTB=1.5"))
        add(card("2N3055", n, "Power, 60 V, 15 A, hFE 20–70 at 4 A", "NPN", "IS=10p NF=1 BF=80 VAF=100 IKF=5 ISE=10p NE=1.5 BR=5 NC=2 RB=0.5 RE=0.01 RC=0.03 CJE=1n VJE=0.75 MJE=0.4 CJC=300p VJC=0.7 MJC=0.4 TF=60n TR=1u XTB=1.5"))
        add(darlington("TIP120", npn = true))

        val p = ModelFamily.PNP
        add(card("2N3906", p, "General purpose, 40 V, 200 mA, hFE 100–300", "PNP", "IS=1.41f XTI=3 EG=1.11 VAF=18.7 BF=180.7 NE=1.5 ISE=0 IKF=80m XTB=1.5 BR=4.977 NC=2 ISC=0 IKR=0 RC=2.5 CJC=9.728p MJC=0.5776 VJC=0.75 FC=0.5 CJE=8.063p MJE=0.3677 VJE=0.75 TR=33.42n TF=179.3p ITF=0.4 VTF=4 XTF=6 RB=10"))
        add(card("2N2907", p, "General purpose, 60 V, 600 mA, hFE 100–300", "PNP", "IS=650f NF=1 BF=250 VAF=100 IKF=0.3 ISE=650f NE=1.5 BR=6 NC=2 RB=10 RC=0.5 CJE=20p VJE=0.75 MJE=0.4 CJC=8p VJC=0.75 MJC=0.45 TF=0.5n TR=40n XTB=1.5"))
        add(card("BC557B", p, "Low noise, 45 V, 100 mA, hFE 200–450", "PNP", "IS=38.3f NF=0.9872 ISE=12.2f NE=1.4 BF=290 IKF=0.15 VAF=21.11 NR=0.9902 ISC=112f NC=1.2 BR=6.1 IKR=0.08 VAR=20 RB=1 RE=0.69 RC=0.5 CJE=17.9p VJE=0.6 MJE=0.39 TF=0.6n CJC=9.7p VJC=0.48 MJC=0.37 TR=100n"))
        add(card("BC327", p, "45 V, 800 mA, hFE 160–400", "PNP", "IS=60f NF=1 BF=300 VAF=60 IKF=0.8 ISE=60f NE=1.5 BR=10 VAR=20 IKR=0.2 RB=3 RE=0.15 RC=0.3 CJE=50p VJE=0.7 MJE=0.35 CJC=18p VJC=0.6 MJC=0.4 TF=1.3n TR=60n XTB=1.5"))
        add(card("2N4403", p, "Switching, 40 V, 600 mA, hFE 100–300", "PNP", "IS=30f NF=1 BF=250 VAF=80 IKF=0.45 ISE=30f NE=1.3 BR=5 NC=2 RB=10 RC=0.5 CJE=30p VJE=0.75 MJE=0.36 CJC=15p VJC=0.75 MJC=0.4 TF=0.5n TR=250n XTB=1.5"))
        add(card("2N5401", p, "High voltage, 150 V, 600 mA, hFE 60–240", "PNP", "IS=3f NF=1 BF=180 VAF=100 IKF=0.1 ISE=3f NE=1.4 BR=5 NC=2 RB=10 RC=1 CJE=22p VJE=0.75 MJE=0.36 CJC=6p VJC=0.75 MJC=0.4 TF=0.6n TR=500n XTB=1.5"))
        add(card("BD140", p, "Medium power, 80 V, 1.5 A, hFE 40–250", "PNP", "IS=0.12p NF=1 BF=200 VAF=80 IKF=0.8 ISE=0.12p NE=1.5 BR=10 NC=2 RB=1 RE=0.05 RC=0.1 CJE=110p VJE=0.75 MJE=0.4 CJC=30p VJC=0.7 MJC=0.4 TF=0.9n TR=100n XTB=1.5"))
        add(card("TIP32C", p, "Power, 100 V, 3 A, hFE 25+ at 1 A", "PNP", "IS=2.5p NF=1 BF=120 VAF=100 IKF=1.2 ISE=2.5p NE=1.5 BR=5 NC=2 RB=1 RE=0.05 RC=0.1 CJE=300p VJE=0.75 MJE=0.4 CJC=120p VJC=0.7 MJC=0.4 TF=25n TR=500n XTB=1.5"))
        add(card("TIP42C", p, "Power, 100 V, 6 A, hFE 15–75 at 3 A", "PNP", "IS=6p NF=1 BF=100 VAF=100 IKF=2.5 ISE=6p NE=1.5 BR=5 NC=2 RB=0.5 RE=0.03 RC=0.05 CJE=500p VJE=0.75 MJE=0.4 CJC=200p VJC=0.7 MJC=0.4 TF=30n TR=500n XTB=1.5"))
        add(darlington("TIP125", npn = false))

        add(card("2N5457", ModelFamily.NJF, "General purpose, IDSS 3 mA, Vp −1.5 V", "NJF", "VTO=-1.5 BETA=1.33m LAMBDA=2.3m RD=1 RS=1 CGS=2.2p CGD=2.2p PB=1 IS=180f"))
        add(card("2N3819", ModelFamily.NJF, "General purpose, IDSS 12 mA, Vp −3 V", "NJF", "VTO=-3 BETA=1.3m LAMBDA=2m RD=1 RS=1 CGS=4p CGD=1.6p PB=1 IS=33f"))
        add(card("J310", ModelFamily.NJF, "VHF amplifier, IDSS 30 mA, Vp −3 V", "NJF", "VTO=-3 BETA=3.3m LAMBDA=14m RD=1 RS=1 CGS=5p CGD=2.5p PB=1 IS=0.1p"))
        add(card("J201", ModelFamily.NJF, "Low current, IDSS 0.6 mA, Vp −0.8 V", "NJF", "VTO=-0.8 BETA=1m LAMBDA=4m RD=1 RS=1 CGS=3p CGD=3p PB=1 IS=0.1p"))
        add(card("2N5460", ModelFamily.PJF, "General purpose, IDSS 3 mA, Vp 2 V", "PJF", "VTO=-2 BETA=0.75m LAMBDA=4m RD=2 RS=2 CGS=5p CGD=5p PB=1 IS=0.1p"))
        add(card("J175", ModelFamily.PJF, "Analog switch, IDSS 20 mA, Vp 4.5 V", "PJF", "VTO=-4.5 BETA=1m LAMBDA=10m RD=2 RS=2 CGS=10p CGD=10p PB=1 IS=0.1p"))

        val nm = ModelFamily.NMOS
        add(card("2N7000", nm, "Small signal, 60 V, 200 mA, 1.8 Ω at 10 V", "VDMOS", "Vto=2.1 Kp=0.25 Lambda=0.01 Rd=0.9 Rs=0.4 Rg=3 Rb=1.2 Cgdmax=30p Cgdmin=4p Cgs=40p Cjo=35p Is=1p Ksubthres=0.1"))
        add(card("2N7002", nm, "Small signal SOT-23, 60 V, 300 mA, 1.4 Ω at 10 V", "VDMOS", "Vto=1.8 Kp=0.3 Lambda=0.01 Rd=0.7 Rs=0.3 Rg=3 Rb=1 Cgdmax=20p Cgdmin=3p Cgs=25p Cjo=20p Is=1p Ksubthres=0.1"))
        add(card("BSS138", nm, "Logic level, 50 V, 200 mA, 0.9 Ω at 4.5 V", "VDMOS", "Vto=1.3 Kp=0.6 Lambda=0.01 Rd=0.25 Rs=0.15 Rg=3 Rb=0.5 Cgdmax=12p Cgdmin=2p Cgs=25p Cjo=15p Is=1p Ksubthres=0.1"))
        add(card("AO3400", nm, "Logic level, 30 V, 5.7 A, 26 mΩ at 4.5 V", "VDMOS", "Vto=1.05 Kp=22 Lambda=0.01 Rd=9m Rs=4m Rg=1.5 Rb=20m Cgdmax=100p Cgdmin=30p Cgs=600p Cjo=100p Is=10p Ksubthres=0.1"))
        add(card("IRF530N", nm, "Power, 100 V, 17 A, 90 mΩ at 10 V", "VDMOS", "Vto=3 Kp=3.5 Lambda=5m Rd=40m Rs=10m Rg=2 Rb=30m Cgdmax=300p Cgdmin=60p Cgs=700p Cjo=200p Is=1n Ksubthres=0.1"))
        add(card("IRF540N", nm, "Power, 100 V, 33 A, 44 mΩ at 10 V", "VDMOS", "Vto=3 Kp=7.5 Lambda=5m Rd=18m Rs=7m Rg=2 Rb=15m Cgdmax=500p Cgdmin=100p Cgs=1.2n Cjo=400p Is=1n Ksubthres=0.1"))
        add(card("IRFZ44N", nm, "Power, 55 V, 49 A, 15 mΩ at 10 V", "VDMOS", "Vto=3.2 Kp=45 Lambda=5m Rd=8m Rs=4m Rg=2 Rb=10m Cgdmax=1.5n Cgdmin=0.1n Cgs=1.4n Cjo=0.8n Is=0.1n Ksubthres=0.1"))
        add(card("IRLZ44N", nm, "Logic-level power, 55 V, 47 A, 23 mΩ at 5 V", "VDMOS", "Vto=1.5 Kp=20.4 Lambda=5m Rd=6m Rs=2m Rg=2 Rb=10m Cgdmax=600p Cgdmin=100p Cgs=1.5n Cjo=500p Is=1n Ksubthres=0.1"))
        add(card("IRF3205", nm, "Power, 55 V, 110 A, 8 mΩ at 10 V", "VDMOS", "Vto=3 Kp=35 Lambda=5m Rd=3m Rs=1m Rg=1.5 Rb=5m Cgdmax=1n Cgdmin=200p Cgs=3n Cjo=1.5n Is=5n Ksubthres=0.1"))
        add(card("PJ_NMOS", nm, "Generic level-1 NMOS, Vt 1 V", "NMOS", "LEVEL=1 VTO=1 KP=2m LAMBDA=0.02"))

        val pm = ModelFamily.PMOS
        add(card("BS250", pm, "Small signal, −45 V, −250 mA, 6 Ω at −10 V", "VDMOS", "pchan Vto=-2.4 Kp=0.15 Lambda=0.02 Rd=4 Rs=1 Rg=3 Rb=2 Cgdmax=40p Cgdmin=5p Cgs=50p Cjo=40p Is=1p"))
        add(card("BSS84", pm, "Small signal SOT-23, −50 V, −130 mA, 3 Ω at −5 V", "VDMOS", "pchan Vto=-1.6 Kp=0.2 Lambda=0.02 Rd=1 Rs=0.5 Rg=3 Rb=1 Cgdmax=10p Cgdmin=2p Cgs=20p Cjo=10p Is=1p"))
        add(card("AO3401", pm, "Logic level, −30 V, −4 A, 50 mΩ at −4.5 V", "VDMOS", "pchan Vto=-0.9 Kp=16.8 Lambda=0.01 Rd=25m Rs=8m Rg=2 Rb=30m Cgdmax=150p Cgdmin=50p Cgs=600p Cjo=150p Is=10p"))
        add(card("IRF9540", pm, "Power, −100 V, −23 A, 95 mΩ at −10 V", "VDMOS", "pchan Vto=-3.5 Kp=12 Lambda=10m Rd=60m Rs=20m Rg=3 Rb=30m Cgdmax=1.2n Cgdmin=0.15n Cgs=1.3n Cjo=0.9n Is=0.1n"))
        add(card("IRF9Z34N", pm, "Power, −55 V, −19 A, 100 mΩ at −10 V", "VDMOS", "pchan Vto=-3 Kp=5 Lambda=10m Rd=60m Rs=10m Rg=3 Rb=30m Cgdmax=600p Cgdmin=100p Cgs=700p Cjo=400p Is=0.5n"))
        add(card("IRF4905", pm, "Power, −55 V, −74 A, 20 mΩ at −10 V", "VDMOS", "pchan Vto=-3 Kp=25 Lambda=10m Rd=10m Rs=4m Rg=2 Rb=10m Cgdmax=1n Cgdmin=300p Cgs=3n Cjo=1.5n Is=2n"))
        add(card("PJ_PMOS", pm, "Generic level-1 PMOS, Vt −1 V", "PMOS", "LEVEL=1 VTO=-1 KP=1m LAMBDA=0.02"))

        add(card("PJ_SW", ModelFamily.SWITCH, "On above 0.6 V, off below 0.4 V; 1 Ω / 1 MΩ", "SW", "Ron=1 Roff=1Meg Vt=0.5 Vh=0.1"))
    }

    private val zenerImpedance = mapOf(
        "1N4728A" to 10.0, "1N4732A" to 8.0, "1N4733A" to 7.0, "1N4734A" to 5.0, "1N4735A" to 2.0,
        "1N4739A" to 5.0, "1N4740A" to 7.0, "1N4742A" to 9.0, "1N4744A" to 14.0, "1N4746A" to 20.0,
        "1N4750A" to 35.0, "1N5231B" to 17.0, "1N5242B" to 30.0,
    )

    private fun darlington(name: String, npn: Boolean): SpiceModel {
        val type = if (npn) "NPN" else "PNP"
        val diode = if (npn) "D1 e c PJD" else "D1 c e PJD"
        return SpiceModel(
            name, if (npn) ModelFamily.NPN else ModelFamily.PNP,
            "${if (npn) "NPN" else "PNP"} Darlington, 60 V, 5 A, hFE 1000+",
            """
            .subckt $name c b e
            * Darlington pair with base-emitter resistors and the integral C-E diode
            Q1 c b e1 PJQ1
            Q2 c e1 e PJQ2
            R1 b e1 8k
            R2 e1 e 120
            $diode
            .model PJQ1 $type(IS=50f BF=80 VAF=100 IKF=0.3 RB=10 RC=1 CJE=50p CJC=30p TF=5n TR=500n)
            .model PJQ2 $type(IS=1p BF=60 VAF=100 IKF=2 ISE=1p NE=1.5 RB=0.5 RC=0.07 RE=0.03 CJE=500p CJC=200p TF=30n TR=1u)
            .model PJD D(IS=1p RS=50m BV=60 IBV=1m TT=1u)
            .ends $name
            """.trimIndent(),
        )
    }

    // ---- op-amps ----

    /**
     * Smooth class-AB output stage: sources from [vp], sinks into [vn], through [rout] up to
     * [ilim]. The few picofarads of output capacitance let ngspice shorten its time step
     * through fast edges instead of failing to converge.
     */
    private fun outputStage(target: String, vp: String, vn: String, rout: Double, ilim: Double) = """
        Bo o 0 V=${fmt(ilim)}*tanh(($target-V(out))/${fmt(rout * ilim)})
        Bsrc $vp out I=(V(o)+sqrt(V(o)*V(o)+1e-8))/2
        Bsnk out $vn I=(sqrt(V(o)*V(o)+1e-8)-V(o))/2
        Rlk out $vn 1e9
        Cout out $vn 5p
    """.trimIndent()

    private class OpAmpSpec(
        val name: String, val description: String,
        val aol: Double, val gbw: Double, val slew: Double,
        val headroomHigh: Double, val headroomLow: Double,
        val supplyCurrent: Double, val currentLimit: Double, val rout: Double,
        val rin: Double, val biasCurrent: Double,
    )

    private fun opAmp(s: OpAmpSpec): SpiceModel {
        // Slew-limited transconductance into an integrator: unity gain at gbw, dominant pole at gbw/aol.
        val c = 10e-12
        val gm = 2 * PI * s.gbw * c
        val islew = s.slew * c
        // Soft clamps hold the integrator inside the output swing so it recovers quickly from saturation.
        val above = "(V(n1)-V(vp)+${fmt(s.headroomHigh)})"
        val below = "(V(vn)+${fmt(s.headroomLow)}-V(n1))"
        return SpiceModel(
            s.name, ModelFamily.OPAMP, s.description,
            """
            |.subckt ${s.name} inp inn vp vn out
            |* Macro-model: Aol ${fmt(s.aol)}, GBW ${fmt(s.gbw)}Hz, SR ${fmt(s.slew / 1e6)} V/us, swing V+ -${fmt(s.headroomHigh)} / V- +${fmt(s.headroomLow)}
            |Rin inp inn ${fmt(s.rin)}
            |Ibp inp vn ${fmt(s.biasCurrent)}
            |Ibn inn vn ${fmt(s.biasCurrent)}
            |B1 0 n1 I=${fmt(islew)}*tanh(${fmt(gm)}*V(inp,inn)/${fmt(islew)})
            |R1 n1 0 ${fmt(s.aol / gm)}
            |C1 n1 0 ${fmt(c)}
            |Bcl n1 0 I=($above+sqrt($above*$above+1e-12))/2-($below+sqrt($below*$below+1e-12))/2
            |${outputStage("V(n1)", "vp", "vn", s.rout, s.currentLimit)}
            |Iq vp vn ${fmt(s.supplyCurrent)}
            |.ends ${s.name}
            """.trimMargin(),
        )
    }

    private fun opAmps(): List<SpiceModel> = listOf(
        OpAmpSpec(OPAMP, "Generic: 100 dB, 10 MHz, 10 V/µs, rail-to-rail output", 1e5, 10e6, 10e6, 0.0, 0.0, 1e-3, 40e-3, 50.0, 10e6, 0.0),
        OpAmpSpec("LM358", "Dual, single supply, 1 MHz, 0.3 V/µs, output reaches ground", 1e5, 1e6, 0.3e6, 1.5, 0.01, 0.35e-3, 30e-3, 50.0, 2e6, -20e-9),
        OpAmpSpec("LM324", "Quad, single supply, 1 MHz, 0.5 V/µs, output reaches ground", 1e5, 1.2e6, 0.5e6, 1.5, 0.01, 0.35e-3, 30e-3, 50.0, 2e6, -20e-9),
        OpAmpSpec("TL072", "Dual JFET input, 3 MHz, 13 V/µs, low noise", 2e5, 3e6, 13e6, 1.5, 1.5, 1.4e-3, 20e-3, 50.0, 1e12, 30e-12),
        OpAmpSpec("TL082", "Dual JFET input, 3 MHz, 13 V/µs", 2e5, 3e6, 13e6, 1.5, 1.5, 1.4e-3, 20e-3, 50.0, 1e12, 30e-12),
        OpAmpSpec("LM741", "Classic single, 1 MHz, 0.5 V/µs, ±2 V headroom", 2e5, 1e6, 0.5e6, 2.0, 2.0, 1.7e-3, 25e-3, 75.0, 2e6, 80e-9),
        OpAmpSpec("NE5532", "Dual low-noise audio, 10 MHz, 9 V/µs", 1e5, 10e6, 9e6, 1.5, 1.5, 4e-3, 38e-3, 30.0, 300e3, 200e-9),
        OpAmpSpec("LM833", "Dual audio, 15 MHz, 7 V/µs", 1e5, 15e6, 7e6, 1.5, 1.5, 2.5e-3, 40e-3, 30.0, 175e3, 300e-9),
        OpAmpSpec("OPA2134", "Dual high-fidelity audio, 8 MHz, 20 V/µs, FET input", 1e6, 8e6, 20e6, 1.0, 1.0, 4e-3, 40e-3, 30.0, 1e13, 5e-12),
        OpAmpSpec("MCP6002", "Dual 1.8–6 V CMOS, 1 MHz, 0.6 V/µs, rail-to-rail", 4e5, 1e6, 0.6e6, 0.025, 0.025, 0.1e-3, 23e-3, 100.0, 1e13, 1e-12),
        OpAmpSpec("LMV358", "Dual 2.7–5.5 V, 1 MHz, 1 V/µs, rail-to-rail output", 1e5, 1e6, 1e6, 0.06, 0.06, 0.2e-3, 40e-3, 60.0, 1e12, 15e-9),
    ).map(::opAmp)

    // ---- comparators ----

    private fun comparator(name: String, description: String, openCollector: Boolean, delay: Double, sink: Double, supplyCurrent: Double): SpiceModel {
        // The decision passes through an RC so the output switches after the datasheet propagation delay.
        val cd = delay / (0.69 * 1e3)
        val decision = if (openCollector) "V(inn,inp)" else "V(inp,inn)"
        // Open collector: the sink current saturates at [sink] with a 0.25 V knee, and its slope never
        // vanishes, which keeps Newton iterations stable when the output swings rail to rail.
        val output = if (openCollector) {
            "Bout out vn I=${fmt(sink)}*min(max(V(d2),0),1)*V(out,vn)/(abs(V(out,vn))+0.25)\nRlk out vn 1e9\nCout out vn 5p"
        } else {
            outputStage("(V(vn)+min(max(V(d2),0),1)*V(vp,vn))", "vp", "vn", 30.0, sink)
        }
        return SpiceModel(
            name, ModelFamily.COMPARATOR, description,
            """
            |.subckt $name inp inn vp vn out
            |* ${if (openCollector) "Open-collector output: add a pull-up resistor" else "Push-pull rail-to-rail output"}, ${fmt(delay * 1e9)} ns delay
            |Iq vp vn ${fmt(supplyCurrent)}
            |Bd d 0 V=0.5*(1+tanh(5000*$decision))
            |Rd d d2 1k
            |Cd d2 0 ${fmt(cd)}
            |$output
            |.ends $name
            """.trimMargin(),
        )
    }

    private fun comparators() = listOf(
        comparator("LM393", "Dual, open collector, 300 ns, needs a pull-up", true, 300e-9, 16e-3, 0.4e-3),
        comparator("LM339", "Quad, open collector, 300 ns, needs a pull-up", true, 300e-9, 16e-3, 0.8e-3),
        comparator("LM311", "Fast single, open collector, 200 ns, 50 mA", true, 200e-9, 50e-3, 5e-3),
        comparator("PJ_COMP", "Generic push-pull, rail-to-rail output, 50 ns", false, 50e-9, 20e-3, 0.1e-3),
    )

    // ---- regulators ----

    // Smooth min/max keep Newton iterations away from hard corners at dropout and current limit.
    private const val SMOOTH = ".func smin(a,b,e) {(a+b-sqrt((a-b)*(a-b)+e*e))/2}\n.func smax(a,b,e) {(a+b+sqrt((a-b)*(a-b)+e*e))/2}"

    private fun fixedRegulator(name: String, description: String, vout: Double, dropout: Double, limit: Double, quiescent: Double, negative: Boolean): SpiceModel {
        val body = if (!negative) {
            """
            |Bq in gnd I=${fmt(quiescent)}*tanh(V(in,gnd)/0.5)
            |Bt t 0 V=smin(${fmt(vout)},V(in,gnd)-${fmt(dropout)},0.05)
            |Bp in out I=smin(smax(500*(V(t)-V(out,gnd)),0,1m),${fmt(limit)},1m)
            |Rbl out gnd 100k
            """.trimMargin()
        } else {
            """
            |Bq gnd in I=${fmt(quiescent)}*tanh(V(gnd,in)/0.5)
            |Bt t 0 V=smin(${fmt(vout)},V(gnd,in)-${fmt(dropout)},0.05)
            |Bp out in I=smin(smax(500*(V(t)-V(gnd,out)),0,1m),${fmt(limit)},1m)
            |Rbl gnd out 100k
            """.trimMargin()
        }
        return SpiceModel(
            name, ModelFamily.REGULATOR, description,
            ".subckt $name in gnd out\n* ${if (negative) "−" else ""}${fmt(vout)} V, ${fmt(dropout)} V dropout, ${fmt(limit)} A limit\n$SMOOTH\n$body\n.ends $name",
        )
    }

    private fun regulators(): List<SpiceModel> = listOf(
        fixedRegulator("LM7805", "+5 V, 1.5 A, 2 V dropout", 5.0, 2.0, 2.2, 5e-3, false),
        fixedRegulator("LM7809", "+9 V, 1.5 A, 2 V dropout", 9.0, 2.0, 2.2, 5e-3, false),
        fixedRegulator("LM7812", "+12 V, 1.5 A, 2 V dropout", 12.0, 2.0, 2.2, 5e-3, false),
        fixedRegulator("LM7815", "+15 V, 1.5 A, 2 V dropout", 15.0, 2.0, 2.2, 5e-3, false),
        fixedRegulator("LM7905", "−5 V, 1.5 A, 1.1 V dropout (IN and OUT negative)", 5.0, 1.1, 1.5, 3e-3, true),
        fixedRegulator("LM7912", "−12 V, 1.5 A, 1.1 V dropout (IN and OUT negative)", 12.0, 1.1, 1.5, 3e-3, true),
        fixedRegulator("LM1117_33", "3.3 V LDO, 800 mA, 1.1 V dropout", 3.3, 1.1, 1.0, 5e-3, false),
        fixedRegulator("AMS1117_33", "3.3 V LDO, 1 A, 1.1 V dropout", 3.3, 1.1, 1.2, 5e-3, false),
        fixedRegulator("LM2940_5", "5 V LDO, 1 A, 0.5 V dropout", 5.0, 0.5, 1.5, 10e-3, false),
        fixedRegulator("MCP1700_33", "3.3 V micropower LDO, 250 mA, 0.18 V dropout, 1.6 µA", 3.3, 0.18, 0.4, 1.6e-6, false),
        SpiceModel(
            "LM317", ModelFamily.REGULATOR_ADJ, "Adjustable positive, 1.25–37 V, 1.5 A: Vout = 1.25 (1 + R2/R1)",
            """
            |.subckt LM317 in adj out
            |* 1.25 V between OUT and ADJ, 1.5 V dropout, 1.5 A limit, 50 uA from ADJ
            |$SMOOTH
            |Iadj in adj 50u
            |Bt t 0 V=smin(V(adj)+1.25,V(in)-1.5,0.05)
            |Bp in out I=smin(smax(500*(V(t)-V(out)),0,1m),1.5,1m)
            |Rbl out adj 1Meg
            |.ends LM317
            """.trimMargin(),
        ),
        SpiceModel(
            "LM337", ModelFamily.REGULATOR_ADJ, "Adjustable negative, −1.25 to −37 V, 1.5 A",
            """
            |.subckt LM337 in adj out
            |* -1.25 V between OUT and ADJ, 1.5 V dropout, 1.5 A limit, 65 uA into ADJ
            |$SMOOTH
            |Iadj adj in 65u
            |Bt t 0 V=smax(V(adj)-1.25,V(in)+1.5,0.05)
            |Bp out in I=smin(smax(500*(V(out)-V(t)),0,1m),1.5,1m)
            |Rbl adj out 1Meg
            |.ends LM337
            """.trimMargin(),
        ),
    )

    // ---- 555 ----

    private fun timer555(name: String, description: String, cmos: Boolean): SpiceModel {
        val divider = if (cmos) "33k" else "5k"
        val supply = if (cmos) "34u" else "0.6m"
        val high = if (cmos) "V(vcc,gnd)" else "(V(vcc,gnd)-1.5)"
        val low = if (cmos) "0" else "0.05"
        val target = "(V(gnd)+V(q)*$high+$low*(1-V(q)))"
        return SpiceModel(
            name, ModelFamily.TIMER, description,
            """
            |.subckt $name gnd trig out reset ctrl thr dis vcc
            |* Comparators at 1/3 and 2/3 Vcc set and reset an XSPICE latch; trigger overrides threshold.
            |* A 1 us power-on reset gives the latch a defined state at the operating point.
            |Bq vcc gnd I=$supply*V(vcc,gnd)
            |R1 vcc ctrl $divider
            |R2 ctrl n2 $divider
            |R3 n2 gnd $divider
            |Bc1 c1 0 V=V(thr,ctrl)
            |Bc2 c2 0 V=V(n2,trig)
            |Bc3 c3 0 V=0.7-V(reset,gnd)
            |Vpor por 0 PWL(0 1 1u 1 1.01u -1)
            |Acmp [c1 c2 c3 por] [r_d s_d x_d p_d] pjtadc
            |Aor1 [x_d p_d] xp_d pjtor
            |Ainv1 xp_d nxp_d pjtinv
            |Ainv2 s_d ns_d pjtinv
            |Aand1 [s_d nxp_d] se_d pjtand
            |Aand2 [r_d ns_d] rn_d pjtand
            |Aor2 [rn_d xp_d] re_d pjtor
            |Alat se_d re_d en_d zero_d zero_d q_d qb_d pjtsr
            |Aen en_d pjtone
            |Azero zero_d pjtzero
            |Adac [q_d qb_d] [q qb] pjtdac
            |${outputStage(target, "vcc", "gnd", if (cmos) 30.0 else 10.0, if (cmos) 0.1 else 0.2)}
            |Bdis dis gnd I=V(qb)*50m*V(dis,gnd)/(abs(V(dis,gnd))+0.2)
            |Cdis dis gnd 5p
            |.model pjtadc adc_bridge(in_low=-1n in_high=1n)
            |.model pjtor d_or(rise_delay=1n fall_delay=1n)
            |.model pjtinv d_inverter(rise_delay=1n fall_delay=1n)
            |.model pjtand d_and(rise_delay=1n fall_delay=1n)
            |.model pjtsr d_srlatch(sr_delay=${if (cmos) "20n" else "50n"} enable_delay=1n set_delay=1n reset_delay=1n)
            |.model pjtone d_pullup
            |.model pjtzero d_pulldown
            |.model pjtdac dac_bridge(out_low=0 out_high=1 out_undef=0.5 t_rise=20n t_fall=20n)
            |.ends $name
            """.trimMargin(),
        )
    }

    private fun timers() = listOf(
        timer555("NE555", "Bipolar 555, 4.5–16 V, 200 mA output, high ≈ Vcc − 1.5 V", cmos = false),
        timer555("TLC555", "CMOS 555, 2–15 V, 170 µA, rail-to-rail output", cmos = true),
    )

    // ---- optocouplers ----

    private fun opto(name: String, description: String, gain: Double) = SpiceModel(
        name, ModelFamily.OPTO, description,
        """
        |.subckt $name a k c e
        |* IR LED; photocurrent (CTR/hFE times the LED current) drives the phototransistor base
        |D1 a a1 PJLED
        |Vs a1 k 0
        |Q1 c b e PJPT
        |F1 c b Vs ${fmt(gain)}
        |Rbe b e 10Meg
        |Cbe b e 20p
        |.model PJLED D(IS=3.4e-16 N=1.5 RS=1 BV=6 IBV=10u CJO=30p)
        |.model PJPT NPN(IS=10f BF=113 VAF=100 IKF=50m RC=20 RB=100 CJE=10p CJC=8p TF=10n TR=1u)
        |.ends $name
        """.trimMargin(),
    )

    private fun optos() = listOf(
        opto("PC817", "Phototransistor output, CTR ≈ 110% at 10 mA", 0.0115),
        opto("4N35", "Phototransistor output, CTR ≈ 85% at 10 mA", 0.0088),
    )

    // ---- crystals ----

    private fun crystal(name: String, description: String, frequency: Double, cm: Double, rm: Double, c0: Double): SpiceModel {
        val lm = 1.0 / ((2 * PI * frequency) * (2 * PI * frequency) * cm)
        return SpiceModel(
            name, ModelFamily.CRYSTAL, description,
            """
            |.subckt $name a b
            |* Butterworth-Van Dyke: series resonance at ${fmt(frequency)}Hz
            |Rm a n1 ${fmt(rm)}
            |Lm n1 n2 ${fmt(lm)}
            |Cm n2 b ${fmt(cm)}
            |C0 a b ${fmt(c0)}
            |.ends $name
            """.trimMargin(),
        )
    }

    private fun crystals() = listOf(
        crystal("XTAL_32K768", "32.768 kHz watch crystal", 32768.0, 2e-15, 35e3, 1.4e-12),
        crystal("XTAL_4MEG", "4 MHz", 4e6, 15e-15, 60.0, 4e-12),
        crystal("XTAL_8MEG", "8 MHz", 8e6, 15e-15, 40.0, 4e-12),
        crystal("XTAL_16MEG", "16 MHz", 16e6, 15e-15, 30.0, 5e-12),
        crystal("XTAL_20MEG", "20 MHz", 20e6, 15e-15, 25.0, 5e-12),
    )

    // ---- digital logic ----

    /** Subcircuit name for each gate symbol; the instance passes `vdd=…`. */
    const val AND2 = "PJ_AND2"
    const val NAND2 = "PJ_NAND2"
    const val OR2 = "PJ_OR2"
    const val NOR2 = "PJ_NOR2"
    const val XOR2 = "PJ_XOR2"
    const val NOT = "PJ_NOT"
    const val SCHMITT = "PJ_SCHMITT"
    const val DFF = "PJ_DFF"

    private const val LOGIC_IO = """.model pjladc adc_bridge(in_low={vdd/2} in_high={vdd/2+1u})
.model pjldac dac_bridge(out_low=0 out_high={vdd} out_undef={vdd/2} t_rise=6n t_fall=6n)"""

    private fun gate(name: String, description: String, model: String, inputs: Int) = SpiceModel(
        name, ModelFamily.LOGIC, description,
        if (inputs == 1) {
            """
            |.subckt $name a y params: vdd=5
            |* CMOS-like gate: switches at vdd/2, 25 ohm output, 8 ns delay
            |Ra a 0 1e12
            |Ain [a] [ad] pjladc
            |Ag ad yd pjlg
            |Aout [yd] [yi] pjldac
            |Rout yi y 25
            |$LOGIC_IO
            |.model pjlg $model(rise_delay=8n fall_delay=8n)
            |.ends $name
            """.trimMargin()
        } else {
            """
            |.subckt $name a b y params: vdd=5
            |* CMOS-like gate: switches at vdd/2, 25 ohm output, 8 ns delay
            |Ra a 0 1e12
            |Rb b 0 1e12
            |Ain [a b] [ad bd] pjladc
            |Ag [ad bd] yd pjlg
            |Aout [yd] [yi] pjldac
            |Rout yi y 25
            |$LOGIC_IO
            |.model pjlg $model(rise_delay=8n fall_delay=8n)
            |.ends $name
            """.trimMargin()
        },
    )

    private fun logic(): List<SpiceModel> = listOf(
        gate(AND2, "2-input AND", "d_and", 2),
        gate(NAND2, "2-input NAND", "d_nand", 2),
        gate(OR2, "2-input OR", "d_or", 2),
        gate(NOR2, "2-input NOR", "d_nor", 2),
        gate(XOR2, "2-input XOR", "d_xor", 2),
        gate(NOT, "Inverter", "d_inverter", 1),
        SpiceModel(
            SCHMITT, ModelFamily.LOGIC, "Schmitt-trigger inverter (74HC14-like), thresholds 0.54 and 0.34 × vdd",
            """
            |.subckt $SCHMITT a y params: vdd=5
            |* Hysteresis from a voltage-controlled switch; output buffered through 25 ohm
            |Ra a 0 1e12
            |S1 h 0 a 0 pjlsw
            |Rp vi h 1k
            |Vdd vi 0 {vdd}
            |Bo yi 0 V=V(h)
            |Rout yi y 25
            |.model pjlsw sw(vt={vdd*0.44} vh={vdd*0.1} ron=1 roff=1e9)
            |.ends $SCHMITT
            """.trimMargin(),
        ),
        SpiceModel(
            DFF, ModelFamily.LOGIC, "Rising-edge D flip-flop, 12 ns clock to Q",
            """
            |.subckt $DFF d clk q qn params: vdd=5
            |Rd d 0 1e12
            |Rc clk 0 1e12
            |Ain [d clk] [dd cd] pjladc
            |Aff dd cd zero zero qd qnd pjldff
            |Az zero pjlzero
            |Aout [qd qnd] [qi qni] pjldac
            |Rq qi q 25
            |Rqn qni qn 25
            |$LOGIC_IO
            |.model pjldff d_dff(clk_delay=12n set_delay=12n reset_delay=12n)
            |.model pjlzero d_pulldown
            |.ends $DFF
            """.trimMargin(),
        ),
    )

    /** Compact SPICE number: 1.061e+09 -> 1.061e9, 0.0001 -> 0.0001. */
    internal fun fmt(value: Double): String {
        if (value == 0.0) return "0"
        val text = String.format(Locale.ROOT, "%.5g", value)
        val (mantissa, exponent) = if ('e' in text) text.split('e').let { it[0] to it[1] } else text to null
        val trimmed = if ('.' in mantissa) mantissa.trimEnd('0').trimEnd('.') else mantissa
        return if (exponent == null) trimmed else trimmed + "e" + exponent.toInt()
    }
}
