package io.github.valerioisufi.pageboard.search.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import io.github.valerioisufi.pageboard.canvas.controller.DrawViewController
import io.github.valerioisufi.pageboard.canvas.render.tile.PdfTileAdapter
import io.github.valerioisufi.pageboard.explorer.DocumentOpener
import io.github.valerioisufi.pageboard.model.DrawDocument
import io.github.valerioisufi.pageboard.model.DrawPdf
import io.github.valerioisufi.pageboard.model.DrawResource
import io.github.valerioisufi.pageboard.model.DrawText
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.change.StrokeData
import io.github.valerioisufi.pageboard.model.geometry.Rect as MmRect
import io.github.valerioisufi.pageboard.search.DocumentSearchSource
import io.github.valerioisufi.pageboard.search.OnlineAnswerBackend
import io.github.valerioisufi.pageboard.search.SearchAnchor
import io.github.valerioisufi.pageboard.search.SearchChunkKind
import io.github.valerioisufi.pageboard.search.SearchEngine
import io.github.valerioisufi.pageboard.search.SearchIndex
import io.github.valerioisufi.pageboard.search.SearchIndexer
import io.github.valerioisufi.pageboard.store.InMemoryKeyValueStorage
import io.github.valerioisufi.pageboard.store.KeyValueDocumentStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

            matches.single().onOpen?.invoke(DocumentOpener { _, _ -> })
            val pageAnchor = openedAnchor as SearchAnchor.Page
            assertEquals(analisiDoc.id, pageAnchor.documentId)
            assertEquals(analisiPage.id, pageAnchor.pageId)

            contentSearch.onQueryChange("")
            assertNull(contentSearch.matchedDocumentIds)
        }
    }

    @Test
    fun showsTheFormulasOfATextInItsSnippetAsPlainMath() = runTest {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)
        val doc = store.createDocument("Limits")
        val page = doc.pages.single()
        val filler = "The limit of the sine divided by the angle, as the angle goes to zero, is found by squeezing it between two functions. "
        val text = DrawText().apply {
            this.text = filler + "**Squeeze:** \$\\cos x \\le \\frac{\\sin x}{x} \\le 1\$ near zero."
            x = 10f
            y = 20f
            width = 100f
            height = 20f
        }
        page.add(text)
        store.saveText(page, text)
        store.flush()

        SearchIndex(SearchIndex.MEMORY_PATH).use { index ->
            val indexer = SearchIndexer(catalog = store, index = index, sources = listOf(DocumentSearchSource(store)), quietMillis = 0L)
            val engine = SearchEngine(index)
            val contentSearch = ContentSearch(index = index, indexer = indexer, engine = engine)
            indexer.indexNow()

            contentSearch.onQueryChange("squeeze")
            contentSearch.searchNow()

            val snippet = contentSearch.matchesOf(doc.id).single().snippet
            assertTrue("Squeeze: cos x ≤ (sin x)/x ≤ 1" in snippet, snippet)
            assertTrue(snippet.startsWith("…"), snippet)
            for (source in listOf("$", "\\", "**")) assertTrue(source !in snippet, snippet)
            // What the agent and the answers read keep the LaTeX
            val hit = engine.search("squeeze").single().hits.single()
            assertTrue("\\frac{\\sin x}{x}" in hit.snippet, hit.snippet)
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

        // A stroke leaves the text of the PDF as it is; a PDF page added changes it
        val version = assertNotNull(pdfSource.version(summary))
        val stroke = StrokeData(
            id = "stroke-1",
            points = listOf(StrokePoint(10f, 10f, 0.5f, 1L), StrokePoint(20f, 12f, 0.5f, 9L)),
            tool = StrokeTool.PRESSURE_PEN,
            size = 0.5f,
            color = 0xFF000000.toInt()
        ).toElement()
        page2.add(stroke)
        store.saveStrokes(page2, listOf(stroke))
        store.flush()
        assertEquals(version, pdfSource.version(store.documents.value.single()), "a stroke does not change the PDF pages")
        val page3 = doc.addPage()
        store.savePage(doc, page3)
        val pdf3 = DrawPdf(1).apply { resourceId = resource.id }
        page3.add(pdf3)
        store.savePdf(page3, pdf3)
        store.flush()
        assertTrue(version != pdfSource.version(store.documents.value.single()), "a PDF page added changes them")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun askOnlineSynthesizesAnswerAndClearsOnNewQuery() = runTest {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)
        val doc = store.createDocument("Analisi 1")
        val page = doc.pages.single()
        val text = DrawText().apply {
            this.text = "Il teorema di Weierstrass richiede un dominio compatto."
            x = 10f
            y = 10f
            width = 100f
            height = 20f
        }
        page.add(text)
        store.saveText(page, text)
        store.flush()

        SearchIndex(SearchIndex.MEMORY_PATH).use { index ->
            val source = DocumentSearchSource(store)
            val indexer = SearchIndexer(catalog = store, index = index, sources = listOf(source), quietMillis = 0L)
            val engine = SearchEngine(index)
            val backend = OnlineAnswerBackend(
                isAvailable = { true },
                generate = { _, _ -> "Richiede un dominio compatto [1]." to "Gemini" }
            )
            val contentSearch = ContentSearch(
                index = index,
                indexer = indexer,
                engine = engine,
                onlineBackend = backend
            )
            indexer.indexNow()

            contentSearch.onQueryChange("Weierstrass")
            contentSearch.searchNow()
            contentSearch.askOnlineNow("Weierstrass")

            val answer = assertNotNull(contentSearch.currentAnswer)
            assertEquals("Richiede un dominio compatto [1].", answer.text)
            assertEquals(1, answer.citations.size)
            assertEquals("Gemini", answer.modelName)

            contentSearch.onQueryChange("Altro argomento")
            assertNull(contentSearch.currentAnswer)
        }
    }
}

class AnswerSourcesTest {
    private val hits = (1..3).map { n ->
        io.github.valerioisufi.pageboard.search.SearchHit(
            anchor = io.github.valerioisufi.pageboard.search.SearchAnchor.Page("doc", "p-$n", pageNumber = n),
            kind = io.github.valerioisufi.pageboard.search.SearchChunkKind.TEXT,
            title = "Notes",
            text = "Passage $n",
            snippet = "Passage $n",
            score = 1.0
        )
    }

    private fun answer(citations: List<Int>) = io.github.valerioisufi.pageboard.search.NotesAnswer(
        question = "q",
        text = "a",
        citations = citations.map { io.github.valerioisufi.pageboard.search.CitedPassage(it, hits[it - 1]) },
        passages = hits,
        modelName = "m"
    )

    @Test
    fun anAnswerThatCitesListsOnlyTheCitedPassages() {
        val sources = AnswerSources.of(answer(listOf(3, 1)))
        kotlin.test.assertTrue(sources.cited)
        kotlin.test.assertEquals(listOf(3, 1), sources.passages.map { it.number })
    }

    @Test
    fun anAnswerThatCitesNothingListsThePassagesSearched() {
        val sources = AnswerSources.of(answer(emptyList()))
        kotlin.test.assertFalse(sources.cited)
        kotlin.test.assertEquals(listOf(1, 2, 3), sources.passages.map { it.number })
    }
}
