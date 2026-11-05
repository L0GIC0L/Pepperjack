package io.github.l0gic0l.pepperjack.spice

import io.github.l0gic0l.pepperjack.model.SpiceDialect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreprocessorTest {
    @Test
    fun expandsInductorAndCapacitorParasitics() {
        val out = LtspiceCompat.translate(
            """
            * title
            L1 a b 10u Rser=20m Rpar=1k
            C1 b 0 1u Rser=5m Lser=2n
            R1 a 0 1k
            """.trimIndent(),
        ).lines()
        assertTrue(out.toString(), "L1 a pj_l1_s 10u" in out)
        assertTrue("Rl1_ser pj_l1_s b 20m" in out)
        assertTrue("Rl1_par a b 1k" in out)
        assertTrue("C1 b pj_c1_1 1u" in out)
        assertTrue("Lc1_ser pj_c1_1 pj_c1_2 2n" in out)
        assertTrue("Rc1_ser pj_c1_2 0 5m" in out)
        assertTrue("R1 a 0 1k" in out)
    }

    @Test
    fun movesSourceResistanceIntoASeriesResistor() {
        val out = LtspiceCompat.translate("* t\nV1 in 0 PULSE(0 1 0 1n 1n 1m 2m) Rser=1\n").lines()
        assertTrue(out.toString(), "V1 pj_v1_s 0 PULSE(0 1 0 1n 1n 1m 2m)" in out)
        assertTrue("Rv1_ser in pj_v1_s 1" in out)
    }

    @Test
    fun dropsZeroParasiticsAndJoinsContinuations() {
        val out = LtspiceCompat.translate("* t\nC1 a 0\n+ 1u Rser=0\n").lines()
        assertTrue(out.toString(), "C1 a 0 1u" in out)
    }

    @Test
    fun continuationsSkipCommentsAsInNgspice() {
        val out = LtspiceCompat.translate("* t\n.model D1 D(Is=1n\n* note\n\n+ N=2)\n").lines()
        assertTrue(out.toString(), ".model D1 D(Is=1n N=2)" in out)
        assertTrue("* note" in out)
    }

    @Test
    fun expandsParamStepLists() {
        val deck = Preprocessor.prepare(
            "* t\n.param C=100n\nC1 out 0 {C}\n.step param C list 10n 1u\n.ac dec 10 1 1k\n.end\n",
            SpiceDialect.LTSPICE,
        )
        assertEquals(listOf("C=10n", "C=1u"), deck.runs.map { it.label })
        val first = deck.runs[0].netlist.lines()
        assertEquals(".param C=10n", first[1])
        assertTrue(first.none { it.startsWith(".step") || it == ".param C=100n" })
    }

    @Test
    fun expandsLinearDecadeAndSourceSteps() {
        val warnings = mutableListOf<String>()
        assertEquals(
            listOf("R=1k", "R=2k", "R=3k"),
            StepSweep.expand("* t\n.step param R 1k 3k 1k\n.end\n", warnings).map { it.label },
        )
        assertEquals(
            listOf("F=1", "F=10", "F=100"),
            StepSweep.expand("* t\n.step dec param F 1 100 1\n.end\n", warnings).map { it.label },
        )
        val runs = StepSweep.expand("* t\n.step V1 list 1 2\n.end\n", warnings)
        assertEquals(listOf("alter V1 dc = 2"), runs[1].commands)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun appendsMissingEnd() {
        val deck = Preprocessor.prepare("* t\nR1 a 0 1k\n.op", SpiceDialect.NGSPICE)
        assertTrue(deck.runs.single().netlist.trimEnd().endsWith(".end"))
    }
}
