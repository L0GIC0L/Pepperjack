package io.github.l0gic0l.pepperjack.spice

import io.github.l0gic0l.pepperjack.data.LibraryModel
import io.github.l0gic0l.pepperjack.model.AnalysisType
import io.github.l0gic0l.pepperjack.model.Circuit
import io.github.l0gic0l.pepperjack.model.GridPoint
import io.github.l0gic0l.pepperjack.model.Part
import io.github.l0gic0l.pepperjack.model.PartDef
import io.github.l0gic0l.pepperjack.model.PartKind
import io.github.l0gic0l.pepperjack.model.PotValue
import io.github.l0gic0l.pepperjack.model.Probe
import io.github.l0gic0l.pepperjack.model.ProbeType
import io.github.l0gic0l.pepperjack.model.Schematic
import io.github.l0gic0l.pepperjack.model.SwitchValue
import io.github.l0gic0l.pepperjack.model.TransformerValue
import io.github.l0gic0l.pepperjack.model.ValueEditor
import io.github.l0gic0l.pepperjack.model.Wire
import io.github.l0gic0l.pepperjack.model.def
import io.github.l0gic0l.pepperjack.model.pinPositions
import io.github.l0gic0l.pepperjack.util.Si
import java.util.Locale

/** A probe resolved to the ngspice vector that holds its data. */
data class ProbeVector(val probe: Probe, val vector: String, val label: String)

