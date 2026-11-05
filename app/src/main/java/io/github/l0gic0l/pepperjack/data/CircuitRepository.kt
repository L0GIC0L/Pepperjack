package io.github.l0gic0l.pepperjack.data

import io.github.l0gic0l.pepperjack.model.Circuit
import io.github.l0gic0l.pepperjack.model.CircuitKind
import io.github.l0gic0l.pepperjack.model.Schematic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Circuits stored as one JSON file each under [directory]. */
class CircuitRepository(private val directory: File) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = false
    }
    private val mutex = Mutex()
    private val _circuits = MutableStateFlow<List<Circuit>>(emptyList())
    private var loaded = false

    /** All circuits, most recently modified first. */
    val circuits: StateFlow<List<Circuit>> = _circuits.asStateFlow()

    suspend fun refresh() = mutex.withLock {
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            val all = directory.listFiles { file -> file.extension == "json" }.orEmpty().mapNotNull { file ->
                runCatching { json.decodeFromString(Circuit.serializer(), file.readText()) }.getOrNull()
            }
            _circuits.value = all.sortedByDescending { it.modifiedAt }
            loaded = true
        }
    }

    suspend fun get(id: String): Circuit? {
        if (!loaded) refresh()
        return _circuits.value.firstOrNull { it.id == id }
    }

    suspend fun save(circuit: Circuit): Circuit = mutex.withLock {
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            val stored = circuit.copy(
                id = circuit.id.ifEmpty { UUID.randomUUID().toString() },
                modifiedAt = System.currentTimeMillis(),
            )
            val target = File(directory, "${stored.id}.json")
            val temp = File(directory, "${stored.id}.json.tmp")
            temp.writeText(json.encodeToString(Circuit.serializer(), stored))
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
            _circuits.value = (listOf(stored) + _circuits.value.filter { it.id != stored.id })
                .sortedByDescending { it.modifiedAt }
            stored
        }
    }

    suspend fun create(name: String, kind: CircuitKind, netlist: String = ""): Circuit = save(
        Circuit(
            id = "",
            name = name,
            kind = kind,
            schematic = Schematic(),
            netlist = netlist.ifEmpty { if (kind == CircuitKind.NETLIST) BLANK_NETLIST.replace("{name}", name) else "" },
        ),
    )

    suspend fun duplicate(circuit: Circuit): Circuit = save(circuit.copy(id = "", name = "${circuit.name} copy"))

    suspend fun delete(id: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            File(directory, "$id.json").delete()
            _circuits.value = _circuits.value.filter { it.id != id }
        }
    }

    companion object {
        val BLANK_NETLIST = """
            * {name}
            V1 in 0 SIN(0 1 1k)
            R1 in out 1k
            C1 out 0 100n

            .tran 10u 5m
            .end
        """.trimIndent() + "\n"
    }
}
