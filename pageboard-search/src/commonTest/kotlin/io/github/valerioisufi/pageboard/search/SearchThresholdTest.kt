package io.github.valerioisufi.pageboard.search

import io.github.valerioisufi.pageboard.ml.Accelerator
import io.github.valerioisufi.pageboard.ml.Embedder
import io.github.valerioisufi.pageboard.ml.InstalledModel
import io.github.valerioisufi.pageboard.ml.ModelCatalog
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * The least score of the vectors (phase 6 of docs/plans/library-search.md): a chunk found by its meaning
 * alone passes above it, a chunk holding the words of the query always passes.
 */
class SearchThresholdTest {
    /** Every chunk on the first axis; a query at the cosine written after `#` in its text. */
    private class FixedScoreEmbedder : Embedder {
        override val model: InstalledModel = InstalledModel(ModelCatalog.EMBEDDING_GEMMA_2_TEXT, "/fake/model.litertlm")
        override val accelerator: Accelerator = Accelerator.CPU
        override val dimensions: Int = 2

        override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { text ->
            val score = text.substringAfter('#', "").toFloatOrNull() ?: return@map floatArrayOf(1f, 0f)
            floatArrayOf(score, sqrt(1f - score * score))
        }

        override fun close() {}
    }

    private fun chunk(page: String, text: String) = SearchChunk(
        sourceId = "source",
        anchor = SearchAnchor.Page("document", page),
        kind = SearchChunkKind.TEXT,
        title = "Notes",
        text = text
    )

    private suspend fun found(query: String): List<String> = SearchIndex(SearchIndex.MEMORY_PATH, dimensions = 2).use { index ->
        val chunks = listOf(chunk("one", "Kademlia uses the XOR metric"), chunk("two", "Block ciphers and their modes"))
        index.replaceChunks("document", "source", "1", chunks)
        val vector = QuantizedVector.fromFloatArray(floatArrayOf(1f, 0f), dimensions = 2)
        index.putVectors(chunks.associate { EmbeddingPrompts.hash(it.title, it.text) to vector })
        SearchEngine(index, embedder = FixedScoreEmbedder())
            .search(query, maxHitsPerDocument = 10)
            .flatMap { result -> result.hits.map { it.text } }
    }

    @Test
    fun aChunkFoundByItsMeaningAloneNeedsTheLeastScore() = runTest {
        assertEquals(2, found("paraphrase #0.75").size)
        assertEquals(emptyList(), found("paraphrase #0.60"))
    }

    @Test
    fun aChunkHoldingTheWordsPassesWhateverItsScore() = runTest {
        assertEquals(listOf("Kademlia uses the XOR metric"), found("kademlia #0.10"))
    }

    @Test
    fun aChunkHoldingFewOfTheWordsDoesNotPassByThem() = runTest {
        assertEquals(emptyList(), found("kademlia carbonara guanciale pomodori #0.10"))
    }
}
