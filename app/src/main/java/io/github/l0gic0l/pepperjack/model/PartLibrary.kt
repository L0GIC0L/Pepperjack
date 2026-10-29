package io.github.l0gic0l.pepperjack.model

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
}

enum class PartCategory(val title: String) {
    PASSIVE("Passives"),
    SOURCE("Sources"),
    SEMICONDUCTOR("Semiconductors"),
    ANALOG("Op-amps & controlled sources"),
    CONNECTION("Connections"),
}

data class PinDef(val name: String, val x: Int, val y: Int)

/** Symbol geometry in grid units, drawn relative to the part origin before rotation. */
sealed interface Shape {
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : Shape
    data class Poly(val points: List<Pair<Float, Float>>, val closed: Boolean = false, val filled: Boolean = false) : Shape
    data class Circle(val cx: Float, val cy: Float, val r: Float, val filled: Boolean = false) : Shape
    data class Glyph(val x: Float, val y: Float, val text: String) : Shape
}

data class PartDef(
    val kind: PartKind,
    val title: String,
    /** SPICE element letter; the netlister prefixes it when a reference does not start with it. */
    val prefix: String,
    /** Prefix shown in reference designators (U1 for op-amps, otherwise the SPICE letter). */
    val refPrefix: String,
    val category: PartCategory,
    val defaultValue: String,
    val valueLabel: String,
    val valueHint: String,
    val pins: List<PinDef>,
    val shapes: List<Shape>,
    /** Built-in model names offered for this part, first is the default. */
    val models: List<String> = emptyList(),
) {
    val hasValue: Boolean get() = kind != PartKind.GROUND && kind != PartKind.OPAMP && kind != PartKind.OPAMP5
    val emitsSpice: Boolean get() = kind != PartKind.GROUND && kind != PartKind.LABEL
}

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

fun Part.pinPositions(): List<GridPoint> = PartLibrary[kind].pins.map { pin ->
    val (tx, ty) = transformPoint(pin.x.toFloat(), pin.y.toFloat(), rotation, mirrored)
    GridPoint(x + Math.round(tx), y + Math.round(ty))
}

