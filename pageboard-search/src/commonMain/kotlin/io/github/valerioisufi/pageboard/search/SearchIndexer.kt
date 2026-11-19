package io.github.valerioisufi.pageboard.search

import io.github.valerioisufi.pageboard.ml.Embedder
import io.github.valerioisufi.pageboard.ml.EmbedderOptions
import io.github.valerioisufi.pageboard.ml.InstalledModels
import io.github.valerioisufi.pageboard.ml.LoadedModels
import io.github.valerioisufi.pageboard.ml.ModelCatalog
import io.github.valerioisufi.pageboard.ml.ModelSpec
import io.github.valerioisufi.pageboard.store.DocumentCatalog
import io.github.valerioisufi.pageboard.store.DocumentSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Current state of [SearchIndexer].
 *
 * @param isIndexing whether the indexer is currently chunking documents or computing embeddings.
 * @param indexedDocuments number of documents in the library whose chunks are up to date.
 * @param totalDocuments total number of documents in the library.
 * @param unembeddedChunks number of distinct chunks still waiting for their embedding vector.
 */
public data class IndexingProgress(
    public val isIndexing: Boolean = false,
    public val indexedDocuments: Int = 0,
    public val totalDocuments: Int = 0,
    public val unembeddedChunks: Int = 0
)

/**
 * Watches [catalog] and [sources], keeps [index] up to date incrementally, and computes missing
 * chunk embeddings when [conditions] allow it and an embedding model is available (Controller).
 *
 * * Deleted documents are removed from the index immediately.
 * * New documents are chunked immediately; edits to already-indexed documents wait [quietMillis]
 *   (30 seconds by default) of quiet before re-chunking.
 * * Only chunks whose [EmbeddingPrompts.hash] is not yet in `vectors` are embedded.
 */
