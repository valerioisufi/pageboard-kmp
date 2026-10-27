package io.github.valerioisufi.drawview.search.ui

import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.search.DocumentSearchSource
import io.github.valerioisufi.drawview.search.SearchAnchor
import io.github.valerioisufi.drawview.search.SearchEngine
import io.github.valerioisufi.drawview.search.SearchIndex
import io.github.valerioisufi.drawview.search.SearchIndexer
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class ContentSearchTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun findsDocumentsByTheirTypedTextAndExposesSnippets() = runTest {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)
        val analisiDoc = store.createDocument("Analisi 1")
        val analisiPage = analisiDoc.pages.single()
        val t1 = DrawText().apply {
            text = "Il teorema di Weierstrass garantisce massimo e minimo assoluti su un compatto."
            x = 10f
            y = 20f
            width = 100f
            height = 20f
        }
        analisiPage.add(t1)
        store.saveText(analisiPage, t1)

        val algebraDoc = store.createDocument("Algebra Lineare")
        val algebraPage = algebraDoc.pages.single()
        val t2 = DrawText().apply {
            text = "Gli autovettori associati ad autovalori distinti sono linearmente indipendenti."
            x = 10f
            y = 20f
            width = 100f
            height = 20f
        }
        algebraPage.add(t2)
        store.saveText(algebraPage, t2)
        store.flush()

        SearchIndex(SearchIndex.MEMORY_PATH).use { index ->
            val source = DocumentSearchSource(store)
            val indexer = SearchIndexer(catalog = store, index = index, sources = listOf(source), quietMillis = 0L)
            val engine = SearchEngine(index)
            var openedAnchor: SearchAnchor? = null
            val contentSearch = ContentSearch(
                index = index,
                indexer = indexer,
                engine = engine,
                onOpenAnchor = { anchor, _ -> openedAnchor = anchor }
            )

            indexer.indexNow()

            assertNull(contentSearch.matchedDocumentIds)

            contentSearch.onQueryChange("Weierstrass")
            contentSearch.searchNow()

            assertEquals(listOf(analisiDoc.id), contentSearch.matchedDocumentIds)
            val matches = contentSearch.matchesOf(analisiDoc.id)
            assertEquals(1, matches.size)
            assertEquals("p. 1", matches.single().location)
            assertTrue("Weierstrass" in matches.single().snippet)

            matches.single().onOpen?.invoke {}
            val pageAnchor = openedAnchor as SearchAnchor.Page
            assertEquals(analisiDoc.id, pageAnchor.documentId)
            assertEquals(analisiPage.id, pageAnchor.pageId)

            contentSearch.onQueryChange("")
            assertNull(contentSearch.matchedDocumentIds)
        }
    }
}
