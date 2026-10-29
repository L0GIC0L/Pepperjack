package io.github.l0gic0l.pepperjack.spice

import io.github.l0gic0l.pepperjack.model.SpiceDialect
import io.github.l0gic0l.pepperjack.util.Si
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/** One simulation pass. [label] names the .step value ("R=2k"), [commands] run before it. */
data class StepRun(val label: String?, val netlist: String, val commands: List<String> = emptyList())

data class PreparedDeck(val runs: List<StepRun>, val warnings: List<String>)

/**
 * Bridges LTspice syntax that ngspice does not accept, then expands `.step` into
 * one run per value. Everything else is passed to ngspice untouched.
 */
object Preprocessor {
    const val MAX_STEPS = 50

    fun prepare(netlist: String, dialect: SpiceDialect): PreparedDeck {
        val warnings = mutableListOf<String>()
        var text = netlist
        if (dialect == SpiceDialect.LTSPICE || dialect == SpiceDialect.LTSPICE_PSPICE) {
            text = LtspiceCompat.translate(text)
        }
        text = stripQuit(text)
        if (!text.trimEnd().lowercase(Locale.ROOT).endsWith(".end")) text = text.trimEnd() + "\n.end\n"
        val runs = StepSweep.expand(text, warnings)
        return PreparedDeck(runs, warnings)
    }

    /** `quit` inside a .control block would tear down ngspice and its results; the app ends runs itself. */
    private fun stripQuit(netlist: String): String {
        var inControl = false
        return netlist.lines().filterNot { line ->
            val word = line.trim().lowercase(Locale.ROOT)
            when {
                word.startsWith(".control") -> inControl = true
                word.startsWith(".endc") -> inControl = false
            }
            inControl && (word == "quit" || word == "exit")
        }.joinToString("\n")
    }
}

/** Rewrites LTspice's parasitic shorthand (Rser, Rpar, Cpar, Lser) into explicit elements. */
object LtspiceCompat {
    private val parasitic = Regex("""(?i)\s(rser|rpar|cpar|lser)\s*=\s*(\{[^\}]*\}|\S+)""")
    private val whitespace = Regex("""\s+""")

    fun translate(netlist: String): String {
        val lines = joinContinuations(netlist.lines())
        val out = ArrayList<String>(lines.size)
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            val kind = trimmed.firstOrNull()?.uppercaseChar()
            if (index == 0 || (kind != 'L' && kind != 'C' && kind != 'V')) {
                out += line
                return@forEachIndexed
            }
            val params = parasitic.findAll(" $trimmed")
                .associate { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2] }
                .filterValues { value -> Si.parse(value)?.let { it != 0.0 } ?: true }
            val stripped = parasitic.replace(" $trimmed", "").trim()
            val tokens = stripped.split(whitespace)
            if (params.isEmpty() || tokens.size < 4) {
                out += if (stripped != trimmed && tokens.size >= 4) stripped else line
                return@forEachIndexed
            }
            val name = tokens[0]
            val n1 = tokens[1]
            val n2 = tokens[2]
            val rest = tokens.drop(3).joinToString(" ")
            val base = name.lowercase(Locale.ROOT)
            val node = { suffix: String -> "pj_${base}_$suffix" }
            when (kind) {
                'V' -> {
                    val rser = params["rser"]
                    if (rser != null) {
                        out += "$name ${node("s")} $n2 $rest"
                        out += "R${base}_ser $n1 ${node("s")} $rser"
                    } else {
                        out += "$name $n1 $n2 $rest"
                    }
                    params["cpar"]?.let { out += "C${base}_par $n1 $n2 $it" }
                }
                'L' -> {
                    val rser = params["rser"]
                    if (rser != null) {
                        out += "$name $n1 ${node("s")} $rest"
                        out += "R${base}_ser ${node("s")} $n2 $rser"
                    } else {
                        out += "$name $n1 $n2 $rest"
                    }
                    params["rpar"]?.let { out += "R${base}_par $n1 $n2 $it" }
                    params["cpar"]?.let { out += "C${base}_par $n1 $n2 $it" }
                }
                else -> { // capacitor: C, then optional series L and R
                    val series = listOfNotNull(
                        params["lser"]?.let { "L${base}_ser" to it },
                        params["rser"]?.let { "R${base}_ser" to it },
                    )
                    if (series.isEmpty()) {
                        out += "$name $n1 $n2 $rest"
                    } else {
                        out += "$name $n1 ${node("1")} $rest"
                        series.forEachIndexed { i, (element, value) ->
                            val from = node("${i + 1}")
                            val to = if (i == series.lastIndex) n2 else node("${i + 2}")
                            out += "$element $from $to $value"
                        }
                    }
                    params["rpar"]?.let { out += "R${base}_par $n1 $n2 $it" }
                }
            }
        }
        return out.joinToString("\n", postfix = "\n")
    }

    /** Folds SPICE "+" continuation lines into the line they continue. */
    fun joinContinuations(lines: List<String>): List<String> {
        val result = ArrayList<String>(lines.size)
        for (line in lines) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("+") && result.isNotEmpty()) {
                result[result.lastIndex] = result.last().trimEnd() + " " + trimmed.substring(1).trim()
            } else {
                result += line
            }
        }
        return result
    }
}

