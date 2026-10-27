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
import io.github.valerioisufi.drawview.canvas.render.DrawViewEngines
import io.github.valerioisufi.drawview.explorer.DocumentOpener
import io.github.valerioisufi.drawview.explorer.LibrarySearch
import io.github.valerioisufi.drawview.explorer.LibrarySearchMatch
import io.github.valerioisufi.drawview.ml.EmbedderOptions
import io.github.valerioisufi.drawview.ml.InstalledModels
import io.github.valerioisufi.drawview.ml.LoadedModels
import io.github.valerioisufi.drawview.ml.ModelCatalog
import io.github.valerioisufi.drawview.ml.ModelDownloads
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
    public val installedModels: InstalledModels? = null,
    public val downloads: ModelDownloads? = null,
    public val loadedModels: LoadedModels? = null,
    public val embeddingSpec: ModelSpec = ModelCatalog.EMBEDDING_GEMMA_2_TEXT,
    initialAnswerModelSpec: ModelSpec = ModelCatalog.GEMMA_4_E2B,
    public val runtimeAvailable: Boolean = loadedModels != null,
    private val onOpenLecture: ((documentId: String, lectureId: String, startMs: Long) -> Unit)? = null,
    private val onOpenAnchor: ((SearchAnchor, openDocument: DocumentOpener) -> Unit)? = null
) : LibrarySearch {
    private val queryFlow = MutableStateFlow("")
    private var resultsByDocument by mutableStateOf<Map<String, DocumentSearchResult>>(emptyMap())

    /** The Gemma 4 model chosen for "Ask your notes" on this device. */
    public var answerModelSpec: ModelSpec by mutableStateOf(initialAnswerModelSpec)

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
        installedModels?.let { models ->
            launch {
                models.installed.collect { ids ->
                    if (answerModelSpec.id !in ids) {
                        val fallback = listOf(ModelCatalog.GEMMA_4_E4B, ModelCatalog.GEMMA_4_E2B)
                            .firstOrNull { it.id in ids }
                        if (fallback != null) {
                            answerModelSpec = fallback
                        }
                    }
                }
            }
        }
    }

    /**
     * Opens the document and navigates to the exact place of [anchor] (page and area, or lecture
     * and minute).
     */
    public fun openAnchor(anchor: SearchAnchor, openDocument: DocumentOpener) {
        val handler = onOpenAnchor
        if (handler != null) {
            handler(anchor, openDocument)
            return
        }
        when (anchor) {
            is SearchAnchor.Document -> openDocument.open(anchor.documentId)
            is SearchAnchor.Page -> openDocument.open(anchor.documentId) { controller ->
                controller.pageManager.jumpToPage(
                    pageId = anchor.pageId,
                    pageNumber = anchor.pageNumber,
                    bounds = anchor.bounds
                )
            }
            is SearchAnchor.Lecture -> {
                openDocument.open(anchor.documentId)
                onOpenLecture?.invoke(anchor.documentId, anchor.lectureId, anchor.startMs)
            }
        }
    }

    private fun toLibraryMatch(hit: SearchHit): LibrarySearchMatch = LibrarySearchMatch(
        snippet = hit.snippet,
        location = formatLocation(hit.anchor),
        icon = iconForKind(hit.kind),
        onOpen = { openDocument -> openAnchor(hit.anchor, openDocument) }
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
    engines: DrawViewEngines = DrawViewEngines.None,
    elementTypes: List<ElementType<*>> = emptyList(),
    extraSources: List<SearchSource> = emptyList(),
    indexPath: String = SearchIndex.defaultPath(),
    conditions: IndexingConditions = IndexingConditions.Always,
    loadedModels: LoadedModels? = null,
    installedModels: InstalledModels? = null,
    downloads: ModelDownloads? = null,
    runtimeAvailable: Boolean = loadedModels != null,
    embeddingSpec: ModelSpec = ModelCatalog.EMBEDDING_GEMMA_2_TEXT,
    embedderOptions: EmbedderOptions = EmbedderOptions(),
    onOpenLecture: ((documentId: String, lectureId: String, startMs: Long) -> Unit)? = null,
    onOpenAnchor: ((SearchAnchor, openDocument: DocumentOpener) -> Unit)? = null
): ContentSearch {
    val scope = rememberCoroutineScope()
    val index = remember(indexPath, embeddingSpec.id) {
        SearchIndex(path = indexPath, embeddingModel = embeddingSpec.id)
    }
    DisposableEffect(index) {
        onDispose { index.close() }
    }
    val sources = remember(store, engines.pdfTiles, elementTypes, extraSources) {
        buildList {
            add(DocumentSearchSource(store, elementTypes))
            add(PdfSearchSource(store, engines.pdfTiles))
            addAll(extraSources)
        }
    }
    val contentSearch = remember(index, store, sources, conditions, loadedModels, installedModels, downloads, runtimeAvailable, embeddingSpec, embedderOptions, onOpenLecture, onOpenAnchor) {
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
        ContentSearch(
            index = index,
            indexer = indexer,
            engine = engine,
            installedModels = installedModels,
            downloads = downloads,
            loadedModels = loadedModels,
            embeddingSpec = embeddingSpec,
            runtimeAvailable = runtimeAvailable,
            onOpenLecture = onOpenLecture,
            onOpenAnchor = onOpenAnchor
        )
    }
    LaunchedEffect(contentSearch) {
        contentSearch.start(scope)
    }
    return contentSearch
}
