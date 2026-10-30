package io.github.l0gic0l.pepperjack.data

import io.github.l0gic0l.pepperjack.spice.LtspiceCompat
import io.github.l0gic0l.pepperjack.spice.SubcktHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** A `.model` or `.subckt` found in a library file. */
data class LibraryModel(
    val name: String,
    val file: String,
    /** The `.model` type (D, NPN, VDMOS, …), or SUBCKT. */
    val type: String,
    /** Subcircuit ports in order; empty for `.model` cards. */
    val pins: List<String> = emptyList(),
) {
    val isSubckt: Boolean get() = type == SUBCKT

    companion object {
        const val SUBCKT = "SUBCKT"
    }
}

/** [source] is the web page a downloaded file came from. */
data class LibraryFile(val name: String, val sizeBytes: Long, val models: List<LibraryModel>, val source: String? = null) {
    val modelNames: List<String> get() = models.map { it.name }
}

/**
 * Vendor model files (.lib, .mod, .sub, .inc, ...). ngspice runs with this folder as
 * its working directory, so a netlist can say `.include TL072.lib` or `.lib opamps.lib`;
 * schematic parts that name a model from here get the `.include` automatically.
 */
class ModelLibrary(val directory: File) {
    private val _files = MutableStateFlow<List<LibraryFile>>(emptyList())
    val files: StateFlow<List<LibraryFile>> = _files.asStateFlow()

    /** Every model in every file, keyed by upper-case name; the first file alphabetically wins. */
    fun index(): Map<String, LibraryModel> = buildMap {
        _files.value.forEach { file -> file.models.forEach { putIfAbsent(it.name.uppercase(Locale.ROOT), it) } }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        directory.mkdirs()
        _files.value = directory.listFiles { file -> file.isFile }.orEmpty()
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .map { file ->
                val text = runCatching { file.readText() }.getOrDefault("")
                val source = text.lineSequence().firstOrNull()?.takeIf { it.startsWith(SOURCE) }?.removePrefix(SOURCE)?.trim()
                LibraryFile(file.name, file.length(), runCatching { parse(file.name, text) }.getOrDefault(emptyList()), source)
            }
    }

    /** Saves a model file as [name], [clean]ed so that ngspice can include it; [source] is noted on its first line. */
    suspend fun add(name: String, bytes: ByteArray, source: String? = null) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val header = source?.let { "$SOURCE $it\n" }.orEmpty()
        File(directory, fileName(name)).writeText(header + clean(decode(bytes)))
        refresh()
    }

    suspend fun read(name: String): String = withContext(Dispatchers.IO) { File(directory, fileName(name)).readText() }

    suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        File(directory, fileName(name)).delete()
        refresh()
    }

    companion object {
        private const val SOURCE = "* Source:"
        private val modelCard = Regex("""(?i)^\s*\.model\s+(\S+)\s+([A-Za-z_]+)""")
        private val directive = Regex("""^\s*\.(\w+)""")

        /** Directives a library may hold outside a subcircuit; any other line there would change the circuit. */
        private val libraryDirectives = setOf("model", "subckt", "ends", "param", "func", "global", "include", "inc")

        /** The name a file is stored under in the library folder. */
        fun fileName(name: String) = name.substringAfterLast('/').replace(Regex("""[^\w.\-+]"""), "_").ifEmpty { "model.lib" }

        /** UTF-8 if the bytes are valid UTF-8, else Latin-1, the usual encoding of older vendor files. */
        fun decode(bytes: ByteArray): String = runCatching {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }.getOrElse { String(bytes, Charsets.ISO_8859_1) }

        /**
         * Makes a vendor file safe to `.include`. Many files carry DOS end-of-file bytes, a
         * byte-order mark, pin diagrams that lost their comment marks, or a test circuit with
         * its analyses and `.end`, and ngspice stops at any of them. Those control characters
         * are dropped and every other line outside a subcircuit that is not a model definition
         * is commented out, so the text, including the maker's license notice, is all kept.
         */
        fun clean(text: String): String {
            var depth = 0
            var continuesComment = false
            return text.replace(Regex("[\u0000\u001a\ufeff]"), "").lineSequence().joinToString("\n") { line ->
                val trimmed = line.trim()
                when {
                    trimmed.isEmpty() || trimmed[0] == '*' -> line
                    trimmed[0] == '+' -> if (depth == 0 && continuesComment) "* $line" else line
                    depth > 0 -> {
                        when (directive.find(trimmed)?.groupValues?.get(1)?.lowercase(Locale.ROOT)) {
                            "subckt" -> depth++
                            "ends" -> depth--
                        }
                        line
                    }
                    else -> {
                        val keyword = directive.find(trimmed)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
                        continuesComment = keyword == "ends" || keyword !in libraryDirectives
                        if (keyword == "subckt") depth = 1
                        if (continuesComment) "* $line" else line
                    }
                }
            }
        }

        /** The `.model` cards and `.subckt`s a file defines; models local to a subcircuit are left out. */
        fun parse(file: String, text: String): List<LibraryModel> {
            val models = mutableListOf<LibraryModel>()
            val subckts = mutableListOf<LibraryModel>()
            var depth = 0
            for (line in LtspiceCompat.joinContinuations(text.lines())) {
                when (directive.find(line)?.groupValues?.get(1)?.lowercase(Locale.ROOT)) {
                    "subckt" -> if (depth++ == 0) {
                        SubcktHeader.parse(line)?.let { subckts += LibraryModel(it.name, file, LibraryModel.SUBCKT, it.pins) }
                    }
                    "ends" -> depth = maxOf(0, depth - 1)
                    "model" -> if (depth == 0) {
                        val card = modelCard.find(line) ?: continue
                        val type = card.groupValues[2].uppercase(Locale.ROOT)
                        // PSpice "AKO" (a kind of) cards copy another model; ngspice has no equivalent.
                        if (type != "AKO") models += LibraryModel(card.groupValues[1], file, type)
                    }
                }
            }
            return (subckts + models).distinctBy { it.name.uppercase(Locale.ROOT) }
        }
    }
}
