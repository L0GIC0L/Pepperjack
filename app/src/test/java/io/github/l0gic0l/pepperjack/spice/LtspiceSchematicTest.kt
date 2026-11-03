package io.github.l0gic0l.pepperjack.spice

import io.github.l0gic0l.pepperjack.model.AnalysisConfig
import io.github.l0gic0l.pepperjack.model.Circuit
import io.github.l0gic0l.pepperjack.model.CircuitKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LtspiceSchematicTest {
    private fun netlist(asc: String): Pair<List<String>, LtspiceSchematic.Imported> {
        val imported = LtspiceSchematic.parse(asc)
        val circuit = Circuit("t", "t", CircuitKind.SCHEMATIC, imported.schematic, analysis = AnalysisConfig())
        return Netlister.netlist(circuit).text.lines() to imported
    }

    // An RC low-pass with a feed-forward capacitor: a mirrored source, R90 parts, and flags.
    private val rc = """
        Version 4
        SHEET 1 880 680
        WIRE 224 -32 192 -32
        WIRE 336 -32 288 -32
        WIRE 144 80 128 80
        WIRE 192 80 192 -32
        WIRE 192 80 144 80
        WIRE 224 80 192 80
        WIRE 336 80 336 -32
        WIRE 336 80 304 80
        WIRE 384 80 336 80
        WIRE 448 80 384 80
        WIRE 448 128 448 80
        WIRE 128 144 128 80
        WIRE 128 256 128 224
        WIRE 448 256 448 192
        FLAG 128 256 0
        FLAG 448 256 0
        FLAG 384 80 out
        FLAG 144 80 in
        SYMBOL voltage 128 128 M0
        SYMATTR InstName V1
        SYMATTR Value PULSE(0 1 0 1n 1n 1m 2m)
        SYMBOL res 320 64 R90
        WINDOW 0 0 56 VBottom 2
        SYMATTR InstName R1
        SYMATTR Value 1k
        SYMBOL cap 432 128 R0
        SYMATTR InstName C1
        SYMATTR Value 4.7µ
        SYMATTR SpiceLine Rser=10m
        SYMBOL cap 288 -48 R90
        SYMATTR InstName C2
        SYMATTR Value 1n
        TEXT -248 280 Left 2 !.tran 3m\n.param x=1
        TEXT -248 320 Left 2 ;a note
        TEXT -248 360 Left 2 !.backanno
    """.trimIndent()

    @Test
    fun importsWiresRotationsMirrorsAndDirectives() {
        val (lines, imported) = netlist(rc)
        assertTrue(lines.toString(), "V1 in 0 PULSE(0 1 0 1n 1n 1m 2m)" in lines)
        assertTrue("R1 out in 1k" in lines)
        assertTrue("C1 out 0 4.7u Rser=10m" in lines)
        assertTrue("C2 out in 1n" in lines)
        assertEquals(".tran 3m\n.param x=1\n* a note", imported.schematic.directives)
        assertTrue(imported.warnings.isEmpty())
    }

    @Test
    fun pinsLandWherePinOrderSays() {
        // A PNP's collector is on top in LTspice and at the bottom in Pepperjack; an op-amp's
        // pins follow SPICE order whatever the symbol's size.
        val asc = """
            Version 4
            SHEET 1 880 680
            FLAG 64 0 c
            FLAG 0 48 b
            FLAG 64 96 e
            FLAG 288 80 inp
            FLAG 288 48 inn
            FLAG 320 32 vp
            FLAG 320 96 vn
            FLAG 352 64 o
            FLAG 496 16 ta
            FLAG 416 16 tb
            SYMBOL pnp 0 0 R0
            SYMATTR InstName Q1
            SYMATTR Value 2N3906
            SYMBOL Opamps\\opamp2 320 0 R0
            SYMATTR InstName U1
            SYMATTR Value TL072
            SYMBOL res 512 0 R90
            SYMATTR InstName R1
            SYMATTR Value 1k
            SYMBOL Misc\\NE555 600 0 R0
            SYMATTR InstName U2
        """.trimIndent()
        val (lines, imported) = netlist(asc)
        assertTrue(lines.toString(), "Q1 c b e 2N3906" in lines)
        assertTrue("XU1 inp inn vp vn o TL072" in lines)
        assertTrue("R1 ta tb 1k" in lines)
        assertTrue(imported.warnings.single(), "U2 (Misc\\\\NE555)" in imported.warnings.single())
        assertFalse(lines.any { it.startsWith("XU2") })
    }

    @Test
    fun decodesWindows1252AndUtf16() {
        val latin = "Version 4\nSYMATTR Value 1µ\n".toByteArray(Charsets.ISO_8859_1)
        assertTrue(LtspiceSchematic.decode(latin).contains("1µ"))
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Version 4\n".toByteArray(Charsets.UTF_16LE)
        assertTrue(LtspiceSchematic.decode(utf16).startsWith("Version 4"))
        assertTrue(LtspiceSchematic.looksLike("x.ASC", ""))
        assertTrue(LtspiceSchematic.looksLike("x.txt", "Version 4\nSHEET 1 880 680"))
    }
}
