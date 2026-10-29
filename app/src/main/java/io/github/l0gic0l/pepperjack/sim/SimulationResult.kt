package io.github.l0gic0l.pepperjack.sim

import io.github.l0gic0l.pepperjack.spice.ProbeVector
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.log10

enum class Quantity(val unit: String) {
    VOLTAGE("V"), CURRENT("A"), TIME("s"), FREQUENCY("Hz"), OTHER("");

    companion object {
        /** Maps ngspice's simvectype (SV_TIME = 1, SV_FREQUENCY, SV_VOLTAGE, SV_CURRENT). */
        fun fromSpiceType(type: Int) = when (type) {
            1 -> TIME
            2 -> FREQUENCY
            3 -> VOLTAGE
            4 -> CURRENT
            else -> OTHER
        }
    }
}

enum class PlotKind(val label: String) {
    OP("Operating point"), TRAN("Transient"), AC("AC"), DC("DC sweep"), OTHER("Analysis");

    companion object {
        fun fromPlotName(name: String): PlotKind = when {
            name.startsWith("tran") -> TRAN
            name.startsWith("ac") -> AC
            name.startsWith("dc") -> DC
            name.startsWith("op") -> OP
            else -> OTHER
        }
    }
}

class Vector(
    val name: String,
    val quantity: Quantity,
    val real: DoubleArray,
    val imag: DoubleArray? = null,
) {
    val size: Int get() = real.size
    val isComplex: Boolean get() = imag != null

    /** Device-internal nodes such as "m1#gate"; hidden from the default trace list. */
    val isInternal: Boolean get() = '#' in name && !name.endsWith("#branch")

    /** What LTspice would call the trace: V(out), I(V1), I(R1). */
    val label: String
        get() = when {
            name.endsWith("#branch") -> "I(${name.removeSuffix("#branch").uppercase()})"
            name.startsWith("@") -> {
                val device = name.substringAfter('@').substringBefore('[')
                "I(${device.uppercase()})"
            }
            quantity == Quantity.VOLTAGE -> "V($name)"
            quantity == Quantity.CURRENT -> "I($name)"
            else -> name
        }

    fun magnitude(i: Int): Double = if (imag == null) kotlin.math.abs(real[i]) else hypot(real[i], imag[i])
    fun magnitudeDb(i: Int): Double = 20 * log10(magnitude(i).coerceAtLeast(1e-300))
    fun phaseDegrees(i: Int): Double = if (imag == null) (if (real[i] < 0) 180.0 else 0.0) else Math.toDegrees(atan2(imag[i], real[i]))
}

data class Plot(
    /** ngspice's plot type name, e.g. "tran1". */
    val name: String,
    val title: String,
    val kind: PlotKind,
    val scale: Vector?,
    val vectors: List<Vector>,
    /** The .step value this plot belongs to, e.g. "R=2k". */
    val stepLabel: String? = null,
)

data class LogLine(val text: String, val isError: Boolean)

data class SimulationResult(
    val plots: List<Plot>,
    val log: List<LogLine>,
    val warnings: List<String>,
    val error: String?,
    val probes: List<ProbeVector> = emptyList(),
    val elapsedMs: Long = 0,
) {
    val succeeded: Boolean get() = error == null && plots.isNotEmpty()
}
