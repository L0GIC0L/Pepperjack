package io.github.l0gic0l.pepperjack.ui.results

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.l0gic0l.pepperjack.sim.Plot
import io.github.l0gic0l.pepperjack.sim.PlotKind
import io.github.l0gic0l.pepperjack.sim.Quantity
import io.github.l0gic0l.pepperjack.sim.SimulationResult
import io.github.l0gic0l.pepperjack.sim.Vector
import io.github.l0gic0l.pepperjack.ui.theme.LocalCircuitColors
import io.github.l0gic0l.pepperjack.util.Si

/** Color of a selected trace: by position among the plot's selected vectors, then by .step. */
fun traceColor(palette: List<Color>, selectedIndex: Int, selectedCount: Int, stepIndex: Int): Color =
    palette[(selectedIndex + stepIndex * maxOf(selectedCount, 1)) % palette.size]

/** Selected vectors of [plots] in plot order, so colors are stable as traces are toggled. */
fun orderedSelection(plots: List<Plot>, selected: Set<String>): List<Vector> =
    plots.firstOrNull()?.vectors?.filter { it.name in selected }.orEmpty()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsPane(
    result: SimulationResult?,
    running: Boolean,
    progress: String?,
    traces: Map<PlotKind, Set<String>>,
    onToggleTrace: (PlotKind, String) -> Unit,
    onRun: () -> Unit,
    onExport: (List<Plot>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        if (running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                progress ?: "Simulating…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (result == null) {
            if (!running) EmptyResults(onRun)
            return@Column
        }
        val groups = remember(result) { result.plots.groupBy { it.kind }.toSortedMap(compareBy { it.ordinal }) }
        val tabs = groups.keys.toList()
        var selectedTab by rememberSaveable(result) { mutableIntStateOf(if (result.succeeded) 0 else tabs.size) }
        val tabIndex = selectedTab.coerceIn(0, tabs.size)

        PrimaryScrollableTabRow(selectedTabIndex = tabIndex, edgePadding = 8.dp) {
            tabs.forEachIndexed { index, kind ->
                Tab(selected = tabIndex == index, onClick = { selectedTab = index }, text = { Text(kind.label) })
            }
            Tab(selected = tabIndex == tabs.size, onClick = { selectedTab = tabs.size }, text = { Text("Log") })
        }
        result.error?.let { MessageCard(it, isError = true) }
        if (result.warnings.isNotEmpty()) WarningsCard(result.warnings)

        if (tabIndex == tabs.size) {
            LogView(result)
        } else {
            val kind = tabs[tabIndex]
            val plots = groups.getValue(kind)
            when (kind) {
                PlotKind.OP -> OperatingPointTable(plots.first())
                else -> PlotView(kind, plots, traces[kind].orEmpty(), { onToggleTrace(kind, it) }, { onExport(plots) })
            }
        }
    }
}

@Composable
private fun EmptyResults(onRun: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.AutoMirrored.Outlined.ShowChart, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("No results yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Run the simulation to plot waveforms, Bode plots and operating points.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = onRun) {
            Icon(Icons.Outlined.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text("Run")
        }
    }
}

