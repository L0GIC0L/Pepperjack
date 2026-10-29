package io.github.l0gic0l.pepperjack

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.l0gic0l.pepperjack.data.Examples
import io.github.l0gic0l.pepperjack.model.CircuitKind
import io.github.l0gic0l.pepperjack.model.SpiceDialect
import io.github.l0gic0l.pepperjack.sim.EngineStatus
import io.github.l0gic0l.pepperjack.sim.LiveView
import io.github.l0gic0l.pepperjack.spice.ModelZoo
import io.github.l0gic0l.pepperjack.spice.Netlister
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the bundled ngspice on a real Android runtime: start-up, then every example. */
@RunWith(AndroidJUnit4::class)
class EngineInstrumentedTest {
    private val container =
        (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PepperjackApplication).container

    @Test
    fun engineStartsAndSimulatesEveryExample() = runBlocking {
        val status = container.engine.status()
        Log.i(TAG, "Engine status: $status")
        assertTrue("$status", status is EngineStatus.Ready)

        for (example in Examples.all) {
            val circuit = example.build()
            val dialect = circuit.dialect ?: SpiceDialect.Default
            val result = if (circuit.kind == CircuitKind.SCHEMATIC) {
                val netlist = Netlister.netlist(circuit)
                container.engine.run(netlist.text, dialect, netlist.probeVectors, netlist.warnings)
            } else {
                container.engine.run(circuit.netlist, dialect)
            }
            Log.i(TAG, "${example.title}: ${result.plots.size} plots, error=${result.error}, ${result.elapsedMs} ms")
            result.log.filter { it.isError }.forEach { Log.w(TAG, "  ${it.text}") }
            assertTrue("${example.title}: ${result.error}", result.succeeded)
            result.probes.forEach { probe ->
                assertTrue(
                    "${example.title} probe ${probe.vector}",
                    result.plots.any { plot -> plot.vectors.any { it.name == probe.vector } },
                )
            }
        }
    }

    /** Live netlists of every schematic example simulate and map onto their wires. */
    @Test
    fun liveViewForEverySchematicExample() = runBlocking {
        assertTrue(container.engine.status() is EngineStatus.Ready)
        for (example in Examples.all) {
            val circuit = example.build()
            if (circuit.kind != CircuitKind.SCHEMATIC) continue
            val netlist = Netlister.netlist(circuit, live = true)
            val result = container.engine.run(netlist.text, circuit.dialect ?: SpiceDialect.Default, netlist.probeVectors, netlist.warnings)
            assertTrue("${example.title}: ${result.error}", result.succeeded)
            val view = LiveView.build(circuit.schematic, netlist, result)
            Log.i(TAG, "${example.title} live: ${view?.segments?.size} segments, ${view?.samples} samples, max ${view?.maxCurrent} A")
            assertTrue(example.title, view != null && view.segments.isNotEmpty() && view.maxCurrent > 0)
        }
    }

    /** Every built-in model on the device's ngspice, including the XSPICE logic and 555 models. */
    @Test
    fun everyBuiltInModelSimulates() = runBlocking {
        assertTrue(container.engine.status() is EngineStatus.Ready)
        for (circuit in ModelZoo.benches()) {
            for (live in listOf(false, true)) {
                val netlist = Netlister.netlist(circuit, live = live)
                val result = container.engine.run(netlist.text, SpiceDialect.Default, netlist.probeVectors, netlist.warnings)
                result.log.filter { it.isError }.forEach { Log.w(TAG, "  ${circuit.name}: ${it.text}") }
                assertTrue("${circuit.name} (live=$live): ${result.error}", result.succeeded)
            }
        }
        Log.i(TAG, "Simulated ${ModelZoo.benches().size} model benches, plain and live")
    }

    private companion object {
        const val TAG = "PepperjackTest"
    }
}
