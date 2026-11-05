package io.github.valerioisufi.drawview.search

import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/** A search limited to some documents: the best chunks elsewhere do not take their place. */
class SearchScopeTest {

    @Test
    fun theChunksOfOtherDocumentsDoNotFillTheLimit() = runTest {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)
        val elsewhere = List(5) { store.createDocument("Limit $it") }
        val here = store.createDocument("A long name of a note about a limit, among other words")
        SearchIndex(SearchIndex.MEMORY_PATH).use { index ->
            SearchIndexer(catalog = store, index = index, sources = listOf(DocumentSearchSource(store)), quietMillis = 0L).indexNow()
            val engine = SearchEngine(index)

            val best = engine.search("limit", limit = 2).map { it.documentId }
            assertEquals(2, best.size)
            assertEquals(true, best.all { id -> elsewhere.any { it.id == id } }, "the shorter names rank first")

            assertEquals(listOf(here.id), engine.search("limit", limit = 2, documentIds = setOf(here.id)).map { it.documentId })
            assertEquals(listOf(here.id), engine.passages("limit", limit = 2, documentIds = setOf(here.id)).map { it.anchor.documentId })
            assertEquals(emptyList(), engine.search("limit", documentIds = emptySet()))
        }
    }
}
