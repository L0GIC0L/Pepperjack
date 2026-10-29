package io.github.l0gic0l.pepperjack.sim

import io.github.l0gic0l.pepperjack.model.GridPoint
import io.github.l0gic0l.pepperjack.model.PartKind
import io.github.l0gic0l.pepperjack.model.Schematic
import io.github.l0gic0l.pepperjack.model.pinPositions
import io.github.l0gic0l.pepperjack.spice.Connectivity
import io.github.l0gic0l.pepperjack.spice.NetlistResult
import io.github.l0gic0l.pepperjack.spice.contains
import java.util.Locale
import kotlin.math.abs

/** A straight piece of wire between two connection points, with what flows through it. */
class LiveSegment(
    val wireId: Long,
    val a: GridPoint,
    val b: GridPoint,
    val net: String?,
    /** Node voltage per sample, or null if the net has no simulated voltage. */
    val voltage: DoubleArray?,
    /** Current per sample flowing from [a] to [b]. */
    val current: DoubleArray,
)

/** What a meter or LED shows, per sample. */
class LiveReadout(val partId: Long, val kind: PartKind, val values: DoubleArray)

/**
 * The live view of one simulation: per-segment voltages and currents on the schematic's
 * wires, and meter and LED readings. Built from a live netlist, which measures the current
 * into every pin; wire currents then follow from Kirchhoff's current law over each wire network.
 */
