package io.github.valerioisufi.drawview.search.ui

import io.github.valerioisufi.drawview.search.DocumentSearchSource
import io.github.valerioisufi.drawview.search.SearchEngine
import io.github.valerioisufi.drawview.search.SearchIndex
import io.github.valerioisufi.drawview.search.SearchIndexer
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The search while it is typed (docs/plans/library-search.md): when it runs, and what it leaves on screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class ContentSearchTypingTest {

    private class Library(
        val store: KeyValueDocumentStore,
        val index: SearchIndex,
        val indexer: SearchIndexer,
        val search: ContentSearch
    )

    /** A search following its queries in the background, on the scheduler of the test. */
    private suspend fun TestScope.library(): Library {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)
        val index = SearchIndex(SearchIndex.MEMORY_PATH)
        val indexer = SearchIndexer(catalog = store, index = index, sources = listOf(DocumentSearchSource(store)), quietMillis = 0L)
        val search = ContentSearch(index = index, indexer = indexer, engine = SearchEngine(index))
        search.searchDispatcher = StandardTestDispatcher(testScheduler)
        search.observeQueries(backgroundScope)
        runCurrent()
        return Library(store, index, indexer, search)
    }

    @Test
    fun aSearchWaitsForAPauseInTheTyping() = runTest {
        val library = library()
        val note = library.store.createDocument("Analisi")
        library.indexer.indexNow()
        runCurrent()

        library.search.onQueryChange("ana")
        advanceTimeBy(100)
        runCurrent()
        assertNull(library.search.matchedDocumentIds, "still typing")

        library.search.onQueryChange("anali")
        advanceTimeBy(100)
        runCurrent()
        assertNull(library.search.matchedDocumentIds, "the pause starts again")

        advanceTimeBy(60)
        runCurrent()
        assertEquals(listOf(note.id), library.search.matchedDocumentIds)
        library.index.close()
    }

    @Test
    fun theResultsOfAnotherQueryAreNeverLeft() = runTest {
        val library = library()
        val note = library.store.createDocument("Analisi")
        library.indexer.indexNow()
        library.search.onQueryChange("analisi")
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(note.id), library.search.matchedDocumentIds)

        library.search.onQueryChange("algebra")
        assertNull(library.search.matchedDocumentIds, "at once, before the next results")
        library.index.close()
    }

    @Test
    fun aSearchLooksInItsScope() = runTest {
        val library = library()
        val analisi = library.store.createDocument("Analisi note")
        val algebra = library.store.createDocument("Algebra note")
        library.indexer.indexNow()

        library.search.onQueryChange("note")
        advanceTimeBy(200)
        runCurrent()
        assertEquals(setOf(analisi.id, algebra.id), assertNotNull(library.search.matchedDocumentIds).toSet())

        library.search.onScopeChange(setOf(algebra.id))
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(algebra.id), library.search.matchedDocumentIds)

        library.search.onScopeChange(null)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(2, library.search.matchedDocumentIds?.size)
        library.index.close()
    }

    @Test
    fun theResultsFollowTheIndexAtTheirOwnPace() = runTest {
        val library = library()
        library.store.createDocument("Analisi uno")
        library.indexer.indexNow()
        library.search.onQueryChange("analisi")
        advanceTimeBy(200)
        runCurrent()
        assertEquals(1, library.search.matchedDocumentIds?.size)

        library.store.createDocument("Analisi due")
        library.indexer.indexNow()
        runCurrent()
        assertEquals(2, library.search.matchedDocumentIds?.size, "the first change at once")

        library.store.createDocument("Analisi tre")
        library.indexer.indexNow()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, library.search.matchedDocumentIds?.size, "the next ones wait")

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(3, library.search.matchedDocumentIds?.size)
        library.index.close()
    }

    @Test
    fun aFailureLeavesTheSearchGoing() = runTest {
        val library = library()
        library.store.createDocument("Analisi")
        library.indexer.indexNow()

        // A closed index fails every search: backgroundScope would fail the test with it
        library.index.close()
        library.search.onQueryChange("analisi")
        advanceTimeBy(200)
        runCurrent()
        assertNull(library.search.matchedDocumentIds)
        library.search.onQueryChange("algebra")
        advanceTimeBy(200)
        runCurrent()
        assertNull(library.search.matchedDocumentIds)
    }
}
