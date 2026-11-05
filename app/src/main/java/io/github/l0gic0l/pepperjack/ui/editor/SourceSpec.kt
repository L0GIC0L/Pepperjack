package io.github.l0gic0l.pepperjack.ui.editor

enum class SourceType(val label: String) { DC("DC"), AC("AC"), SINE("Sine"), PULSE("Pulse"), CUSTOM("Custom") }

/** Editable form of an independent source value such as `SIN(0 1 1k) AC 1`. */
data class SourceSpec(
    val type: SourceType = SourceType.DC,
    val dc: String = "5",
    val acMagnitude: String = "",
    val sinOffset: String = "0",
    val sinAmplitude: String = "1",
    val sinFrequency: String = "1k",
    val pulse: List<String> = listOf("0", "5", "0", "1n", "1n", "0.5m", "1m"),
    val custom: String = "",
) {
    fun toSpice(): String {
        val ac = acMagnitude.trim().takeIf { it.isNotEmpty() }?.let { " AC $it" } ?: ""
        return when (type) {
            SourceType.DC -> "DC ${dc.trim().ifEmpty { "0" }}"
            SourceType.AC -> "DC ${dc.trim().ifEmpty { "0" }} AC ${acMagnitude.trim().ifEmpty { "1" }}"
            SourceType.SINE -> "SIN(${sinOffset.ifBlank { "0" }} ${sinAmplitude.ifBlank { "1" }} ${sinFrequency.ifBlank { "1k" }})$ac"
            SourceType.PULSE -> "PULSE(${pulse.joinToString(" ") { it.ifBlank { "0" } }})$ac"
            SourceType.CUSTOM -> custom.trim()
        }
    }

    companion object {
        val pulseLabels = listOf("Initial", "Pulsed", "Delay", "Rise", "Fall", "Width", "Period")

        private val acPattern = Regex("""(?i)\bAC\s+([^\s()]+)""")
        private val sinPattern = Regex("""(?i)^\s*(?:DC\s+\S+\s+)?SIN\s*\(([^)]*)\)\s*(?:AC\s+\S+\s*)?$""")
        private val pulsePattern = Regex("""(?i)^\s*(?:DC\s+\S+\s+)?PULSE\s*\(([^)]*)\)\s*(?:AC\s+\S+\s*)?$""")
        private val dcPattern = Regex("""(?i)^\s*(?:DC\s+)?([^\s()]+)\s*(?:AC\s+\S+\s*)?$""")

        fun parse(value: String): SourceSpec {
            val ac = acPattern.find(value)?.groupValues?.get(1) ?: ""
            sinPattern.matchEntire(value)?.let { match ->
                val args = match.groupValues[1].trim().split(Regex("""[\s,]+"""))
                if (args.size <= 3) {
                    return SourceSpec(
                        type = SourceType.SINE,
                        sinOffset = args.getOrElse(0) { "0" },
                        sinAmplitude = args.getOrElse(1) { "1" },
                        sinFrequency = args.getOrElse(2) { "1k" },
                        acMagnitude = ac,
                    )
                }
            }
            pulsePattern.matchEntire(value)?.let { match ->
                val args = match.groupValues[1].trim().split(Regex("""[\s,]+"""))
                if (args.size <= 7) {
                    return SourceSpec(
                        type = SourceType.PULSE,
                        pulse = List(7) { args.getOrElse(it) { "0" } },
                        acMagnitude = ac,
                    )
                }
            }
            dcPattern.matchEntire(value)?.let { match ->
                val dc = match.groupValues[1]
                if (!dc.equals("AC", ignoreCase = true)) {
                    return SourceSpec(type = if (ac.isNotEmpty()) SourceType.AC else SourceType.DC, dc = dc, acMagnitude = ac)
                }
            }
            if (Regex("""(?i)^\s*AC\s+\S+\s*$""").matches(value)) {
                return SourceSpec(type = SourceType.AC, dc = "0", acMagnitude = ac)
            }
            return SourceSpec(type = SourceType.CUSTOM, custom = value)
        }
    }
}