public class SearchIndexer(
    private val catalog: DocumentCatalog,
    private val index: SearchIndex,
    private val sources: List<SearchSource>,
    private val conditions: IndexingConditions = IndexingConditions.Always,
    private val loadedModels: LoadedModels? = null,
    private val installedModels: InstalledModels? = null,
    private val embeddingSpec: ModelSpec = ModelCatalog.EMBEDDING_GEMMA_2_TEXT,
    private val embedderOptions: EmbedderOptions = EmbedderOptions(),
    private val embedder: Embedder? = null,
    public val quietMillis: Long = DEFAULT_QUIET_MILLIS
) {
    private val mutex = Mutex()
    private val progressState = MutableStateFlow(IndexingProgress())

    /** Current progress of the indexer. */
    public val progress: StateFlow<IndexingProgress> = progressState.asStateFlow()

    /**
     * Starts following [catalog], [sources], [conditions] and [installedModels] in [scope].
     * Returns the running [Job].
     */
    public fun start(scope: CoroutineScope): Job = scope.launch {
        var initialPass = true
        val installedChanges: Flow<Unit> = installedModels?.installed?.map {} ?: emptyFlow()
        val conditionChanges: Flow<Unit> = conditions.canIndex.map {}
        val triggers: Flow< Boolean> = merge(
            catalog.documents.map { true },
            *sources.map { source -> source.changes.map { true } }.toTypedArray(),
            installedChanges.map { false },
            conditionChanges.map { false }
        )

        triggers.collectLatest { isContentChange ->
            val docs = catalog.documents.first()
            removeDeletedDocuments(docs)
            if (!initialPass && isContentChange && quietMillis > 0L && !hasUnindexedDocument(docs)) {
                delay(quietMillis)
            }
            initialPass = false
            indexNow()
        }
    }

    /**
     * Runs one full incremental pass immediately: removes deleted documents, re-chunks documents
     * whose version changed in any of [sources], embeds missing chunks if an embedding model is
     * available and [conditions] allow it, and completes any pending index rebuild.
     */
    public suspend fun indexNow() {
        mutex.withLock {
            val documents = catalog.documents.first()
            removeDeletedDocuments(documents)
            val total = documents.size
            progressState.value = progressState.value.copy(
                isIndexing = true,
                totalDocuments = total,
                unembeddedChunks = index.unembeddedCount()
            )
            try {
                val versionsBySource = sources.associate { source ->
                    source.id to index.documentVersions(source.id)
                }
                var completedDocs = 0
                for (document in documents) {
                    for (source in sources) {
                        val currentVersion = source.version(document)
                        val indexedVersion = versionsBySource[source.id]?.get(document.id)
                        if (currentVersion == null) {
                            if (indexedVersion != null) {
                                index.removeSource(document.id, source.id)
                            }
                        } else if (currentVersion != indexedVersion) {
                            conditions.canIndex.first { it }
                            val chunks = source.chunks(document)
                            index.replaceChunks(document.id, source.id, currentVersion, chunks)
                        }
                    }
                    completedDocs++
                    progressState.value = IndexingProgress(
                        isIndexing = true,
                        indexedDocuments = completedDocs,
                        totalDocuments = total,
                        unembeddedChunks = index.unembeddedCount()
                    )
                }

                embedMissingChunks(total, completedDocs)

                if (index.isRebuilding) {
                    index.completeRebuild()
                }
            } finally {
                progressState.value = progressState.value.copy(
                    isIndexing = false,
                    totalDocuments = total,
                    unembeddedChunks = index.unembeddedCount()
                )
            }
        }
    }

    private suspend fun embedMissingChunks(totalDocuments: Int, indexedDocuments: Int) {
        if (!hasEmbedder()) return
        while (true) {
            if (!conditions.canIndex.first()) return
            val batch = index.unembeddedChunks(EMBED_BATCH_SIZE)
            if (batch.isEmpty()) break
            val texts = batch.map { EmbeddingPrompts.document(it.title, it.text) }
            val vectors = try {
                computeEmbeddings(texts)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return
            } ?: return
            if (vectors.size != batch.size) return
            val quantized = LinkedHashMap<String, QuantizedVector>(batch.size)
            for (i in batch.indices) {
                quantized[batch[i].hash] = QuantizedVector.fromFloatArray(vectors[i], index.dimensions)
            }
            index.putVectors(quantized)
            progressState.value = IndexingProgress(
                isIndexing = true,
                indexedDocuments = indexedDocuments,
                totalDocuments = totalDocuments,
                unembeddedChunks = index.unembeddedCount()
            )
        }
    }

    private fun hasEmbedder(): Boolean {
        if (embedder != null) return true
        val installed = installedModels?.get(embeddingSpec) ?: return false
        return loadedModels != null && installed.path.isNotEmpty()
    }

    private suspend fun computeEmbeddings(texts: List<String>): List<FloatArray>? {
        embedder?.let { return it.embed(texts) }
        val installed = installedModels?.get(embeddingSpec) ?: return null
        val loader = loadedModels ?: return null
        return loader.withEmbedder(installed, embedderOptions, keepLoadedMillis = KEEP_EMBEDDER_LOADED_MS) { active ->
            active.embed(texts)
        }
    }

    private fun removeDeletedDocuments(documents: List<DocumentSummary>) {
        val existingIds = documents.mapTo(HashSet(documents.size)) { it.id }
        for (indexedId in index.indexedDocumentIds()) {
            if (indexedId !in existingIds) {
                index.removeDocument(indexedId)
            }
        }
    }

    private fun hasUnindexedDocument(documents: List<DocumentSummary>): Boolean {
        val indexedIds = index.indexedDocumentIds()
        return documents.any { it.id !in indexedIds }
    }

    public companion object {
        /** Default quiet window (30 seconds) before re-indexing an edited document. */
        public const val DEFAULT_QUIET_MILLIS: Long = 30_000L

        internal const val EMBED_BATCH_SIZE: Int = 16
        private const val KEEP_EMBEDDER_LOADED_MS: Long = 10_000L
    }
}