/** Axis-aligned bounds of the transformed symbol in grid units: left, top, right, bottom. */
fun Part.bounds(): FloatArray {
    val def = PartLibrary[kind]
    var left = Float.MAX_VALUE
    var top = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE
    fun add(px: Float, py: Float) {
        val (tx, ty) = transformPoint(px, py, rotation, mirrored)
        left = minOf(left, x + tx); right = maxOf(right, x + tx)
        top = minOf(top, y + ty); bottom = maxOf(bottom, y + ty)
    }
    def.pins.forEach { add(it.x.toFloat(), it.y.toFloat()) }
    def.shapes.forEach { shape ->
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
    private val defs: Map<PartKind, PartDef> by lazy { build().associateBy { it.kind } }

    operator fun get(kind: PartKind): PartDef = defs.getValue(kind)

    val all: List<PartDef> get() = PartKind.entries.map { get(it) }

    // ---- geometry helpers ----

    private fun line(x1: Number, y1: Number, x2: Number, y2: Number) =
        Shape.Line(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())

    private fun poly(vararg points: Pair<Number, Number>, closed: Boolean = false, filled: Boolean = false) =
        Shape.Poly(points.map { it.first.toFloat() to it.second.toFloat() }, closed, filled)

    private fun arc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float, segments: Int = 10): Shape.Poly {
        val points = (0..segments).map { i ->
            val a = (startDeg + sweepDeg * i / segments) * PI.toFloat() / 180f
            (cx + r * cos(a)) to (cy + r * sin(a))
        }
        return Shape.Poly(points)
    }

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

    private val twoPins = listOf(PinDef("1", 0, 0), PinDef("2", 0, 4))
    private val sourcePins = listOf(PinDef("+", 0, 0), PinDef("-", 0, 4))
    private val diodePins = listOf(PinDef("A", 0, 0), PinDef("K", 0, 4))
    private val controlledPins = listOf(PinDef("+", 0, 0), PinDef("-", 0, 4), PinDef("C+", -2, 1), PinDef("C-", -2, 3))
    private val diamond = poly(0 to 1, 0.8 to 2, 0 to 3, -0.8 to 2, closed = true)
    private val diamondLeads = listOf(line(0, 0, 0, 1), line(0, 3, 0, 4))
    private val controlLeads = listOf(line(-2, 1, -1, 1), line(-2, 3, -1, 3)) +
        plus(-1.4f, 1.45f, 0.15f) + minus(-1.4f, 2.55f, 0.15f)

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

    private fun opampBody(): List<Shape> = listOf(
        poly(-2 to -2, -2 to 2, 2 to 0, closed = true),
        line(-3, -1, -2, -1), line(-3, 1, -2, 1), line(2, 0, 3, 0),
        minus(-1.5f, -1f),
    ) + plus(-1.5f, 1f)

    private fun build(): List<PartDef> = listOf(
        PartDef(
            PartKind.RESISTOR, "Resistor", "R", "R", PartCategory.PASSIVE, "1k", "Resistance", "e.g. 4.7k, 10Meg",
            twoPins,
            listOf(
                line(0, 0, 0, 1),
                poly(
                    0 to 1, 0.4 to 1.1667, -0.4 to 1.5, 0.4 to 1.8333, -0.4 to 2.1667,
                    0.4 to 2.5, -0.4 to 2.8333, 0 to 3,
                ),
                line(0, 3, 0, 4),
            ),
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
            PartKind.VOLTAGE, "Voltage source", "V", "V", PartCategory.SOURCE, "DC 5", "Source",
            "DC 5, AC 1, SIN(0 1 1k), PULSE(0 5 0 1n 1n 0.5m 1m)",
            sourcePins,
            listOf(line(0, 0, 0, 1), Shape.Circle(0f, 2f, 1f), line(0, 3, 0, 4), minus(0f, 2.5f)) + plus(0f, 1.5f),
        ),
        PartDef(
            PartKind.CURRENT, "Current source", "I", "I", PartCategory.SOURCE, "DC 1m", "Source",
            "DC 1m, AC 1, SIN(0 1m 1k)",
            sourcePins,
            listOf(
                line(0, 0, 0, 1), Shape.Circle(0f, 2f, 1f), line(0, 3, 0, 4),
                line(0, 1.4, 0, 2.4), arrowHead(0f, 1.4f, 0f, 2.65f),
            ),
        ),
        PartDef(
            PartKind.DIODE, "Diode", "D", "D", PartCategory.SEMICONDUCTOR, "1N4148", "Model", "Diode model name",
            diodePins, diodeBody(line(-0.6, 2.5, 0.6, 2.5)),
            models = listOf("1N4148", "1N4007", "1N5819"),
        ),
        PartDef(
            PartKind.ZENER, "Zener diode", "D", "D", PartCategory.SEMICONDUCTOR, "1N4733", "Model", "Zener model name",
            diodePins, diodeBody(poly(-0.8 to 2.3, -0.6 to 2.5, 0.6 to 2.5, 0.8 to 2.7)),
            models = listOf("1N4733", "1N4742"),
        ),
        PartDef(
            PartKind.LED, "LED", "D", "D", PartCategory.SEMICONDUCTOR, "LED_RED", "Model", "LED model name",
            diodePins,
            diodeBody(line(-0.6, 2.5, 0.6, 2.5)) + listOf(
                line(0.75, 1.9, 1.3, 1.45), arrowHead(0.75f, 1.9f, 1.4f, 1.37f, 0.25f, 0.13f),
                line(0.75, 2.4, 1.3, 1.95), arrowHead(0.75f, 2.4f, 1.4f, 1.87f, 0.25f, 0.13f),
            ),
            models = listOf("LED_RED", "LED_GREEN", "LED_BLUE"),
        ),
        PartDef(
            PartKind.NPN, "NPN transistor", "Q", "Q", PartCategory.SEMICONDUCTOR, "2N3904", "Model", "BJT model name",
            listOf(PinDef("C", 1, -2), PinDef("B", -2, 0), PinDef("E", 1, 2)), bjt(pnp = false),
            models = listOf("2N3904", "2N2222", "BC547B"),
        ),
        PartDef(
            PartKind.PNP, "PNP transistor", "Q", "Q", PartCategory.SEMICONDUCTOR, "2N3906", "Model", "BJT model name",
            listOf(PinDef("C", 1, 2), PinDef("B", -2, 0), PinDef("E", 1, -2)), bjt(pnp = true),
            models = listOf("2N3906", "BC557B"),
        ),
        PartDef(
            PartKind.NMOS, "N-channel MOSFET", "M", "M", PartCategory.SEMICONDUCTOR, "2N7000", "Model", "MOSFET model name",
            listOf(PinDef("D", 1, -2), PinDef("G", -2, 0), PinDef("S", 1, 2)), mosfet(pmos = false),
            models = listOf("2N7000", "IRFZ44N", "PJ_NMOS"),
        ),
        PartDef(
            PartKind.PMOS, "P-channel MOSFET", "M", "M", PartCategory.SEMICONDUCTOR, "BS250", "Model", "MOSFET model name",
            listOf(PinDef("D", 1, 2), PinDef("G", -2, 0), PinDef("S", 1, -2)), mosfet(pmos = true),
            models = listOf("BS250", "IRF9540", "PJ_PMOS"),
        ),
        PartDef(
            PartKind.OPAMP, "Ideal op-amp", "X", "U", PartCategory.ANALOG, "", "", "",
            listOf(PinDef("IN+", -3, 1), PinDef("IN-", -3, -1), PinDef("OUT", 3, 0)), opampBody(),
        ),
        PartDef(
            PartKind.OPAMP5, "Op-amp with supplies", "X", "U", PartCategory.ANALOG, "", "", "",
            listOf(
                PinDef("IN+", -3, 1), PinDef("IN-", -3, -1),
                PinDef("V+", 0, -3), PinDef("V-", 0, 3), PinDef("OUT", 3, 0),
            ),
            opampBody() + listOf(line(0, -3, 0, -1), line(0, 3, 0, 1)),
        ),
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
            models = listOf("PJ_SW"),
        ),
        PartDef(
            PartKind.GROUND, "Ground", "", "GND", PartCategory.CONNECTION, "", "", "",
            listOf(PinDef("0", 0, 0)),
            listOf(line(0, 0, 0, 1), line(-0.8, 1, 0.8, 1), line(-0.5, 1.3, 0.5, 1.3), line(-0.2, 1.6, 0.2, 1.6)),
        ),
        PartDef(
            PartKind.LABEL, "Net label", "", "NET", PartCategory.CONNECTION, "out", "Net name", "Pins with the same label connect",
            listOf(PinDef("N", 0, 0)),
            listOf(Shape.Circle(0f, 0f, 0.15f, filled = true)),
        ),
    )
}
