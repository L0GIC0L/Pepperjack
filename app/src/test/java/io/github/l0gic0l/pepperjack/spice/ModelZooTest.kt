package io.github.l0gic0l.pepperjack.spice

import io.github.l0gic0l.pepperjack.model.AnalysisConfig
import io.github.l0gic0l.pepperjack.model.AnalysisType
import io.github.l0gic0l.pepperjack.model.Circuit
import io.github.l0gic0l.pepperjack.model.CircuitKind
import io.github.l0gic0l.pepperjack.model.Part
import io.github.l0gic0l.pepperjack.model.PartKind
import io.github.l0gic0l.pepperjack.model.PartLibrary
import io.github.l0gic0l.pepperjack.model.Schematic
import io.github.l0gic0l.pepperjack.model.SpiceDialect
import io.github.l0gic0l.pepperjack.model.pinPositions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** A small test circuit for every built-in model and every symbol. */
object ModelZoo {
    /** A schematic whose pins are connected purely through net labels. */
    class Bench(val name: String) {
        private var nextId = 1L
        private val parts = mutableListOf<Part>()

        fun part(kind: PartKind, ref: String, vararg nets: String, value: String? = null): Part {
            val part = Part(nextId++, kind, ref, 0, nextId.toInt() * 20, value = value ?: PartLibrary[kind].defaultValue)
            parts += part
            part.pinPositions().zip(nets.toList()).forEach { (point, net) ->
                val kindAt = if (net == "0") PartKind.GROUND else PartKind.LABEL
                parts += Part(nextId++, kindAt, net, point.x, point.y, value = if (kindAt == PartKind.LABEL) net else "")
            }
            return part
        }

        fun circuit(analysis: AnalysisConfig, directives: String = "") = Circuit(
            id = name, name = name, kind = CircuitKind.SCHEMATIC,
            schematic = Schematic(parts = parts.toList(), directives = directives),
            analysis = analysis,
        )
    }

    val tran = AnalysisConfig(type = AnalysisType.TRAN, tranStop = "2m", tranStep = "2u")

