package io.github.valerioisufi.drawview.search

import io.github.valerioisufi.drawview.ml.Accelerator
import io.github.valerioisufi.drawview.ml.GenerationPart
import io.github.valerioisufi.drawview.ml.GenerationRequest
import io.github.valerioisufi.drawview.ml.Generator
import io.github.valerioisufi.drawview.ml.InstalledModel
import io.github.valerioisufi.drawview.ml.ModelCatalog
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
}
