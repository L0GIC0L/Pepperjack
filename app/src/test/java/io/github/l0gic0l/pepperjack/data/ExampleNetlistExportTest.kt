package io.github.l0gic0l.pepperjack.data

import io.github.l0gic0l.pepperjack.model.CircuitKind
import io.github.l0gic0l.pepperjack.model.SpiceDialect
import io.github.l0gic0l.pepperjack.spice.Netlister
import io.github.l0gic0l.pepperjack.spice.Preprocessor
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Every example must netlist cleanly. With PEPPERJACK_EXPORT_DIR set, the netlists are
 * also written there (as NN-name.<ngbehavior>.cir) for scripts/check-netlists.sh.
 */
class ExampleNetlistExportTest {
    @Test
    fun examplesNetlistWithoutWarnings() {
        val dir = System.getenv("PEPPERJACK_EXPORT_DIR")?.let { File(it).apply { mkdirs() } }
        Examples.all.forEachIndexed { index, example ->
            val circuit = example.build()
            val text = if (circuit.kind == CircuitKind.SCHEMATIC) {
                val result = Netlister.netlist(circuit)
                assertTrue("${example.title}: ${result.warnings}", result.warnings.isEmpty())
                result.text
            } else {
                circuit.netlist
            }
            assertTrue(example.title, text.trimEnd().endsWith(".end"))
            val dialect = circuit.dialect ?: SpiceDialect.Default
            val deck = Preprocessor.prepare(text, dialect)
            assertTrue("${example.title}: ${deck.warnings}", deck.warnings.isEmpty())
            if (dir != null) {
                val behavior = dialect.ngBehavior ?: "native"
                val slug = example.title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
                deck.runs.forEachIndexed { run, step ->
                    val suffix = if (deck.runs.size > 1) "-s${run + 1}" else ""
                    val name = String.format(Locale.ROOT, "%02d-%s%s.%s.cir", index + 1, slug, suffix, behavior)
                    File(dir, name).writeText(step.netlist)
                }
            }
        }
    }
}