/** Expands a single LTspice `.step` directive into separate runs. */
object StepSweep {
    private val stepLine = Regex("""(?im)^[ \t]*\.step[ \t]+(.*)$""")
    private val whitespace = Regex("""\s+""")

    fun expand(netlist: String, warnings: MutableList<String>): List<StepRun> {
        val lines = LtspiceCompat.joinContinuations(netlist.lines())
        val stepIndexes = lines.indices.filter { stepLine.matches(lines[it]) }
        if (stepIndexes.isEmpty()) return listOf(StepRun(null, netlist))
        if (stepIndexes.size > 1) warnings += "Only the first .step is swept; nested steps are not supported."

        val spec = stepLine.matchEntire(lines[stepIndexes.first()])!!.groupValues[1]
        val base = lines.filterIndexed { i, _ -> i !in stepIndexes }
        val sweep = parse(spec)
        if (sweep == null) {
            warnings += "Could not understand \".step $spec\"; running once without it."
            return listOf(StepRun(null, base.joinToString("\n", postfix = "\n")))
        }
        var values = sweep.values
        if (values.size > Preprocessor.MAX_STEPS) {
            warnings += "The .step sweep has ${values.size} points; only the first ${Preprocessor.MAX_STEPS} run."
            values = values.take(Preprocessor.MAX_STEPS)
        }
        return values.map { value ->
            val label = "${sweep.target}=$value"
            if (sweep.isParam) {
                StepRun(label, withParam(base, sweep.target, value))
            } else {
                StepRun(label, base.joinToString("\n", postfix = "\n"), listOf("alter ${sweep.target} dc = $value"))
            }
        }
    }

    private class Sweep(val target: String, val isParam: Boolean, val values: List<String>)

    private fun parse(spec: String): Sweep? {
        val tokens = spec.trim().split(whitespace).toMutableList()
        var mode = "lin"
        if (tokens.firstOrNull()?.lowercase(Locale.ROOT) in setOf("lin", "oct", "dec")) mode = tokens.removeAt(0).lowercase(Locale.ROOT)
        val isParam = tokens.firstOrNull()?.equals("param", ignoreCase = true) == true
        if (isParam) tokens.removeAt(0)
        val target = tokens.removeFirstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        if (!isParam && target.first().uppercaseChar() !in setOf('V', 'I')) return null
        if (tokens.firstOrNull()?.equals("list", ignoreCase = true) == true) {
            return tokens.drop(1).takeIf { it.isNotEmpty() }?.let { Sweep(target, isParam, it) }
        }
        if (tokens.size < 3) return null
        val start = Si.parse(tokens[0]) ?: return null
        val stop = Si.parse(tokens[1]) ?: return null
        val increment = Si.parse(tokens[2]) ?: return null
        val values = mutableListOf<Double>()
        when (mode) {
            "lin" -> {
                if (increment == 0.0) return null
                val count = ((stop - start) / increment + 1e-9).toLong()
                if (count < 0 || count > 10_000) return null
                for (i in 0..count) values += start + i * increment
            }
            else -> {
                if (start <= 0 || stop <= 0 || increment < 1) return null
                val ratio = if (mode == "oct") 2.0 else 10.0
                val factor = ratio.pow(1.0 / increment)
                var v = start
                while (v <= stop * (1 + 1e-9) && values.size <= 10_000) {
                    values += v
                    v *= factor
                }
            }
        }
        return Sweep(target, isParam, values.map { if (abs(it) < 1e-21) "0" else Si.formatSpice(it) })
    }

    private fun withParam(lines: List<String>, name: String, value: String): String {
        val assignment = Regex("""(?i)(^|\s)${Regex.escape(name)}\s*=\s*(\{[^\}]*\}|\S+)""")
        val cleaned = lines.mapNotNull { line ->
            if (!line.trimStart().startsWith(".param", ignoreCase = true)) return@mapNotNull line
            val rest = assignment.replace(line, " ").trim()
            if (rest.equals(".param", ignoreCase = true)) null else rest
        }
        val title = cleaned.firstOrNull() ?: ""
        return (listOf(title, ".param $name=$value") + cleaned.drop(1)).joinToString("\n", postfix = "\n")
    }
}
