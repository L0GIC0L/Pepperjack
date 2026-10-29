package io.github.l0gic0l.pepperjack.model

import io.github.l0gic0l.pepperjack.spice.ModelFamily
import io.github.l0gic0l.pepperjack.spice.SpiceModel
import io.github.l0gic0l.pepperjack.spice.SpiceModels
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

@kotlinx.serialization.Serializable
enum class PartKind {
    RESISTOR, CAPACITOR, INDUCTOR,
    VOLTAGE, CURRENT,
    DIODE, ZENER, LED,
    NPN, PNP, NMOS, PMOS,
    OPAMP, OPAMP5,
    VCVS, VCCS, CCVS, CCCS, BEHAVIORAL, SWITCH,
    GROUND, LABEL,
    POT, TRANSFORMER, CRYSTAL,
    SPST, SPDT, AMMETER, VOLTMETER,
    SCHOTTKY, NJFET, PJFET,
    COMPARATOR, REGULATOR, REGULATOR_ADJ, TIMER555, OPTO,
    AND, NAND, OR, NOR, XOR, NOT, SCHMITT, DFF,
    CUSTOM,
}

enum class PartCategory(val title: String) {
    PASSIVE("Passives"),
    SOURCE("Sources"),
    INTERACTIVE("Switches & meters"),
    SEMICONDUCTOR("Semiconductors"),
    IC("Analog ICs"),
    DIGITAL("Digital logic"),
    ANALOG("Controlled sources"),
    CONNECTION("Connections"),
    CUSTOM("Your symbols"),
}

/** How the properties sheet edits a part's value. */
enum class ValueEditor { NONE, TEXT, EXPRESSION, SOURCE, MODEL, LABEL, POT, TRANSFORMER, SPST, SPDT, LOGIC, PARAMS }

data class PinDef(val name: String, val x: Int, val y: Int)

enum class GlyphAlign { START, CENTER, END }

/** Symbol geometry in grid units, drawn relative to the part origin before rotation. */
sealed interface Shape {
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : Shape
    data class Poly(val points: List<Pair<Float, Float>>, val closed: Boolean = false, val filled: Boolean = false) : Shape
    data class Circle(val cx: Float, val cy: Float, val r: Float, val filled: Boolean = false) : Shape

    /** Text centred vertically on (x, y); [size] is the text height in grid units. Text never rotates. */
    data class Glyph(
        val x: Float,
        val y: Float,
        val text: String,
        val size: Float = 0.9f,
        val bold: Boolean = true,
        val align: GlyphAlign = GlyphAlign.CENTER,
    ) : Shape
}

data class PartDef(
    val kind: PartKind,
    val title: String,
    /** SPICE element letter; the netlister prefixes it when a reference does not start with it. */
    val prefix: String,
    /** Prefix shown in reference designators (U1 for ICs, otherwise the SPICE letter). */
    val refPrefix: String,
    val category: PartCategory,
    val defaultValue: String,
    val valueLabel: String,
    val valueHint: String,
    val pins: List<PinDef>,
    val shapes: List<Shape>,
    /** Model families offered in the model picker; the first model of [defaultValue] is the default. */
    val families: List<ModelFamily> = emptyList(),
    val editor: ValueEditor = ValueEditor.TEXT,
    /** Symbols that show their state (switch positions) draw these instead of [shapes]. */
    val stateShapes: ((String) -> List<Shape>)? = null,
) {
    val hasValue: Boolean get() = editor != ValueEditor.NONE
    val emitsSpice: Boolean get() = kind != PartKind.GROUND && kind != PartKind.LABEL

    /** Built-in models for this symbol. */
    val models: List<SpiceModel> get() = families.flatMap { SpiceModels.family(it) }

    fun shapesFor(value: String): List<Shape> = stateShapes?.invoke(value) ?: shapes
}

/** The drawable definition of a placed part, including user-made symbols. */
val Part.def: PartDef
    get() = if (kind == PartKind.CUSTOM && symbol != null) CustomSymbols.def(symbol) else PartLibrary[kind]

/** Rotates/mirrors a symbol-space point. Rotation is clockwise on a y-down screen. */
fun transformPoint(x: Float, y: Float, rotation: Int, mirrored: Boolean): Pair<Float, Float> {
    var px = if (mirrored) -x else x
    var py = y
    repeat(rotation and 3) {
        val nx = -py
        py = px
        px = nx
    }
    return px to py
}

fun Part.pinPositions(): List<GridPoint> = def.pins.map { pin ->
    val (tx, ty) = transformPoint(pin.x.toFloat(), pin.y.toFloat(), rotation, mirrored)
    GridPoint(x + Math.round(tx), y + Math.round(ty))
}

