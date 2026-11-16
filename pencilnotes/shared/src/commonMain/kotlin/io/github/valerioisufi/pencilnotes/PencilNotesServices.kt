package io.github.valerioisufi.pencilnotes

import androidx.compose.runtime.snapshotFlow
import io.github.valerioisufi.drawview.canvas.controller.PageTopics
import io.github.valerioisufi.drawview.canvas.render.DrawViewEngines
import io.github.valerioisufi.drawview.canvas.render.ImageCache
import io.github.valerioisufi.drawview.export.ui.DocumentExportUi
import io.github.valerioisufi.drawview.latex.LatexEngine
import io.github.valerioisufi.drawview.lecture.DeviceModels
import io.github.valerioisufi.drawview.lecture.LectureAssistant
import io.github.valerioisufi.drawview.lecture.ui.createLectureAssistant
import io.github.valerioisufi.drawview.lecture.ui.markdownInLanguage
import io.github.valerioisufi.drawview.export.MarkdownAppendix
import io.github.valerioisufi.drawview.lecture.ui.documentTag
import io.github.valerioisufi.drawview.lecture.ui.editorExtension
import io.github.valerioisufi.drawview.lecture.ui.onlineAnswerBackend
import io.github.valerioisufi.drawview.lecture.ui.searchSource
import io.github.valerioisufi.drawview.ml.InstalledModels
import io.github.valerioisufi.drawview.ml.LoadedModels
import io.github.valerioisufi.drawview.ml.ModelDownloads
import io.github.valerioisufi.drawview.ml.litert.LiteRtRuntime
import io.github.valerioisufi.drawview.pdf.PdfiumEngine
import io.github.valerioisufi.drawview.search.MutableIndexingConditions
import io.github.valerioisufi.drawview.search.ui.ContentSearch
import io.github.valerioisufi.drawview.search.ui.createContentSearch
import io.github.valerioisufi.drawview.sync.iroh.IrohInternet
import io.github.valerioisufi.drawview.sync.ui.Collaboration
import io.github.valerioisufi.drawview.sync.ui.createCollaboration
import io.github.valerioisufi.drawview.sync.ui.editorExtension
import io.github.valerioisufi.drawview.ui.extension.EditorExtension
import io.github.valerioisufi.drawview.ui.extension.DocumentTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What works on the notes of PencilNotes in this process (`docs/plans/document-window.md`), created once
 * on first use and shared by every window: the engines drawing the documents, the lecture
 * assistant, the collaboration with the other devices, the search in the notes with the local
 * models, the export. The windows bind them with `PencilNotesWindow`; the application [stop]s them
 * before exiting.
 */
object PencilNotesServices {
    /** Where the services work together, on the main thread, until [stop]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // The local models of the search and of the lecture assistant (EmbeddingGemma 2, Gemma 4)
    private val liteRtRuntime = LiteRtRuntime()
    private val installedModels = InstalledModels()
    private val loadedModels = LoadedModels(liteRtRuntime)
    private val modelDownloads = ModelDownloads(installedModels)

    /**
     * The lecture assistant: one lecture recorded at a time, also while its document or window is
     * closed; its local requests on the models of the search when the settings choose them.
     */
    val lectureAssistant: LectureAssistant =
        createLectureAssistant(DeviceModels(installedModels, loadedModels, modelDownloads, isAvailable = liteRtRuntime.isAvailable))

    /**
     * The documents shared with the devices of the local network, with their lectures, and over
     * the internet (iroh) once the user turns it on in the sharing.
     */
    val collaboration: Collaboration =
        createCollaboration(PencilNotesData.documents, attachments = lectureAssistant.attachments, internet = IrohInternet)

    /**
     * The documents exported as PDF, as the image of a page or as Markdown (with the notes of their
     * lectures), and the exports going on.
     */
    val export: DocumentExportUi = DocumentExportUi(
        PencilNotesData.documents,
        appName = "PencilNotes",
        markdownAppendix = MarkdownAppendix { documentId -> lectureAssistant.markdownInLanguage(documentId) }
    )

    /** The notes are indexed while no lecture is recorded or transcribed. */
    private val indexingConditions = MutableIndexingConditions(initialCanIndex = true)

    /** The search in the contents of the notes and of the lectures, and the answers about them. */
    val search: ContentSearch = createContentSearch(
        store = PencilNotesData.store,
        pdfTiles = PdfiumEngine::createTileAdapter,
        extraSources = listOf(lectureAssistant.searchSource(PencilNotesData.store)),
        conditions = indexingConditions,
        loadedModels = loadedModels,
        installedModels = installedModels,
        downloads = modelDownloads,
        runtimeAvailable = liteRtRuntime.isAvailable,
        onlineBackend = lectureAssistant.onlineAnswerBackend(),
        onOpenLecture = lectureAssistant::openLectureAt
    )

    /**
     * The PDF pages drawn by PDFium, the formulas by MicroTeX, the images decoded once for every
     * window, and a text placed next to the pages it is about (the review of a lecture, a text
     * imported) by the search.
     */
    val engines: DrawViewEngines = DrawViewEngines(
        pdfTiles = PdfiumEngine::createTileAdapter,
        formulas = LatexEngine(),
        topics = PageTopics { documentId, text -> search.engine.pagesAbout(documentId, text) },
        images = ImageCache()
    )

    /** The documents with a lecture: their badge and a filter of the library. */
    val lectureTag: DocumentTag = lectureAssistant.documentTag()

    init {
        search.start(scope)
        // A tablet uses the models of a computer of the local network (Ollama)
        lectureAssistant.localModels.nearbyHosts = collaboration::nearbyHosts
        scope.launch {
            snapshotFlow { !lectureAssistant.isBusy }.collect(indexingConditions::setCanIndex)
        }
        // The lectures of the documents deleted for good go with them (not of the ones in the trash)
        scope.launch {
            val store = PencilNotesData.store
            var known: Set<String>? = null
            combine(store.documents, store.trash) { summaries, trash ->
                summaries.mapTo(mutableSetOf()) { it.id } + trash.flatMap { it.documentIds }
            }.collect { ids ->
                known?.let { lectureAssistant.forgetDocuments(it - ids) }
                known = ids
            }
        }
    }

    /**
     * The extension of every editor of PencilNotes: the suggestions while writing from the words of
     * the notes, the lectures, the sharing, the export, and the external models with [agents].
     */
    fun editorExtension(agents: PencilNotesAgents?): EditorExtension {
        val extension = search.editorExtension + lectureAssistant.editorExtension() + collaboration.editorExtension() + export.editorExtension
        return agents?.let { extension + it.editorExtension } ?: extension
    }

    /** Stops working with the other devices, e.g. when the last activity of Android finishes (a window binding the collaboration starts it again). */
    fun stopCollaboration() {
        scope.launch { collaboration.stop() }
    }

    /**
     * Stops the services before the application exits: the collaboration (waiting for it a few
     * seconds at most), the work of the search, then its index.
     */
    suspend fun stop() {
        withTimeoutOrNull(STOP_WAIT_MS) { collaboration.stop() }
        scope.cancel()
        search.close()
    }

    private const val STOP_WAIT_MS = 3_000L
}
