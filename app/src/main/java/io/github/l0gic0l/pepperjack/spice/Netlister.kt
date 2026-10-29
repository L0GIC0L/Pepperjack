package io.github.l0gic0l.pepperjack.spice

import io.github.l0gic0l.pepperjack.model.Circuit
import io.github.l0gic0l.pepperjack.model.GridPoint
import io.github.l0gic0l.pepperjack.model.Part
import io.github.l0gic0l.pepperjack.model.PartKind
import io.github.l0gic0l.pepperjack.model.PartLibrary
import io.github.l0gic0l.pepperjack.model.Probe
import io.github.l0gic0l.pepperjack.model.ProbeType
import io.github.l0gic0l.pepperjack.model.Schematic
import io.github.l0gic0l.pepperjack.model.Wire
import io.github.l0gic0l.pepperjack.model.pinPositions
import java.util.Locale

/** A probe resolved to the ngspice vector that holds its data. */
data class ProbeVector(val probe: Probe, val vector: String, val label: String)

data class NetlistResult(
    val text: String,
    val warnings: List<String>,
    val probeVectors: List<ProbeVector>,
)

/**
 * Electrical connectivity of a schematic. Pins and wire endpoints at the same grid
 * point connect, a point lying on a wire connects to it (T-junction), and net labels
 * with the same name connect across the sheet. Crossing wires do not connect.
 */
class Connectivity(private val schematic: Schematic) {
    private val index = HashMap<GridPoint, Int>()
    private val parent = ArrayList<Int>()

    private fun id(point: GridPoint): Int = index.getOrPut(point) {
        parent.add(parent.size)
        parent.size - 1
    }

    private fun find(i: Int): Int {
        var x = i
        while (parent[x] != x) {
            parent[x] = parent[parent[x]]
            x = parent[x]
        }
        return x
    }

    private fun union(a: Int, b: Int) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parent[ra] = rb
    }

    init {
        schematic.parts.forEach { part -> part.pinPositions().forEach { id(it) } }
        schematic.wires.forEach { id(it.start); id(it.end) }
        val points = index.keys.toList()
        for (wire in schematic.wires) {
            union(id(wire.start), id(wire.end))
            for (point in points) {
                if (point != wire.start && point != wire.end && wire.contains(point)) union(id(point), id(wire.start))
            }
        }
        schematic.parts
            .filter { it.kind == PartKind.LABEL }
            .groupBy { netName(it.value) }
            .values
            .forEach { group ->
                group.zipWithNext { a, b -> union(id(a.pinPositions()[0]), id(b.pinPositions()[0])) }
            }
    }

    /** Net root for a point, including points on a wire's interior; null if unconnected. */
    fun root(point: GridPoint): Int? {
        index[point]?.let { return find(it) }
        val wire = schematic.wires.firstOrNull { it.contains(point) } ?: return null
        return find(id(wire.start))
    }

    fun connected(a: GridPoint, b: GridPoint): Boolean {
        val ra = root(a) ?: return false
        return ra == root(b)
    }

    companion object {
        /** SPICE-safe node name from a label. */
        fun netName(label: String): String =
            label.trim().replace(Regex("""[\s,=()]+"""), "_").ifEmpty { "unnamed" }
    }
}

fun Wire.contains(point: GridPoint): Boolean {
    val cross = (x2 - x1).toLong() * (point.y - y1) - (y2 - y1).toLong() * (point.x - x1)
    if (cross != 0L) return false
    return point.x in minOf(x1, x2)..maxOf(x1, x2) && point.y in minOf(y1, y2)..maxOf(y1, y2)
}

/** Grid points where three or more connections meet; drawn as junction dots. */
fun Schematic.junctions(): Set<GridPoint> {
    val counts = HashMap<GridPoint, Int>()
    wires.forEach {
        counts.merge(it.start, 1, Int::plus)
        counts.merge(it.end, 1, Int::plus)
    }
    parts.forEach { part -> part.pinPositions().forEach { counts.merge(it, 1, Int::plus) } }
    val candidates = counts.keys.toList()
    for (wire in wires) {
        for (point in candidates) {
            if (point != wire.start && point != wire.end && wire.contains(point)) counts.merge(point, 2, Int::plus)
        }
    }
    return counts.filterValues { it >= 3 }.keys
}

