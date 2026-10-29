package io.github.l0gic0l.pepperjack.data

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

data class LibraryFile(val name: String, val sizeBytes: Long, val models: List<LibraryModel>) {
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
                val models = runCatching { parse(file.name, file.readText()) }.getOrDefault(emptyList())
                LibraryFile(file.name, file.length(), models)
            }
    }

    suspend fun add(name: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        File(directory, sanitize(name)).writeBytes(bytes)
        refresh()
    }

    suspend fun read(name: String): String = withContext(Dispatchers.IO) { File(directory, sanitize(name)).readText() }

    suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        File(directory, sanitize(name)).delete()
        refresh()
    }

    private fun sanitize(name: String) = name.substringAfterLast('/').replace(Regex("""[^\w.\-+]"""), "_").ifEmpty { "model.lib" }

    companion object {
        private val modelCard = Regex("""(?im)^[ \t]*\.model[ \t]+(\S+)[ \t]+([A-Za-z_]+)""")

        fun parse(file: String, text: String): List<LibraryModel> {
            val models = modelCard.findAll(text).map {
                LibraryModel(it.groupValues[1], file, it.groupValues[2].uppercase(Locale.ROOT))
            }
            val subckts = SubcktHeader.parseAll(text).map { LibraryModel(it.name, file, LibraryModel.SUBCKT, it.pins) }
            return (subckts + models).distinctBy { it.name.uppercase(Locale.ROOT) }.toList()
        }
    }
}
