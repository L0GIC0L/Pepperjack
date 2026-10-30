package io.github.l0gic0l.pepperjack.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class OnlineModelsTest {
    private val index by lazy { File("src/main/assets/${OnlineModelIndex.ASSET}").inputStream().use(OnlineModelIndex::read) }

    @Test
    fun bundledIndexLoads() {
        assertEquals("kicad-spice-library/KiCad-Spice-Library", index.repository)
        assertTrue(index.commit, index.commit.matches(Regex("[0-9a-f]{40}")))
        assertTrue(index.files.size > 1900)
        assertTrue(index.models.size > 60_000)
        val lm358 = index.models.filter { it.name == "LM358" }
        assertTrue(lm358.isNotEmpty() && lm358.all { it.isSubckt && it.pins == 5 })
    }

    @Test
    fun searchRanksExactThenPrefixThenContains() {
        val names = index.search("2N390", limit = 400).models.map { it.name.uppercase() }
        val exact = names.takeWhile { it == "2N390" }
        val prefix = names.drop(exact.size).takeWhile { it.startsWith("2N390") }
        val contains = names.drop(exact.size + prefix.size)
        assertTrue(prefix.isNotEmpty() && "2N3904" in prefix && contains.none { it.startsWith("2N390") })
        assertTrue(contains.all { "2N390" in it })
        val diodes = index.search("1N4", limit = 20) { it.type == "D" }
        assertTrue(diodes.total > 20 && diodes.models.size == 20 && diodes.models.all { it.type == "D" })
        // A maker's folder name finds its models too.
        assertTrue(index.search("Texas Instruments", limit = 5).models.all { "Texas Instruments" in it.file.path })
    }

    @Test
    fun urlsAndLocalNames() {
        val file = OnlineFile("Models/Manufacturer/Texas Instruments/OPA 1.lib", 100)
        val raw = index.downloadUrl(file)
        assertEquals(
            "https://raw.githubusercontent.com/kicad-spice-library/KiCad-Spice-Library/${index.commit}/" +
                "Models/Manufacturer/Texas%20Instruments/OPA%201.lib",
            raw,
        )
        assertEquals("Manufacturer/Texas Instruments", file.folder)
        assertEquals(listOf("OPA_1.lib", "Manufacturer_Texas_Instruments_OPA_1.lib"), file.localNames)

        // A file of the same name from elsewhere is kept; the download takes its longer name.
        val other = LibraryFile("OPA_1.lib", 10, emptyList())
        assertNull(index.copyOf(file, listOf(other)))
        assertEquals("Manufacturer_Texas_Instruments_OPA_1.lib", index.storeAs(file, listOf(other)))
        // A copy, from this or an earlier commit, is found and replaced in place.
        val copy = LibraryFile("OPA_1.lib", 10, emptyList(), source = index.sourceUrl(file).replace(index.commit, "0123abc"))
        assertNotNull(index.copyOf(file, listOf(copy)))
        assertEquals("OPA_1.lib", index.storeAs(file, listOf(copy)))
    }

    /**
     * Not a regular test: with PEPPERJACK_ONLINE_LIBRARY set to a clone of the library and
     * PEPPERJACK_SURVEY_OUT to a folder, writes every indexed file as the app stores it, plus
     * a deck per file that instantiates each of its models, for the ngspice load check that
     * `scripts/build-model-index.py` describes. The decks have no analysis: loading a deck
     * expands every subcircuit and binds every model, which is where incompatibilities show.
     */
    @Test
    fun writeSurveyDecks() {
        val clone = System.getenv("PEPPERJACK_ONLINE_LIBRARY")?.let(::File)
        val out = System.getenv("PEPPERJACK_SURVEY_OUT")?.let(::File)
        assumeTrue(clone != null && out != null)
        out!!.mkdirs()
        val byFile = index.models.groupBy { it.file }
        index.files.forEachIndexed { i, file ->
            val name = "f$i.lib"
            val text = ModelLibrary.clean(ModelLibrary.decode(File(clone, file.path).readBytes()))
            File(out, name).writeText(text)
            val deck = StringBuilder("* ${file.path}\n.include $name\n")
            byFile[file].orEmpty().forEachIndexed { n, model ->
                deck.append(instance(n, model)).append('\n')
            }
            deck.append(".end\n")
            File(out, "f$i.cir").writeText(deck.toString())
        }
    }

    /** One instance of [model], with each pin tied to ground through 1 MΩ and the first driven at 1 V through 1 kΩ. */
    private fun instance(n: Int, model: OnlineModel): String {
        val nodes = when (model.type) {
            "SUBCKT" -> (1..model.pins).map { "n${n}_$it" }
            "D" -> listOf("n${n}_1", "n${n}_2")
            "NPN", "PNP", "NJF", "PJF", "VDMOS" -> listOf("n${n}_1", "n${n}_2", "n${n}_3")
            "NMOS", "PMOS" -> listOf("n${n}_1", "n${n}_2", "n${n}_3", "n${n}_3")
            else -> return "* skipped ${model.type} ${model.name}"
        }
        val letter = when (model.type) {
            "SUBCKT" -> "X"
            "D" -> "D"
            "NPN", "PNP" -> "Q"
            "NJF", "PJF" -> "J"
            else -> "M"
        }
        val ties = nodes.distinct().joinToString("\n") { "R$it $it 0 1Meg" }
        val drive = nodes.firstOrNull()?.let { "\nVd$n d$n 0 1\nRd$n d$n $it 1k" }.orEmpty()
        return "$letter$n ${nodes.joinToString(" ")} ${model.name}\n$ties$drive"
    }
}