    private fun benchFor(model: SpiceModel): Circuit? = Bench("model-" + model.name).run {
        when (model.family) {
            ModelFamily.DIODE, ModelFamily.SCHOTTKY, ModelFamily.LED -> {
                part(PartKind.VOLTAGE, "V1", "in", "0", value = "PULSE(-5 5 0 10u 10u 0.5m 1m)")
                part(PartKind.RESISTOR, "R1", "in", "a", value = "220")
                part(PartKind.DIODE, "D1", "a", "0", value = model.name)
            }
            ModelFamily.ZENER -> {
                part(PartKind.VOLTAGE, "V1", "in", "0", value = "PULSE(-40 40 0 10u 10u 0.5m 1m)")
                part(PartKind.RESISTOR, "R1", "in", "k", value = "1k")
                part(PartKind.ZENER, "D1", "0", "k", value = model.name)
            }
            ModelFamily.NPN -> {
                part(PartKind.VOLTAGE, "V1", "vcc", "0", value = "DC 12")
                part(PartKind.VOLTAGE, "V2", "in", "0", value = "PULSE(0 5 0 10u 10u 0.5m 1m)")
                part(PartKind.RESISTOR, "R1", "in", "b", value = "1k")
                part(PartKind.RESISTOR, "R2", "vcc", "c", value = "100")
                part(PartKind.NPN, "Q1", "c", "b", "0", value = model.name)
            }
            ModelFamily.PNP -> {
                part(PartKind.VOLTAGE, "V1", "vcc", "0", value = "DC 12")
                part(PartKind.VOLTAGE, "V2", "in", "0", value = "PULSE(12 7 0 10u 10u 0.5m 1m)")
                part(PartKind.RESISTOR, "R1", "in", "b", value = "1k")
                part(PartKind.RESISTOR, "R2", "c", "0", value = "100")
                part(PartKind.PNP, "Q1", "c", "b", "vcc", value = model.name)
            }
            ModelFamily.NJF -> {
                part(PartKind.VOLTAGE, "V1", "vdd", "0", value = "DC 12")
                part(PartKind.VOLTAGE, "V2", "g", "0", value = "SIN(-1 0.5 1k)")
                part(PartKind.RESISTOR, "R1", "vdd", "d", value = "1k")
                part(PartKind.NJFET, "J1", "d", "g", "0", value = model.name)
            }
            ModelFamily.PJF -> {
                part(PartKind.VOLTAGE, "V1", "vdd", "0", value = "DC -12")
                part(PartKind.VOLTAGE, "V2", "g", "0", value = "SIN(1 0.5 1k)")
                part(PartKind.RESISTOR, "R1", "vdd", "d", value = "1k")
                part(PartKind.PJFET, "J1", "d", "g", "0", value = model.name)
            }
            ModelFamily.NMOS -> {
                part(PartKind.VOLTAGE, "V1", "vdd", "0", value = "DC 12")
                part(PartKind.VOLTAGE, "V2", "g", "0", value = "PULSE(0 10 0 10u 10u 0.5m 1m)")
                part(PartKind.RESISTOR, "R1", "vdd", "d", value = "100")
                part(PartKind.NMOS, "M1", "d", "g", "0", value = model.name)
            }
            ModelFamily.PMOS -> {
                part(PartKind.VOLTAGE, "V1", "vdd", "0", value = "DC 12")
                part(PartKind.VOLTAGE, "V2", "g", "0", value = "PULSE(12 2 0 10u 10u 0.5m 1m)")
                part(PartKind.RESISTOR, "R1", "d", "0", value = "100")
                part(PartKind.PMOS, "M1", "d", "g", "vdd", value = model.name)
            }
            ModelFamily.OPAMP -> {
                part(PartKind.VOLTAGE, "V1", "vp", "0", value = "DC 12")
                part(PartKind.VOLTAGE, "V2", "0", "vn", value = "DC 12")
                part(PartKind.VOLTAGE, "V3", "in", "0", value = "SIN(0 4 1k)")
                part(PartKind.RESISTOR, "R1", "out", "fb", value = "10k")
                part(PartKind.RESISTOR, "R2", "fb", "0", value = "10k")
                part(PartKind.RESISTOR, "R3", "out", "0", value = "2k")
                part(PartKind.OPAMP5, "U1", "in", "fb", "vp", "vn", "out", value = model.name)
            }
            ModelFamily.COMPARATOR -> {
                part(PartKind.VOLTAGE, "V1", "vp", "0", value = "DC 5")
                part(PartKind.VOLTAGE, "V2", "in", "0", value = "SIN(2.5 1 1k)")
                part(PartKind.VOLTAGE, "V3", "ref", "0", value = "DC 2.5")
                part(PartKind.RESISTOR, "R1", "vp", "out", value = "10k")
                part(PartKind.COMPARATOR, "U1", "in", "ref", "vp", "0", "out", value = model.name)
            }
            ModelFamily.REGULATOR -> {
                val negative = model.name.startsWith("LM79")
                part(PartKind.VOLTAGE, "V1", "in", "0", value = if (negative) "PWL(0 0 0.5m -20)" else "PWL(0 0 0.5m 20)")
                part(PartKind.CAPACITOR, "C1", "out", "0", value = "10u")
                part(PartKind.RESISTOR, "R1", "out", "0", value = "100")
                part(PartKind.REGULATOR, "U1", "in", "0", "out", value = model.name)
            }
            ModelFamily.REGULATOR_ADJ -> {
                val negative = model.name == "LM337"
                part(PartKind.VOLTAGE, "V1", "in", "0", value = if (negative) "PWL(0 0 0.5m -20)" else "PWL(0 0 0.5m 20)")
                part(PartKind.RESISTOR, "R1", "out", "adj", value = "240")
                part(PartKind.RESISTOR, "R2", "adj", "0", value = "720")
                part(PartKind.RESISTOR, "R3", "out", "0", value = "100")
                part(PartKind.REGULATOR_ADJ, "U1", "in", "adj", "out", value = model.name)
            }
            ModelFamily.TIMER -> {
                part(PartKind.VOLTAGE, "V1", "vcc", "0", value = "DC 9")
                part(PartKind.RESISTOR, "R1", "vcc", "dis", value = "1k")
                part(PartKind.RESISTOR, "R2", "dis", "trg", value = "10k")
                part(PartKind.CAPACITOR, "C1", "trg", "0", value = "100n")
                part(PartKind.CAPACITOR, "C2", "ctl", "0", value = "10n")
                part(PartKind.RESISTOR, "R3", "out", "0", value = "1k")
                // gnd trig out reset ctrl thr dis vcc
                part(PartKind.TIMER555, "U1", "0", "trg", "out", "vcc", "ctl", "trg", "dis", "vcc", value = model.name)
            }
            ModelFamily.OPTO -> {
                part(PartKind.VOLTAGE, "V1", "in", "0", value = "PULSE(0 5 0 10u 10u 0.5m 1m)")
                part(PartKind.VOLTAGE, "V2", "vcc", "0", value = "DC 5")
                part(PartKind.RESISTOR, "R1", "in", "a", value = "330")
                part(PartKind.RESISTOR, "R2", "vcc", "c", value = "1k")
                part(PartKind.OPTO, "U1", "a", "0", "c", "0", value = model.name)
            }
            ModelFamily.CRYSTAL -> {
                part(PartKind.VOLTAGE, "V1", "in", "0", value = "PULSE(0 1 0 1n 1n 10n 1u)")
                part(PartKind.RESISTOR, "R1", "in", "a", value = "1k")
                part(PartKind.CRYSTAL, "Y1", "a", "0", value = model.name)
                return circuit(AnalysisConfig(type = AnalysisType.TRAN, tranStop = "20u", tranStep = "1n"))
            }
            ModelFamily.SWITCH -> {
                part(PartKind.VOLTAGE, "V1", "vcc", "0", value = "DC 5")
                part(PartKind.VOLTAGE, "V2", "ctl", "0", value = "PULSE(0 1 0 10u 10u 0.5m 1m)")
                part(PartKind.RESISTOR, "R1", "vcc", "a", value = "1k")
                part(PartKind.SWITCH, "S1", "a", "0", "ctl", "0", value = model.name)
            }
            ModelFamily.LOGIC -> return null
        }
        circuit(tran)
    }

