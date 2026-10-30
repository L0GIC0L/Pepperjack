package io.github.l0gic0l.pepperjack.model

import io.github.l0gic0l.pepperjack.util.Si
import java.util.Locale
import kotlin.math.roundToInt

/** A potentiometer's value text, "10k 50%": total resistance and wiper position (0..1 from pin 1). */
data class PotValue(val resistance: String, val position: Double) {
    fun format(): String = "$resistance ${(position * 100).roundToInt()}%"

    companion object {
        fun parse(value: String): PotValue {
            val tokens = value.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            val resistance = tokens.firstOrNull { !it.endsWith("%") } ?: "10k"
            val position = tokens.firstOrNull { it.endsWith("%") }?.removeSuffix("%")?.toDoubleOrNull()?.div(100)
                ?: tokens.getOrNull(1)?.toDoubleOrNull()
                ?: 0.5
            return PotValue(resistance, position.coerceIn(0.0, 1.0))
        }
    }
}

/** A transformer's value text, "10m 10:1 0.999": primary inductance, turns ratio Np:Ns, coupling. */
data class TransformerValue(val primary: String, val primaryTurns: Double, val secondaryTurns: Double, val coupling: String) {
    fun format(): String = buildString {
        append(primary).append(' ').append(fmt(primaryTurns)).append(':').append(fmt(secondaryTurns))
        if (coupling != DEFAULT_COUPLING) append(' ').append(coupling)
    }

    /** Secondary inductance: L scales with turns squared. */
    fun secondary(): String {
        val ratio = secondaryTurns / primaryTurns
        val lp = Si.parse(primary)
        return if (lp != null) Si.formatSpice(lp * ratio * ratio) else "{(${primary.trim('{', '}')})*${ratio * ratio}}"
    }

    companion object {
        const val DEFAULT_COUPLING = "0.999"

        fun parse(value: String): TransformerValue {
            val tokens = value.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            val ratio = tokens.firstOrNull { ':' in it }
            val others = tokens.filter { ':' !in it }
            val np = ratio?.substringBefore(':')?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
            val ns = ratio?.substringAfter(':')?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
            return TransformerValue(
                primary = others.getOrNull(0) ?: "10m",
                primaryTurns = np,
                secondaryTurns = ns,
                coupling = others.getOrNull(1) ?: DEFAULT_COUPLING,
            )
        }

        private fun fmt(value: Double): String =
            if (value == value.toLong().toDouble()) value.toLong().toString() else String.format(Locale.ROOT, "%.3g", value)
    }
}

/** Switch positions, as stored in a switch part's value. */
object SwitchValue {
    fun isClosed(value: String) = value.trim().equals("closed", ignoreCase = true)
    fun toggleSpst(value: String) = if (isClosed(value)) "open" else "closed"
    fun isB(value: String) = value.trim().equals("B", ignoreCase = true)
    fun toggleSpdt(value: String) = if (isB(value)) "A" else "B"
}
