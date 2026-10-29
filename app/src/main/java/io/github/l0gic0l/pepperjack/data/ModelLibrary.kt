package io.github.l0gic0l.pepperjack.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

data class LibraryFile(val name: String, val sizeBytes: Long, val modelNames: List<String>)

/**
 * Vendor model files (.lib, .mod, .sub, .inc, ...). ngspice runs with this folder as
 * its working directory, so a netlist can say `.include TL072.lib` or `.lib opamps.lib`.
 */
class ModelLibrary(val directory: File) {
    private val _files = MutableStateFlow<List<LibraryFile>>(emptyList())
    val files: StateFlow<List<LibraryFile>> = _files.asStateFlow()

    private val definition = Regex("""(?im)^\s*\.(?:model|subckt)\s+(\S+)""")

    suspend fun refresh() = withContext(Dispatchers.IO) {
        directory.mkdirs()
        _files.value = directory.listFiles { file -> file.isFile }.orEmpty()
            .sortedBy { it.name.lowercase() }
            .map { file ->
                val names = runCatching { definition.findAll(file.readText()).map { it.groupValues[1] }.distinct().toList() }
                    .getOrDefault(emptyList())
                LibraryFile(file.name, file.length(), names)
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
}
