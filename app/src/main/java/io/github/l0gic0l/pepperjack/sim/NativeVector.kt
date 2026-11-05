package io.github.l0gic0l.pepperjack.sim

/** Built by the JNI bridge (see pepperjack_jni.cpp getVector); keep the constructor stable. */
class NativeVector(
    val name: String,
    val type: Int,
    val isComplex: Boolean,
    val real: DoubleArray,
    val imag: DoubleArray?,
)

/** Thin JNI surface over libngspice. All calls must be serialized by the caller. */
internal object NgSpiceNative {
    val bridgeLoaded: Boolean = runCatching { System.loadLibrary("pepperjack") }.isSuccess

    /** Returns null on success, otherwise the dlopen/dlsym error. */
    external fun load(libraryPath: String): String?
    external fun initialize(): Boolean
    external fun unload()
    external fun command(command: String): Int
    external fun loadCircuit(lines: Array<String>): Int
    external fun startBackground(command: String): Int
    external fun waitForBackground(timeoutMs: Long): Boolean
    external fun halt()
    external fun isRunning(): Boolean
    external fun hasExited(): Boolean
    external fun drainOutput(): Array<String>
    external fun status(): String
    external fun currentPlot(): String?
    external fun allPlots(): Array<String>
    external fun allVectors(plot: String): Array<String>
    external fun scaleName(plot: String): String?
    external fun plotTitle(plot: String): String?
    external fun getVector(name: String): NativeVector?
    external fun setWorkingDirectory(path: String): Boolean
}
