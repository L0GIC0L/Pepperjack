package io.github.l0gic0l.pepperjack.data

import io.github.l0gic0l.pepperjack.model.AnalysisConfig
import io.github.l0gic0l.pepperjack.model.Circuit
import io.github.l0gic0l.pepperjack.model.CircuitKind
import io.github.l0gic0l.pepperjack.model.GridPoint
import io.github.l0gic0l.pepperjack.model.Part
import io.github.l0gic0l.pepperjack.model.PartKind
import io.github.l0gic0l.pepperjack.model.PartLibrary
import io.github.l0gic0l.pepperjack.model.Probe
import io.github.l0gic0l.pepperjack.model.ProbeType
import io.github.l0gic0l.pepperjack.model.Schematic
import io.github.l0gic0l.pepperjack.model.Wire
import io.github.l0gic0l.pepperjack.model.pinPositions

/**
 * Builds an example schematic. Coordinates are grid units with y pointing down. A two-terminal
 * part at rotation 0 runs top to bottom from (x, y) to (x, y + 4); rotation 3 runs left to
 * right to (x + 4, y), rotation 1 right to left to (x − 4, y), rotation 2 bottom to top.
 */
internal class Sheet {
    private var nextId = 1L
    val parts = mutableListOf<Part>()
    val wires = mutableListOf<Wire>()
    val probes = mutableListOf<Probe>()

    fun part(kind: PartKind, ref: String, x: Int, y: Int, rotation: Int = 0, value: String? = null, mirrored: Boolean = false): Part {
        val part = Part(nextId++, kind, ref, x, y, rotation, mirrored, value = value ?: PartLibrary[kind].defaultValue)
        parts += part
        return part
    }

    /** A wire through the points given as x, y pairs. */
    fun w(vararg xy: Int) {
        require(xy.size >= 4 && xy.size % 2 == 0)
        for (i in 0 until xy.size - 2 step 2) wire(xy[i], xy[i + 1], xy[i + 2], xy[i + 3])
    }

    /** A source with its − pin grounded and its + pin labelled [net] (if given). */
    fun supply(ref: String, x: Int, y: Int, value: String, net: String? = null): Part =
        part(PartKind.VOLTAGE, ref, x, y, value = value).also {
            ground(x, y + 4)
            if (net != null) label(x, y, net)
        }

    /** The ±[volts] supply pair for op-amps, labelled vcc and vee, at (x, y) and (x + 4, y). */
    fun splitSupply(x: Int, y: Int, volts: Int = 12) {
        supply("VCC", x, y, "DC $volts", "vcc")
        supply("VEE", x + 4, y, "DC -$volts", "vee")
    }

    /** An op-amp or comparator with its supply pins labelled vcc and vee. */
    fun amp(kind: PartKind, ref: String, x: Int, y: Int, model: String, vp: String = "vcc", vn: String = "vee", mirrored: Boolean = false, rotation: Int = 0): Part =
        part(kind, ref, x, y, rotation, model, mirrored).also {
            label(it.pin(2), vp)
            if (vn == "0") ground(it.pin(3)) else label(it.pin(3), vn)
        }

    fun Part.pin(i: Int): GridPoint = pinPositions()[i]

    fun probe(p: GridPoint) = probe(p.x, p.y)

    fun ground(x: Int, y: Int) = part(PartKind.GROUND, "GND", x, y)
    fun label(x: Int, y: Int, name: String) = part(PartKind.LABEL, name, x, y, value = name)
    fun wire(x1: Int, y1: Int, x2: Int, y2: Int) {
        wires += Wire(nextId++, x1, y1, x2, y2)
    }

    /** A wire through [points] in order, each leg horizontal or vertical. */
    fun wire(vararg points: GridPoint) = points.toList().zipWithNext { a, b -> wire(a.x, a.y, b.x, b.y) }

    fun label(p: GridPoint, name: String) = label(p.x, p.y, name)
    fun ground(p: GridPoint) = ground(p.x, p.y)

    fun probe(x: Int, y: Int) {
        probes += Probe(ProbeType.VOLTAGE, x, y)
    }

    fun probeCurrent(part: Part) {
        probes += Probe(ProbeType.CURRENT, partId = part.id)
    }

    fun circuit(name: String, analysis: AnalysisConfig, directives: String = "") = Circuit(
        id = "",
        name = name,
        kind = CircuitKind.SCHEMATIC,
        schematic = Schematic(parts.toList(), wires.toList(), directives, probes.toList()),
        analysis = analysis,
    )
}
