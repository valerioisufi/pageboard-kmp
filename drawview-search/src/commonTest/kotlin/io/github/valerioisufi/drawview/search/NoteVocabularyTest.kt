package io.github.valerioisufi.drawview.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

/** The words of the notes from the search index (`docs/plans/writing-suggestions.md`). */
class NoteVocabularyTest {

    private fun SearchIndex.put(documentId: String, vararg chunks: Pair<SearchChunkKind, String>) {
        replaceChunks(
            documentId = documentId,
            sourceId = "test",
            version = chunks.joinToString { it.second },
            chunks = chunks.map { (kind, text) ->
                SearchChunk(sourceId = "test", kind = kind, anchor = SearchAnchor.Page(documentId, "page"), title = "Note", text = text)
            }
        )
    }

    @Test
    fun theTextsOfAKindAreReadForADocumentOrForAll() {
        SearchIndex(SearchIndex.MEMORY_PATH).use { index ->
            index.put("a", SearchChunkKind.TEXT to "testo a", SearchChunkKind.PDF to "slide a")
            index.put("b", SearchChunkKind.PDF to "slide b")
            assertEquals(listOf("slide a"), index.texts("a", setOf(SearchChunkKind.PDF)))
            assertEquals(setOf("slide a", "slide b"), index.texts(null, setOf(SearchChunkKind.PDF)).toSet())
            assertEquals(emptyList(), index.texts("a", emptySet()))
        }
    }

    @Test
    fun aDocumentHasTheWordsOfItsSlidesHandwritingAndLecturesNotOfItsTypedTexts() = runTest {
        SearchIndex(SearchIndex.MEMORY_PATH).use { index ->
            index.put(
                "a",
                SearchChunkKind.TITLE to "Corso di grafi",
                SearchChunkKind.TEXT to "testo digitato",
                SearchChunkKind.PDF to "Dijkstra",
                SearchChunkKind.HANDWRITING to "cammino",
                SearchChunkKind.LECTURE_TRANSCRIPT to "spiegazione",
                SearchChunkKind.LECTURE_NOTES to "riassunto"
            )
            val vocabulary = NoteVocabulary(index, backgroundScope, StandardTestDispatcher(testScheduler))
            assertNull(vocabulary.document("a"))
            testScheduler.runCurrent()
            val words = vocabulary.document("a")!!
            for (word in listOf("Dijkstra", "cammino", "spiegazione", "riassunto")) assertEquals(1, words.count(word), word)
            assertEquals(0, words.count("digitato"))
            assertEquals(0, words.count("grafi"))
        }
    }

    @Test
    fun theLibraryHasEveryDocumentButTheirNames() = runTest {
        SearchIndex(SearchIndex.MEMORY_PATH).use { index ->
            index.put("a", SearchChunkKind.TITLE to "Algebra", SearchChunkKind.TEXT to "matrice")
            index.put("b", SearchChunkKind.PDF to "matrice inversa")
            val vocabulary = NoteVocabulary(index, backgroundScope, StandardTestDispatcher(testScheduler))
            vocabulary.library()
            testScheduler.runCurrent()
            val words = vocabulary.library()!!
            assertEquals(2, words.count("matrice"))
            assertEquals(0, words.count("Algebra"))
        }
    }

    @Test
    fun aDocumentIsBuiltAgainWhenTheIndexChangesTheLibraryOnlyAfterItsRefresh() = runTest {
        SearchIndex(SearchIndex.MEMORY_PATH).use { index ->
            index.put("a", SearchChunkKind.PDF to "vecchio")
            val time = TestTimeSource()
            val vocabulary = NoteVocabulary(index, backgroundScope, StandardTestDispatcher(testScheduler), libraryRefresh = 2.minutes, timeSource = time)
            vocabulary.document("a")
            vocabulary.library()
            testScheduler.runCurrent()

            index.put("a", SearchChunkKind.PDF to "nuovo")
            // What was built is answered while it is built again
            assertEquals(1, vocabulary.document("a")!!.count("vecchio"))
            assertEquals(1, vocabulary.library()!!.count("vecchio"))
            testScheduler.runCurrent()
            assertEquals(1, vocabulary.document("a")!!.count("nuovo"))
            assertEquals(0, vocabulary.library()!!.count("nuovo"))

            time += 2.minutes
            vocabulary.library()
            testScheduler.runCurrent()
            assertEquals(1, vocabulary.library()!!.count("nuovo"))
        }
    }

    @Test
    fun onlyTheDocumentsAskedForLastAreKept() = runTest {
        SearchIndex(SearchIndex.MEMORY_PATH).use { index ->
            val vocabulary = NoteVocabulary(index, backgroundScope, StandardTestDispatcher(testScheduler))
            for (i in 0..NoteVocabulary.MAX_DOCUMENTS) {
                index.put("d$i", SearchChunkKind.PDF to "parola$i")
                vocabulary.document("d$i")
            }
            testScheduler.runCurrent()
            // The first one was let go: asked again, it is built again
            assertNull(vocabulary.document("d0"))
            assertEquals(1, vocabulary.document("d${NoteVocabulary.MAX_DOCUMENTS}")!!.count("parola${NoteVocabulary.MAX_DOCUMENTS}"))
        }
    }
}
