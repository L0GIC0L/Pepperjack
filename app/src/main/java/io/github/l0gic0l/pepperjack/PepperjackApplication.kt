package io.github.l0gic0l.pepperjack

import android.app.Application
import android.content.Context
import io.github.l0gic0l.pepperjack.data.CircuitRepository
import io.github.l0gic0l.pepperjack.data.CrashReporter
import io.github.l0gic0l.pepperjack.data.ModelLibrary
import io.github.l0gic0l.pepperjack.data.SettingsRepository
import io.github.l0gic0l.pepperjack.sim.SimulationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class PepperjackApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        container = AppContainer(this)
    }
}

/** App-wide singletons, created once per process. */
class AppContainer(context: Context) {
    val circuits = CircuitRepository(File(context.filesDir, "circuits"))
    val library = ModelLibrary(File(context.filesDir, "library"))
    val settings = SettingsRepository(context)
    val engine = SimulationEngine(context, library.directory)

    /** Outlives screens; used for work that must finish, such as the final save on exit. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