class LiveView(
    /** Sample times for a transient, or null for an operating point (one sample). */
    val time: DoubleArray?,
    val segments: List<LiveSegment>,
    val readouts: List<LiveReadout>,
    val maxVoltage: Double,
    val maxCurrent: Double,
) {
    val samples: Int get() = time?.size ?: 1
    val duration: Double get() = time?.let { if (it.isEmpty()) 0.0 else it.last() - it.first() } ?: 0.0

    /** Fractional sample position for simulation time [t]. */
    fun position(t: Double): Double {
        val times = time ?: return 0.0
        if (times.size < 2 || t <= times.first()) return 0.0
        if (t >= times.last()) return (times.size - 1).toDouble()
        var lo = 0
        var hi = times.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= t) lo = mid else hi = mid
        }
        val span = times[hi] - times[lo]
        return lo + if (span > 0) (t - times[lo]) / span else 0.0
    }

    companion object {
        /** Value of [series] at fractional sample [position], linearly interpolated. */
        fun sample(series: DoubleArray, position: Double): Double {
            if (series.isEmpty()) return 0.0
            val i = position.toInt().coerceIn(0, series.size - 1)
            val j = (i + 1).coerceAtMost(series.size - 1)
            val f = position - i
            return series[i] + (series[j] - series[i]) * f
        }

        fun build(schematic: Schematic, netlist: NetlistResult, result: SimulationResult): LiveView? {
            val plot = result.plots.firstOrNull { it.kind == PlotKind.TRAN }
                ?: result.plots.firstOrNull { it.kind == PlotKind.OP }
                ?: return null
            val time = if (plot.kind == PlotKind.TRAN) plot.scale?.real else null
            val samples = time?.size ?: 1
            val byName = plot.vectors.associateBy { it.name.lowercase(Locale.ROOT) }
            val zeros = DoubleArray(samples)

            fun series(name: String): DoubleArray? = byName[name.lowercase(Locale.ROOT)]?.real?.takeIf { it.size == samples }
            fun voltage(net: String): DoubleArray? = if (net == "0") zeros else series(net)
            fun pinCurrent(partId: Long, pin: Int): DoubleArray? = netlist.pinCurrents[partId]?.getOrNull(pin)?.let(::series)

            val segments = buildSegments(schematic, netlist, samples, ::voltage, ::pinCurrent)

            val readouts = schematic.parts.mapNotNull { part ->
                when (part.kind) {
                    PartKind.AMMETER, PartKind.LED -> pinCurrent(part.id, 0)?.let { LiveReadout(part.id, part.kind, it) }
                    PartKind.VOLTMETER -> {
                        val nets = netlist.pinNets[part.id] ?: return@mapNotNull null
                        val plus = voltage(nets[0]) ?: return@mapNotNull null
                        val minus = voltage(nets[1]) ?: return@mapNotNull null
                        LiveReadout(part.id, part.kind, DoubleArray(samples) { plus[it] - minus[it] })
                    }
                    else -> null
                }
            }

            val maxVoltage = segments.mapNotNull { it.voltage }.distinct().maxOfOrNull { s -> s.maxOf { abs(it) } } ?: 0.0
            val maxCurrent = segments.maxOfOrNull { s -> s.current.maxOf { abs(it) } } ?: 0.0
            return LiveView(time, segments, readouts, maxVoltage, maxCurrent)
        }

        private fun buildSegments(
            schematic: Schematic,
            netlist: NetlistResult,
            samples: Int,
            voltage: (String) -> DoubleArray?,
            pinCurrent: (Long, Int) -> DoubleArray?,
        ): List<LiveSegment> {
            // Split every wire wherever a pin or another wire's end lies on it.
            val points = HashSet<GridPoint>()
            schematic.wires.forEach { points += it.start; points += it.end }
            schematic.parts.forEach { points += it.pinPositions() }
            class Edge(val wireId: Long, val a: GridPoint, val b: GridPoint)
            val edges = ArrayList<Edge>()
            for (wire in schematic.wires) {
                if (wire.start == wire.end) continue
                val stops = points.filter { it == wire.start || it == wire.end || wire.contains(it) }
                    .sortedBy { abs(it.x - wire.x1) + abs(it.y - wire.y1) }
                stops.zipWithNext { p, q -> edges += Edge(wire.id, p, q) }
            }
            if (edges.isEmpty()) return emptyList()

            val vertexIndex = HashMap<GridPoint, Int>()
            val vertices = ArrayList<GridPoint>()
            fun vertex(p: GridPoint) = vertexIndex.getOrPut(p) { vertices += p; vertices.size - 1 }
            val adjacency = HashMap<Int, MutableList<Int>>() // vertex -> edge indices
            edges.forEachIndexed { e, edge ->
                adjacency.getOrPut(vertex(edge.a)) { mutableListOf() } += e
                adjacency.getOrPut(vertex(edge.b)) { mutableListOf() } += e
            }

            // What happens at each vertex: current leaving the wires into device pins, and
            // whether the net continues elsewhere through a ground symbol or net label.
            val outflow = HashMap<Int, DoubleArray>()
            val virtual = HashSet<Int>()
            val netAt = HashMap<Int, String>()
            for (part in schematic.parts) {
                part.pinPositions().forEachIndexed { pin, point ->
                    val v = vertexIndex[point] ?: return@forEachIndexed
                    when (part.kind) {
                        PartKind.GROUND -> { virtual += v; netAt.putIfAbsent(v, "0") }
                        PartKind.LABEL -> { virtual += v; netAt.putIfAbsent(v, Connectivity.netName(part.value)) }
                        else -> {
                            netlist.pinNets[part.id]?.getOrNull(pin)?.let { netAt[v] = it }
                            val current = pinCurrent(part.id, pin) ?: return@forEachIndexed
                            val sum = outflow.getOrPut(v) { DoubleArray(samples) }
                            for (k in 0 until samples) sum[k] += current[k]
                        }
                    }
                }
            }

            val segments = arrayOfNulls<LiveSegment>(edges.size)
            val visited = BooleanArray(vertices.size)
            val zeros = DoubleArray(samples)
            for (start in vertices.indices) {
                if (visited[start]) continue
                // Collect the connected wire network, then root its spanning tree where the
                // net leaves the sheet (ground or a label) so that vertex absorbs any imbalance.
                val component = ArrayList<Int>()
                val stack = ArrayDeque<Int>().apply { add(start) }
                visited[start] = true
                while (stack.isNotEmpty()) {
                    val v = stack.removeLast()
                    component += v
                    adjacency[v].orEmpty().forEach { e ->
                        val other = vertexIndex.getValue(if (vertices[v] == edges[e].a) edges[e].b else edges[e].a)
                        if (!visited[other]) { visited[other] = true; stack.add(other) }
                    }
                }
                val root = component.firstOrNull { it in virtual } ?: component.firstOrNull { it in outflow } ?: component.first()
                val net = component.firstNotNullOfOrNull { v -> netAt[v]?.takeIf { v !in virtual } } ?: component.firstNotNullOfOrNull { netAt[it] }
                val volts = net?.let(voltage)

                // Breadth-first spanning tree from the root.
                val parentEdge = HashMap<Int, Int>()
                val order = ArrayList<Int>()
                val seen = HashSet<Int>().apply { add(root) }
                val queue = ArrayDeque<Int>().apply { add(root) }
                while (queue.isNotEmpty()) {
                    val v = queue.removeFirst()
                    order += v
                    adjacency[v].orEmpty().forEach { e ->
                        val other = vertexIndex.getValue(if (vertices[v] == edges[e].a) edges[e].b else edges[e].a)
                        if (seen.add(other)) { parentEdge[other] = e; queue.add(other) }
                    }
                }
                // Current through a tree edge equals everything that leaves the network below it.
                val subtree = HashMap<Int, DoubleArray>()
                for (v in order.asReversed()) {
                    val total = subtree.getOrPut(v) { outflow[v]?.copyOf() ?: DoubleArray(samples) }
                    val e = parentEdge[v] ?: continue
                    val edge = edges[e]
                    val parent = vertexIndex.getValue(if (vertices[v] == edge.a) edge.b else edge.a)
                    val towardsB = vertices[v] == edge.b
                    segments[e] = LiveSegment(edge.wireId, edge.a, edge.b, net, volts, if (towardsB) total else DoubleArray(samples) { -total[it] })
                    val up = subtree.getOrPut(parent) { outflow[parent]?.copyOf() ?: DoubleArray(samples) }
                    for (k in 0 until samples) up[k] += total[k]
                }
                // Edges closing a wire loop carry no current in this picture.
                component.forEach { v ->
                    adjacency[v].orEmpty().forEach { e ->
                        if (segments[e] == null) segments[e] = LiveSegment(edges[e].wireId, edges[e].a, edges[e].b, net, volts, zeros)
                    }
                }
            }
            return segments.filterNotNull()
        }
    }
}