    private fun logicBench(): Circuit = Bench("logic-gates").run {
        part(PartKind.VOLTAGE, "V1", "a", "0", value = "PULSE(0 3.3 0 10n 10n 5u 10u)")
        part(PartKind.VOLTAGE, "V2", "b", "0", value = "PULSE(0 3.3 0 10n 10n 10u 20u)")
        listOf(PartKind.AND, PartKind.NAND, PartKind.OR, PartKind.NOR, PartKind.XOR).forEachIndexed { i, kind ->
            part(kind, "U${i + 1}", "a", "b", "y$i", value = "3.3")
            part(PartKind.RESISTOR, "R${i + 1}", "y$i", "0", value = "10k")
        }
        part(PartKind.NOT, "U6", "a", "na", value = "3.3")
        part(PartKind.DFF, "U7", "qn", "a", "q", "qn", value = "3.3")
        part(PartKind.SCHMITT, "U8", "c", "s", value = "5")
        part(PartKind.RESISTOR, "R6", "s", "c", value = "10k")
        part(PartKind.CAPACITOR, "C1", "c", "0", value = "10n")
        circuit(AnalysisConfig(type = AnalysisType.TRAN, tranStop = "200u", tranStep = "10n"))
    }

    fun interactiveBench(): Circuit = Bench("switches-meters-passives").run {
        part(PartKind.VOLTAGE, "V1", "vcc", "0", value = "SIN(0 10 50)")
        part(PartKind.SPST, "SW1", "vcc", "s1", value = "closed")
        part(PartKind.SPDT, "SW2", "s1", "pa", "pb", value = "B")
        part(PartKind.POT, "RV1", "pa", "0", "w", value = "10k 25%")
        part(PartKind.RESISTOR, "R1", "pb", "0", value = "1k")
        part(PartKind.AMMETER, "AM1", "pb", "pm")
        part(PartKind.RESISTOR, "R2", "pm", "0", value = "1k")
        part(PartKind.VOLTMETER, "VM1", "pm", "0")
        part(PartKind.TRANSFORMER, "T1", "s1", "0", "sec", "0", value = "100m 10:1")
        part(PartKind.RESISTOR, "R3", "sec", "0", value = "100")
        part(PartKind.SCHOTTKY, "D1", "sec", "dc", value = "1N5819")
        part(PartKind.CAPACITOR, "C1", "dc", "0", value = "100u")
        part(PartKind.RESISTOR, "R4", "dc", "0", value = "1k")
        circuit(AnalysisConfig(type = AnalysisType.TRAN, tranStop = "60m", tranStep = "20u"))
    }

