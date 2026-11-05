package io.github.l0gic0l.pepperjack.data

import io.github.l0gic0l.pepperjack.model.CustomSymbol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** User-made symbols for library subcircuits, kept in one JSON file. */
class SymbolRepository(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serializer = ListSerializer(CustomSymbol.serializer())
    private val mutex = Mutex()
    private val _symbols = MutableStateFlow<List<CustomSymbol>>(emptyList())
    val symbols: StateFlow<List<CustomSymbol>> = _symbols.asStateFlow()
    private var loaded = false

    suspend fun load() = mutex.withLock {
        if (loaded) return@withLock
        _symbols.value = withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())
        }
        loaded = true
    }

    suspend fun get(id: String): CustomSymbol? {
        load()
        return _symbols.value.firstOrNull { it.id == id }
    }

    suspend fun save(symbol: CustomSymbol) = update { list ->
        if (list.any { it.id == symbol.id }) list.map { if (it.id == symbol.id) symbol else it } else list + symbol
    }

    suspend fun delete(id: String) = update { list -> list.filterNot { it.id == id } }

    private suspend fun update(transform: (List<CustomSymbol>) -> List<CustomSymbol>) {
        load()
        mutex.withLock {
            val next = transform(_symbols.value).sortedBy { it.title.lowercase() }
            _symbols.value = next
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(json.encodeToString(serializer, next))
                temp.renameTo(file)
            }
        }
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}