/** Axis-aligned bounds of the transformed symbol in grid units: left, top, right, bottom. */
fun Part.bounds(): FloatArray {
    val def = def
    var left = Float.MAX_VALUE
    var top = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE
    fun add(px: Float, py: Float) {
        val (tx, ty) = transformPoint(px, py, rotation, mirrored)
        left = minOf(left, x + tx); right = maxOf(right, x + tx)
        top = minOf(top, y + ty); bottom = maxOf(bottom, y + ty)
    }
    add(0f, 0f)
    def.pins.forEach { add(it.x.toFloat(), it.y.toFloat()) }
    def.shapesFor(value).forEach { shape ->
        when (shape) {
            is Shape.Line -> { add(shape.x1, shape.y1); add(shape.x2, shape.y2) }
            is Shape.Poly -> shape.points.forEach { add(it.first, it.second) }
            is Shape.Circle -> {
                add(shape.cx - shape.r, shape.cy - shape.r); add(shape.cx + shape.r, shape.cy + shape.r)
            }
            is Shape.Glyph -> add(shape.x, shape.y)
        }
    }
    return floatArrayOf(left, top, right, bottom)
}

object PartLibrary {
    private val ordered: List<PartDef> by lazy { build() }
    private val defs: Map<PartKind, PartDef> by lazy { ordered.associateBy { it.kind } }

    operator fun get(kind: PartKind): PartDef = defs.getValue(kind)

    /** Every built-in symbol, in part-picker order. */
    val all: List<PartDef> get() = ordered.filter { it.kind != PartKind.CUSTOM }

    // ---- geometry helpers ----

    private fun line(x1: Number, y1: Number, x2: Number, y2: Number) =
        Shape.Line(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())

    private fun poly(vararg points: Pair<Number, Number>, closed: Boolean = false, filled: Boolean = false) =
        Shape.Poly(points.map { it.first.toFloat() to it.second.toFloat() }, closed, filled)

    private fun rect(left: Number, top: Number, right: Number, bottom: Number) =
        poly(left to top, right to top, right to bottom, left to bottom, closed = true)

