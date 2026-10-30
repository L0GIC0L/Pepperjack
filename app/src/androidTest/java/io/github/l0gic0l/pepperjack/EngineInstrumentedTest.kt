package io.github.l0gic0l.pepperjack

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.l0gic0l.pepperjack.data.Examples
import io.github.l0gic0l.pepperjack.model.CircuitKind
import io.github.l0gic0l.pepperjack.model.PartKind
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
    fun engineStartsAndSimulatesEveryExample(): Unit = runBlocking {
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
    fun liveViewForEverySchematicExample(): Unit = runBlocking {
        assertTrue(container.engine.status() is EngineStatus.Ready)
        for (example in Examples.all) {
            val circuit = example.build()
            if (circuit.kind != CircuitKind.SCHEMATIC) continue
            val netlist = Netlister.netlist(circuit, live = true)
            val result = container.engine.run(netlist.text, circuit.dialect ?: SpiceDialect.Default, netlist.probeVectors, netlist.warnings)
            assertTrue("${example.title}: ${result.error}", result.succeeded)
            val view = LiveView.build(circuit.schematic, netlist, result)
            Log.i(TAG, "${example.title} live: ${view?.segments?.size} segments, ${view?.samples} samples, max ${view?.maxCurrent} A")
            assertTrue(example.title, view != null && view.segments.isNotEmpty())
            // Live views show the DC operating point (or a transient); AC-only examples carry no DC current.
            if (example.title in drivenExamples) assertTrue("${example.title} has current", view!!.maxCurrent > 1e-4)
        }
    }

    /** Every built-in model on the device's ngspice, including the XSPICE logic and 555 models. */
    @Test
    fun everyBuiltInModelSimulates(): Unit = runBlocking {
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

    /** The online library end to end: download TI's LM358 model from GitHub, then simulate it in a schematic. */
    @Test
    fun anOnlineModelDownloadsAndSimulates(): Unit = runBlocking {
        assertTrue(container.engine.status() is EngineStatus.Ready)
        val index = container.onlineModels.index()
        val model = index.models.first { it.name == "LM358" && it.file.path.endsWith("Texas Instruments/lm358.mod") }
        val name = container.onlineModels.add(index, model.file)
        val stored = container.library.files.value.first { it.name == name }
        assertTrue(stored.source, index.isCopy(stored.source, model.file))
        assertTrue(stored.models.any { it.name == "LM358" && it.pins.size == 5 })

        // A non-inverting amplifier with a gain of 2 on ±12 V supplies.
        val circuit = ModelZoo.Bench("online-lm358").run {
            part(PartKind.VOLTAGE, "V1", "vp", "0", value = "DC 12")
            part(PartKind.VOLTAGE, "V2", "0", "vn", value = "DC 12")
            part(PartKind.VOLTAGE, "V3", "in", "0", value = "SIN(0 2 1k)")
            part(PartKind.RESISTOR, "R1", "out", "fb", value = "10k")
            part(PartKind.RESISTOR, "R2", "fb", "0", value = "10k")
            part(PartKind.OPAMP5, "U1", "in", "fb", "vp", "vn", "out", value = "LM358")
            circuit(ModelZoo.tran)
        }
        val netlist = Netlister.netlist(circuit, container.library.index())
        assertTrue(netlist.text, netlist.text.contains(".include $name"))
        val result = container.engine.run(netlist.text, SpiceDialect.Default, netlist.probeVectors, netlist.warnings)
        result.log.filter { it.isError }.forEach { Log.w(TAG, "  online LM358: ${it.text}") }
        assertTrue(result.error, result.succeeded)
        val out = result.plots.flatMap { it.vectors }.first { it.name.equals("v(out)", true) || it.name.equals("out", true) }
        val peak = out.real.maxOf { it }
        Log.i(TAG, "Online LM358: peak output $peak V")
        assertTrue("peak $peak", peak in 3.6..4.4)
    }

    private companion object {
        const val TAG = "PepperjackTest"
        val drivenExamples = setOf("Light switch (try live mode)", "Potentiometer dimmer (live)", "555 LED blinker", "7805 regulator", "Half adder (logic gates)")
    }
}
