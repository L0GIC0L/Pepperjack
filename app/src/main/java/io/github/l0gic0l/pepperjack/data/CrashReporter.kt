package io.github.l0gic0l.pepperjack.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import io.github.l0gic0l.pepperjack.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the reason for the last crash so the app can show it on the next launch:
 * Kotlin/Java exceptions are written by an uncaught-exception handler, and native
 * crashes (ngspice, the JNI bridge) are read back from Android's tombstone for the
 * process via ApplicationExitInfo.
 */
object CrashReporter {
    private const val FILE = "last-crash.txt"
    private const val PREFS = "crash_reporter"
    private const val SEEN = "last_seen_exit"

    fun install(context: Context) {
        val file = File(context.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                file.writeText(header("crashed in thread ${thread.name}") + "\n" + trace)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** A report for a crash that has not been shown yet, or null. Call off the main thread. */
    fun pendingReport(context: Context): String? {
        val file = File(context.filesDir, FILE)
        if (file.exists()) {
            val text = runCatching { file.readText() }.getOrNull()
            file.delete()
            markExitsSeen(context)
            if (!text.isNullOrBlank()) return text
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong(SEEN, 0L)
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val exit = runCatching { manager.getHistoricalProcessExitReasons(context.packageName, 0, 1) }
            .getOrNull()?.firstOrNull() ?: return null
        prefs.edit { putLong(SEEN, exit.timestamp) }
        if (exit.timestamp <= seen) return null
        return when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> nativeReport(exit)
            ApplicationExitInfo.REASON_CRASH -> header("crashed", exit.timestamp) + "\n" + (exit.description ?: "")
            else -> null
        }
    }

    private fun markExitsSeen(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val latest = runCatching { manager.getHistoricalProcessExitReasons(context.packageName, 0, 1) }
            .getOrNull()?.firstOrNull() ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong(SEEN, latest.timestamp) }
    }

    private fun header(what: String, time: Long = System.currentTimeMillis()): String =
        "Pepperjack ${BuildConfig.VERSION_NAME} ($what, " +
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, Locale.US).format(Date(time)) + ")\n" +
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            Build.SUPPORTED_ABIS.joinToString()

    @RequiresApi(Build.VERSION_CODES.R)
    private fun nativeReport(exit: ApplicationExitInfo): String {
        val header = header("native crash", exit.timestamp)
        val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { exit.traceInputStream?.use { it.readBytes() } }.getOrNull()
        } else {
            null
        }
        val details = bytes?.let { runCatching { Tombstone.format(it) }.getOrNull() }
            ?: exit.description
            ?: "No tombstone was recorded."
        return "$header\n\n$details"
    }
}

/**
 * Formats the parts of an Android tombstone (debuggerd's tombstone.proto) that explain a
 * crash: the signal, abort message, the crashing thread's backtrace and recent log lines.
 */
internal object Tombstone {
    private class Field(val number: Int, val varint: Long, val bytes: ByteArray?)

    private fun parse(data: ByteArray): List<Field> {
        val fields = ArrayList<Field>()
        var pos = 0
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = data[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        while (pos < data.size) {
            val key = varint()
            val number = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> fields += Field(number, varint(), null)
                1 -> { fields += Field(number, readLittleEndian(data, pos, 8), null); pos += 8 }
                2 -> {
                    val length = varint().toInt()
                    fields += Field(number, 0, data.copyOfRange(pos, pos + length))
                    pos += length
                }
                5 -> { fields += Field(number, readLittleEndian(data, pos, 4), null); pos += 4 }
                else -> error("Unsupported wire type")
            }
        }
        return fields
    }

    private fun readLittleEndian(data: ByteArray, at: Int, size: Int): Long {
        var value = 0L
        for (i in size - 1 downTo 0) value = (value shl 8) or (data[at + i].toLong() and 0xFF)
        return value
    }

    private fun List<Field>.varint(n: Int) = firstOrNull { it.number == n }?.varint ?: 0L
    private fun List<Field>.string(n: Int) = firstOrNull { it.number == n }?.bytes?.toString(Charsets.UTF_8) ?: ""
    private fun List<Field>.message(n: Int) = firstOrNull { it.number == n }?.bytes?.let(::parse)
    private fun List<Field>.messages(n: Int) = filter { it.number == n }.mapNotNull { it.bytes?.let(::parse) }

    fun format(bytes: ByteArray): String = buildString {
        val tombstone = parse(bytes)
        val tid = tombstone.varint(6)
        tombstone.message(10)?.let { signal ->
            append("signal ${signal.varint(1)} (${signal.string(2)}), code ${signal.varint(3)} (${signal.string(4)})")
            if (signal.varint(8) != 0L) append(String.format(Locale.ROOT, ", fault addr 0x%x", signal.varint(9)))
            append('\n')
        }
        tombstone.string(14).takeIf { it.isNotBlank() }?.let { append("Abort message: $it\n") }
        tombstone.messages(15).map { it.string(1) }.filter { it.isNotBlank() }.forEach { append("Cause: $it\n") }

        val thread = tombstone.messages(16)
            .firstOrNull { it.varint(1) == tid }
            ?.message(2)
        if (thread != null) {
            append("\nBacktrace (thread ${thread.string(2)}):\n")
            thread.messages(4).take(40).forEachIndexed { i, frame ->
                append(String.format(Locale.ROOT, "  #%02d pc %016x  %s", i, frame.varint(1), frame.string(6)))
                val function = frame.string(4)
                if (function.isNotBlank()) append(" ($function+${frame.varint(5)})")
                append('\n')
            }
        }

        val logs = tombstone.messages(18).flatMap { buffer -> buffer.messages(2) }
        if (logs.isNotEmpty()) {
            append("\nRecent log:\n")
            logs.takeLast(80).forEach { line ->
                val priority = "??VDIWEF".getOrElse(line.varint(4).toInt()) { '?' }
                append("$priority ${line.string(5)}: ${line.string(6).trimEnd()}\n")
            }
        }
    }
}
