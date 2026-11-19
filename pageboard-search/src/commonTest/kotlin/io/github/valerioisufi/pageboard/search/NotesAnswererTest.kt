package io.github.valerioisufi.pageboard.search

import io.github.valerioisufi.pageboard.ml.Accelerator
import io.github.valerioisufi.pageboard.ml.GenerationPart
import io.github.valerioisufi.pageboard.ml.GenerationRequest
import io.github.valerioisufi.pageboard.ml.Generator
import io.github.valerioisufi.pageboard.ml.InstalledModel
import io.github.valerioisufi.pageboard.ml.ModelCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class NotesAnswererTest {
    @Test
    fun answersOnDeviceAndExtractsOrderedUniqueCitations() = runTest {
        SearchIndex(SearchIndex.MEMORY_PATH).use { index ->
            index.replaceChunks(
                documentId = "doc-1",
                sourceId = "doc",
                version = "v1",
                chunks = listOf(
                    SearchChunk(
                        sourceId = "doc",
                        anchor = SearchAnchor.Page("doc-1", "p-3", pageNumber = 3),
                        kind = SearchChunkKind.TEXT,
                        title = "Analisi 1",
                        text = "Il teorema di Weierstrass afferma che ogni funzione continua su un compatto ammette massimo e minimo assoluti."
                    ),
                    SearchChunk(
                        sourceId = "doc",
                        anchor = SearchAnchor.Lecture("doc-1", "lec-1", startMs = 750_000L),
                        kind = SearchChunkKind.LECTURE_TRANSCRIPT,
                        title = "Analisi 1",
                        text = "Se l'intervallo non è chiuso o non è limitato, il teorema di Weierstrass non vale in generale."
                    )
                )
            )

            var capturedRequest: GenerationRequest? = null
            val fakeGenerator = object : Generator {
                override val model: InstalledModel = InstalledModel(ModelCatalog.GEMMA_4_E2B, "/fake")
                override val accelerator: Accelerator = Accelerator.CPU

                override suspend fun generate(
                    request: GenerationRequest,
                    onProgress: (Int) -> Unit
                ): String {
                    capturedRequest = request
                    onProgress(12)
                    return "Per il teorema di Weierstrass [1], una funzione continua su un compatto ammette massimo e minimo [1]; senza compattezza non vale [2]."
                }

                override fun close() {}
            }

            val engine = SearchEngine(index)
            val answerer = NotesAnswerer(engine = engine, generator = fakeGenerator)
            var lastProgress = 0
            val answer = answerer.answerOnDevice(
                question = "Cosa dice il teorema di Weierstrass?",
                modelSpec = ModelCatalog.GEMMA_4_E2B,
                onProgress = { lastProgress = it }
            )

            val promptText = (capturedRequest?.parts?.singleOrNull() as? GenerationPart.Text)?.text.orEmpty()
            assertEquals(12, lastProgress)
            assertEquals("Gemma 4 E2B", answer.modelName)
            assertTrue(promptText.contains("[1] Analisi 1"))
            assertTrue(promptText.contains("[2] Analisi 1"))
            assertEquals(2, answer.citations.size)
            assertEquals(1, answer.citations[0].number)
            assertEquals(2, answer.citations[1].number)
        }
    }

    @Test
    fun answersOnlineThroughBackendAndReturnsEmptyWhenNoPassagesMatch() = runTest {
        SearchIndex(SearchIndex.MEMORY_PATH).use { index ->
            index.replaceChunks(
                documentId = "doc-2",
                sourceId = "doc",
                version = "v1",
                chunks = listOf(
                    SearchChunk(
                        sourceId = "doc",
                        anchor = SearchAnchor.Page("doc-2", "p-1", pageNumber = 1),
                        kind = SearchChunkKind.PDF,
                        title = "Sistemi Operativi",
                        text = "Rate Monotonic assegna priorità fisse inversamente proporzionali al periodo."
                    )
                )
            )
            val engine = SearchEngine(index)
            val answerer = NotesAnswerer(engine)
            val onlineBackend = OnlineAnswerBackend { _, prompt ->
                assertTrue(prompt.contains("[1] Sistemi Operativi (p. 1)"))
                "Rate Monotonic usa priorità fisse inversamente proporzionali al periodo [1]." to "gemini-2.5-flash"
            }

            val answer = answerer.answerOnline("Come funziona Rate Monotonic?", onlineBackend)
            assertEquals("gemini-2.5-flash", answer.modelName)
            assertEquals(1, answer.citations.size)
            assertEquals(1, answer.citations.single().number)

            val empty = answerer.answerOnline("Domanda su cucina molecolare", onlineBackend)
            assertEquals("", empty.text)
            assertTrue(empty.citations.isEmpty())
            assertTrue(empty.passages.isEmpty())
        }
    }

    private val passages = (1..4).map { n ->
        SearchHit(
            anchor = SearchAnchor.Page("doc", "p-$n", pageNumber = n),
            kind = SearchChunkKind.TEXT,
            title = "Calculus I",
            text = "Passage $n",
            snippet = "Passage $n",
            score = 1.0
        )
    }

    private fun cited(answer: String): List<Int> =
        NotesAnswerer.extractCitations(answer, passages).map { it.number }

    @Test
    fun readsSingleCitations() {
        assertEquals(listOf(1), cited("A limit exists [1]."))
        assertEquals(listOf(2, 1), cited("First [2], then [1] and [2] again."))
    }

    @Test
    fun readsListsWithAndWithoutSpaces() {
        assertEquals(listOf(1, 2, 3), cited("See [1] and [2, 3]."))
        assertEquals(listOf(2, 3), cited("See [2,3]."))
        assertEquals(listOf(3, 1, 4), cited("See [ 3 ; 1 ,4 ]."))
    }

    @Test
    fun readsAdjacentBrackets() {
        assertEquals(listOf(1, 2), cited("See [1][2]."))
    }

    @Test
    fun readsRanges() {
        assertEquals(listOf(1, 2, 3), cited("See [1-3]."))
        assertEquals(listOf(2, 3, 4), cited("See [2 – 4]."))
        assertEquals(listOf(4, 1, 2), cited("See [4], [1-2, 4]."))
    }

    @Test
    fun ignoresBracketsWithANumberBeyondThePassages() {
        assertEquals(listOf(1), cited("See [2, 9] and [0] and [7], but [1]."))
        assertEquals(emptyList(), cited("See [3-1000000] and [0, 1]."))
        assertEquals(emptyList(), cited("See [5-8] and [3-1] and [99999999999]."))
    }

    @Test
    fun ignoresBracketsInMathAndCode() {
        assertEquals(listOf(2), cited("On $[0, 1]$ the function is bounded [2]."))
        assertEquals(listOf(3), cited("$$\\int_{[1,2]} f$$ and `a[1]` [3]."))
        assertEquals(listOf(4), cited("```\nval a = b[1]\n```\nAn array [4]."))
    }

    @Test
    fun readsCitationsBetweenDollarsOfPrices() {
        assertEquals(listOf(1, 2), cited("It costs \$5 [1] and \$10 [2]."))
    }

    @Test
    fun ignoresBracketsAfterTheWordInterval() {
        assertEquals(listOf(3), cited("On the interval [1, 2] the function is continuous [3]."))
        assertEquals(listOf(3), cited("Nell'intervallo [1,2] la funzione è continua [3]."))
    }

    @Test
    fun ignoresBracketsThatAreNotCitations() {
        assertEquals(emptyList(), cited("See [a], [1a], [1-], [^1] and [ ]."))
        assertEquals(emptyList(), NotesAnswerer.extractCitations("See [1].", emptyList()))
    }
}
