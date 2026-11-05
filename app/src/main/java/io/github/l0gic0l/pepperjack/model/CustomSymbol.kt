package io.github.l0gic0l.pepperjack.model

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

@Serializable
enum class PinSide { LEFT, RIGHT, TOP, BOTTOM }

@Serializable
enum class SymbolBody(val label: String) { BOX("Block"), AMPLIFIER("Amplifier") }

/** One subcircuit port: which side of the symbol it leaves from and its slot along that side. */
@Serializable
data class SymbolPin(val name: String, val side: PinSide, val order: Int)

/**
 * A user-made symbol for a library subcircuit, like LTspice's auto-generated block
 * symbols. Pins are listed in the subcircuit's port order. A copy is stored in every
 * placed part, so circuits stay self-contained.
 */
@Serializable
data class CustomSymbol(
    val id: String,
    val title: String,
    val subckt: String,
    /** Model library file that defines [subckt]; empty when it comes from the circuit's directives. */
    val library: String,
    val pins: List<SymbolPin>,
    val body: SymbolBody = SymbolBody.BOX,
    val refPrefix: String = "U",
    /** Default instance parameters, e.g. "R=10k". */
    val params: String = "",
) {
    companion object {
        private val inputs = Regex("""(?i)^(in|inp|inn|in[+-]|[+-]in|ninv|inv|non_?inv\w*|vin[+-]?|[+-]|a|b|d|clk|ck|s|r|set|reset|en|trig\w*|thr\w*|ctrl|fb|adj|sense\w*)$""")
        private val outputs = Regex("""(?i)^(out\w*|o|y|q|qn|q_?bar|nq|vout|output|dis\w*|sw|lx)$""")
        private val positive = Regex("""(?i)^(v\+|vp|vpos|vcc|vdd|vs\+|v_?plus|vin|pvin|avdd|v\+\w*|vcc\w*|vdd\w*|vbat|bat|vs)$""")
        private val negative = Regex("""(?i)^(v-|vn|vneg|vee|vss|vs-|v_?minus|gnd|agnd|pgnd|dgnd|ground|0|com|v-\w*|vee\w*|vss\w*|gnd\w*)$""")

        /** A first arrangement LTspice-style: supplies top/bottom, inputs left, outputs right. */
        fun autoArrange(names: List<String>): List<SymbolPin> {
            val sides = names.map { name ->
                when {
                    positive.matches(name) -> PinSide.TOP
                    negative.matches(name) -> PinSide.BOTTOM
                    outputs.matches(name) -> PinSide.RIGHT
                    inputs.matches(name) -> PinSide.LEFT
                    else -> null
                }
            }.toMutableList()
            // Unclassified pins alternate left/right, keeping the sides balanced.
            names.indices.filter { sides[it] == null }.forEach { i ->
                val left = sides.count { it == PinSide.LEFT }
                val right = sides.count { it == PinSide.RIGHT }
                sides[i] = if (left <= right) PinSide.LEFT else PinSide.RIGHT
            }
            val counters = HashMap<PinSide, Int>()
            return names.mapIndexed { i, name ->
                val side = sides[i]!!
                val order = counters.merge(side, 1, Int::plus)!! - 1
                SymbolPin(name, side, order)
            }
        }

        fun create(id: String, subckt: String, library: String, pinNames: List<String>): CustomSymbol {
            val body = if (pinNames.size == 5 && looksLikeOpAmp(pinNames)) SymbolBody.AMPLIFIER else SymbolBody.BOX
            return CustomSymbol(id, subckt, subckt, library, autoArrange(pinNames), body, refPrefix = "U")
        }

        private fun looksLikeOpAmp(names: List<String>): Boolean {
            val sides = autoArrange(names).groupingBy { it.side }.eachCount()
            return sides[PinSide.LEFT] == 2 && sides[PinSide.RIGHT] == 1
        }
    }
}

