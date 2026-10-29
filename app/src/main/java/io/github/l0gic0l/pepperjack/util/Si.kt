package io.github.l0gic0l.pepperjack.util

import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10

/** Engineering notation: SPICE-style parsing and SI-prefixed display formatting. */
object Si {
    private val number = Regex("""^([+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?)([a-zA-Zµμ]*)$""")

    /**
     * Parses a SPICE number such as `4.7k`, `10Meg`, `2.2u` or `1e-3`.
     * Follows SPICE rules: suffixes are case-insensitive, `m` is milli, `meg` is mega,
     * and trailing unit letters (`10V`, `1kHz`) are ignored.
     */
    fun parse(text: String): Double? {
        val match = number.matchEntire(text.trim()) ?: return null
        val base = match.groupValues[1].toDoubleOrNull() ?: return null
        val suffix = match.groupValues[2].lowercase(Locale.ROOT)
        val multiplier = when {
            suffix.isEmpty() -> 1.0
            suffix.startsWith("meg") -> 1e6
            suffix.startsWith("mil") -> 25.4e-6
            else -> when (suffix[0]) {
                't' -> 1e12
                'g' -> 1e9
                'k' -> 1e3
                'm' -> 1e-3
                'u', 'µ', 'μ' -> 1e-6
                'n' -> 1e-9
                'p' -> 1e-12
                'f' -> 1e-15
                'a' -> 1e-18
                else -> 1.0
            }
        }
        return base * multiplier
    }

    /** Formats [value] with an SI prefix and [digits] significant digits, e.g. `4.7 kΩ`. */
    fun format(value: Double, unit: String = "", digits: Int = 3, space: Boolean = true): String {
        val sep = if (space && unit.isNotEmpty()) " " else ""
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return (if (value > 0) "∞" else "-∞") + sep + unit
        if (value == 0.0) return "0$sep$unit"
        val rounded = BigDecimal(value).round(MathContext(digits)).toDouble()
        val exponent = (floor(log10(abs(rounded)) / 3).toInt() * 3).coerceIn(-15, 12)
        val scaled = rounded / Math.pow(10.0, exponent.toDouble())
        val magnitude = floor(log10(abs(scaled))).toInt()
        val decimals = (digits - 1 - magnitude).coerceAtLeast(0)
        val text = String.format(Locale.US, "%.${decimals}f", scaled).let {
            if (it.contains('.')) it.trimEnd('0').trimEnd('.') else it
        }
        return text + sep + prefix(exponent) + unit
    }

    /** Formats a value for writing back into a netlist (`4.7k`, `10Meg`, `1u`). */
    fun formatSpice(value: Double): String {
        if (value == 0.0 || value.isNaN() || value.isInfinite()) return "0"
        val rounded = BigDecimal(value).round(MathContext(6)).toDouble()
        val exponent = (floor(log10(abs(rounded)) / 3).toInt() * 3).coerceIn(-15, 12)
        val scaled = rounded / Math.pow(10.0, exponent.toDouble())
        val text = BigDecimal(scaled).round(MathContext(6)).stripTrailingZeros().toPlainString()
        val suffix = when (exponent) {
            12 -> "T"
            9 -> "G"
            6 -> "Meg"
            3 -> "k"
            0 -> ""
            -3 -> "m"
            -6 -> "u"
            -9 -> "n"
            -12 -> "p"
            else -> "f"
        }
        return text + suffix
    }

    private fun prefix(exponent: Int) = when (exponent) {
        12 -> "T"
        9 -> "G"
        6 -> "M"
        3 -> "k"
        0 -> ""
        -3 -> "m"
        -6 -> "µ"
        -9 -> "n"
        -12 -> "p"
        else -> "f"
    }
}
