package io.github.l0gic0l.pepperjack.spice

import io.github.l0gic0l.pepperjack.model.GridPoint
import io.github.l0gic0l.pepperjack.model.Part
import io.github.l0gic0l.pepperjack.model.PartKind
import io.github.l0gic0l.pepperjack.model.PartLibrary
import io.github.l0gic0l.pepperjack.model.Schematic
import io.github.l0gic0l.pepperjack.model.Wire
import io.github.l0gic0l.pepperjack.model.transformPoint
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reads LTspice schematics (.asc) into Pepperjack schematics.
 *
 * An .asc file places symbols and wires on a 16-unit grid but does not say where a
 * symbol's pins are: that is in LTspice's symbol files. The standard symbols' pin
 * offsets are listed here, from Analog Devices' LTspice schematic reference. Pepperjack's
 * symbols are a little smaller than LTspice's, so each part is placed in the orientation
 * that lines its pins up best, and short wires run from its pins to where LTspice's wires
 * end. Symbols that are not listed (vendor ICs with their own pinouts, hierarchical
 * blocks) are left out and reported.
 */
object LtspiceSchematic {
    data class Imported(val schematic: Schematic, val warnings: List<String>)

    private class Symbol(val kind: PartKind, vararg pins: Pair<Int, Int>) {
        val pins = pins.toList()
    }

    // Pin offsets at R0 in SPICE node order, which is also Pepperjack's pin order.
    private val symbols = buildMap {
        val twoTerminal = listOf("res" to PartKind.RESISTOR, "ind" to PartKind.INDUCTOR, "ind2" to PartKind.INDUCTOR)
        twoTerminal.forEach { (name, kind) -> put(name, Symbol(kind, 16 to 16, 16 to 96)) }
        put("res2", Symbol(PartKind.RESISTOR, 16 to 0, 16 to 64))
        put("cap", Symbol(PartKind.CAPACITOR, 16 to 0, 16 to 64))
        put("polcap", Symbol(PartKind.CAPACITOR, 16 to 0, 16 to 64))
        put("voltage", Symbol(PartKind.VOLTAGE, 0 to 16, 0 to 96))
        put("current", Symbol(PartKind.CURRENT, 0 to 0, 0 to 80))
        put("diode", Symbol(PartKind.DIODE, 16 to 0, 16 to 64))
        put("schottky", Symbol(PartKind.SCHOTTKY, 16 to 0, 16 to 64))
        put("zener", Symbol(PartKind.ZENER, 16 to 0, 16 to 64))
        put("led", Symbol(PartKind.LED, 16 to 0, 16 to 64))
        listOf("npn", "npn2", "npn3").forEach { put(it, Symbol(PartKind.NPN, 64 to 0, 0 to 48, 64 to 96)) }
        listOf("pnp", "pnp2").forEach { put(it, Symbol(PartKind.PNP, 64 to 0, 0 to 48, 64 to 96)) }
        listOf("nmos", "nmos4").forEach { put(it, Symbol(PartKind.NMOS, 48 to 0, 0 to 80, 48 to 96)) }
        listOf("pmos", "pmos4").forEach { put(it, Symbol(PartKind.PMOS, 48 to 0, 0 to 80, 48 to 96)) }
        put("njf", Symbol(PartKind.NJFET, 48 to 0, 0 to 64, 48 to 96))
        put("pjf", Symbol(PartKind.PJFET, 48 to 0, 0 to 64, 48 to 96))
        put("e", Symbol(PartKind.VCVS, 0 to 16, 0 to 96, -48 to 32, -48 to 80))
        put("e2", Symbol(PartKind.VCVS, 0 to 16, 0 to 96, -48 to 80, -48 to 32))
        put("g", Symbol(PartKind.VCCS, 0 to 96, 0 to 16, -48 to 32, -48 to 80))
        put("g2", Symbol(PartKind.VCCS, 0 to 96, 0 to 16, -48 to 80, -48 to 32))
        put("f", Symbol(PartKind.CCCS, 0 to 0, 0 to 80))
        put("h", Symbol(PartKind.CCVS, 0 to 16, 0 to 96))
        put("bv", Symbol(PartKind.BEHAVIORAL, 0 to 16, 0 to 96))
        put("bi", Symbol(PartKind.BEHAVIORAL, 0 to 0, 0 to 80))
        put("bi2", Symbol(PartKind.BEHAVIORAL, 0 to 80, 0 to 0))
        put("sw", Symbol(PartKind.SWITCH, 0 to 16, 0 to 96, -48 to 80, -48 to 32))
        put("opamp", Symbol(PartKind.OPAMP, -32 to 80, -32 to 48, 32 to 64))
        put("opamp2", Symbol(PartKind.OPAMP5, -32 to 80, -32 to 48, 0 to 32, 0 to 96, 32 to 64))
        // LTspice's generic op-amp macro-models, centred on their origin.
        listOf("universalopamp", "universalopamp2").forEach {
            put(it, Symbol(PartKind.OPAMP5, -32 to 16, -32 to -16, 0 to -32, 0 to 32, 32 to 0))
        }
    }

