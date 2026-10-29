package io.github.l0gic0l.pepperjack.spice

/**
 * Generic device models bundled with the schematic parts. Parameters are typical
 * datasheet-level approximations, good for learning and first-pass design; use a
 * vendor model (via the Model library) when exact behaviour matters.
 */
object SpiceModels {
    data class Model(val name: String, val type: String, val card: String)

    private val models = listOf(
        Model("1N4148", "D", ".model 1N4148 D(IS=4.352n N=1.906 RS=0.6458 BV=110 IBV=100u CJO=0.7048p VJ=0.869 M=0.03 FC=0.5 TT=3.48n)"),
        Model("1N4007", "D", ".model 1N4007 D(IS=7.02767n N=1.80803 RS=34.15m EG=1.05743 XTI=5 BV=1000 IBV=50n CJO=10p VJ=0.7 M=0.5 FC=0.5 TT=100n)"),
        Model("1N5819", "D", ".model 1N5819 D(IS=31.7u N=1.373 RS=51m EG=0.69 XTI=2 BV=40 IBV=1m CJO=110p VJ=0.33 M=0.35 TT=5n)"),
        Model("1N4733", "D", ".model 1N4733 D(IS=1n N=1.5 RS=1 BV=5.1 IBV=49m CJO=200p VJ=0.75 M=0.33)"),
        Model("1N4742", "D", ".model 1N4742 D(IS=1n N=1.5 RS=2 BV=12 IBV=21m CJO=80p VJ=0.75 M=0.33)"),
        Model("LED_RED", "D", ".model LED_RED D(IS=1e-20 N=1.7 RS=2.5 BV=5 IBV=10u CJO=40p)"),
        Model("LED_GREEN", "D", ".model LED_GREEN D(IS=1.2e-19 N=2 RS=3 BV=5 IBV=10u CJO=40p)"),
        Model("LED_BLUE", "D", ".model LED_BLUE D(IS=6.3e-22 N=2.5 RS=5 BV=5 IBV=10u CJO=40p)"),
        Model(
            "2N3904", "NPN",
            ".model 2N3904 NPN(IS=6.734f XTI=3 EG=1.11 VAF=74.03 BF=416.4 NE=1.259 ISE=6.734f IKF=66.78m XTB=1.5 " +
                "BR=0.7371 NC=2 ISC=0 IKR=0 RC=1 CJC=3.638p MJC=0.3085 VJC=0.75 FC=0.5 CJE=4.493p MJE=0.2593 " +
                "VJE=0.75 TR=239.5n TF=301.2p ITF=0.4 VTF=4 XTF=2 RB=10)",
        ),
        Model(
            "2N2222", "NPN",
            ".model 2N2222 NPN(IS=14.34f XTI=3 EG=1.11 VAF=74.03 BF=255.9 NE=1.307 ISE=14.34f IKF=0.2847 XTB=1.5 " +
                "BR=6.092 NC=2 ISC=0 IKR=0 RC=1 CJC=7.306p MJC=0.3416 VJC=0.75 FC=0.5 CJE=22.01p MJE=0.377 " +
                "VJE=0.75 TR=46.91n TF=411.1p ITF=0.6 VTF=1.7 XTF=3 RB=10)",
        ),
        Model(
            "BC547B", "NPN",
            ".model BC547B NPN(IS=18f NF=0.9955 ISE=50f NE=1.46 BF=400 IKF=0.14 VAF=80 NR=1.005 ISC=172f NC=1.27 " +
                "BR=35.5 IKR=0.03 VAR=12.5 RB=0.56 RE=0.6 RC=0.25 CJE=13p TF=0.64n CJC=4p VJC=0.54 MJC=0.33 TR=50.72n)",
        ),
        Model(
            "2N3906", "PNP",
            ".model 2N3906 PNP(IS=1.41f XTI=3 EG=1.11 VAF=18.7 BF=180.7 NE=1.5 ISE=0 IKF=80m XTB=1.5 BR=4.977 " +
                "NC=2 ISC=0 IKR=0 RC=2.5 CJC=9.728p MJC=0.5776 VJC=0.75 FC=0.5 CJE=8.063p MJE=0.3677 VJE=0.75 " +
                "TR=33.42n TF=179.3p ITF=0.4 VTF=4 XTF=6 RB=10)",
        ),
        Model(
            "BC557B", "PNP",
            ".model BC557B PNP(IS=38.3f NF=0.9872 ISE=12.2f NE=1.4 BF=290 IKF=0.15 VAF=21.11 NR=0.9902 ISC=112f " +
                "NC=1.2 BR=6.1 IKR=0.08 VAR=20 RB=1 RE=0.69 RC=0.5 CJE=17.9p VJE=0.6 MJE=0.39 TF=0.6n CJC=9.7p " +
                "VJC=0.48 MJC=0.37 TR=100n)",
        ),
        Model(
            "2N7000", "VDMOS",
            ".model 2N7000 VDMOS(Vto=2.1 Kp=0.25 Lambda=0.01 Rd=0.9 Rs=0.4 Rg=3 Rb=1.2 Cgdmax=30p Cgdmin=4p " +
                "Cgs=40p Cjo=35p Is=1p Ksubthres=0.1)",
        ),
        Model(
            "IRFZ44N", "VDMOS",
            ".model IRFZ44N VDMOS(Vto=3.2 Kp=45 Lambda=5m Rd=8m Rs=4m Rg=2 Rb=10m Cgdmax=1.5n Cgdmin=0.1n " +
                "Cgs=1.4n Cjo=0.8n Is=0.1n Ksubthres=0.1)",
        ),
        Model(
            "BS250", "VDMOS",
            ".model BS250 VDMOS(pchan Vto=-2.4 Kp=0.15 Lambda=0.02 Rd=4 Rs=1 Rg=3 Rb=2 Cgdmax=40p Cgdmin=5p " +
                "Cgs=50p Cjo=40p Is=1p)",
        ),
        Model(
            "IRF9540", "VDMOS",
            ".model IRF9540 VDMOS(pchan Vto=-3.5 Kp=12 Lambda=10m Rd=60m Rs=20m Rg=3 Rb=30m Cgdmax=1.2n " +
                "Cgdmin=0.15n Cgs=1.3n Cjo=0.9n Is=0.1n)",
        ),
        Model("PJ_NMOS", "NMOS", ".model PJ_NMOS NMOS(LEVEL=1 VTO=1 KP=2m LAMBDA=0.02)"),
        Model("PJ_PMOS", "PMOS", ".model PJ_PMOS PMOS(LEVEL=1 VTO=-1 KP=1m LAMBDA=0.02)"),
        Model("PJ_SW", "SW", ".model PJ_SW SW(Ron=1 Roff=1Meg Vt=0.5 Vh=0.1)"),
    ).associateBy { it.name.uppercase() }

    fun find(name: String): Model? = models[name.trim().uppercase()]

    val names: List<String> get() = models.values.map { it.name }

    const val IDEAL_OPAMP = "PJ_OPAMP_IDEAL"
    const val OPAMP = "PJ_OPAMP"

    val idealOpAmp = """
        .subckt PJ_OPAMP_IDEAL inp inn out
        * Ideal op-amp: open-loop gain 1e6, no bandwidth or supply limits
        E1 out 0 inp inn 1e6
        .ends PJ_OPAMP_IDEAL
    """.trimIndent()

    val opAmp = """
        .subckt PJ_OPAMP inp inn vp vn out
        * Single-pole op-amp: 100 dB open-loop gain, 10 MHz GBW, output limited to the rails
        Rin inp inn 10Meg
        G1 0 n1 inp inn 1m
        R1 n1 0 100Meg
        C1 n1 0 15.9p
        D1 n1 vp PJ_DCLAMP
        D2 vn n1 PJ_DCLAMP
        B1 out 0 V=max(min(V(n1), V(vp)), V(vn))
        .model PJ_DCLAMP D(IS=10f)
        .ends PJ_OPAMP
    """.trimIndent()
}