data class NetlistResult(
    val text: String,
    val warnings: List<String>,
    val probeVectors: List<ProbeVector>,
    /** Net name of every pin, by part id, in pin order ("0" is ground). */
    val pinNets: Map<Long, List<String>> = emptyMap(),
    /** Live netlists only: the vector holding the current flowing from the net into each pin. */
    val pinCurrents: Map<Long, List<String>> = emptyMap(),
    /** Live netlists only: the analysis that runs (transient or operating point). */
    val liveAnalysis: AnalysisType? = null,
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
    private val tranDirective = Regex("""(?i)^\s*\.tran\b""")
    private val stepDirective = Regex("""(?i)^\s*\.step\b""")
    private val includeDirective = Regex("""(?im)^\s*\.(include|inc|lib)\b""")
    private val userModel = Regex("""(?im)^\s*\.model\s+(\S+)\s+([A-Za-z_]+)""")

    /**
     * Name prefix of the zero-volt sources that measure pin currents in live netlists. ngspice
     * reports their currents as lower-case "vpj_…#branch" vectors, which are hidden from traces.
     */
    const val LIVE_AMMETER = "Vpj_"

    /** Parts with a native ngspice current vector when they are primitive devices. */
    private val nativeCurrent = setOf(
        PartKind.VOLTAGE, PartKind.INDUCTOR, PartKind.VCVS, PartKind.CCVS, PartKind.RESISTOR, PartKind.CAPACITOR,
        PartKind.BEHAVIORAL, PartKind.CURRENT, PartKind.DIODE, PartKind.SCHOTTKY, PartKind.ZENER, PartKind.LED,
        PartKind.NPN, PartKind.PNP, PartKind.NMOS, PartKind.PMOS,
    )

    /** SPICE instance name: the reference, prefixed with the element letter when needed. */
    fun spiceName(part: Part): String {
        val def = part.def
        val ref = part.ref.trim().replace(Regex("""\s+"""), "_").ifEmpty { def.refPrefix + part.id }
        return if (ref.startsWith(def.prefix, ignoreCase = true)) ref else def.prefix + ref
    }

    /** A model name resolved to where its definition comes from. */
    private class Resolved(val isSubckt: Boolean, val type: String, val pins: Int?, val builtIn: SpiceModel?, val file: String?)

    private class Resolver(directives: String, private val library: Map<String, LibraryModel>) {
        private val directiveModels = HashMap<String, Resolved>().apply {
            userModel.findAll(directives).forEach {
                put(it.groupValues[1].uppercase(Locale.ROOT), Resolved(false, it.groupValues[2].uppercase(Locale.ROOT), null, null, null))
            }
            SubcktHeader.parseAll(directives).forEach {
                put(it.name.uppercase(Locale.ROOT), Resolved(true, LibraryModel.SUBCKT, it.pins.size, null, null))
            }
        }

        fun resolve(name: String): Resolved? {
            val key = name.uppercase(Locale.ROOT)
            directiveModels[key]?.let { return it }
            SpiceModels.find(name)?.let { return Resolved(it.isSubckt, it.type, it.pins.size.takeIf { _ -> it.isSubckt }, it, null) }
            library[key]?.let { return Resolved(it.isSubckt, it.type, it.pins.size.takeIf { _ -> it.isSubckt }, null, it.file) }
            return null
        }
    }

    /**
     * Netlists a schematic. [library] is the user's model library index, so parts can use
     * vendor models by name. A [live] netlist measures every pin current with a 0 V source
     * and runs a transient (if the circuit's analysis is one) or an operating point.
     */
    fun netlist(
        circuit: Circuit,
        library: Map<String, LibraryModel> = emptyMap(),
        live: Boolean = false,
        generator: String = "Pepperjack",
    ): NetlistResult {
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
        val used = names.values.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        var counter = 0
        fun nameFor(root: Int): String = names.getOrPut(root) {
            var candidate: String
            do {
                candidate = String.format(Locale.ROOT, "N%03d", ++counter)
            } while (candidate.lowercase(Locale.ROOT) in used)
            candidate
        }

        // Count device pins on every net to spot floating pins.
        val pinsOnNet = HashMap<Int, Int>()
        schematic.parts.forEach { part ->
            part.pinPositions().forEach { point -> connectivity.root(point)?.let { pinsOnNet.merge(it, 1, Int::plus) } }
        }

        val resolver = Resolver(schematic.directives, library)
        val hasIncludes = includeDirective.containsMatchIn(schematic.directives)

        val elements = mutableListOf<String>()
        val ammeters = mutableListOf<String>()
        val builtIns = LinkedHashSet<SpiceModel>()
        val includes = LinkedHashSet<String>()
        val instanceNames = HashSet<String>()
        val pinNets = LinkedHashMap<Long, List<String>>()
        val pinCurrents = LinkedHashMap<Long, List<String>>()
        val probeVectors = mutableListOf<ProbeVector>()
        val saves = mutableListOf<String>()
        var needsIdealOpAmp = false

        val currentProbes = schematic.probes.filter { it.type == ProbeType.CURRENT }.map { it.partId }.toSet()

        fun modelOf(part: Part, def: PartDef) = part.value.trim().substringBefore(' ').ifBlank { def.defaultValue }

        for (part in schematic.parts) {
            val def = part.def
            if (!def.emitsSpice) continue
            val nets = part.pinPositions().mapIndexed { i, point ->
                val root = connectivity.root(point)!!
                if ((pinsOnNet[root] ?: 0) < 2) warnings += "${part.ref} pin ${def.pins[i].name} is not connected."
                nameFor(root)
            }.toMutableList()
            pinNets[part.id] = nets.toList()
            val name = spiceName(part)
            if (!instanceNames.add(name.uppercase(Locale.ROOT))) warnings += "Duplicate reference ${part.ref}."

            val resolved = if (def.editor == ValueEditor.MODEL) resolver.resolve(modelOf(part, def)) else null
            val isSubckt = resolved?.isSubckt == true || def.prefix == "X"

            // A current probe reads a native device vector where one exists, otherwise a 0 V source on pin 1.
            if (part.id in currentProbes) {
                val native = part.kind in nativeCurrent && !isSubckt
                val vector = if (native) nativeCurrentVector(part, name) else {
                    val node = "pj_probe_${part.id}"
                    ammeters += "Vprobe_${sanitize(part.ref)} ${nets[0]} $node 0"
                    nets[0] = node
                    "vprobe_${sanitize(part.ref).lowercase(Locale.ROOT)}#branch"
                }
                if (vector.startsWith("@")) saves += ".save $vector"
                val probe = schematic.probes.first { it.type == ProbeType.CURRENT && it.partId == part.id }
                probeVectors += ProbeVector(probe, vector, "I(${part.ref})")
            }

            if (live) {
                pinCurrents[part.id] = nets.indices.map { i ->
                    val node = "pj_${part.id}_$i"
                    val ammeter = "$LIVE_AMMETER${part.id}_$i"
                    ammeters += "$ammeter ${nets[i]} $node 0"
                    nets[i] = node
                    ammeter.lowercase(Locale.ROOT) + "#branch"
                }
            }

            val nodes = nets.joinToString(" ")
            val value = part.value.trim()
            fun valueOrDefault(): String = value.ifEmpty {
                if (def.hasValue && def.defaultValue.isNotEmpty()) warnings += "${part.ref} has no value; using ${def.defaultValue}."
                def.defaultValue
            }

            when (part.kind) {
                PartKind.OPAMP -> {
                    needsIdealOpAmp = true
                    elements += "$name $nodes ${SpiceModels.IDEAL_OPAMP}"
                }
                PartKind.AMMETER -> elements += "$name $nodes 0"
                PartKind.VOLTMETER -> elements += "$name $nodes ${valueOrDefault()}"
                PartKind.SPST -> elements += "$name $nodes ${if (SwitchValue.isClosed(value)) CLOSED else OPEN}"
                PartKind.SPDT -> {
                    val toB = SwitchValue.isB(value)
                    elements += "${name}_a ${nets[0]} ${nets[1]} ${if (toB) OPEN else CLOSED}"
                    elements += "${name}_b ${nets[0]} ${nets[2]} ${if (toB) CLOSED else OPEN}"
                }
                PartKind.POT -> {
                    val pot = PotValue.parse(valueOrDefault())
                    // Pin order is 1, 2, wiper; the wiper position is measured from pin 1.
                    elements += "${name}_a ${nets[0]} ${nets[2]} ${scaled(pot.resistance, pot.position)}"
                    elements += "${name}_b ${nets[2]} ${nets[1]} ${scaled(pot.resistance, 1 - pot.position)}"
                }
                PartKind.TRANSFORMER -> {
                    val t = TransformerValue.parse(valueOrDefault())
                    elements += "${name}_p ${nets[0]} ${nets[1]} ${t.primary}"
                    elements += "${name}_s ${nets[2]} ${nets[3]} ${t.secondary()}"
                    elements += "K$name ${name}_p ${name}_s ${t.coupling}"
                }
                PartKind.AND, PartKind.NAND, PartKind.OR, PartKind.NOR, PartKind.XOR, PartKind.NOT,
                PartKind.SCHMITT, PartKind.DFF -> {
                    val subckt = logicSubckt.getValue(part.kind)
                    builtIns += SpiceModels.find(subckt)!!
                    val vdd = Si.parse(value)?.let { Si.formatSpice(it) } ?: run {
                        if (value.isNotEmpty()) warnings += "${part.ref}: logic supply \"$value\" is not a number; using 5 V."
                        "5"
                    }
                    elements += "$name $nodes $subckt vdd=$vdd"
                }
                PartKind.CUSTOM -> {
                    val symbol = part.symbol
                    if (symbol == null) {
                        warnings += "${part.ref} has lost its symbol; delete it and place the symbol again."
                    } else {
                        val found = resolver.resolve(symbol.subckt)
                        when {
                            found?.file != null -> includes += found.file
                            found == null && symbol.library.isNotEmpty() -> includes += symbol.library
                            found == null && !hasIncludes ->
                                warnings += "Subcircuit ${symbol.subckt} is not defined. Add ${symbol.library.ifEmpty { "its file" }} to the Model library."
                        }
                        if (found?.pins != null && found.pins != nets.size) {
                            warnings += "${symbol.subckt} has ${found.pins} pins but ${part.ref}'s symbol has ${nets.size}; edit the symbol."
                        }
                        elements += "$name $nodes ${symbol.subckt} $value".trimEnd()
                    }
                }
                else -> if (def.editor == ValueEditor.MODEL) {
                    val model = modelOf(part, def)
                    val rest = value.substringAfter(' ', "").trim()
                    when {
                        resolved == null -> if (!hasIncludes) {
                            warnings += "Model $model is not defined. Add it with a .model directive or the Model library."
                        }
                        resolved.builtIn != null -> builtIns += resolved.builtIn
                        resolved.file != null -> includes += resolved.file
                    }
                    elements += when {
                        resolved?.isSubckt == true -> {
                            if (resolved.pins != null && resolved.pins != nets.size) {
                                warnings += "$model has ${resolved.pins} pins but the ${def.title.lowercase(Locale.ROOT)} symbol has ${nets.size}. " +
                                    "Make a symbol for it in the Model library."
                            }
                            val x = if (name.startsWith("X", ignoreCase = true)) name else "X$name"
                            "$x $nodes $model $rest".trimEnd()
                        }
                        def.prefix == "X" -> {
                            if (resolved != null) warnings += "$model is a .model card, not a subcircuit, so it cannot be a ${def.title.lowercase(Locale.ROOT)}."
                            "$name $nodes $model $rest".trimEnd()
                        }
                        (part.kind == PartKind.NMOS || part.kind == PartKind.PMOS) && resolved?.type != "VDMOS" ->
                            // Level 1-3 MOSFETs take four nodes; tie the bulk to the source.
                            "$name $nodes ${nets[2]} $model $rest".trimEnd()
                        else -> "$name $nodes $model $rest".trimEnd()
                    }
                } else {
                    elements += "$name $nodes ${valueOrDefault()}"
                }
            }
        }

        for (probe in schematic.probes) {
            if (probe.type != ProbeType.VOLTAGE) continue
            val root = connectivity.root(GridPoint(probe.x, probe.y)) ?: continue
            val net = nameFor(root)
            if (net == "0") continue
            probeVectors += ProbeVector(probe, net.lowercase(Locale.ROOT), "V($net)")
        }

        if (schematic.parts.any { it.kind != PartKind.GROUND && it.kind != PartKind.LABEL } && "0" !in names.values) {
            warnings += "There is no ground. Add a ground symbol so every node has a reference."
        }

        // Live views need a time-domain or bias-point result; other analyses become an operating point.
        var directives = schematic.directives.trim()
        var liveAnalysis: AnalysisType? = null
        val analysisLine: String?
        if (live) {
            val lines = directives.lines()
            val userTran = lines.any { tranDirective.containsMatchIn(it) }
            if (lines.any { stepDirective.containsMatchIn(it) }) warnings += "Live simulation runs without .step."
            directives = lines.filterNot {
                stepDirective.containsMatchIn(it) || (analysisDirective.containsMatchIn(it) && !tranDirective.containsMatchIn(it))
            }.joinToString("\n").trim()
            liveAnalysis = if (userTran || circuit.analysis.type == AnalysisType.TRAN) AnalysisType.TRAN else AnalysisType.OP
            analysisLine = when {
                userTran -> null
                liveAnalysis == AnalysisType.TRAN -> circuit.analysis.toSpice()
                else -> ".op"
            }
        } else {
            analysisLine = if (analysisDirective.containsMatchIn(directives)) null else circuit.analysis.toSpice()
        }

        val text = buildString {
            appendLine("* ${circuit.name}")
            appendLine("* Generated by $generator")
            includes.forEach { appendLine(".include $it") }
            elements.forEach { appendLine(it) }
            if (ammeters.isNotEmpty()) {
                appendLine("* Current meters")
                ammeters.forEach { appendLine(it) }
            }
            val cards = builtIns.filterNot { it.isSubckt }
            val subckts = builtIns.filter { it.isSubckt }
            if (cards.isNotEmpty() || subckts.isNotEmpty() || needsIdealOpAmp) {
                appendLine()
                cards.forEach { appendLine(it.text) }
                if (needsIdealOpAmp) appendLine(SpiceModels.idealOpAmp)
                subckts.forEach { appendLine(it.text) }
            }
            if (saves.isNotEmpty() || live) {
                appendLine()
                appendLine(".save all")
                saves.distinct().forEach { appendLine(it) }
            }
            appendLine()
            analysisLine?.let { appendLine(it) }
            if (directives.isNotBlank()) appendLine(directives)
            appendLine(".end")
        }
        return NetlistResult(text, warnings.distinct(), probeVectors, pinNets, pinCurrents, liveAnalysis)
    }

    private const val CLOSED = "1m"
    private const val OPEN = "1T"

    private val logicSubckt = mapOf(
        PartKind.AND to SpiceModels.AND2, PartKind.NAND to SpiceModels.NAND2, PartKind.OR to SpiceModels.OR2,
        PartKind.NOR to SpiceModels.NOR2, PartKind.XOR to SpiceModels.XOR2, PartKind.NOT to SpiceModels.NOT,
        PartKind.SCHMITT to SpiceModels.SCHMITT, PartKind.DFF to SpiceModels.DFF,
    )

    private fun sanitize(ref: String) = ref.replace(Regex("""[^\w]"""), "_")

    /** A resistance times a fraction, never zero (ngspice rejects 0 ohm resistors). */
    private fun scaled(resistance: String, fraction: Double): String {
        val r = Si.parse(resistance)
        return if (r != null) Si.formatSpice(maxOf(r * fraction, 1e-3)) else "{max((${resistance.trim('{', '}')})*$fraction,1m)}"
    }

    private fun nativeCurrentVector(part: Part, spiceName: String): String {
        val name = spiceName.lowercase(Locale.ROOT)
        return when (part.kind) {
            PartKind.VOLTAGE, PartKind.INDUCTOR, PartKind.VCVS, PartKind.CCVS -> "$name#branch"
            PartKind.RESISTOR, PartKind.CAPACITOR, PartKind.BEHAVIORAL -> "@$name[i]"
            PartKind.CURRENT -> "@$name[current]"
            PartKind.NPN, PartKind.PNP -> "@$name[ic]"
            else -> "@$name[id]" // diodes and MOSFETs
        }
    }

    /** Whether a current probe can be placed on [part]. */
    fun canProbeCurrent(part: Part): Boolean = part.def.emitsSpice
}