    /** LTspice-only macro-models that ngspice lacks, run on Pepperjack's generic op-amp instead. */
    private val genericOpAmps = setOf("universalopamp", "universalopamp2")

    /** Vendor op-amps in LTspice's Opamps folder share the opamp2 pinout. */
    private val vendorOpAmp = symbols.getValue("opamp2")

    fun looksLike(name: String, text: String) =
        name.endsWith(".asc", ignoreCase = true) || text.trimStart('\ufeff', ' ', '\n', '\r').startsWith("Version 4")

    /** LTspice writes Windows-1252; some versions write UTF-16. */
    fun decode(bytes: ByteArray): String {
        val utf16 = (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
            (bytes.size >= 4 && bytes[1] == 0.toByte() && bytes[3] == 0.toByte())
        val text = when {
            utf16 -> String(bytes, Charsets.UTF_16LE)
            else -> runCatching { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() }
                .getOrElse { String(bytes, Charset.forName("windows-1252")) }
        }
        return text.removePrefix("\ufeff").replace("\r\n", "\n")
    }

    private class PendingSymbol(val name: String, val x: Int, val y: Int, val orientation: String) {
        val attrs = LinkedHashMap<String, String>()
    }

    fun parse(text: String): Imported {
        val warnings = mutableListOf<String>()
        val ltWires = mutableListOf<IntArray>()
        val flags = mutableListOf<Triple<Int, Int, String>>()
        val pending = mutableListOf<PendingSymbol>()
        val directives = mutableListOf<String>()
        var current: PendingSymbol? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            val tokens = line.split(Regex("""\s+"""))
            when (tokens.firstOrNull()?.uppercase(Locale.ROOT)) {
                "WIRE" -> tokens.drop(1).take(4).mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 }?.let { ltWires += it.toIntArray() }
                "FLAG" -> if (tokens.size >= 4) {
                    val x = tokens[1].toIntOrNull()
                    val y = tokens[2].toIntOrNull()
                    if (x != null && y != null) flags += Triple(x, y, tokens[3])
                }
                "SYMBOL" -> if (tokens.size >= 5) {
                    current = PendingSymbol(tokens[1], tokens[2].toInt(), tokens[3].toInt(), tokens[4].uppercase(Locale.ROOT)).also { pending += it }
                }
                "SYMATTR" -> current?.let { symbol ->
                    if (tokens.size >= 2) symbol.attrs[tokens[1]] = line.split(Regex("""\s+"""), 3).getOrElse(2) { "" }
                }
                "TEXT" -> {
                    // TEXT x y justification size content: "!" marks a directive, ";" a comment.
                    val content = line.split(Regex("""\s+"""), 6).getOrNull(5) ?: ""
                    val body = content.drop(1).replace("\\n", "\n")
                    when {
                        content.startsWith("!") -> directives += body.lines().filterNot { it.trim().startsWith(".backanno", ignoreCase = true) }
                        content.startsWith(";") -> directives += body.lines().map { "* $it" }
                    }
                }
            }
        }

        fun grid(v: Int) = (v / 16.0).roundToInt()
        fun grid(x: Int, y: Int) = GridPoint(grid(x), grid(y))

        var nextId = 1L
        val parts = mutableListOf<Part>()
        val wires = mutableListOf<Wire>()
        fun wire(a: GridPoint, b: GridPoint) {
            if (a != b) wires += Wire(nextId++, a.x, a.y, b.x, b.y)
        }

        for (w in ltWires) {
            val a = grid(w[0], w[1])
            val b = grid(w[2], w[3])
            if (a.x != b.x && a.y != b.y) {
                // Pepperjack wires are drawn square: take a diagonal LTspice wire round its corner.
                wire(a, GridPoint(b.x, a.y))
                wire(GridPoint(b.x, a.y), b)
            } else {
                wire(a, b)
            }
        }
        for ((x, y, name) in flags) {
            val at = grid(x, y)
            parts += if (name == "0") {
                Part(nextId++, PartKind.GROUND, "GND", at.x, at.y)
            } else {
                Part(nextId++, PartKind.LABEL, name, at.x, at.y, value = name)
            }
        }