object Netlister {
    private val analysisDirective = Regex("""(?im)^\s*\.(tran|ac|dc|op|noise|tf|disto|pz|sens|sp|pss)\b""")
    private val includeDirective = Regex("""(?im)^\s*\.(include|inc|lib)\b""")

    /** SPICE instance name: the reference, prefixed with the element letter when needed. */
    fun spiceName(part: Part): String {
        val def = PartLibrary[part.kind]
        val ref = part.ref.trim().replace(Regex("""\s+"""), "_").ifEmpty { def.refPrefix + part.id }
        return if (ref.startsWith(def.prefix, ignoreCase = true)) ref else def.prefix + ref
    }

    fun netlist(circuit: Circuit, generator: String = "Pepperjack"): NetlistResult {
        val schematic = circuit.schematic
        val connectivity = Connectivity(schematic)
        val warnings = mutableListOf<String>()
        val names = HashMap<Int, String>()

        // Ground first, then labels, then automatic names in part order (stable across edits).
        schematic.parts.filter { it.kind == PartKind.GROUND }.forEach { ground ->
            connectivity.root(ground.pinPositions()[0])?.let { names[it] = "0" }
        }
        schematic.parts.filter { it.kind == PartKind.LABEL }.forEach { label ->
            val root = connectivity.root(label.pinPositions()[0]) ?: return@forEach
            val name = Connectivity.netName(label.value)
            val existing = names[root]
            when {
                existing == null -> names[root] = name
                existing == "0" && !name.equals("0", true) && !name.equals("gnd", true) ->
                    warnings += "Net label \"$name\" is on the ground net."
                existing != name && existing != "0" ->
                    warnings += "Net labels \"$existing\" and \"$name\" are on the same net; using \"$existing\"."
            }
        }
        val used = names.values.mapTo(HashSet()) { it.lowercase() }
        var counter = 0
        fun nameFor(root: Int): String = names.getOrPut(root) {
            var candidate: String
            do {
                candidate = String.format(Locale.ROOT, "N%03d", ++counter)
            } while (candidate.lowercase() in used)
            candidate
        }

        // Count device pins on every net to spot floating pins.
        val pinsOnNet = HashMap<Int, Int>()
        schematic.parts.forEach { part ->
            part.pinPositions().forEach { point -> connectivity.root(point)?.let { pinsOnNet.merge(it, 1, Int::plus) } }
        }

        val userModels = Regex("""(?im)^\s*\.(model|subckt)\s+(\S+)(?:\s+(\w+))?""")
            .findAll(schematic.directives)
            .associate { it.groupValues[2].uppercase() to it.groupValues[3].uppercase() }
        val hasIncludes = includeDirective.containsMatchIn(schematic.directives)

        val elements = mutableListOf<String>()
        val modelsUsed = LinkedHashSet<String>()
        val subcircuits = LinkedHashSet<String>()
        val instanceNames = HashSet<String>()

        for (part in schematic.parts) {
            val def = PartLibrary[part.kind]
            if (!def.emitsSpice) continue
            val pins = part.pinPositions()
            val nets = pins.mapIndexed { i, point ->
                val root = connectivity.root(point)!!
                if ((pinsOnNet[root] ?: 0) < 2) {
                    warnings += "${part.ref} pin ${def.pins[i].name} is not connected."
                }
                nameFor(root)
            }
            val name = spiceName(part)
            if (!instanceNames.add(name.uppercase())) warnings += "Duplicate reference ${part.ref}."
            val value = part.value.trim().ifEmpty {
                if (def.hasValue) warnings += "${part.ref} has no value; using ${def.defaultValue}."
                def.defaultValue
            }
            val model = value.substringBefore(' ')
            elements += when (part.kind) {
                PartKind.DIODE, PartKind.ZENER, PartKind.LED, PartKind.NPN, PartKind.PNP, PartKind.SWITCH -> {
                    modelsUsed += model
                    "$name ${nets.joinToString(" ")} $value"
                }
                PartKind.NMOS, PartKind.PMOS -> {
                    modelsUsed += model
                    val type = userModels[model.uppercase()] ?: SpiceModels.find(model)?.type
                    // VDMOS (power MOSFET) models take three nodes; others get bulk tied to source.
                    if (type == "VDMOS") "$name ${nets.joinToString(" ")} $value"
                    else "$name ${nets[0]} ${nets[1]} ${nets[2]} ${nets[2]} $value"
                }
                PartKind.OPAMP -> {
                    subcircuits += SpiceModels.IDEAL_OPAMP
                    "$name ${nets.joinToString(" ")} ${SpiceModels.IDEAL_OPAMP}"
                }
                PartKind.OPAMP5 -> {
                    subcircuits += SpiceModels.OPAMP
                    "$name ${nets.joinToString(" ")} ${SpiceModels.OPAMP}"
                }
                else -> "$name ${nets.joinToString(" ")} $value"
            }
        }

        val modelCards = mutableListOf<String>()
        for (model in modelsUsed) {
            if (model.uppercase() in userModels) continue
            val builtIn = SpiceModels.find(model)
            when {
                builtIn != null -> modelCards += builtIn.card
                !hasIncludes -> warnings += "Model $model is not defined. Add it with a .model directive or the Model library."
            }
        }

        val probeVectors = mutableListOf<ProbeVector>()
        val saves = mutableListOf<String>()
        for (probe in schematic.probes) {
            when (probe.type) {
                ProbeType.VOLTAGE -> {
                    val root = connectivity.root(GridPoint(probe.x, probe.y)) ?: continue
                    val net = nameFor(root)
                    if (net == "0") continue
                    probeVectors += ProbeVector(probe, net.lowercase(), "V($net)")
                }
                ProbeType.CURRENT -> {
                    val part = schematic.parts.firstOrNull { it.id == probe.partId } ?: continue
                    val vector = currentVector(part) ?: continue
                    if (vector.startsWith("@")) saves += ".save $vector"
                    probeVectors += ProbeVector(probe, vector, "I(${part.ref})")
                }
            }
        }

        if (schematic.parts.any { it.kind != PartKind.GROUND && it.kind != PartKind.LABEL } && "0" !in names.values) {
            warnings += "There is no ground. Add a ground symbol so every node has a reference."
        }

        val text = buildString {
            appendLine("* ${circuit.name}")
            appendLine("* Generated by $generator")
            elements.forEach { appendLine(it) }
            if (modelCards.isNotEmpty() || subcircuits.isNotEmpty()) {
                appendLine()
                modelCards.forEach { appendLine(it) }
                if (SpiceModels.IDEAL_OPAMP in subcircuits) appendLine(SpiceModels.idealOpAmp)
                if (SpiceModels.OPAMP in subcircuits) appendLine(SpiceModels.opAmp)
            }
            if (saves.isNotEmpty()) {
                appendLine()
                appendLine(".save all")
                saves.distinct().forEach { appendLine(it) }
            }
            appendLine()
            if (!analysisDirective.containsMatchIn(schematic.directives)) appendLine(circuit.analysis.toSpice())
            if (schematic.directives.isNotBlank()) appendLine(schematic.directives.trim())
            appendLine(".end")
        }
        return NetlistResult(text, warnings.distinct(), probeVectors)
    }

    /** ngspice vector holding a part's current, or null if it cannot be probed. */
    fun currentVector(part: Part): String? {
        val name = spiceName(part).lowercase()
        return when (part.kind) {
            PartKind.VOLTAGE, PartKind.INDUCTOR, PartKind.VCVS, PartKind.CCVS -> "$name#branch"
            PartKind.RESISTOR, PartKind.CAPACITOR, PartKind.BEHAVIORAL -> "@$name[i]"
            PartKind.CURRENT -> "@$name[current]"
            PartKind.DIODE, PartKind.ZENER, PartKind.LED -> "@$name[id]"
            PartKind.NPN, PartKind.PNP -> "@$name[ic]"
            PartKind.NMOS, PartKind.PMOS -> "@$name[id]"
            else -> null
        }
    }
}
