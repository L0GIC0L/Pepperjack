package io.github.l0gic0l.pepperjack.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.GZIPInputStream

/** A file in the online library. [path] is relative to the repository root. */
data class OnlineFile(val path: String, val sizeBytes: Long) {
    val name: String get() = path.substringAfterLast('/')

    /** The folder, without the repository's `Models/` and `uncategorized/` levels. */
    val folder: String get() = path.substringBeforeLast('/', "").removePrefix("Models").removePrefix("/").removePrefix("uncategorized/")

    /** Names to store this file under in the library: its own name, or its whole path if another file has that. */
    val localNames: List<String> get() = listOf(name, path.removePrefix("Models/").replace('/', '_')).map(ModelLibrary::fileName)
}

/**
 * A `.model` or `.subckt` the online library defines. [pins] is 0 for `.model` cards.
 * [loads] is false when ngspice reported errors loading it when the index was built.
 */
data class OnlineModel(val name: String, val type: String, val pins: Int, val file: OnlineFile, val loads: Boolean = true) {
    val isSubckt: Boolean get() = type == LibraryModel.SUBCKT
}

/**
 * The names of the models in KiCad-Spice-Library, the largest open collection of
 * manufacturer SPICE models, pinned at one commit. The index holds names only; a file is
 * downloaded from GitHub when the user adds it, so each one comes from the repository
 * under its maker's own terms. `scripts/build-model-index.py` regenerates the index.
 */
class OnlineModelIndex(val repository: String, val commit: String, val files: List<OnlineFile>, val models: List<OnlineModel>) {
    private val upperNames = models.map { it.name.uppercase(Locale.ROOT) }
    private val upperPaths = files.associateWith { it.path.uppercase(Locale.ROOT) }

    val pageUrl: String get() = "https://github.com/$repository"

    fun downloadUrl(file: OnlineFile): String = "https://raw.githubusercontent.com/$repository/$commit/${encode(file.path)}"

    /** The file's page on GitHub, noted in the library copy as its source. */
    fun sourceUrl(file: OnlineFile): String = "$pageUrl/blob/$commit/${encode(file.path)}"

    /** Whether a library file noted as from [source] is a copy of [file], from this or another commit. */
    fun isCopy(source: String?, file: OnlineFile): Boolean =
        source != null && source.startsWith("$pageUrl/blob/") && source.endsWith("/" + encode(file.path))

    private fun encode(path: String) = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /** The library file that is a copy of [file], if it has been added. */
    fun copyOf(file: OnlineFile, have: List<LibraryFile>): LibraryFile? = have.firstOrNull { isCopy(it.source, file) }

    /** The name to store [file] under: an earlier copy's, else the first of its names no other file uses. */
    fun storeAs(file: OnlineFile, have: List<LibraryFile>): String =
        copyOf(file, have)?.name ?: file.localNames.firstOrNull { name -> have.none { it.name == name } } ?: file.localNames.last()

    data class Results(val models: List<OnlineModel>, val total: Int)

    /**
     * Models whose name contains [query], best first: exact names, then names that start
     * with it, then the rest, then models in files whose path contains it (a maker or a
     * family such as "Texas" or "opamp"). Shorter names rank first within each group.
     */
    fun search(query: String, limit: Int = 300, filter: (OnlineModel) -> Boolean = { true }): Results {
        val q = query.trim().uppercase(Locale.ROOT)
        val ranked = ArrayList<Pair<Int, Int>>()
        for (i in models.indices) {
            val model = models[i]
            if (!filter(model)) continue
            val name = upperNames[i]
            val rank = when {
                q.isEmpty() -> 2
                name == q -> 0
                name.startsWith(q) -> 1
                name.contains(q) -> 2
                upperPaths.getValue(model.file).contains(q) -> 3
                else -> continue
            }
            ranked += rank to i
        }
        ranked.sortWith(compareBy({ it.first }, { upperNames[it.second].length }, { upperNames[it.second] }, { models[it.second].file.path }))
        return Results(ranked.take(limit).map { models[it.second] }, ranked.size)
    }

    companion object {
        const val ASSET = "online-models.tsv.gz"

        /** Reads the gzipped, tab-separated index that `scripts/build-model-index.py` writes. */
        fun read(input: InputStream): OnlineModelIndex {
            var repository = ""
            var commit = ""
            val files = ArrayList<OnlineFile>()
            val models = ArrayList<OnlineModel>()
            GZIPInputStream(input).bufferedReader().useLines { lines ->
                for (line in lines) {
                    val f = line.split('\t')
                    when (f[0]) {
                        "S" -> { repository = f[1]; commit = f[2] }
                        "F" -> files += OnlineFile(f[1], f[2].toLong())
                        "M" -> models += OnlineModel(f[1], f[2], f[3].toInt(), files[f[4].toInt()], loads = f.getOrNull(5) != "1")
                    }
                }
            }
            return OnlineModelIndex(repository, commit, files, models)
        }
    }
}

/** The online library: its bundled index, and downloads into the user's [library]. */
class OnlineModels(private val context: Context, private val library: ModelLibrary) {
    private val loaded = lazy { context.assets.open(OnlineModelIndex.ASSET).use(OnlineModelIndex::read) }

    suspend fun index(): OnlineModelIndex = withContext(Dispatchers.IO) { loaded.value }

    /** Downloads [file] into the library and returns the name it is stored under. */
    suspend fun add(index: OnlineModelIndex, file: OnlineFile): String = withContext(Dispatchers.IO) {
        val bytes = download(index.downloadUrl(file))
        val name = index.storeAs(file, library.files.value)
        library.add(name, bytes, source = index.sourceUrl(file))
        name
    }

    private fun download(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub answered ${connection.responseCode} ${connection.responseMessage.orEmpty()}".trim())
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
