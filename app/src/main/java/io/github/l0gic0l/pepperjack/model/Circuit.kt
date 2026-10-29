package io.github.l0gic0l.pepperjack.model

import kotlinx.serialization.Serializable

@Serializable
enum class CircuitKind { SCHEMATIC, NETLIST }

/** A saved circuit: either a schematic (netlist generated) or a hand-written netlist. */
@Serializable
data class Circuit(
    val id: String,
    val name: String,
    val kind: CircuitKind,
    val schematic: Schematic = Schematic(),
    val netlist: String = "",
    val analysis: AnalysisConfig = AnalysisConfig(),
    /** Null means "use the default dialect from Settings". */
    val dialect: SpiceDialect? = null,
    val modifiedAt: Long = 0L,
)

/** Integer coordinates on the schematic grid. */
@Serializable
data class GridPoint(val x: Int, val y: Int)

@Serializable
data class Schematic(
    val parts: List<Part> = emptyList(),
    val wires: List<Wire> = emptyList(),
    /** Extra SPICE lines, like LTspice's "SPICE directive" text on the sheet. */
    val directives: String = "",
    val probes: List<Probe> = emptyList(),
) {
    val nextId: Long
        get() = maxOf(parts.maxOfOrNull { it.id } ?: 0L, wires.maxOfOrNull { it.id } ?: 0L) + 1
}

@Serializable
data class Part(
    val id: Long,
    val kind: PartKind,
    val ref: String,
    val x: Int,
    val y: Int,
    /** Quarter turns clockwise, 0..3. */
    val rotation: Int = 0,
    val mirrored: Boolean = false,
    val value: String = "",
    /** The symbol of a [PartKind.CUSTOM] part (a library subcircuit). */
    val symbol: CustomSymbol? = null,
) {
    val origin: GridPoint get() = GridPoint(x, y)
}

@Serializable
data class Wire(val id: Long, val x1: Int, val y1: Int, val x2: Int, val y2: Int) {
    val start: GridPoint get() = GridPoint(x1, y1)
    val end: GridPoint get() = GridPoint(x2, y2)
}

@Serializable
enum class ProbeType { VOLTAGE, CURRENT }

/**
 * A trace the user asked to plot. Voltage probes are anchored to a grid point
 * (whatever net touches it); current probes are anchored to a part.
 */
@Serializable
data class Probe(
    val type: ProbeType,
    val x: Int = 0,
    val y: Int = 0,
    val partId: Long = 0L,
)

@Serializable
enum class AnalysisType(val label: String) {
    OP("DC operating point"),
    TRAN("Transient"),
    AC("AC sweep"),
    DC("DC sweep"),
}

@Serializable
data class AnalysisConfig(
    val type: AnalysisType = AnalysisType.TRAN,
    val tranStop: String = "10m",
    val tranStep: String = "10u",
    val tranStart: String = "0",
    val tranMaxStep: String = "",
    val tranUic: Boolean = false,
    val acSweep: String = "dec",
    val acPoints: Int = 100,
    val acStart: String = "1",
    val acStop: String = "1Meg",
    val dcSource: String = "V1",
    val dcStart: String = "0",
    val dcStop: String = "5",
    val dcStep: String = "0.1",
) {
    fun toSpice(): String = when (type) {
        AnalysisType.OP -> ".op"
        AnalysisType.TRAN -> buildString {
            append(".tran ").append(tranStep).append(' ').append(tranStop)
            val start = tranStart.ifBlank { "0" }
            if (tranMaxStep.isNotBlank()) {
                append(' ').append(start).append(' ').append(tranMaxStep)
            } else if (start != "0") {
                append(' ').append(start)
            }
            if (tranUic) append(" uic")
        }
        AnalysisType.AC -> ".ac $acSweep $acPoints $acStart $acStop"
        AnalysisType.DC -> ".dc $dcSource $dcStart $dcStop $dcStep"
    }
}

/**
 * Netlist dialects. ngspice translates these through its `ngbehavior` compatibility
 * modes; the trailing "a" applies the translation to the whole netlist, not only to
 * included model libraries.
 */
@Serializable
enum class SpiceDialect(val label: String, val description: String, val ngBehavior: String?) {
    NGSPICE(
        "ngspice / SPICE3 / Multisim",
        "Native ngspice syntax. Best for SPICE3, XSPICE and Multisim netlists.",
        null,
    ),
    LTSPICE(
        "LTspice",
        "LTspice syntax and model libraries, including Rser/Cpar and LTspice B-source functions.",
        "lta",
    ),
    PSPICE(
        "PSpice / OrCAD",
        "PSpice syntax and vendor model libraries (VALUE, TABLE, IF expressions).",
        "psa",
    ),
    LTSPICE_PSPICE(
        "LTspice + PSpice models",
        "LTspice syntax that also accepts PSpice vendor models. The most forgiving choice.",
        "ltpsa",
    ),
    HSPICE(
        "HSPICE",
        "HSPICE syntax and foundry model cards.",
        "hsa",
    ),
    SPICE3(
        "Berkeley SPICE3",
        "Strict SPICE3f5 behaviour without ngspice extensions.",
        "s3",
    ),
    ;

    companion object {
        val Default = LTSPICE_PSPICE
    }
}
