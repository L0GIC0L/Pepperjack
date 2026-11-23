package io.github.l0gic0l.pepperjack.model

import io.github.l0gic0l.pepperjack.util.Si
import java.util.Locale
import kotlin.math.roundToInt

/** What a measurement probe shows beside it. */
enum class ProbeReading(val token: String, val label: String) {
    VOLTAGE("V", "Voltage"),
    CURRENT("I", "Current"),
    PEAK_TO_PEAK("PP", "Peak to peak"),
    RMS("RMS", "RMS"),
    DC("DC", "DC (mean)"),
    FREQUENCY("F", "Frequency"),
}

/** A probe's value text, "V I PP RMS": the readings it shows, in any order. */
object ProbeValue {
    const val DEFAULT = "V I PP RMS"

    fun parse(value: String): Set<ProbeReading> {
        val tokens = value.trim().uppercase(Locale.ROOT).split(Regex("""[\s,]+""")).toSet()
        return ProbeReading.entries.filter { it.token in tokens }.toSet()
    }

    fun format(readings: Set<ProbeReading>): String =
        ProbeReading.entries.filter { it in readings }.joinToString(" ") { it.token }
}

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

/** What an electronic load holds constant, and the unit of its setting. */
enum class LoadMode(val token: String, val title: String, val unit: String) {
    CC("CC", "Constant current", "A"),
    CR("CR", "Constant resistance", "Ω"),
    CV("CV", "Constant voltage", "V"),
    CP("CP", "Constant power", "W"),
}

/**
 * An electronic load's value text, "CC 6 Von=10 on=30m sweep": its mode and setting; the
 * input voltage it draws above (Von, none by default); when its input turns on in the run
 * (at the start by default); whether it sweeps its setting from nothing up to the set value
 * at mid-run and back down, to trace a supply's V–I curve in one run; and "off" when its
 * input is switched off, as with a bench load's input button.
 */
data class ELoadValue(
    val mode: LoadMode,
    val setting: String,
    val von: String = "",
    val on: String = "",
    val sweep: Boolean = false,
    val input: Boolean = true,
) {
    fun format(): String = buildString {
        append(mode.token).append(' ').append(setting)
        if (von.isNotBlank()) append(" Von=").append(von.trim())
        if (on.isNotBlank()) append(" on=").append(on.trim())
        if (sweep) append(" sweep")
        if (!input) append(" off")
    }

    companion object {
        const val DEFAULT = "CC 1"

        fun parse(value: String): ELoadValue {
            val tokens = value.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            fun option(name: String) = tokens.firstOrNull { it.startsWith("$name=", ignoreCase = true) }?.substringAfter('=').orEmpty()
            val mode = tokens.firstNotNullOfOrNull { t -> LoadMode.entries.firstOrNull { it.token.equals(t, ignoreCase = true) } } ?: LoadMode.CC
            val flags = setOf("sweep", "off")
            val setting = tokens.firstOrNull { t ->
                '=' !in t && LoadMode.entries.none { it.token.equals(t, ignoreCase = true) } && t.lowercase(Locale.ROOT) !in flags
            }
            return ELoadValue(
                mode = mode,
                setting = setting ?: "1",
                von = option("Von"),
                on = option("on"),
                sweep = tokens.any { it.equals("sweep", ignoreCase = true) },
                input = tokens.none { it.equals("off", ignoreCase = true) },
            )
        }
    }
}
