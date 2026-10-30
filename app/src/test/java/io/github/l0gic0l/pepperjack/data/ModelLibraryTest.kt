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
    fun decodeFallsBackToLatin1() {
        val latin1 = "* © 1998 µA".toByteArray(Charsets.ISO_8859_1)
        assertEquals("* © 1998 µA", ModelLibrary.decode(latin1))
        assertEquals("* µ", ModelLibrary.decode("* µ".toByteArray()))
    }
}
