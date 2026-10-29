package io.github.l0gic0l.pepperjack.sim

import android.content.Context
import android.os.SystemClock
import io.github.l0gic0l.pepperjack.model.SpiceDialect
import io.github.l0gic0l.pepperjack.spice.Preprocessor
import io.github.l0gic0l.pepperjack.spice.ProbeVector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

sealed interface EngineStatus {
    data class Ready(val version: String, val codeModels: List<String>) : EngineStatus
    data class Unavailable(val reason: String) : EngineStatus
}

/**
 * Runs netlists through the bundled ngspice. ngspice is a process-wide singleton,
 * so every call is serialized; simulations run on ngspice's background thread and
 * can be cancelled through coroutine cancellation (mapped to `bg_halt`).
 */
class SimulationEngine(context: Context, private val workingDirectory: File) {
    private val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
    private val mutex = Mutex()
    private var status: EngineStatus? = null

    private val _progress = MutableStateFlow<String?>(null)

    /** ngspice's status line while a simulation runs ("tran: 45.2%"), else null. */
    val progress: StateFlow<String?> = _progress.asStateFlow()

    suspend fun status(): EngineStatus = mutex.withLock { withContext(Dispatchers.IO) { ensureReady() } }

    suspend fun run(
        netlist: String,
        dialect: SpiceDialect,
        probes: List<ProbeVector> = emptyList(),
        warnings: List<String> = emptyList(),
    ): SimulationResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val started = SystemClock.elapsedRealtime()
            val engine = ensureReady()
            if (engine is EngineStatus.Unavailable) {
                return@withContext SimulationResult(emptyList(), emptyList(), warnings, engine.reason)
            }
            val deck = Preprocessor.prepare(netlist, dialect)
            val log = mutableListOf<LogLine>()
            val plots = mutableListOf<Plot>()
            var error: String? = null
            val hasControlBlock = Regex("""(?im)^\s*\.control\b""").containsMatchIn(netlist)
            workingDirectory.mkdirs()
            NgSpiceNative.setWorkingDirectory(workingDirectory.absolutePath)

            try {
                for (step in deck.runs) {
                    ensureActive()
                    quietly("destroy all")
                    quietly("remcirc")
                    quietly(dialect.ngBehavior?.let { "set ngbehavior=$it" } ?: "unset ngbehavior")

                    val loadStart = log.size
                    val rc = NgSpiceNative.loadCircuit(step.netlist.lines().toTypedArray())
                    log += drain()
                    val loadLog = log.subList(loadStart, log.size)
                    if (rc != 0 || loadLog.any { it.text.contains("circuit not parsed", true) ||
                            it.text.contains("simulation interrupted due to error", true) }
                    ) {
                        error = PARSE_ERROR
                        break
                    }
                    step.commands.forEach { quietly(it) }

                    if (!hasControlBlock) {
                        if (NgSpiceNative.startBackground("bg_run") != 0) {
                            error = "ngspice refused to start the simulation."
                            break
                        }
                        try {
                            while (!NgSpiceNative.waitForBackground(100)) {
                                _progress.value = NgSpiceNative.status().ifBlank { null }
                                log += drain()
                                ensureActive()
                            }
                        } catch (e: CancellationException) {
                            NgSpiceNative.halt()
                            throw e
                        }
                    }
                    log += drain()
                    if (NgSpiceNative.hasExited()) {
                        error = "ngspice stopped unexpectedly. It will restart on the next run."
                        break
                    }
                    NgSpiceNative.allPlots()
                        .filter { it != "const" }
                        .mapNotNullTo(plots) { readPlot(it, step.label) }
                }
            } finally {
                _progress.value = null
            }