        val skipped = mutableListOf<String>()
        for (symbol in pending) {
            // Library paths are written with doubled backslashes: Opamps\\AD822 in the file.
            val path = symbol.name.split('\\', '/').filter { it.isNotEmpty() }
            val leaf = path.last()
            val folder = path.dropLast(1).joinToString("/").lowercase(Locale.ROOT)
            val def = symbols[leaf.lowercase(Locale.ROOT)] ?: if (folder == "opamps") vendorOpAmp else null
            val ref = symbol.attrs["InstName"] ?: leaf
            if (def == null) {
                skipped += "$ref (${symbol.name})"
                continue
            }
            val ltPins = def.pins.map { (px, py) ->
                val (dx, dy) = orient(px, py, symbol.orientation)
                grid(symbol.x + dx, symbol.y + dy)
            }
            val pinDefs = PartLibrary[def.kind].pins
            if (symbol.name.lowercase(Locale.ROOT).let { it == "nmos4" || it == "pmos4" }) {
                warnings += "$ref: Pepperjack MOSFETs tie the body to the source."
            }
            // The orientation and offset that bring Pepperjack's pins closest to LTspice's.
            val natural = natural(symbol.orientation)
            val (rotation, mirrored, origin) = (0..7).map { (it % 4) to (it >= 4) }.map { (rot, mir) ->
                val offsets = pinDefs.map { pin ->
                    val (tx, ty) = transformPoint(pin.x.toFloat(), pin.y.toFloat(), rot, mir)
                    GridPoint(tx.roundToInt(), ty.roundToInt())
                }
                val ox = ltPins.indices.map { ltPins[it].x - offsets[it].x }.sorted()[ltPins.size / 2]
                val oy = ltPins.indices.map { ltPins[it].y - offsets[it].y }.sorted()[ltPins.size / 2]
                val cost = ltPins.indices.sumOf { abs(ox + offsets[it].x - ltPins[it].x) + abs(oy + offsets[it].y - ltPins[it].y) }
                Triple(Triple(rot, mir, GridPoint(ox, oy)), cost, if (rot to mir == natural) 0 else 1)
            }.minWith(compareBy({ it.second }, { it.third })).first
            val generic = leaf.lowercase(Locale.ROOT) in genericOpAmps
            if (generic) warnings += "$ref: LTspice's $leaf is simulated with Pepperjack's generic op-amp (100 dB, 10 MHz)."
            val value = if (generic) SpiceModels.OPAMP else valueOf(def.kind, symbol, leaf)
            val part = Part(nextId++, def.kind, ref, origin.x, origin.y, rotation, mirrored, value = value)
            parts += part
            val placed = pinDefs.map { pin ->
                val (tx, ty) = transformPoint(pin.x.toFloat(), pin.y.toFloat(), rotation, mirrored)
                GridPoint(origin.x + tx.roundToInt(), origin.y + ty.roundToInt())
            }
            placed.forEachIndexed { i, from ->
                val to = ltPins[i]
                if (from == to) return@forEachIndexed
                // Square the stub off on whichever side keeps it clear of the part's other pins.
                val others = (placed + ltPins).filterIndexed { j, _ -> j % placed.size != i }
                val horizontalFirst = GridPoint(to.x, from.y)
                val corner = if (passesThrough(from, horizontalFirst, to, others)) GridPoint(from.x, to.y) else horizontalFirst
                wire(from, corner)
                wire(corner, to)
            }
        }
        if (skipped.isNotEmpty()) {
            warnings += "Left out, as Pepperjack has no symbol for them: ${skipped.joinToString()}."
            directives.add(0, "* Not imported from LTspice: ${skipped.joinToString()}")
        }
        return Imported(Schematic(parts, wires, directives.joinToString("\n").trim()), warnings)
    }

    private fun passesThrough(a: GridPoint, corner: GridPoint, b: GridPoint, points: List<GridPoint>): Boolean {
        fun on(p: GridPoint, s: GridPoint, e: GridPoint) =
            (p.x == s.x && p.x == e.x && p.y in minOf(s.y, e.y)..maxOf(s.y, e.y)) ||
                (p.y == s.y && p.y == e.y && p.x in minOf(s.x, e.x)..maxOf(s.x, e.x))
        return points.any { (on(it, a, corner) || on(it, corner, b)) && it != b }
    }

    /** LTspice's orientation of a symbol offset: rotate, then mirror. */
    private fun orient(px: Int, py: Int, orientation: String): Pair<Int, Int> = when (orientation) {
        "R90" -> -py to px
        "R180" -> -px to -py
        "R270" -> py to -px
        "M0" -> -px to py
        "M90" -> py to px
        "M180" -> px to -py
        "M270" -> -py to -px
        else -> px to py
    }

    /** Pepperjack mirrors before rotating, so LTspice's Mn is Pepperjack's mirror with the opposite turn. */
    private fun natural(orientation: String): Pair<Int, Boolean> {
        val turns = (orientation.drop(1).toIntOrNull() ?: 0) / 90
        return if (orientation.startsWith("M")) ((4 - turns) % 4) to true else turns to false
    }

    private fun valueOf(kind: PartKind, symbol: PendingSymbol, leaf: String): String {
        fun attr(name: String) = symbol.attrs[name]?.trim().orEmpty().replace('µ', 'u').replace('μ', 'u')
        val main = attr("Value").ifEmpty { attr("SpiceModel") }
        return when (kind) {
            PartKind.OPAMP5 -> main.ifEmpty { leaf }
            PartKind.OPAMP -> ""
            else -> listOf(main, attr("Value2"), attr("SpiceLine"), attr("SpiceLine2")).filter { it.isNotEmpty() }.joinToString(" ")
        }
    }
}
