package io.github.valerioisufi.pageboard.search

import io.github.valerioisufi.pageboard.ml.Embedder
import io.github.valerioisufi.pageboard.ml.EmbedderOptions
import io.github.valerioisufi.pageboard.ml.InstalledModels
import io.github.valerioisufi.pageboard.ml.LoadedModels
import io.github.valerioisufi.pageboard.ml.ModelCatalog
import io.github.valerioisufi.pageboard.ml.ModelSpec
import kotlinx.coroutines.CancellationException

/**
 * A matching piece of a document returned by [SearchEngine], with its [anchor], its [kind], its
 * [snippet] and its [score].
 */
public data class SearchHit(
    public val anchor: SearchAnchor,
    public val kind: SearchChunkKind,
    public val title: String,
    public val text: String,
    public val snippet: String,
    public val score: Double
)

/**
 * The hits of a single document grouped and ranked by [SearchEngine].
 */
public data class DocumentSearchResult(
    public val documentId: String,
    public val title: String,
    public val hits: List<SearchHit>,
    public val score: Double
)

/**
 * Searches [index] by words (FTS5 BM25) and, when the embedding model is installed, by meaning
 * (EmbeddingGemma 2 vectors), merging and grouping the results by place and document (Controller,
 * Information Expert).
 */
public class SearchEngine(
    private val index: SearchIndex,
    private val loadedModels: LoadedModels? = null,
    private val installedModels: InstalledModels? = null,
    private val embeddingSpec: ModelSpec = ModelCatalog.EMBEDDING_GEMMA_2_TEXT,
    private val embedderOptions: EmbedderOptions = EmbedderOptions(),
    private val embedder: Embedder? = null
) {
    /**
     * Searches for [query] across all indexed documents (only [documentIds], when given), grouping
     * hits by document and place.
     */
    public suspend fun search(
        query: String,
        limit: Int = SearchIndex.DEFAULT_SEARCH_LIMIT,
        maxHitsPerDocument: Int = DEFAULT_MAX_HITS_PER_DOCUMENT,
        documentIds: Set<String>? = null
    ): List<DocumentSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || limit <= 0) return emptyList()

        val ranked = rankChunks(
            rawQuery = trimmed,
            formattedEmbeddingQuery = EmbeddingPrompts.query(trimmed),
            limit = limit,
            documentIds = documentIds,
            leastVectorScore = LEAST_VECTOR_SCORE
        )
        if (ranked.isEmpty()) return emptyList()

        val byDocument = LinkedHashMap<String, MutableList<ScoredChunk>>()
        for (scored in ranked) {
            byDocument.getOrPut(scored.indexed.chunk.anchor.documentId) { ArrayList() }.add(scored)
        }

        return byDocument.map { (documentId, docChunks) ->
            val distinctPlaces = deduplicateByPlace(docChunks)
            val hits = distinctPlaces.take(maxHitsPerDocument.coerceAtLeast(1)).map { scored ->
                toSearchHit(scored, trimmed)
            }
            val title = docChunks.first().indexed.chunk.title
            val bestScore = hits.maxOfOrNull { it.score } ?: 0.0
            DocumentSearchResult(
                documentId = documentId,
                title = title,
                hits = hits,
                score = bestScore
            )
        }.sortedByDescending { it.score }
    }

    /**
     * Retrieves the top [limit] passages most relevant to [question] for "Ask your notes"
     * (`NotesAnswerer`), using the question-answering embedding prompt when the embedding model is
     * available; only of [documentIds], when given.
     */
    public suspend fun passages(
        question: String,
        limit: Int = DEFAULT_PASSAGE_LIMIT,
        documentIds: Set<String>? = null
    ): List<SearchHit> {
        val trimmed = question.trim()
        if (trimmed.isEmpty() || limit <= 0) return emptyList()
        val ranked = rankChunks(
            rawQuery = trimmed,
            formattedEmbeddingQuery = EmbeddingPrompts.question(trimmed),
            limit = SearchIndex.DEFAULT_SEARCH_LIMIT,
            documentIds = documentIds
        )
        return deduplicateByPlace(ranked)
            .filter { it.indexed.chunk.kind != SearchChunkKind.TITLE }
            .ifEmpty { deduplicateByPlace(ranked) }
            .take(limit)
            .map { toSearchHit(it, trimmed) }
    }

    /**
     * The pages of document [documentId] about [text] (its typed text, its handwriting, its PDF
     * pages; not its lectures), the closest first, at most [limit]: by meaning when the embedding
     * model is installed, by words otherwise.
     */
    public suspend fun pagesAbout(documentId: String, text: String, limit: Int = DEFAULT_TOPIC_PAGES): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || limit <= 0) return emptyList()
        val ranked = rankChunks(rawQuery = trimmed, formattedEmbeddingQuery = EmbeddingPrompts.query(trimmed), limit = SearchIndex.DEFAULT_SEARCH_LIMIT, documentIds = setOf(documentId))
        return ranked.asSequence()
            .filter { it.indexed.chunk.kind != SearchChunkKind.LECTURE_TRANSCRIPT && it.indexed.chunk.kind != SearchChunkKind.LECTURE_NOTES }
            .mapNotNull { (it.indexed.chunk.anchor as? SearchAnchor.Page)?.takeIf { page -> page.documentId == documentId }?.pageId }
            .distinct()
            .take(limit)
            .toList()
    }

    private suspend fun rankChunks(
        rawQuery: String,
        formattedEmbeddingQuery: String,
        limit: Int,
        documentIds: Set<String>?,
        leastVectorScore: Double? = null
    ): List<ScoredChunk> {
        val foundByWords = index.searchWords(rawQuery, limit, documentIds)
        val queryVector = embedSingle(formattedEmbeddingQuery)
        val foundByVectors = if (queryVector != null) {
            index.searchVectors(queryVector, limit, documentIds)
        } else {
            emptyList()
        }

        if (foundByVectors.isEmpty()) {
            return foundByWords
        }
        // Vectors always have a closest chunk, however unrelated; the words of the query still pass
        val queryWords = searchWordsOf(rawQuery).filter { it.length > 1 }.distinct()
        fun holdsTheWords(scored: ScoredChunk) = wordCoverage(scored.indexed.chunk, queryWords) >= MIN_BOOST_COVERAGE
        val vectorResults = if (leastVectorScore == null) foundByVectors else foundByVectors.filter { it.score >= leastVectorScore || holdsTheWords(it) }
        val wordResults = if (leastVectorScore == null) foundByWords else foundByWords.filter(::holdsTheWords)

        if (vectorResults.isEmpty()) {
            return wordResults
        }
        if (wordResults.isEmpty()) {
            return vectorResults
        }
        return mergeVectorsAndWords(rawQuery, vectorResults, wordResults, limit)
    }

    /**
     * Merges [vectorResults] and [wordResults] following the findings of phase 0 (`docs/plans/search.md`):
     * vectors drive the ranking so paraphrases are preserved, while chunks that match most or all
     * of the query's content words receive a calibrated lexical boost so exact technical terms and
     * algorithm names are never missed.
     */
    internal fun mergeVectorsAndWords(
        rawQuery: String,
        vectorResults: List<ScoredChunk>,
        wordResults: List<ScoredChunk>,
        limit: Int
    ): List<ScoredChunk> {
        val queryWords = searchWordsOf(rawQuery).filter { it.length > 1 }.distinct()
        val maxWordScore = wordResults.maxOfOrNull { it.score }?.takeIf { it > 0.0 } ?: 1.0
        val wordById = wordResults.associateBy { it.indexed.id }
        val wordRankById = HashMap<Long, Int>(wordResults.size)
        wordResults.forEachIndexed { idx, item -> wordRankById[item.indexed.id] = idx }

        val merged = LinkedHashMap<Long, ScoredChunk>()

        for (vecScored in vectorResults) {
            val id = vecScored.indexed.id
            val wordMatch = wordById[id]
            val bonus = if (wordMatch != null) {
                lexicalBoost(
                    chunk = vecScored.indexed.chunk,
                    queryWords = queryWords,
                    wordScore = wordMatch.score,
                    maxWordScore = maxWordScore,
                    wordRank = wordRankById[id] ?: Int.MAX_VALUE
                )
            } else {
                0.0
            }
            merged[id] = ScoredChunk(vecScored.indexed, vecScored.score + bonus)
        }

        // Include strong lexical matches that were outside the top vector candidates
        val minVectorScore = vectorResults.minOfOrNull { it.score } ?: 0.0
        for ((rank, wordScored) in wordResults.withIndex()) {
            val id = wordScored.indexed.id
            if (id in merged) continue
            val coverage = wordCoverage(wordScored.indexed.chunk, queryWords)
            if (coverage >= HIGH_WORD_COVERAGE || rank < 3) {
                val boost = lexicalBoost(
                    chunk = wordScored.indexed.chunk,
                    queryWords = queryWords,
                    wordScore = wordScored.score,
                    maxWordScore = maxWordScore,
                    wordRank = rank
                )
                merged[id] = ScoredChunk(wordScored.indexed, minVectorScore + boost)
            }
        }

        return merged.values
            .sortedWith(compareByDescending<ScoredChunk> { it.score }.thenBy { it.indexed.id })
            .take(limit)
    }

    private fun lexicalBoost(
        chunk: SearchChunk,
        queryWords: List<String>,
        wordScore: Double,
        maxWordScore: Double,
        wordRank: Int
    ): Double {
        val coverage = wordCoverage(chunk, queryWords)
        if (coverage < MIN_BOOST_COVERAGE && wordRank > 0) return 0.0
        val normalizedBm25 = (wordScore / maxWordScore).coerceIn(0.0, 1.0)
        val rankBonus = if (wordRank == 0 && coverage >= HIGH_WORD_COVERAGE) TOP_LEXICAL_RANK_BONUS else 0.0
        return coverage * LEXICAL_COVERAGE_WEIGHT + normalizedBm25 * LEXICAL_BM25_WEIGHT + rankBonus
    }

    private fun wordCoverage(chunk: SearchChunk, queryWords: List<String>): Double {
        if (queryWords.isEmpty()) return 0.0
        val chunkWords = searchWordsOf("${chunk.title} ${chunk.text}").toSet()
        val matched = queryWords.count { qWord ->
            qWord in chunkWords || chunkWords.any { it.startsWith(qWord) }
        }
        return matched.toDouble() / queryWords.size.toDouble()
    }

    private fun deduplicateByPlace(chunks: List<ScoredChunk>): List<ScoredChunk> {
        val byPlace = LinkedHashMap<PlaceKey, ScoredChunk>()
        for (scored in chunks) {
            val key = placeKeyOf(scored.indexed.chunk)
            val existing = byPlace[key]
            if (existing == null || scored.score > existing.score) {
                byPlace[key] = scored
            }
        }
        return byPlace.values.sortedByDescending { it.score }
    }

    private fun placeKeyOf(chunk: SearchChunk): PlaceKey = when (val anchor = chunk.anchor) {
        is SearchAnchor.Document -> PlaceKey(
            documentId = anchor.documentId,
            kind = chunk.kind,
            pageOrLectureId = "",
            bucket = 0L
        )
        is SearchAnchor.Page -> PlaceKey(
            documentId = anchor.documentId,
            kind = chunk.kind,
            pageIdOrNumber = anchor.pageId.ifEmpty { anchor.pageNumber.toString() },
            boundsKey = anchor.bounds?.let {
                "${(it.left / 10f).toInt()}:${(it.top / 10f).toInt()}"
            }.orEmpty()
        )
        is SearchAnchor.Lecture -> PlaceKey(
            documentId = anchor.documentId,
            kind = chunk.kind,
            pageOrLectureId = anchor.lectureId,
            bucket = anchor.startMs / 60_000L
        )
    }

    private fun toSearchHit(scored: ScoredChunk, query: String): SearchHit {
        val chunk = scored.indexed.chunk
        return SearchHit(
            anchor = chunk.anchor,
            kind = chunk.kind,
            title = chunk.title,
            text = chunk.text,
            snippet = snippetOf(chunk.text, query),
            score = scored.score
        )
    }

    private suspend fun embedSingle(formattedText: String): FloatArray? {
        try {
            embedder?.let { active ->
                return active.embed(listOf(formattedText)).firstOrNull()
            }
            val installed = installedModels?.get(embeddingSpec) ?: return null
            val loader = loadedModels ?: return null
            return loader.withEmbedder(installed, embedderOptions, keepLoadedMillis = KEEP_EMBEDDER_LOADED_MS) { active ->
                active.embed(listOf(formattedText)).firstOrNull()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
    }

    private data class PlaceKey(
        val documentId: String,
        val kind: SearchChunkKind,
        val pageOrLectureId: String = "",
        val pageIdOrNumber: String = "",
        val boundsKey: String = "",
        val bucket: Long = 0L
    )

    public companion object {
        public const val DEFAULT_MAX_HITS_PER_DOCUMENT: Int = 3
        public const val DEFAULT_PASSAGE_LIMIT: Int = 10
        public const val DEFAULT_TOPIC_PAGES: Int = 3

        /**
         * The least cosine similarity of a chunk found by its meaning alone, measured for
         * EmbeddingGemma 2 at 512 dimensions in phase 6 of `docs/plans/library-search.md`.
         */
        private const val LEAST_VECTOR_SCORE = 0.68

        private const val MIN_BOOST_COVERAGE = 0.5
        private const val HIGH_WORD_COVERAGE = 0.75
        private const val LEXICAL_COVERAGE_WEIGHT = 0.05
        private const val LEXICAL_BM25_WEIGHT = 0.03
        private const val TOP_LEXICAL_RANK_BONUS = 0.04
        private const val KEEP_EMBEDDER_LOADED_MS = 60_000L
    }
}
