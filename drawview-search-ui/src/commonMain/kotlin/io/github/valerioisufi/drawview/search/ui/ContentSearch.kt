package io.github.valerioisufi.drawview.search.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.valerioisufi.drawview.explorer.LibrarySearch
import io.github.valerioisufi.drawview.explorer.LibrarySearchMatch
import io.github.valerioisufi.drawview.ml.EmbedderOptions
import io.github.valerioisufi.drawview.ml.InstalledModels
import io.github.valerioisufi.drawview.ml.LoadedModels
import io.github.valerioisufi.drawview.ml.ModelCatalog
import io.github.valerioisufi.drawview.ml.ModelSpec
import io.github.valerioisufi.drawview.model.ElementType
import io.github.valerioisufi.drawview.search.DocumentSearchResult
import io.github.valerioisufi.drawview.search.DocumentSearchSource
import io.github.valerioisufi.drawview.search.IndexingConditions
import io.github.valerioisufi.drawview.search.IndexingProgress
import io.github.valerioisufi.drawview.search.SearchAnchor
import io.github.valerioisufi.drawview.search.SearchChunkKind
import io.github.valerioisufi.drawview.search.SearchEngine
import io.github.valerioisufi.drawview.search.SearchHit
import io.github.valerioisufi.drawview.search.SearchIndex
import io.github.valerioisufi.drawview.search.SearchIndexer
import io.github.valerioisufi.drawview.search.SearchSource
import io.github.valerioisufi.drawview.store.DocumentStore
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Search of the contents of the notes for the document library ([LibrarySearch]), backed by
 * [SearchIndex], [SearchIndexer] and [SearchEngine] (Controller).
 */
public class ContentSearch(
    public val index: SearchIndex,
    public val indexer: SearchIndexer,
    public val engine: SearchEngine,
    private val onOpenAnchor: ((SearchAnchor, openDocument: (String) -> Unit) -> Unit)? = null
) : LibrarySearch {
    private val queryFlow = MutableStateFlow("")
    private var resultsByDocument by mutableStateOf<Map<String, DocumentSearchResult>>(emptyMap())

    override var matchedDocumentIds: List<String>? by mutableStateOf(null)
        private set

    /** Current state of the background indexer. */
    public val indexingProgress: StateFlow<IndexingProgress> get() = indexer.progress

    override fun onQueryChange(query: String) {
        queryFlow.value = query
        if (query.isBlank()) {
            matchedDocumentIds = null
            resultsByDocument = emptyMap()
        }
    }

    override fun matchesOf(documentId: String): List<LibrarySearchMatch> {
        val docResult = resultsByDocument[documentId] ?: return emptyList()
        return docResult.hits
            .filter { it.kind != SearchChunkKind.TITLE }
            .map { hit -> toLibraryMatch(hit) }
    }

    /** Starts the background indexer and reacts to query and index updates in [scope]. */
    public fun start(scope: CoroutineScope) {
        indexer.start(scope)
        scope.launchSearchCollector()
    }

    /** Collects query and index changes in [scope] without starting the indexer loop. */
    internal fun observeQueries(scope: CoroutineScope) {
        scope.launchSearchCollector()
    }

    /** Executes the search for [query] immediately and updates [matchedDocumentIds] and [matchesOf]. */
    internal suspend fun searchNow(query: String = queryFlow.value) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            matchedDocumentIds = null
            resultsByDocument = emptyMap()
        } else {
            val results = engine.search(trimmed)
            matchedDocumentIds = results.map { it.documentId }
            resultsByDocument = results.associateBy { it.documentId }
        }
    }

    private fun CoroutineScope.launchSearchCollector() {
        launch {
            combine(queryFlow, index.revision) { query, _ -> query.trim() }
                .collectLatest { trimmed ->
                    searchNow(trimmed)
                }
        }
    }

    private fun toLibraryMatch(hit: SearchHit): LibrarySearchMatch = LibrarySearchMatch(
        snippet = hit.snippet,
        location = formatLocation(hit.anchor),
        icon = iconForKind(hit.kind),
        onOpen = { openDocument ->
            val handler = onOpenAnchor
            if (handler != null) {
                handler(hit.anchor, openDocument)
            } else {
                openDocument(hit.anchor.documentId)
            }
        }
    )

    public companion object {
        internal fun formatLocation(anchor: SearchAnchor): String = when (anchor) {
            is SearchAnchor.Document -> ""
            is SearchAnchor.Page -> "p. ${anchor.pageNumber}"
            is SearchAnchor.Lecture -> {
                val totalSeconds = (anchor.startMs / 1000L).coerceAtLeast(0L)
                val minutes = totalSeconds / 60L
                val seconds = totalSeconds % 60L
                "$minutes:${seconds.toString().padStart(2, '0')}"
            }
        }

        internal fun iconForKind(kind: SearchChunkKind): ImageVector? = when (kind) {
            SearchChunkKind.TITLE -> null
            SearchChunkKind.TEXT -> DrawViewIcons.Text
            SearchChunkKind.PDF -> DrawViewIcons.FileText
            SearchChunkKind.HANDWRITING -> DrawViewIcons.Pencil
            SearchChunkKind.LECTURE_TRANSCRIPT -> DrawViewIcons.Mic
            SearchChunkKind.LECTURE_NOTES -> DrawViewIcons.Transcript
        }
    }
}

/**
 * Remembers and runs a [ContentSearch] for [store] across [DocumentSearchSource] and any
 * [extraSources] (such as PDF pages and lectures).
 */
@Composable
public fun rememberContentSearch(
    store: DocumentStore,
    elementTypes: List<ElementType<*>> = emptyList(),
    extraSources: List<SearchSource> = emptyList(),
    indexPath: String = SearchIndex.defaultPath(),
    conditions: IndexingConditions = IndexingConditions.Always,
    loadedModels: LoadedModels? = null,
    installedModels: InstalledModels? = null,
    embeddingSpec: ModelSpec = ModelCatalog.EMBEDDING_GEMMA_2_TEXT,
    embedderOptions: EmbedderOptions = EmbedderOptions(),
    onOpenAnchor: ((SearchAnchor, openDocument: (String) -> Unit) -> Unit)? = null
): ContentSearch {
    val scope = rememberCoroutineScope()
    val index = remember(indexPath, embeddingSpec.id) {
        SearchIndex(path = indexPath, embeddingModel = embeddingSpec.id)
    }
    DisposableEffect(index) {
        onDispose { index.close() }
    }
    val sources = remember(store, elementTypes, extraSources) {
        listOf(DocumentSearchSource(store, elementTypes)) + extraSources
    }
    val contentSearch = remember(index, store, sources, conditions, loadedModels, installedModels, embeddingSpec, embedderOptions, onOpenAnchor) {
        val indexer = SearchIndexer(
            catalog = store,
            index = index,
            sources = sources,
            conditions = conditions,
            loadedModels = loadedModels,
            installedModels = installedModels,
            embeddingSpec = embeddingSpec,
            embedderOptions = embedderOptions
        )
        val engine = SearchEngine(
            index = index,
            loadedModels = loadedModels,
            installedModels = installedModels,
            embeddingSpec = embeddingSpec,
            embedderOptions = embedderOptions
        )
        ContentSearch(index = index, indexer = indexer, engine = engine, onOpenAnchor = onOpenAnchor)
    }
    LaunchedEffect(contentSearch) {
        contentSearch.start(scope)
    }
    return contentSearch
}
