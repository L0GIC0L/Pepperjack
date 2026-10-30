package io.github.l0gic0l.pepperjack.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLibraryTest {
    private val vendorFile = """
        |* ACME op-amps. Copyright ACME; redistribution requires this notice.
        |   |  pin 1  |  pin 2  |
        |.model DX D(IS=1e-14
        |+ N=1.05)
        |.SUBCKT AMP1 1 2 3 4 5
        |.model DLOCAL D
        |D1 1 2 DX
        |.ends AMP1
        |V1 1 0 5
        |+ AC 1
        |.tran 1u 1m
        |.model QX NPN(BF=200)
        |.model QY AKO:QX NPN(BF=300)
        |.end
        |${"\u001a\u001a"}
    """.trimMargin()

    @Test
    fun cleanKeepsOnlyDefinitionsLive() {
        val clean = ModelLibrary.clean("﻿" + vendorFile).lines()
        val live = clean.filter { it.isNotBlank() && !it.trimStart().startsWith("*") }
        assertEquals(
            listOf(
                ".model DX D(IS=1e-14", "+ N=1.05)",
                ".SUBCKT AMP1 1 2 3 4 5", ".model DLOCAL D", "D1 1 2 DX", ".ends AMP1",
                ".model QX NPN(BF=200)", ".model QY AKO:QX NPN(BF=300)",
            ),
            live,
        )
        // Nothing is lost: the stray lines are still there, as comments.
        assertTrue("* V1 1 0 5" in clean)
        assertTrue("* + AC 1" in clean)
        assertTrue("* .end" in clean)
        assertTrue(clean.first().startsWith("* ACME op-amps"))
        assertFalse(clean.any { '\u001a' in it || '﻿' in it })
    }

    @Test
    fun parseListsTopLevelDefinitionsOnly() {
        val models = ModelLibrary.parse("acme.lib", vendorFile)
        assertEquals(listOf("AMP1", "DX", "QX"), models.map { it.name })
        assertEquals(listOf("1", "2", "3", "4", "5"), models.first().pins)
        assertEquals("D", models[1].type)
    }

    @Test
    fun cleanClosesSubcircuitsTheFileLeavesOpen() {
        val text = ".subckt A 1 2\nR1 1 2 1k\n.subckt B 1 2\nR1 1 2 2k\n.ends B\n.subckt C 1 2\nR1 1 2 3k\n.end\n"
        val live = ModelLibrary.clean(text).lines().filter { it.isNotBlank() && !it.startsWith("*") }
        assertEquals(
            listOf(".subckt A 1 2", "R1 1 2 1k", ".ends", ".subckt B 1 2", "R1 1 2 2k", ".ends B", ".subckt C 1 2", "R1 1 2 3k", ".ends"),
            live,
        )
        assertEquals(listOf("A", "B", "C"), ModelLibrary.parse("x.lib", ModelLibrary.clean(text)).map { it.name })
        // Balanced files may nest subcircuits, as ngspice allows.
        val nested = ".subckt OUTER 1 2\n.subckt INNER 1 2\nR1 1 2 1k\n.ends INNER\nX1 1 2 INNER\n.ends OUTER\n"
        assertEquals(nested.trimEnd(), ModelLibrary.clean(nested).trimEnd())
        assertEquals(listOf("OUTER"), ModelLibrary.parse("x.lib", nested).map { it.name })
    }

    @Test
    fun decodeFallsBackToLatin1() {
        val latin1 = "* © 1998 µA".toByteArray(Charsets.ISO_8859_1)
        assertEquals("* © 1998 µA", ModelLibrary.decode(latin1))
        assertEquals("* µ", ModelLibrary.decode("* µ".toByteArray()))
    }
}
