package io.github.valerioisufi.drawview.search.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.canvas.render.DrawViewEngines
import io.github.valerioisufi.drawview.canvas.render.tile.PdfTileAdapter
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
import io.github.valerioisufi.drawview.search.NoteVocabulary
import io.github.valerioisufi.drawview.search.NotesAnswer
import io.github.valerioisufi.drawview.search.NotesAnswerer
import io.github.valerioisufi.drawview.search.OnlineAnswerBackend
import io.github.valerioisufi.drawview.search.SearchAnchor
import io.github.valerioisufi.drawview.search.SearchChunkKind
import io.github.valerioisufi.drawview.search.SearchEngine
import io.github.valerioisufi.drawview.search.SearchHit
import io.github.valerioisufi.drawview.search.SearchIndex
import io.github.valerioisufi.drawview.search.SearchIndexer
import io.github.valerioisufi.drawview.search.SearchSource
import io.github.valerioisufi.drawview.search.snippetOf
import io.github.valerioisufi.drawview.store.DocumentStore
import io.github.valerioisufi.drawview.ui.extension.EditorExtension
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Search of the contents of the notes for the document library ([LibrarySearch]), backed by
 * [SearchIndex], [SearchIndexer], [SearchEngine] and [NotesAnswerer] (Controller).
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
    public val answerer: NotesAnswerer = NotesAnswerer(engine, loadedModels, installedModels),
    public val onlineBackend: OnlineAnswerBackend? = null,
    private val onOpenLecture: ((documentId: String, lectureId: String, startMs: Long) -> Unit)? = null,
    private val onOpenAnchor: ((SearchAnchor, openDocument: DocumentOpener) -> Unit)? = null
) : LibrarySearch {
    private val queryFlow = MutableStateFlow("")
    private val scopeFlow = MutableStateFlow<Set<String>?>(null)

    /** The documents the search and the answers look at, `null` for all of them. */
    internal val scope: StateFlow<Set<String>?> get() = scopeFlow

    /** Where the index is read: never the thread of the interface, the typing would wait for it. */
    internal var searchDispatcher: CoroutineDispatcher = Dispatchers.Default

    /** `true` while a search takes long enough to say so. */
    internal var isSearchSlow: Boolean by mutableStateOf(false)
        private set
    private var resultsByDocument by mutableStateOf<Map<String, DocumentSearchResult>>(emptyMap())
    private var activeScope: CoroutineScope? = null
    private var answerJob: Job? = null

    /** The Gemma 4 model chosen for "Ask your notes" on this device. */
    public var answerModelSpec: ModelSpec by mutableStateOf(initialAnswerModelSpec)

    /** The most recent answer synthesized for the current query, or `null` if none. */
    public var currentAnswer: NotesAnswer? by mutableStateOf(null)
        internal set

    /** `true` while an answer is being generated for the current query. */
    public var isAnswering: Boolean by mutableStateOf(false)
        internal set

    /** Error message from the most recent answer attempt, or `null`. */
    public var answerError: String? by mutableStateOf(null)
        internal set

    override var matchedDocumentIds: List<String>? by mutableStateOf(null)
        private set

    override val headerContent: (@Composable (query: String, openDocument: DocumentOpener) -> Unit) =
        { query, openDocument ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IndexingNotice(search = this@ContentSearch)
                AskNotesCard(search = this@ContentSearch, query = query, openDocument = openDocument)
            }
        }

    /** Current state of the background indexer. */
    public val indexingProgress: StateFlow<IndexingProgress> get() = indexer.progress

    override fun onQueryChange(query: String) {
        val previousTrimmed = queryFlow.value.trim()
        val nextTrimmed = query.trim()
        queryFlow.value = query
        if (nextTrimmed != previousTrimmed) {
            answerJob?.cancel()
            answerJob = null
            isAnswering = false
            currentAnswer = null
            answerError = null
            // Never the results of another query, even for a moment
            matchedDocumentIds = null
            resultsByDocument = emptyMap()
        }
    }

    override fun onScopeChange(documentIds: Set<String>?) {
        scopeFlow.value = documentIds
    }

    /**
     * Generates an answer to [question] on this device using [answerModelSpec] (Gemma 4).
     */
    public fun askOnDevice(question: String = queryFlow.value) {
        val scope = activeScope ?: return
        val trimmed = question.trim()
        if (trimmed.isEmpty()) return
        answerJob?.cancel()
        answerJob = scope.launch {
            askOnDeviceNow(trimmed)
        }
    }

    /**
     * Generates an answer to [question] with the configured [onlineBackend] (such as Google Gemini).
     */
    public fun askOnline(question: String = queryFlow.value) {
        val scope = activeScope ?: return
        val trimmed = question.trim()
        if (trimmed.isEmpty() || onlineBackend == null) return
        answerJob?.cancel()
        answerJob = scope.launch {
            askOnlineNow(trimmed)
        }
    }

    internal suspend fun askOnDeviceNow(question: String = queryFlow.value) {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) return
        isAnswering = true
        answerError = null
        try {
            currentAnswer = answerer.answerOnDevice(trimmed, modelSpec = answerModelSpec, documentIds = scopeFlow.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            answerError = e.message ?: e::class.simpleName ?: "Unknown error"
        } finally {
            isAnswering = false
        }
    }

    internal suspend fun askOnlineNow(question: String = queryFlow.value) {
        val backend = onlineBackend ?: return
        val trimmed = question.trim()
        if (trimmed.isEmpty()) return
        isAnswering = true
        answerError = null
        try {
            currentAnswer = answerer.answerOnline(trimmed, backend = backend, documentIds = scopeFlow.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            answerError = e.message ?: e::class.simpleName ?: "Unknown error"
        } finally {
            isAnswering = false
        }
    }

    override fun matchesOf(documentId: String): List<LibrarySearchMatch> {
        val docResult = resultsByDocument[documentId] ?: return emptyList()
        return docResult.hits
            .filter { it.kind != SearchChunkKind.TITLE }
            .map { hit -> toLibraryMatch(hit) }
    }

    /**
     * Starts the background indexer and reacts to query and index updates in [scope], until
     * [scope] is cancelled: cancel it before closing the index.
     */
    public fun start(scope: CoroutineScope) {
        activeScope = scope
        vocabulary = NoteVocabulary(index, scope)
        indexer.start(scope)
        scope.launchSearchCollector()
    }

    /** The words of the notes for the suggestions while writing, once [start]ed. */
    private var vocabulary: NoteVocabulary? = null

    /**
     * The extension of the editors that suggests the end of the word being written from the words
     * of the notes: of the document, of its slides, handwriting and lectures, of the library
     * (`docs/plans/writing-suggestions.md`). Before [start], from the document only.
     */
    public val editorExtension: EditorExtension = EditorExtension {
        textSuggestions(NoteWordSuggester(vocabulary = { vocabulary }))
    }

    /** Closes the index, once the scope given to [start] is cancelled. */
    public fun close() {
        index.close()
    }

    /** Collects query and index changes in [scope] without starting the indexer loop. */
    internal fun observeQueries(scope: CoroutineScope) {
        activeScope = scope
        scope.launchSearchCollector()
    }

    /** Executes the search for [query] immediately and updates [matchedDocumentIds] and [matchesOf]. */
    internal suspend fun searchNow(query: String = queryFlow.value) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            matchedDocumentIds = null
            resultsByDocument = emptyMap()
        } else {
            val scope = scopeFlow.value
            val results = withContext(searchDispatcher) {
                engine.search(trimmed, documentIds = scope).map { result ->
                    result.copy(hits = result.hits.map { hit -> hit.withReadableSnippet(trimmed) })
                }
            }
            matchedDocumentIds = results.map { it.documentId }
            resultsByDocument = results.associateBy { it.documentId }
        }
    }

    private fun CoroutineScope.launchSearchCollector() {
        launch {
            combine(queryFlow.map { it.trim() }, scopeFlow) { query, scope -> query to scope }
                .distinctUntilChanged()
                .collectLatest { (query, _) ->
                    if (query.isEmpty()) {
                        searchNow(query)
                        return@collectLatest
                    }
                    // Not at every key
                    delay(TYPING_PAUSE_MILLIS)
                    val searched = index.revision.value
                    searchShowingProgress(query)
                    // What the index learns reaches the results, without moving them under the
                    // pointer at every note read, and without ever stopping a search running
                    index.revision.dropWhile { it == searched }.conflate().collect {
                        searchShowingProgress(query)
                        delay(INDEX_REFRESH_MILLIS)
                    }
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
     * Runs the search of [query], saying so when it takes long. A failure leaves the names found
     * by the library, and the search and the indexer going.
     */
    private suspend fun searchShowingProgress(query: String) = coroutineScope {
        val slow = launch {
            delay(SLOW_SEARCH_MILLIS)
            isSearchSlow = true
        }
        try {
            searchNow(query)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            matchedDocumentIds = null
            resultsByDocument = emptyMap()
        } finally {
            slow.cancel()
            isSearchSlow = false
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
        /**
         * [this] with the snippet the library shows: for a text or the notes of a lecture, its
         * formulas written as plain math ([ReadableMath]) before the window is cut, so no formula
         * is cut in half before it is read.
         */
        internal fun SearchHit.withReadableSnippet(query: String): SearchHit = when (kind) {
            SearchChunkKind.TEXT, SearchChunkKind.LECTURE_NOTES -> {
                val readable = ReadableMath.of(text)
                if (readable == text) this else copy(snippet = snippetOf(readable, query))
            }
            else -> this
        }

        /** The pause in the typing a search waits for. */
        private const val TYPING_PAUSE_MILLIS = 150L

        /** How long a search runs before saying so. */
        private const val SLOW_SEARCH_MILLIS = 300L

        /** How often the results follow the changes of the index. */
        private const val INDEX_REFRESH_MILLIS = 5_000L

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
 * A [ContentSearch] of [store] across [DocumentSearchSource], the pages of its PDFs (read by the
 * adapters of [pdfTiles], see `DrawViewEngines.pdfTiles`) and any [extraSources] (such as the
 * lectures), its index at [indexPath]: for an application whose windows share one search, which
 * [ContentSearch.start]s it in a scope of its own and [ContentSearch.close]s it once that scope is
 * cancelled. [rememberContentSearch] does both in a composition.
 */
public fun createContentSearch(
    store: DocumentStore,
    pdfTiles: () -> PdfTileAdapter? = { null },
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
    onlineBackend: OnlineAnswerBackend? = null,
    onOpenLecture: ((documentId: String, lectureId: String, startMs: Long) -> Unit)? = null,
    onOpenAnchor: ((SearchAnchor, openDocument: DocumentOpener) -> Unit)? = null
): ContentSearch = searchOver(
    SearchIndex(path = indexPath, embeddingModel = embeddingSpec.id),
    sourcesOf(store, pdfTiles, elementTypes, extraSources),
    store, conditions, loadedModels, installedModels, downloads, runtimeAvailable, embeddingSpec, embedderOptions,
    onlineBackend, onOpenLecture, onOpenAnchor
)

/** The notes of [store], the pages of its PDFs and [extraSources]. */
private fun sourcesOf(
    store: DocumentStore,
    pdfTiles: () -> PdfTileAdapter?,
    elementTypes: List<ElementType<*>>,
    extraSources: List<SearchSource>
): List<SearchSource> = buildList {
    add(DocumentSearchSource(store, elementTypes))
    add(PdfSearchSource(store, pdfTiles))
    addAll(extraSources)
}

/** The search of [sources] in [index]. */
private fun searchOver(
    index: SearchIndex,
    sources: List<SearchSource>,
    store: DocumentStore,
    conditions: IndexingConditions,
    loadedModels: LoadedModels?,
    installedModels: InstalledModels?,
    downloads: ModelDownloads?,
    runtimeAvailable: Boolean,
    embeddingSpec: ModelSpec,
    embedderOptions: EmbedderOptions,
    onlineBackend: OnlineAnswerBackend?,
    onOpenLecture: ((documentId: String, lectureId: String, startMs: Long) -> Unit)?,
    onOpenAnchor: ((SearchAnchor, openDocument: DocumentOpener) -> Unit)?
): ContentSearch {
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
    return ContentSearch(
        index = index,
        indexer = indexer,
        engine = engine,
        installedModels = installedModels,
        downloads = downloads,
        loadedModels = loadedModels,
        embeddingSpec = embeddingSpec,
        runtimeAvailable = runtimeAvailable,
        onlineBackend = onlineBackend,
        onOpenLecture = onOpenLecture,
        onOpenAnchor = onOpenAnchor
    )
}

/**
 * Remembers and runs a [ContentSearch] for [store] across [DocumentSearchSource] and any
 * [extraSources] (such as PDF pages and lectures), in this composition (see [createContentSearch]
 * for a search shared by several windows).
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
    onlineBackend: OnlineAnswerBackend? = null,
    onOpenLecture: ((documentId: String, lectureId: String, startMs: Long) -> Unit)? = null,
    onOpenAnchor: ((SearchAnchor, openDocument: DocumentOpener) -> Unit)? = null
): ContentSearch {
    val index = remember(indexPath, embeddingSpec.id) {
        SearchIndex(path = indexPath, embeddingModel = embeddingSpec.id)
    }
    DisposableEffect(index) {
        onDispose { index.close() }
    }
    val sources = remember(store, engines.pdfTiles, elementTypes, extraSources) {
        sourcesOf(store, engines.pdfTiles, elementTypes, extraSources)
    }
    val contentSearch = remember(index, store, sources, conditions, loadedModels, installedModels, downloads, runtimeAvailable, embeddingSpec, embedderOptions, onlineBackend, onOpenLecture, onOpenAnchor) {
        searchOver(
            index, sources, store, conditions, loadedModels, installedModels, downloads, runtimeAvailable, embeddingSpec,
            embedderOptions, onlineBackend, onOpenLecture, onOpenAnchor
        )
    }
    // The indexer and the answers of a search replaced stop with it, before its index is closed
    LaunchedEffect(contentSearch) {
        contentSearch.start(this)
    }
    return contentSearch
}
