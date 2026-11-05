package io.github.l0gic0l.pepperjack.ui.common

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import io.github.l0gic0l.pepperjack.AppContainer
import io.github.l0gic0l.pepperjack.model.CircuitKind
import io.github.l0gic0l.pepperjack.model.SpiceDialect
import io.github.l0gic0l.pepperjack.spice.LtspiceSchematic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_IMPORT_BYTES = 8 * 1024 * 1024

suspend fun displayName(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Imported"
}

suspend fun readBytes(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val bytes = input.readNBytesCompat(MAX_IMPORT_BYTES + 1)
        require(bytes.size <= MAX_IMPORT_BYTES) { "File is larger than 8 MB." }
        bytes
    } ?: error("Could not open the file.")
}

suspend fun writeText(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
        ?: error("Could not write the file.")
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private val modelExtensions = setOf("lib", "mod", "sub", "inc", "fam", "mdl")

/** Whether a file opened from another app is a model library rather than a netlist. */
fun isModelFile(name: String) = name.substringAfterLast('.', "").lowercase() in modelExtensions

/**
 * Imports a SPICE netlist, or an LTspice schematic (.asc) as a schematic, as a new circuit
 * and returns its id.
 */
suspend fun importNetlist(context: Context, container: AppContainer, uri: Uri): Result<String> = runCatching {
    val name = displayName(context, uri)
    val bytes = readBytes(context, uri)
    val text = bytes.toString(Charsets.UTF_8).replace("\r\n", "\n")
    if (LtspiceSchematic.looksLike(name, text)) return@runCatching importAsc(context, container, name, bytes)
    require(text.isNotBlank()) { "The file is empty." }
    val ltspice = Regex("""(?i)\b(rser|rpar|cpar)\s*=|^\s*\.(step|backanno)\b""", RegexOption.MULTILINE).containsMatchIn(text)
    val circuit = container.circuits.create(name.substringBeforeLast('.'), CircuitKind.NETLIST, text)
    val stored = if (ltspice) container.circuits.save(circuit.copy(dialect = SpiceDialect.LTSPICE)) else circuit
    stored.id
}

private suspend fun importAsc(context: Context, container: AppContainer, name: String, bytes: ByteArray): String {
    val imported = withContext(Dispatchers.Default) { LtspiceSchematic.parse(LtspiceSchematic.decode(bytes)) }
    require(imported.schematic.parts.isNotEmpty()) { "No parts found in this LTspice schematic." }
    val circuit = container.circuits.create(name.substringBeforeLast('.'), CircuitKind.SCHEMATIC)
    val stored = container.circuits.save(circuit.copy(schematic = imported.schematic, dialect = SpiceDialect.LTSPICE))
    if (imported.warnings.isNotEmpty()) Toast.makeText(context, imported.warnings.joinToString(" "), Toast.LENGTH_LONG).show()
    return stored.id
}