    private fun arc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float, segments: Int = 10): Shape.Poly {
        val points = (0..segments).map { i ->
            val a = (startDeg + sweepDeg * i / segments) * PI.toFloat() / 180f
            (cx + r * cos(a)) to (cy + r * sin(a))
        }
        return Shape.Poly(points)
    }

    /** Quadratic Bézier from p0 to p2 bent towards p1. */
    private fun curve(p0: Pair<Float, Float>, p1: Pair<Float, Float>, p2: Pair<Float, Float>, segments: Int = 10) =
        Shape.Poly(
            (0..segments).map { i ->
                val t = i.toFloat() / segments
                val u = 1 - t
                (u * u * p0.first + 2 * u * t * p1.first + t * t * p2.first) to
                    (u * u * p0.second + 2 * u * t * p1.second + t * t * p2.second)
            },
        )

    /** Filled arrowhead whose tip sits at (toX, toY), pointing away from (fromX, fromY). */
    private fun arrowHead(fromX: Float, fromY: Float, toX: Float, toY: Float, length: Float = 0.35f, half: Float = 0.18f): Shape.Poly {
        val len = hypot(toX - fromX, toY - fromY)
        val ux = (toX - fromX) / len
        val uy = (toY - fromY) / len
        val bx = toX - ux * length
        val by = toY - uy * length
        return Shape.Poly(
            listOf(toX to toY, (bx - uy * half) to (by + ux * half), (bx + uy * half) to (by - ux * half)),
            closed = true,
            filled = true,
        )
    }

    private fun plus(cx: Float, cy: Float, size: Float = 0.2f) =
        listOf(line(cx - size, cy, cx + size, cy), line(cx, cy - size, cx, cy + size))

    private fun minus(cx: Float, cy: Float, size: Float = 0.2f) = line(cx - size, cy, cx + size, cy)

    private fun label(x: Number, y: Number, text: String, size: Float = 0.55f, align: GlyphAlign = GlyphAlign.CENTER) =
        Shape.Glyph(x.toFloat(), y.toFloat(), text, size, bold = false, align = align)

    private val twoPins = listOf(PinDef("1", 0, 0), PinDef("2", 0, 4))
    private val sourcePins = listOf(PinDef("+", 0, 0), PinDef("-", 0, 4))
    private val diodePins = listOf(PinDef("A", 0, 0), PinDef("K", 0, 4))
    private val threePins = listOf(PinDef("D", 1, -2), PinDef("G", -2, 0), PinDef("S", 1, 2))
    private val controlledPins = listOf(PinDef("+", 0, 0), PinDef("-", 0, 4), PinDef("C+", -2, 1), PinDef("C-", -2, 3))
    private val gatePins = listOf(PinDef("A", -3, -1), PinDef("B", -3, 1), PinDef("Y", 3, 0))
    private val inverterPins = listOf(PinDef("A", -3, 0), PinDef("Y", 3, 0))
    private val ampPins = listOf(
        PinDef("IN+", -3, 1), PinDef("IN-", -3, -1),
        PinDef("V+", 0, -3), PinDef("V-", 0, 3), PinDef("OUT", 3, 0),
    )
    private val diamond = poly(0 to 1, 0.8 to 2, 0 to 3, -0.8 to 2, closed = true)
    private val diamondLeads = listOf(line(0, 0, 0, 1), line(0, 3, 0, 4))
    private val controlLeads = listOf(line(-2, 1, -1, 1), line(-2, 3, -1, 3)) +
        plus(-1.4f, 1.45f, 0.15f) + minus(-1.4f, 2.55f, 0.15f)

    private val resistorBody = listOf(
        line(0, 0, 0, 1),
        poly(
            0 to 1, 0.4 to 1.1667, -0.4 to 1.5, 0.4 to 1.8333, -0.4 to 2.1667,
            0.4 to 2.5, -0.4 to 2.8333, 0 to 3,
        ),
        line(0, 3, 0, 4),
    )

    private fun diodeBody(bar: Shape) = listOf(
        line(0, 0, 0, 1.5),
        poly(-0.6 to 1.5, 0.6 to 1.5, 0 to 2.5, closed = true, filled = true),
        bar,
        line(0, 2.5, 0, 4),
    )

    private fun bjt(pnp: Boolean): List<Shape> {
        // Collector/emitter leads meet the base bar at x = -0.5.
        val shapes = mutableListOf<Shape>(
            line(-2, 0, -0.5, 0),
            line(-0.5, -0.9, -0.5, 0.9),
            line(-0.5, -0.4, 1, -1.2), line(1, -1.2, 1, -2),
            line(-0.5, 0.4, 1, 1.2), line(1, 1.2, 1, 2),
        )
        shapes += if (pnp) {
            arrowHead(1f, -1.2f, -0.05f, -0.64f) // emitter on top, arrow points in
        } else {
            arrowHead(-0.5f, 0.4f, 0.78f, 1.08f) // emitter at the bottom, arrow points out
        }
        return shapes
    }

    private fun mosfet(pmos: Boolean): List<Shape> {
        val sourceY = if (pmos) -0.65f else 0.65f
        val drainY = -sourceY
        val shapes = mutableListOf<Shape>(
            line(-2, 0, -0.7, 0),
            line(-0.7, -0.8, -0.7, 0.8),
            line(-0.35, -0.9, -0.35, -0.4), line(-0.35, -0.25, -0.35, 0.25), line(-0.35, 0.4, -0.35, 0.9),
            line(-0.35, drainY, 1, drainY), line(1, drainY, 1, if (pmos) 2 else -2),
            line(-0.35, sourceY, 1, sourceY), line(1, sourceY, 1, if (pmos) -2 else 2),
            line(-0.35, 0, 1, 0), line(1, 0, 1, sourceY),
        )
        shapes += if (pmos) arrowHead(-0.3f, 0f, 0.55f, 0f) else arrowHead(0.6f, 0f, -0.3f, 0f)
        return shapes
    }

    private fun jfet(pChannel: Boolean): List<Shape> = listOf(
        line(-2, 0, -0.3, 0),
        line(-0.3, -1, -0.3, 1),
        line(-0.3, -0.7, 1, -0.7), line(1, -0.7, 1, -2),
        line(-0.3, 0.7, 1, 0.7), line(1, 0.7, 1, 2),
        if (pChannel) arrowHead(-0.4f, 0f, -1.25f, 0f) else arrowHead(-1.3f, 0f, -0.4f, 0f),
    )

    private fun opampBody(): List<Shape> = listOf(
        poly(-2 to -2, -2 to 2, 2 to 0, closed = true),
        line(-3, -1, -2, -1), line(-3, 1, -2, 1), line(2, 0, 3, 0),
        minus(-1.5f, -1f),
    ) + plus(-1.5f, 1f)

    private val supplyLeads = listOf(line(0, -3, 0, -1), line(0, 3, 0, 1))

    private fun meter(letter: String) = listOf(
        line(0, 0, 0, 1), Shape.Circle(0f, 2f, 1f), line(0, 3, 0, 4),
        Shape.Glyph(0f, 2f, letter, 1f),
    ) + plus(0.75f, 0.75f, 0.15f)

    private fun regulator(middle: String) = listOf(
        rect(-2, -1.2, 2, 1),
        line(-3, 0, -2, 0), line(2, 0, 3, 0), line(0, 1, 0, 2),
        label(-1.8, -0.3, "IN", align = GlyphAlign.START),
        label(1.8, -0.3, "OUT", align = GlyphAlign.END),
        label(0, 0.5, middle),
    )

    // Logic gate outlines, inputs at x = -3, output at x = 3.
    private val andBody = listOf(
        line(-1.5, -1.5, -1.5, 1.5), line(-1.5, -1.5, 0, -1.5), line(0, 1.5, -1.5, 1.5),
        arc(0f, 0f, 1.5f, -90f, 180f, 14),
    )
    private val orBack = arc(-3.5f, 0f, 2.5f, -36.87f, 73.74f, 10)
    private val orBody = listOf(
        orBack,
        curve(-1.5f to -1.5f, 0.6f to -1.5f, 1.5f to 0f),
        curve(-1.5f to 1.5f, 0.6f to 1.5f, 1.5f to 0f),
    )
    private fun gateLeads(inputEnd: Float, outputStart: Float) =
        listOf(line(-3, -1, inputEnd, -1), line(-3, 1, inputEnd, 1), line(outputStart, 0, 3, 0))
    private val bubble = Shape.Circle(1.8f, 0f, 0.3f)
    private val inverterBody = listOf(poly(-1.5 to -1.2, -1.5 to 1.2, 1.1 to 0, closed = true), Shape.Circle(1.4f, 0f, 0.3f))

    private fun spstShapes(value: String): List<Shape> {
        val closed = value.trim().equals("closed", ignoreCase = true)
        return listOf(
            line(0, 0, 0, 1.28), Shape.Circle(0f, 1.4f, 0.12f), Shape.Circle(0f, 2.6f, 0.12f), line(0, 2.72, 0, 4),
            if (closed) line(0.0, 2.48, 0.12, 1.45) else line(0.08, 2.5, 0.95, 1.55),
        )
    }

    private fun spdtShapes(value: String): List<Shape> {
        val toB = value.trim().equals("B", ignoreCase = true)
        return listOf(
            line(0, 4, 0, 2.72), Shape.Circle(0f, 2.6f, 0.12f),
            line(-1, 0, -1, 1.28), Shape.Circle(-1f, 1.4f, 0.12f),
            line(1, 0, 1, 1.28), Shape.Circle(1f, 1.4f, 0.12f),
            if (toB) line(0.05, 2.5, 0.9, 1.5) else line(-0.05, 2.5, -0.9, 1.5),
            label(-1.45, 0.75, "A", 0.5f), label(1.45, 0.75, "B", 0.5f),
        )
    }

    private fun build(): List<PartDef> = listOf(
        // ---- passives ----
        PartDef(
            PartKind.RESISTOR, "Resistor", "R", "R", PartCategory.PASSIVE, "1k", "Resistance", "e.g. 4.7k, 10Meg",
            twoPins, resistorBody,
        ),
        PartDef(
            PartKind.CAPACITOR, "Capacitor", "C", "C", PartCategory.PASSIVE, "100n", "Capacitance", "e.g. 100n, 4.7u",
            twoPins,
            listOf(
                line(0, 0, 0, 1.75), line(-0.75, 1.75, 0.75, 1.75),
                line(-0.75, 2.25, 0.75, 2.25), line(0, 2.25, 0, 4),
            ),
        ),
        PartDef(
            PartKind.INDUCTOR, "Inductor", "L", "L", PartCategory.PASSIVE, "10m", "Inductance", "e.g. 10u, 1m",
            twoPins,
            listOf(line(0, 0, 0, 1)) +
                (0 until 4).map { arc(0f, 1.25f + 0.5f * it, 0.25f, -90f, 180f) } +
                line(0, 3, 0, 4),
        ),
        PartDef(
            PartKind.POT, "Potentiometer", "R", "RV", PartCategory.PASSIVE, "10k 50%", "Resistance and wiper",
            "Total resistance and wiper position, e.g. 10k 25%",
            listOf(PinDef("1", 0, 0), PinDef("2", 0, 4), PinDef("W", 2, 2)),
            resistorBody + listOf(line(2, 2, 0.9, 2), arrowHead(2f, 2f, 0.5f, 2f, 0.4f, 0.2f)),
            editor = ValueEditor.POT,
        ),
        PartDef(
            PartKind.TRANSFORMER, "Transformer", "L", "T", PartCategory.PASSIVE, "10m 10:1", "Inductance and ratio",
            "Primary inductance, turns ratio and coupling, e.g. 10m 10:1 0.999",
            listOf(PinDef("P1", -2, 0), PinDef("P2", -2, 4), PinDef("S1", 2, 0), PinDef("S2", 2, 4)),
            listOf(line(-2, 0, -1, 0), line(-1, 0, -1, 1), line(-1, 3, -1, 4), line(-1, 4, -2, 4)) +
                (0 until 4).map { arc(-1f, 1.25f + 0.5f * it, 0.25f, -90f, 180f) } +
                listOf(line(2, 0, 1, 0), line(1, 0, 1, 1), line(1, 3, 1, 4), line(1, 4, 2, 4)) +
                (0 until 4).map { arc(1f, 1.25f + 0.5f * it, 0.25f, -90f, -180f) } +
                listOf(
                    line(-0.2, 0.8, -0.2, 3.2), line(0.2, 0.8, 0.2, 3.2),
                    Shape.Circle(-0.55f, 0.75f, 0.12f, filled = true), Shape.Circle(0.55f, 0.75f, 0.12f, filled = true),
                ),
            editor = ValueEditor.TRANSFORMER,
        ),
        PartDef(
            PartKind.CRYSTAL, "Crystal", "X", "Y", PartCategory.PASSIVE, "XTAL_16MEG", "Model", "Crystal model",
            twoPins,
            listOf(
                line(0, 0, 0, 1.3), line(-0.7, 1.3, 0.7, 1.3), rect(-0.45, 1.6, 0.45, 2.4),
                line(-0.7, 2.7, 0.7, 2.7), line(0, 2.7, 0, 4),
            ),
            families = listOf(ModelFamily.CRYSTAL), editor = ValueEditor.MODEL,
        ),

        // ---- sources ----
        PartDef(
            PartKind.VOLTAGE, "Voltage source", "V", "V", PartCategory.SOURCE, "DC 5", "Source",
            "DC 5, AC 1, SIN(0 1 1k), PULSE(0 5 0 1n 1n 0.5m 1m)",
            sourcePins,
            listOf(line(0, 0, 0, 1), Shape.Circle(0f, 2f, 1f), line(0, 3, 0, 4), minus(0f, 2.5f)) + plus(0f, 1.5f),
            editor = ValueEditor.SOURCE,
        ),
        PartDef(
            PartKind.CURRENT, "Current source", "I", "I", PartCategory.SOURCE, "DC 1m", "Source",
            "DC 1m, AC 1, SIN(0 1m 1k)",
            sourcePins,
            listOf(
                line(0, 0, 0, 1), Shape.Circle(0f, 2f, 1f), line(0, 3, 0, 4),
                line(0, 1.4, 0, 2.4), arrowHead(0f, 1.4f, 0f, 2.65f),
            ),
            editor = ValueEditor.SOURCE,
        ),

        // ---- switches and meters ----
        PartDef(
            PartKind.SPST, "Toggle switch", "R", "SW", PartCategory.INTERACTIVE, "open", "Position",
            "Tap it while live simulating to flip it",
            twoPins, spstShapes("open"), editor = ValueEditor.SPST, stateShapes = ::spstShapes,
        ),
        PartDef(
            PartKind.SPDT, "Changeover switch", "R", "SW", PartCategory.INTERACTIVE, "A", "Position",
            "COM connects to A or B; tap it while live simulating",
            listOf(PinDef("COM", 0, 4), PinDef("A", -1, 0), PinDef("B", 1, 0)),
            spdtShapes("A"), editor = ValueEditor.SPDT, stateShapes = ::spdtShapes,
        ),
        PartDef(
            PartKind.AMMETER, "Ammeter", "V", "AM", PartCategory.INTERACTIVE, "", "", "",
            twoPins, meter("A"), editor = ValueEditor.NONE,
        ),
        PartDef(
            PartKind.VOLTMETER, "Voltmeter", "R", "VM", PartCategory.INTERACTIVE, "10Meg", "Input resistance",
            "Like a bench multimeter's 10 MΩ; use 1T for an ideal meter",
            twoPins, meter("V"),
        ),

        // ---- semiconductors ----
        PartDef(
            PartKind.DIODE, "Diode", "D", "D", PartCategory.SEMICONDUCTOR, "1N4148", "Model", "Diode model name",
            diodePins, diodeBody(line(-0.6, 2.5, 0.6, 2.5)),
            families = listOf(ModelFamily.DIODE, ModelFamily.SCHOTTKY), editor = ValueEditor.MODEL,
        ),
        PartDef(
            PartKind.SCHOTTKY, "Schottky diode", "D", "D", PartCategory.SEMICONDUCTOR, "1N5819", "Model", "Diode model name",
            diodePins,
            diodeBody(poly(-0.6 to 2.75, -0.85 to 2.75, -0.85 to 2.5, 0.85 to 2.5, 0.85 to 2.25, 0.6 to 2.25)),
            families = listOf(ModelFamily.SCHOTTKY, ModelFamily.DIODE), editor = ValueEditor.MODEL,
        ),
        PartDef(
            PartKind.ZENER, "Zener diode", "D", "D", PartCategory.SEMICONDUCTOR, "1N4733A", "Model", "Zener model name",
            diodePins, diodeBody(poly(-0.8 to 2.3, -0.6 to 2.5, 0.6 to 2.5, 0.8 to 2.7)),
            families = listOf(ModelFamily.ZENER), editor = ValueEditor.MODEL,
        ),
        PartDef(
            PartKind.LED, "LED", "D", "D", PartCategory.SEMICONDUCTOR, "LED_RED", "Model", "LED model name",
            diodePins,
            diodeBody(line(-0.6, 2.5, 0.6, 2.5)) + listOf(
                line(0.75, 1.9, 1.3, 1.45), arrowHead(0.75f, 1.9f, 1.4f, 1.37f, 0.25f, 0.13f),
                line(0.75, 2.4, 1.3, 1.95), arrowHead(0.75f, 2.4f, 1.4f, 1.87f, 0.25f, 0.13f),
            ),
            families = listOf(ModelFamily.LED), editor = ValueEditor.MODEL,
        ),
        PartDef(
            PartKind.NPN, "NPN transistor", "Q", "Q", PartCategory.SEMICONDUCTOR, "2N3904", "Model", "BJT model name",
            listOf(PinDef("C", 1, -2), PinDef("B", -2, 0), PinDef("E", 1, 2)), bjt(pnp = false),
            families = listOf(ModelFamily.NPN), editor = ValueEditor.MODEL,
        ),
        PartDef(
            PartKind.PNP, "PNP transistor", "Q", "Q", PartCategory.SEMICONDUCTOR, "2N3906", "Model", "BJT model name",
            listOf(PinDef("C", 1, 2), PinDef("B", -2, 0), PinDef("E", 1, -2)), bjt(pnp = true),
            families = listOf(ModelFamily.PNP), editor = ValueEditor.MODEL,
        ),
        PartDef(
            PartKind.NJFET, "N-channel JFET", "J", "J", PartCategory.SEMICONDUCTOR, "2N5457", "Model", "JFET model name",
            threePins, jfet(pChannel = false),
            families = listOf(ModelFamily.NJF), editor = ValueEditor.MODEL,
        ),
        PartDef(
            PartKind.PJFET, "P-channel JFET", "J", "J", PartCategory.SEMICONDUCTOR, "2N5460", "Model", "JFET model name",
            listOf(PinDef("D", 1, 2), PinDef("G", -2, 0), PinDef("S", 1, -2)), jfet(pChannel = true),
            families = listOf(ModelFamily.PJF), editor = ValueEditor.MODEL,
        ),
        PartDef(
            PartKind.NMOS, "N-channel MOSFET", "M", "M", PartCategory.SEMICONDUCTOR, "2N7000", "Model", "MOSFET model name",
            threePins, mosfet(pmos = false),
            families = listOf(ModelFamily.NMOS), editor = ValueEditor.MODEL,
        ),
        PartDef(
            PartKind.PMOS, "P-channel MOSFET", "M", "M", PartCategory.SEMICONDUCTOR, "BS250", "Model", "MOSFET model name",
            listOf(PinDef("D", 1, 2), PinDef("G", -2, 0), PinDef("S", 1, -2)), mosfet(pmos = true),
            families = listOf(ModelFamily.PMOS), editor = ValueEditor.MODEL,
        ),

        // ---- analog ICs ----
        PartDef(
            PartKind.OPAMP, "Ideal op-amp", "X", "U", PartCategory.IC, "", "", "",
            listOf(PinDef("IN+", -3, 1), PinDef("IN-", -3, -1), PinDef("OUT", 3, 0)), opampBody(),
            editor = ValueEditor.NONE,
        ),
        PartDef(
            PartKind.OPAMP5, "Op-amp", "X", "U", PartCategory.IC, SpiceModels.OPAMP, "Model", "Op-amp model",
            ampPins, opampBody() + supplyLeads,
            families = listOf(ModelFamily.OPAMP), editor = ValueEditor.MODEL,
        ),
        PartDef(
            PartKind.COMPARATOR, "Comparator", "X", "U", PartCategory.IC, "PJ_COMP", "Model", "Comparator model",
            ampPins,
            opampBody() + supplyLeads + poly(-0.9 to 0.45, -0.35 to 0.45, -0.35 to -0.45, 0.2 to -0.45),
            families = listOf(ModelFamily.COMPARATOR), editor = ValueEditor.MODEL,
        ),
        PartDef(
            PartKind.REGULATOR, "Voltage regulator", "X", "U", PartCategory.IC, "LM7805", "Model", "Regulator model",
            listOf(PinDef("IN", -3, 0), PinDef("GND", 0, 2), PinDef("OUT", 3, 0)), regulator("GND"),
            families = listOf(ModelFamily.REGULATOR), editor = ValueEditor.MODEL,
        ),
        PartDef(
            PartKind.REGULATOR_ADJ, "Adjustable regulator", "X", "U", PartCategory.IC, "LM317", "Model", "Regulator model",
            listOf(PinDef("IN", -3, 0), PinDef("ADJ", 0, 2), PinDef("OUT", 3, 0)), regulator("ADJ"),
            families = listOf(ModelFamily.REGULATOR_ADJ), editor = ValueEditor.MODEL,
        ),
        PartDef(
            PartKind.TIMER555, "555 timer", "X", "U", PartCategory.IC, "NE555", "Model", "Timer model",
            // Subcircuit port order: gnd trig out reset ctrl thr dis vcc.
            listOf(
                PinDef("GND", -1, 4), PinDef("TRIG", -4, -2), PinDef("OUT", 4, 0), PinDef("RESET", 1, -4),
                PinDef("CTRL", 1, 4), PinDef("THR", -4, 0), PinDef("DIS", -4, 2), PinDef("VCC", -1, -4),
            ),
            listOf(
                rect(-3, -3, 3, 3),
                line(-1, -4, -1, -3), line(1, -4, 1, -3), line(-1, 4, -1, 3), line(1, 4, 1, 3),
                line(-4, -2, -3, -2), line(-4, 0, -3, 0), line(-4, 2, -3, 2), line(4, 0, 3, 0),
                label(-2.75, -2, "TRIG", align = GlyphAlign.START), label(-2.75, 0, "THR", align = GlyphAlign.START),
                label(-2.75, 2, "DIS", align = GlyphAlign.START), label(2.75, 0, "OUT", align = GlyphAlign.END),
                label(-1, -2.45, "VCC", 0.5f), label(1, -2.45, "RST", 0.5f),
                label(-1, 2.45, "GND", 0.5f), label(1, 2.45, "CV", 0.5f),
                Shape.Glyph(0.9f, -1.1f, "555", 0.8f),
            ),
            families = listOf(ModelFamily.TIMER), editor = ValueEditor.MODEL,
        ),
        PartDef(
            PartKind.OPTO, "Optocoupler", "X", "U", PartCategory.IC, "PC817", "Model", "Optocoupler model",
            listOf(PinDef("A", -3, -2), PinDef("K", -3, 2), PinDef("C", 3, -2), PinDef("E", 3, 2)),
            listOf(
                rect(-2.3, -2.8, 2.3, 2.8),
                line(-3, -2, -1.2, -2), line(-1.2, -2, -1.2, -0.5),
                poly(-1.8 to -0.5, -0.6 to -0.5, -1.2 to 0.5, closed = true, filled = true),
                line(-1.8, 0.5, -0.6, 0.5), line(-1.2, 0.5, -1.2, 2), line(-1.2, 2, -3, 2),
                line(-0.4, -0.35, 0.15, 0.05), arrowHead(-0.4f, -0.35f, 0.3f, 0.15f, 0.25f, 0.13f),
                line(-0.4, 0.25, 0.15, 0.65), arrowHead(-0.4f, 0.25f, 0.3f, 0.75f, 0.25f, 0.13f),
                line(0.6, -0.8, 0.6, 0.8),
                line(0.6, -0.35, 1.6, -1.1), line(1.6, -1.1, 1.6, -2), line(1.6, -2, 3, -2),
                line(0.6, 0.35, 1.6, 1.1), arrowHead(0.6f, 0.35f, 1.45f, 0.99f), line(1.6, 1.1, 1.6, 2), line(1.6, 2, 3, 2),
            ),
            families = listOf(ModelFamily.OPTO), editor = ValueEditor.MODEL,
        ),

        // ---- digital ----
        PartDef(
            PartKind.AND, "AND gate", "X", "U", PartCategory.DIGITAL, "5", "Logic supply (V)", "Output high level and threshold reference",
            gatePins, andBody + gateLeads(-1.5f, 1.5f), editor = ValueEditor.LOGIC,
        ),
        PartDef(
            PartKind.NAND, "NAND gate", "X", "U", PartCategory.DIGITAL, "5", "Logic supply (V)", "Output high level and threshold reference",
            gatePins, andBody + bubble + gateLeads(-1.5f, 2.1f), editor = ValueEditor.LOGIC,
        ),
        PartDef(
            PartKind.OR, "OR gate", "X", "U", PartCategory.DIGITAL, "5", "Logic supply (V)", "Output high level and threshold reference",
            gatePins, orBody + gateLeads(-1.21f, 1.5f), editor = ValueEditor.LOGIC,
        ),
        PartDef(
            PartKind.NOR, "NOR gate", "X", "U", PartCategory.DIGITAL, "5", "Logic supply (V)", "Output high level and threshold reference",
            gatePins, orBody + bubble + gateLeads(-1.21f, 2.1f), editor = ValueEditor.LOGIC,
        ),
        PartDef(
            PartKind.XOR, "XOR gate", "X", "U", PartCategory.DIGITAL, "5", "Logic supply (V)", "Output high level and threshold reference",
            gatePins, orBody + arc(-3.95f, 0f, 2.5f, -36.87f, 73.74f, 10) + gateLeads(-1.66f, 1.5f), editor = ValueEditor.LOGIC,
        ),
        PartDef(
            PartKind.NOT, "Inverter", "X", "U", PartCategory.DIGITAL, "5", "Logic supply (V)", "Output high level and threshold reference",
            inverterPins, inverterBody + listOf(line(-3, 0, -1.5, 0), line(1.7, 0, 3, 0)), editor = ValueEditor.LOGIC,
        ),
        PartDef(
            PartKind.SCHMITT, "Schmitt inverter", "X", "U", PartCategory.DIGITAL, "5", "Logic supply (V)",
            "Switches at 0.54 × and 0.34 × the supply, like a 74HC14",
            inverterPins,
            inverterBody + listOf(
                line(-3, 0, -1.5, 0), line(1.7, 0, 3, 0),
                poly(-1.3 to 0.3, -0.7 to 0.3, -0.7 to -0.3), poly(-1.0 to 0.3, -1.0 to -0.3, -0.4 to -0.3),
            ),
            editor = ValueEditor.LOGIC,
        ),
        PartDef(
            PartKind.DFF, "D flip-flop", "X", "U", PartCategory.DIGITAL, "5", "Logic supply (V)", "Captures D on the rising clock edge",
            listOf(PinDef("D", -3, -1), PinDef("CLK", -3, 1), PinDef("Q", 3, -1), PinDef("QN", 3, 1)),
            listOf(
                rect(-2, -2, 2, 2),
                line(-3, -1, -2, -1), line(-3, 1, -2, 1), line(2, -1, 3, -1), line(2, 1, 3, 1),
                poly(-2 to 0.6, -1.6 to 1, -2 to 1.4),
                label(-1.75, -1, "D", 0.6f, GlyphAlign.START), label(1.75, -1, "Q", 0.6f, GlyphAlign.END),
                label(1.75, 1, "/Q", 0.6f, GlyphAlign.END),
            ),
            editor = ValueEditor.LOGIC,
        ),

        // ---- controlled sources ----
        PartDef(
            PartKind.VCVS, "Voltage-controlled voltage source", "E", "E", PartCategory.ANALOG, "10", "Gain", "Voltage gain",
            controlledPins,
            listOf(diamond) + diamondLeads + controlLeads + plus(0f, 1.6f, 0.15f) + minus(0f, 2.4f, 0.15f),
        ),
        PartDef(
            PartKind.VCCS, "Voltage-controlled current source", "G", "G", PartCategory.ANALOG, "1m", "Transconductance", "Siemens",
            controlledPins,
            listOf(diamond) + diamondLeads + controlLeads +
                listOf(line(0, 1.45, 0, 2.3), arrowHead(0f, 1.45f, 0f, 2.55f, 0.3f, 0.15f)),
        ),
        PartDef(
            PartKind.CCVS, "Current-controlled voltage source", "H", "H", PartCategory.ANALOG, "V1 1k",
            "Control and transresistance", "Sensing source and ohms, e.g. V1 1k",
            sourcePins,
            listOf(diamond) + diamondLeads + plus(0f, 1.6f, 0.15f) + minus(0f, 2.4f, 0.15f),
        ),
        PartDef(
            PartKind.CCCS, "Current-controlled current source", "F", "F", PartCategory.ANALOG, "V1 10",
            "Control and gain", "Sensing source and current gain, e.g. V1 10",
            sourcePins,
            listOf(diamond) + diamondLeads + listOf(line(0, 1.45, 0, 2.3), arrowHead(0f, 1.45f, 0f, 2.55f, 0.3f, 0.15f)),
        ),
        PartDef(
            PartKind.BEHAVIORAL, "Behavioral source", "B", "B", PartCategory.ANALOG, "V=sin(2*pi*1k*time)",
            "Expression", "V=… or I=…, e.g. V=V(in)*2",
            sourcePins,
            listOf(line(0, 0, 0, 1), Shape.Circle(0f, 2f, 1f), line(0, 3, 0, 4), Shape.Glyph(0f, 2f, "B")),
            editor = ValueEditor.EXPRESSION,
        ),
        PartDef(
            PartKind.SWITCH, "Voltage-controlled switch", "S", "S", PartCategory.ANALOG, "PJ_SW", "Model",
            "Switch model name",
            controlledPins,
            listOf(
                line(0, 0, 0, 1.3), Shape.Circle(0f, 1.4f, 0.1f), Shape.Circle(0f, 2.6f, 0.1f),
                line(0, 2.7, 0, 4), line(0, 2.5, 0.65, 1.5),
                line(-2, 1, -0.9, 1), line(-0.9, 1, -0.9, 1.6),
                line(-2, 3, -0.9, 3), line(-0.9, 3, -0.9, 2.4),
                poly(-1.2 to 1.6, -0.6 to 1.6, -0.6 to 2.4, -1.2 to 2.4, closed = true),
            ),
            families = listOf(ModelFamily.SWITCH), editor = ValueEditor.MODEL,
        ),

        // ---- connections ----
        PartDef(
            PartKind.GROUND, "Ground", "", "GND", PartCategory.CONNECTION, "", "", "",
            listOf(PinDef("0", 0, 0)),
            listOf(line(0, 0, 0, 1), line(-0.8, 1, 0.8, 1), line(-0.5, 1.3, 0.5, 1.3), line(-0.2, 1.6, 0.2, 1.6)),
            editor = ValueEditor.NONE,
        ),
        PartDef(
            PartKind.LABEL, "Net label", "", "NET", PartCategory.CONNECTION, "out", "Net name", "Pins with the same label connect",
            listOf(PinDef("N", 0, 0)),
            listOf(Shape.Circle(0f, 0f, 0.15f, filled = true)),
            editor = ValueEditor.LABEL,
        ),

        // Placeholder for a custom part whose symbol is missing.
        PartDef(
            PartKind.CUSTOM, "Subcircuit", "X", "U", PartCategory.CUSTOM, "", "Parameters", "",
            emptyList(), listOf(rect(-2, -2, 2, 2), Shape.Glyph(0f, 0f, "?")),
            editor = ValueEditor.PARAMS,
        ),
    )
}
