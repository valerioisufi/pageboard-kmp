package io.github.valerioisufi.drawview.search.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.render.tile.PdfTileAdapter
import io.github.valerioisufi.drawview.explorer.DocumentOpener
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawPdf
import io.github.valerioisufi.drawview.model.DrawResource
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.geometry.Rect as MmRect
import io.github.valerioisufi.drawview.search.DocumentSearchSource
import io.github.valerioisufi.drawview.search.SearchAnchor
import io.github.valerioisufi.drawview.search.SearchChunkKind
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

            matches.single().onOpen?.invoke(DocumentOpener { _, _ -> })
            val pageAnchor = openedAnchor as SearchAnchor.Page
            assertEquals(analisiDoc.id, pageAnchor.documentId)
            assertEquals(analisiPage.id, pageAnchor.pageId)

            contentSearch.onQueryChange("")
            assertNull(contentSearch.matchedDocumentIds)
        }
    }

    @Test
    fun opensPageAndLectureAnchorsAtTheirExactPlaces() = runTest {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)
        val doc = DrawDocument(id = "doc-1", title = "Analisi 2").apply {
            addPage()
            addPage()
        }
        val targetPage = doc.pages[2]
        val controller = DrawViewController(document = doc, coroutineScope = backgroundScope)

        SearchIndex(SearchIndex.MEMORY_PATH).use { index ->
            val indexer = SearchIndexer(catalog = store, index = index, sources = emptyList(), quietMillis = 0L)
            val engine = SearchEngine(index)
            var openedLecture: Triple<String, String, Long>? = null
            val contentSearch = ContentSearch(
                index = index,
                indexer = indexer,
                engine = engine,
                onOpenLecture = { docId, lectureId, startMs ->
                    openedLecture = Triple(docId, lectureId, startMs)
                }
            )

            var openedDocId: String? = null
            val opener = DocumentOpener { id, then ->
                openedDocId = id
                then(controller)
            }

            contentSearch.openAnchor(
                SearchAnchor.Page(
                    documentId = doc.id,
                    pageId = targetPage.id,
                    pageNumber = 3,
                    bounds = MmRect(left = 20f, top = 150f, right = 180f, bottom = 200f)
                ),
                opener
            )
            assertEquals(doc.id, openedDocId)
            // Lay out the viewport after openAnchor: it should center on page 3 (index 2), not page 1
            controller.viewport.setSize(1000f, 800f)
            assertEquals(2, controller.viewport.mostVisiblePageIndex())

            contentSearch.openAnchor(
                SearchAnchor.Lecture(
                    documentId = doc.id,
                    lectureId = "lec-42",
                    startMs = 125_000L
                ),
                opener
            )
            assertEquals(Triple(doc.id, "lec-42", 125_000L), openedLecture)
        }
    }

    @Test
    fun pdfSearchSourceIndexesPdfPagesThroughPdfPort() = runTest {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)
        val doc = store.createDocument("Sistemi Operativi")
        val resource = DrawResource("res-pdf-1", DrawResource.ResourceType.PDF, "/fake/slides.pdf", "hash1")
        doc.addResource(resource)
        store.saveResource(doc, resource)

        val page1 = doc.pages.single()
        val pdf1 = DrawPdf(0).apply { resourceId = resource.id }
        page1.add(pdf1)
        store.savePdf(page1, pdf1)

        val page2 = doc.addPage()
        store.savePage(doc, page2)
        val pdf2 = DrawPdf(1).apply { resourceId = resource.id }
        page2.add(pdf2)
        store.savePdf(page2, pdf2)
        store.flush()

        val fakeAdapter = object : PdfTileAdapter {
            override fun renderPdfPage(canvas: Canvas, resourcePath: String, pdfPageIndex: Int, pageRect: Rect, clipRect: Rect) {}
            override fun getPageSizesMm(resourcePath: String): List<Pair<Float, Float>> = listOf(210f to 297f, 210f to 297f)
            override fun getPageText(resourcePath: String, pdfPageIndex: Int): String? = when (pdfPageIndex) {
                0 -> "Earliest Deadline First (EDF) schedules tasks by dynamic priority."
                1 -> "Rate Monotonic assigns fixed priorities inversely proportional to the period."
                else -> null
            }
        }

        val summary = store.documents.value.single()
        val pdfSource = PdfSearchSource(store) { fakeAdapter }
        val chunks = pdfSource.chunks(summary)
        assertEquals(2, chunks.size)
        assertEquals(SearchChunkKind.PDF, chunks[0].kind)
        assertEquals(1, (chunks[0].anchor as SearchAnchor.Page).pageNumber)
        assertEquals(2, (chunks[1].anchor as SearchAnchor.Page).pageNumber)

        val absentSource = PdfSearchSource(store, pdfTiles = null)
        assertNull(absentSource.version(summary))
        assertTrue(absentSource.chunks(summary).isEmpty())
    }
}