@Composable
private fun MessageCard(message: String, isError: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.WarningAmber, null)
            Spacer(Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun WarningsCard(warnings: List<String>) {
    var expanded by remember(warnings) { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        onClick = { expanded = !expanded },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WarningAmber, null)
                Spacer(Modifier.width(12.dp))
                Text(
                    if (warnings.size == 1) warnings.first() else "${warnings.size} warnings",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded && warnings.size > 1) {
                warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlotView(
    kind: PlotKind,
    plots: List<Plot>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onExport: () -> Unit,
) {
    val palette = LocalCircuitColors.current.traces
    val scheme = MaterialTheme.colorScheme
    val chartColors = ChartColors(grid = scheme.outlineVariant.copy(alpha = 0.6f), axis = scheme.outline, label = scheme.onSurfaceVariant, cursor = scheme.tertiary)
    val cursors = remember(plots) { Cursors() }
    val window = remember(plots) { ChartWindow() }
    val phaseWindow = remember(plots) { ChartWindow() }
    val first = plots.first()
    val scale = first.scale
    val chosen = orderedSelection(plots, selected)

    data class Series(val vector: Vector, val plot: Plot, val color: Color, val label: String)
    val series = plots.flatMapIndexed { stepIndex, plot ->
        chosen.mapIndexedNotNull { i, chosenVector ->
            val vector = plot.vectors.firstOrNull { it.name == chosenVector.name } ?: return@mapIndexedNotNull null
            val label = vector.label + (plot.stepLabel?.let { " [$it]" } ?: "")
            Series(vector, plot, traceColor(palette, i, chosen.size, stepIndex), label)
        }
    }
    val xs = { plot: Plot -> plot.scale?.real ?: DoubleArray(plot.vectors.firstOrNull()?.size ?: 0) { it.toDouble() } }
    val xUnit = scale?.quantity?.unit ?: ""
    val logX = kind == PlotKind.AC && first.scale?.real?.all { it > 0 } == true

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
            Text(
                first.title + if (plots.size > 1) " · ${plots.size} steps" else "",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (cursors.a != null || cursors.b != null) TextButton(onClick = { cursors.clear() }) { Text("Clear cursors") }
            IconButton(onClick = onExport) { Icon(Icons.Outlined.IosShare, contentDescription = "Export CSV") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp)) {
            if (series.isEmpty()) {
                Text(
                    "Pick traces below to plot them.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else if (kind == PlotKind.AC) {
                Column {
                    val magnitude = series.map { s ->
                        Trace(s.label, s.color, xs(s.plot), DoubleArray(s.vector.size) { s.vector.magnitudeDb(it) })
                    }
                    val phase = series.map { s ->
                        Trace(s.label, s.color, xs(s.plot), DoubleArray(s.vector.size) { s.vector.phaseDegrees(it) })
                    }
                    WaveformChart(magnitude, xUnit, "dB", logX, cursors, window, chartColors, Modifier.weight(1f).fillMaxWidth())
                    WaveformChart(phase, xUnit, "°", logX, cursors, phaseWindow, chartColors, Modifier.weight(0.7f).fillMaxWidth(), sharedX = window)
                }
            } else {
                val unit = series.map { it.vector.quantity }.distinct().singleOrNull()?.unit ?: ""
                val traceList = series.map { s -> Trace(s.label, s.color, xs(s.plot), s.vector.real) }
                WaveformChart(traceList, xUnit, unit, logX, cursors, window, chartColors, Modifier.fillMaxSize())
            }
        }
        if (series.isNotEmpty() && (cursors.a != null || cursors.b != null)) {
            CursorReadout(series.map { Triple(it.label, it.color, it) }.map { (label, color, s) ->
                ReadoutRow(label, color, s.vector, xs(s.plot), kind == PlotKind.AC)
            }, cursors, xUnit)
        }
        if (series.size > 1) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                series.take(24).forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(s.color, CircleShape))
                        Spacer(Modifier.width(4.dp))
                        Text(s.label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        HorizontalDivider()
        val choices = first.vectors.filterNot { it.isInternal }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            choices.forEach { vector ->
                val index = chosen.indexOfFirst { it.name == vector.name }
                FilterChip(
                    selected = index >= 0,
                    onClick = { onToggle(vector.name) },
                    label = { Text(vector.label) },
                    leadingIcon = if (index >= 0) {
                        { Box(Modifier.size(10.dp).background(traceColor(palette, index, chosen.size, 0), CircleShape)) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

private class ReadoutRow(val label: String, val color: Color, val vector: Vector, val x: DoubleArray, val ac: Boolean)

@Composable
private fun CursorReadout(rows: List<ReadoutRow>, cursors: Cursors, xUnit: String) {
    val a = cursors.a
    val b = cursors.b
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).heightIn(max = 160.dp)) {
            val header = buildString {
                a?.let { append("A = ${Si.format(it, xUnit)}") }
                b?.let { append("    B = ${Si.format(it, xUnit)}") }
                if (a != null && b != null) {
                    append("    Δ = ${Si.format(b - a, xUnit)}")
                    if (xUnit == "s" && b != a) append("  (1/Δ = ${Si.format(1 / kotlin.math.abs(b - a), "Hz")})")
                }
            }
            Text(header, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            LazyColumn(contentPadding = PaddingValues(top = 4.dp)) {
                items(rows) { row ->
                    fun valueAt(x: Double?): String {
                        if (x == null) return ""
                        if (row.ac) {
                            val mag = interpolate(row.x, DoubleArray(row.vector.size) { row.vector.magnitudeDb(it) }, x) ?: return "—"
                            val ph = interpolate(row.x, DoubleArray(row.vector.size) { row.vector.phaseDegrees(it) }, x) ?: return "—"
                            return "%.2f dB  %.1f°".format(mag, ph)
                        }
                        return interpolate(row.x, row.vector.real, x)?.let { Si.format(it, row.vector.quantity.unit, 4) } ?: "—"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(row.color, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(row.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(valueAt(a), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        if (b != null) {
                            Spacer(Modifier.width(12.dp))
                            Text(valueAt(b), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OperatingPointTable(plot: Plot) {
    val sections = remember(plot) {
        val visible = plot.vectors.filterNot { it.isInternal }
        listOf(
            "Node voltages" to visible.filter { it.quantity == Quantity.VOLTAGE },
            "Branch and device currents" to visible.filter { it.quantity == Quantity.CURRENT },
            "Other" to visible.filter { it.quantity != Quantity.VOLTAGE && it.quantity != Quantity.CURRENT },
        ).filter { it.second.isNotEmpty() }
    }
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            sections.forEach { (title, vectors) ->
                item {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(vectors, key = { it.name }) { vector ->
                    ListItem(
                        headlineContent = { Text(vector.label, fontFamily = FontFamily.Monospace) },
                        trailingContent = {
                            Text(
                                Si.format(vector.real.firstOrNull() ?: Double.NaN, vector.quantity.unit, 5),
                                style = MaterialTheme.typography.bodyLarge,
                                fontFamily = FontFamily.Monospace,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LogView(result: SimulationResult) {
    val lines = remember(result) { result.log.filter { it.text.isNotBlank() } }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxSize()) {
        SelectionContainer {
            LazyColumn(contentPadding = PaddingValues(16.dp)) {
                item {
                    Text(
                        "Finished in ${result.elapsedMs} ms",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(lines) { line ->
                    Text(
                        line.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = if (line.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** CSV of every vector in [plots] (one block per .step), complex values as magnitude and phase. */
fun plotsToCsv(plots: List<Plot>): String = buildString {
    plots.forEach { plot ->
        plot.stepLabel?.let { appendLine("# step $it") }
        val columns = listOfNotNull(plot.scale) + plot.vectors
        appendLine(columns.joinToString(",") { v -> if (v.isComplex) "${v.name} (dB),${v.name} (deg)" else v.name })
        val rows = columns.maxOfOrNull { it.size } ?: 0
        for (i in 0 until rows) {
            appendLine(
                columns.joinToString(",") { v ->
                    when {
                        i >= v.size -> if (v.isComplex) "," else ""
                        v.isComplex -> "${v.magnitudeDb(i)},${v.phaseDegrees(i)}"
                        else -> v.real[i].toString()
                    }
                },
            )
        }
        appendLine()
    }
}
