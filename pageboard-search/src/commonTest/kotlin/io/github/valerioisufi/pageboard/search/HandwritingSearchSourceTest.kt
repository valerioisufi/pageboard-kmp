package io.github.valerioisufi.pageboard.search

import io.github.valerioisufi.pageboard.handwriting.HandwritingEngines
import io.github.valerioisufi.pageboard.handwriting.HandwritingReading
import io.github.valerioisufi.pageboard.handwriting.InkReading
import io.github.valerioisufi.pageboard.handwriting.InkSample
import io.github.valerioisufi.pageboard.handwriting.TextInkRecognizer
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.change.StrokeData
import io.github.valerioisufi.pageboard.store.InMemoryKeyValueStorage
import io.github.valerioisufi.pageboard.store.KeyValueDocumentStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandwritingSearchSourceTest {
    /** Reads a line as "riga" and its number of strokes, "riga" and "linea" among its candidates. */
    private class Reader(var version0: String = "fake 1") : TextInkRecognizer {
        var reads = 0
        override val version: String get() = version0
        override suspend fun languages() = setOf("it")
        override suspend fun recognize(ink: InkSample, language: String, context: String): InkReading {
            reads++
            return InkReading("riga ${ink.strokes.size}", listOf("linea"), "riga ${ink.strokes.size} linea")
        }
    }

    /** A word of [letters] letters written at ([x], [y]). */
    private fun word(x: Float, y: Float, letters: Int) = (0 until letters).map { i ->
        StrokeData(
            id = "s-$x-$y-$i",
            points = (0..6).map { k -> StrokePoint(x + i * 4f + k * 0.4f, y + if (k % 2 == 0) 0f else 4f, 0.5f, k * 8L) },
            tool = StrokeTool.PRESSURE_PEN,
            size = 0.5f,
            color = 0
        ).toElement()
    }

    @Test
    fun aBlockIsReadOnceAndAgainOnlyWhenItChangesOrItsReaderDoes() = runTest {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)
        val document = store.createDocument("Appunti")
        val page = document.pages.single()
        // Two blocks far from each other, and a highlight that is not read
        val first = word(10f, 20f, 3)
        val second = word(10f, 150f, 5)
        val highlight = StrokeData("h", listOf(StrokePoint(0f, 0f), StrokePoint(50f, 0f)), StrokeTool.HIGHLIGHTER, 5f, 0).toElement()
        (first + second + highlight).forEach { page.add(it) }
        store.saveStrokes(page, first + second + listOf(highlight))
        store.flush()

        val index = SearchIndex(SearchIndex.MEMORY_PATH)
        val reader = Reader()
        var enabled = true
        val source = HandwritingSearchSource(store, index, HandwritingReading(HandwritingEngines(listOf(reader))), language = { "it-IT" }, isEnabled = { enabled })
        val summary = store.documents.first().single()

        val chunks = source.chunks(summary)
        assertEquals(listOf("riga 3", "riga 5"), chunks.map { it.text })
        assertTrue(chunks.all { it.kind == SearchChunkKind.HANDWRITING })
        assertEquals(listOf("riga 3 linea", "riga 5 linea"), chunks.map { it.extraWords })
        assertEquals(2, reader.reads)
        val version = source.version(summary)

        // Read again: from the index
        source.chunks(summary)
        assertEquals(2, reader.reads)

        // A letter added to the second word: only its block is read again
        val more = word(30f, 150f, 1)
        page.add(more.single())
        store.saveStrokes(page, more)
        store.flush()
        val changed = store.documents.first().single()
        assertNotEquals(version, source.version(changed))
        assertEquals(listOf("riga 3", "riga 6"), source.chunks(changed).map { it.text })
        assertEquals(3, reader.reads)

        // Another version of the reader: all read again
        reader.version0 = "fake 2"
        source.chunks(changed)
        assertEquals(5, reader.reads)

        // The other readings found by the words, not shown in the text
        index.replaceChunks(summary.id, source.id, "v", source.chunks(changed))
        assertTrue(index.searchWords("linea").isNotEmpty(), "an other reading is found")
        assertTrue(index.texts(summary.id, setOf(SearchChunkKind.HANDWRITING)).none { "linea" in it }, "and not counted by the vocabulary")

        // Turned off: nothing
        enabled = false
        assertNull(source.version(changed))
        assertEquals(emptyList(), source.chunks(changed))
        index.close()
    }

    @Test
    fun aBlockMissedWhenTheReaderStopsReadingIsReadWhenItComesBack() = runTest {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)
        val document = store.createDocument("Appunti")
        val page = document.pages.single()
        val blocks = word(10f, 20f, 3) + word(10f, 150f, 5)
        blocks.forEach { page.add(it) }
        store.saveStrokes(page, blocks)
        store.flush()
        // Reads the first block, then stops reading (an application leaving the foreground)
        val reader = object : TextInkRecognizer {
            var reading = true
            override val version: String = "fake 1"
            override suspend fun languages() = if (reading) setOf("it") else emptySet()
            override suspend fun recognize(ink: InkSample, language: String, context: String): InkReading? =
                if (reading) InkReading("riga ${ink.strokes.size}").also { reading = false } else null
        }
        val index = SearchIndex(SearchIndex.MEMORY_PATH)
        val source = HandwritingSearchSource(store, index, HandwritingReading(HandwritingEngines(listOf(reader))), language = { "it" })
        val summary = store.documents.first().single()

        assertEquals(listOf("riga 3"), source.chunks(summary).map { it.text })
        assertNull(source.version(summary), "no version while the reader does not read")

        // Back: the block missed is read, not kept empty
        reader.reading = true
        assertEquals(listOf("riga 3", "riga 5"), source.chunks(summary).map { it.text })
        index.close()
    }

    @Test
    fun withoutAReaderOfTheLanguageThereIsNothing() = runTest {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)
        val document = store.createDocument("Notes")
        val page = document.pages.single()
        word(10f, 20f, 3).forEach { page.add(it) }
        store.saveStrokes(page, page.strokes)
        store.flush()
        val index = SearchIndex(SearchIndex.MEMORY_PATH)
        val source = HandwritingSearchSource(store, index, HandwritingReading(HandwritingEngines(listOf(Reader()))), language = { "en" })
        val summary = store.documents.first().single()
        assertNull(source.version(summary))
        assertEquals(emptyList(), source.chunks(summary))
        index.close()
    }

    @Test
    fun theHandwritingOfADocumentRemovedIsForgotten() = runTest {
        val index = SearchIndex(SearchIndex.MEMORY_PATH)
        index.putHandwriting("d", listOf(StoredHandwriting("k", "r", "it", "testo", "testo")), setOf("k"))
        assertEquals(setOf("k"), index.handwriting("d").keys)
        index.putHandwriting("d", emptyList(), emptySet())
        assertTrue(index.handwriting("d").isEmpty(), "a block no longer there")
        index.putHandwriting("d", listOf(StoredHandwriting("k", "r", "it", "testo", "testo")), setOf("k"))
        index.removeDocument("d")
        assertTrue(index.handwriting("d").isEmpty())
        index.close()
    }
}