/** Builds (and caches) the drawable definition of a custom symbol. */
object CustomSymbols {
    private val cache = object : LinkedHashMap<CustomSymbol, PartDef>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CustomSymbol, PartDef>?) = size > 64
    }

    fun def(symbol: CustomSymbol): PartDef = synchronized(cache) { cache.getOrPut(symbol) { build(symbol) } }

    private const val LABEL_SIZE = 0.62f
    private const val CHAR_WIDTH = 0.36f

    private fun build(symbol: CustomSymbol): PartDef {
        val bySide = PinSide.entries.associateWith { side -> symbol.pins.withIndex().filter { it.value.side == side }.sortedBy { it.value.order } }
        val left = bySide.getValue(PinSide.LEFT)
        val right = bySide.getValue(PinSide.RIGHT)
        val top = bySide.getValue(PinSide.TOP)
        val bottom = bySide.getValue(PinSide.BOTTOM)
        val longestLeft = left.maxOfOrNull { it.value.name.length } ?: 0
        val longestRight = right.maxOfOrNull { it.value.name.length } ?: 0
        // Half sizes in whole grid units, so every pin lands on the grid two units apart.
        var halfHeight = maxOf(left.size, right.size, 1)
        var halfWidth = maxOf(
            2,
            top.size,
            bottom.size,
            ceil(((longestLeft + longestRight) * CHAR_WIDTH + 1.4f) / 2f).toInt(),
        )
        if (symbol.body == SymbolBody.AMPLIFIER) {
            halfHeight = maxOf(halfHeight, 2)
            halfWidth = maxOf(halfWidth, 2, top.size * 2, bottom.size * 2)
        }

        val pins = arrayOfNulls<PinDef>(symbol.pins.size)
        val shapes = mutableListOf<Shape>()
        fun slot(count: Int, index: Int) = -(count - 1) + 2 * index

        when (symbol.body) {
            SymbolBody.BOX -> shapes += Shape.Poly(
                listOf(
                    -halfWidth.toFloat() to -halfHeight.toFloat(), halfWidth.toFloat() to -halfHeight.toFloat(),
                    halfWidth.toFloat() to halfHeight.toFloat(), -halfWidth.toFloat() to halfHeight.toFloat(),
                ),
                closed = true,
            )
            SymbolBody.AMPLIFIER -> shapes += Shape.Poly(
                listOf(-halfWidth.toFloat() to -halfHeight.toFloat(), -halfWidth.toFloat() to halfHeight.toFloat(), halfWidth.toFloat() to 0f),
                closed = true,
            )
        }

        // x of the amplifier's slanted edge at height y, and y of the edge at x (top: negative).
        fun ampEdgeX(y: Float) = halfWidth - 2f * halfWidth * abs(y) / halfHeight
        fun ampEdgeY(x: Float) = halfHeight * (1f - (x + halfWidth) / (2f * halfWidth))

        left.forEachIndexed { i, (index, pin) ->
            val y = slot(left.size, i)
            pins[index] = PinDef(pin.name, -halfWidth - 1, y)
            shapes += Shape.Line(-halfWidth - 1f, y.toFloat(), -halfWidth.toFloat(), y.toFloat())
            shapes += Shape.Glyph(-halfWidth + 0.3f, y.toFloat(), pin.name, LABEL_SIZE, bold = false, align = GlyphAlign.START)
        }
        right.forEachIndexed { i, (index, pin) ->
            val y = slot(right.size, i)
            pins[index] = PinDef(pin.name, halfWidth + 1, y)
            val edge = if (symbol.body == SymbolBody.AMPLIFIER) ampEdgeX(y.toFloat()) else halfWidth.toFloat()
            shapes += Shape.Line(halfWidth + 1f, y.toFloat(), edge, y.toFloat())
            if (symbol.body == SymbolBody.BOX) {
                shapes += Shape.Glyph(halfWidth - 0.3f, y.toFloat(), pin.name, LABEL_SIZE, bold = false, align = GlyphAlign.END)
            }
        }
        top.forEachIndexed { i, (index, pin) ->
            val x = slot(top.size, i)
            pins[index] = PinDef(pin.name, x, -halfHeight - 1)
            val edge = if (symbol.body == SymbolBody.AMPLIFIER) -ampEdgeY(x.toFloat()) else -halfHeight.toFloat()
            shapes += Shape.Line(x.toFloat(), -halfHeight - 1f, x.toFloat(), edge)
            shapes += Shape.Glyph(x.toFloat(), edge + 0.55f, pin.name, LABEL_SIZE * 0.85f, bold = false)
        }
        bottom.forEachIndexed { i, (index, pin) ->
            val x = slot(bottom.size, i)
            pins[index] = PinDef(pin.name, x, halfHeight + 1)
            val edge = if (symbol.body == SymbolBody.AMPLIFIER) ampEdgeY(x.toFloat()) else halfHeight.toFloat()
            shapes += Shape.Line(x.toFloat(), halfHeight + 1f, x.toFloat(), edge)
            shapes += Shape.Glyph(x.toFloat(), edge - 0.55f, pin.name, LABEL_SIZE * 0.85f, bold = false)
        }

        return PartDef(
            kind = PartKind.CUSTOM,
            title = symbol.title,
            prefix = "X",
            refPrefix = symbol.refPrefix.ifBlank { "U" }.uppercase(Locale.ROOT),
            category = PartCategory.CUSTOM,
            defaultValue = symbol.params,
            valueLabel = "Parameters",
            valueHint = "Instance parameters passed to ${symbol.subckt}, e.g. R=10k",
            pins = pins.map { it!! },
            shapes = shapes,
        )
    }
}