            if (error == null && plots.isEmpty()) {
                error = when {
                    log.any { it.text.contains("circuit not parsed", ignoreCase = true) } -> PARSE_ERROR
                    else -> log.lastOrNull { it.isError }?.text
                        ?: "No results were produced. Add an analysis such as .tran, .ac, .dc or .op."
                }
            }
            SimulationResult(
                plots = plots.sortedWith(compareBy({ it.kind.ordinal }, { it.name })),
                log = log,
                warnings = warnings + deck.warnings,
                error = error,
                probes = probes,
                elapsedMs = SystemClock.elapsedRealtime() - started,
            )
        }
    }

    private fun ensureReady(): EngineStatus {
        status?.let { current ->
            if (current is EngineStatus.Ready && !NgSpiceNative.hasExited()) return current
            if (current is EngineStatus.Unavailable) return current
            NgSpiceNative.unload()
        }
        val result = if (!NgSpiceNative.bridgeLoaded) {
            EngineStatus.Unavailable("The native bridge failed to load on this device.")
        } else {
            val library = File(nativeLibraryDir, "libngspice.so")
            val loadError = NgSpiceNative.load(library.absolutePath)
            when {
                loadError != null -> EngineStatus.Unavailable(
                    if (!library.exists()) {
                        "This build does not include the ngspice engine. Build it with scripts/build-ngspice-android.sh, " +
                            "or install an APK from the project's GitHub Actions."
                    } else {
                        "ngspice failed to load: $loadError"
                    },
                )
                !NgSpiceNative.initialize() -> EngineStatus.Unavailable("ngspice failed to initialize.")
                else -> {
                    val models = CODE_MODELS.filter { name ->
                        val file = File(nativeLibraryDir, "libngspice_cm_$name.so")
                        file.exists() && NgSpiceNative.command("codemodel ${file.absolutePath}") == 0
                    }
                    NgSpiceNative.command("set nomoremode")
                    NgSpiceNative.drainOutput()
                    NgSpiceNative.command("version")
                    val banner = NgSpiceNative.drainOutput().joinToString("\n")
                    val version = Regex("""ngspice-(\S+)""").find(banner)?.groupValues?.get(1) ?: "unknown"
                    EngineStatus.Ready(version, models)
                }
            }
        }
        status = result
        return result
    }

    private fun quietly(command: String) {
        NgSpiceNative.command(command)
        NgSpiceNative.drainOutput()
    }

    private fun drain(): List<LogLine> = NgSpiceNative.drainOutput().map { line ->
        when {
            line.startsWith("stderr ") -> LogLine(line.removePrefix("stderr "), isError = isErrorText(line))
            line.startsWith("stdout ") -> LogLine(line.removePrefix("stdout "), isError = false)
            else -> LogLine(line, isError = false)
        }
    }

    private fun isErrorText(line: String): Boolean {
        val text = line.removePrefix("stderr ").trimStart()
        return text.startsWith("Error", ignoreCase = true) || text.contains("error:", ignoreCase = true) ||
            text.contains("not parsed", ignoreCase = true) || text.contains("interrupted", ignoreCase = true)
    }

    private fun readPlot(plotName: String, stepLabel: String?): Plot? {
        val names = NgSpiceNative.allVectors(plotName).filter { it.none(Char::isWhitespace) }
        if (names.isEmpty()) return null
        val vectors = names.mapNotNull { name ->
            NgSpiceNative.getVector("$plotName.$name")?.takeIf { it.real.isNotEmpty() }?.let {
                Vector(it.name, Quantity.fromSpiceType(it.type), it.real, it.imag)
            }
        }
        NgSpiceNative.drainOutput()
        val kind = PlotKind.fromPlotName(plotName)
        val scaleName = NgSpiceNative.scaleName(plotName)
        val scale = if (kind == PlotKind.OP) null else {
            vectors.firstOrNull { it.name.equals(scaleName, ignoreCase = true) }
                ?: vectors.firstOrNull { it.quantity == Quantity.TIME || it.quantity == Quantity.FREQUENCY }
                ?: vectors.firstOrNull { it.name.endsWith("-sweep") }
        }
        // AC frequency is stored as a complex vector with zero imaginary part.
        val realScale = scale?.let { Vector(it.name, it.quantity, it.real, null) }
        return Plot(
            name = plotName,
            title = NgSpiceNative.plotTitle(plotName) ?: kind.label,
            kind = kind,
            scale = realScale,
            vectors = vectors.filter { it !== scale },
            stepLabel = stepLabel,
        )
    }

    companion object {
        private const val PARSE_ERROR = "The netlist could not be parsed. See the log for the offending line."

        /** XSPICE code models bundled by scripts/build-ngspice-android.sh, in spinit order. */
        val CODE_MODELS = listOf("spice2poly", "analog", "digital", "xtradev", "xtraevt", "tlines")
    }
}