    fun benches(): List<Circuit> =
        SpiceModels.models.mapNotNull { benchFor(it) } + logicBench() + interactiveBench()
}

/**
 * Netlists every [ModelZoo] bench. With PEPPERJACK_EXPORT_DIR set the netlists are written
 * there too, so scripts/check-netlists.sh runs each one through ngspice in two dialects.
 */
class ModelZooTest {
    @Test
    fun everyModelAndSymbolNetlistsCleanly() {
        val dir = System.getenv("PEPPERJACK_EXPORT_DIR")?.let { File(it).apply { mkdirs() } }
        val benches = ModelZoo.benches()
        assertTrue(benches.size > 100)
        for (circuit in benches) {
            for (live in listOf(false, true)) {
                val result = Netlister.netlist(circuit, live = live)
                assertEquals("${circuit.name} (live=$live)", emptyList<String>(), result.warnings)
                if (dir == null) continue
                // Every bench in the app's default dialect; live netlists and native mode for the models.
                val dialects = if (live) listOf(SpiceDialect.Default) else listOf(SpiceDialect.Default, SpiceDialect.NGSPICE)
                for (dialect in dialects) {
                    val deck = Preprocessor.prepare(result.text, dialect)
                    val behavior = dialect.ngBehavior ?: "native"
                    val slug = circuit.name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
                    val suffix = if (live) "-live" else ""
                    File(dir, "zoo-$slug$suffix.$behavior.cir").writeText(deck.runs.single().netlist)
                }
            }
        }
    }

    @Test
    fun subcircuitModelsBecomeXInstances() {
        val bench = ModelZoo.Bench("darlington").apply {
            part(PartKind.VOLTAGE, "V1", "vcc", "0", value = "DC 12")
            part(PartKind.RESISTOR, "R1", "vcc", "b", value = "10k")
            part(PartKind.NPN, "Q1", "vcc", "b", "e", value = "TIP120")
            part(PartKind.RESISTOR, "R2", "e", "0", value = "10")
        }.circuit(ModelZoo.tran)
        val text = Netlister.netlist(bench).text
        assertTrue(text, text.lines().any { it.startsWith("XQ1 vcc b e TIP120") })
        assertTrue(text, text.contains(".subckt TIP120 c b e"))
    }

    @Test
    fun liveNetlistMeasuresEveryPin() {
        val circuit = ModelZoo.interactiveBench()
        val result = Netlister.netlist(circuit, live = true)
        val pins = circuit.schematic.parts.filter { it.kind != PartKind.GROUND && it.kind != PartKind.LABEL }.sumOf { it.pinPositions().size }
        assertEquals(pins, result.pinCurrents.values.sumOf { it.size })
        assertEquals(pins, result.text.lines().count { it.startsWith(Netlister.LIVE_AMMETER) })
        // Every current the live view reads must be the branch vector ngspice makes for an element in the deck.
        val elements = result.text.lines().map { it.substringBefore(' ').lowercase() }.toSet()
        result.pinCurrents.values.flatten().forEach { vector ->
            assertTrue(vector, vector.endsWith("#branch") && vector.removeSuffix("#branch") in elements)
        }
        assertEquals(AnalysisType.TRAN, result.liveAnalysis)
    }
}
