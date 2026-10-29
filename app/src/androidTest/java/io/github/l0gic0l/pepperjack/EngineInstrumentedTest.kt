package io.github.l0gic0l.pepperjack

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.l0gic0l.pepperjack.data.Examples
import io.github.l0gic0l.pepperjack.model.CircuitKind
import io.github.l0gic0l.pepperjack.model.SpiceDialect
import io.github.l0gic0l.pepperjack.sim.EngineStatus
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

    private companion object {
        const val TAG = "PepperjackTest"
    }
}
