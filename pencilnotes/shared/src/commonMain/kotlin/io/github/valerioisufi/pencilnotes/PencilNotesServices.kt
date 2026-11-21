package io.github.valerioisufi.pencilnotes

import androidx.compose.runtime.snapshotFlow
import io.github.valerioisufi.pageboard.canvas.controller.PageTopics
import io.github.valerioisufi.pageboard.canvas.render.PageboardEngines
import io.github.valerioisufi.pageboard.canvas.render.ImageCache
import io.github.valerioisufi.pageboard.export.DocumentRecordings
import io.github.valerioisufi.pageboard.export.ExportedRecording
import io.github.valerioisufi.pageboard.export.MarkdownAppendix
import io.github.valerioisufi.pageboard.export.ui.DocumentExportUi
import io.github.valerioisufi.pageboard.latex.LatexEngine
import io.github.valerioisufi.pageboard.lecture.DeviceModels
import io.github.valerioisufi.pageboard.lecture.LectureAssistant
import io.github.valerioisufi.pageboard.lecture.formatRecordingTimestamp
import io.github.valerioisufi.pageboard.lecture.ui.createLectureAssistant
import io.github.valerioisufi.pageboard.lecture.ui.markdownInLanguage
import io.github.valerioisufi.pageboard.lecture.ui.documentTag
import io.github.valerioisufi.pageboard.lecture.ui.editorExtension
import io.github.valerioisufi.pageboard.lecture.ui.onlineAnswerBackend
import io.github.valerioisufi.pageboard.lecture.ui.searchSource
import io.github.valerioisufi.pageboard.ml.InstalledModels
import io.github.valerioisufi.pageboard.ml.LoadedModels
import io.github.valerioisufi.pageboard.ml.ModelDownloads
import io.github.valerioisufi.pageboard.ml.litert.LiteRtRuntime
import io.github.valerioisufi.pageboard.pdf.PdfiumEngine
import io.github.valerioisufi.pageboard.search.MutableIndexingConditions
import io.github.valerioisufi.pageboard.handwriting.HandwritingEngines
import io.github.valerioisufi.pageboard.handwriting.ui.HandwritingConversion
import io.github.valerioisufi.pageboard.handwriting.ui.handwritingEditorExtension
import io.github.valerioisufi.pageboard.handwriting.HandwritingReading
import io.github.valerioisufi.pageboard.search.HandwritingSearch
import io.github.valerioisufi.pageboard.search.ui.ContentSearch
import androidx.compose.ui.text.intl.Locale
import io.github.valerioisufi.pageboard.search.ui.createContentSearch
import io.github.valerioisufi.pageboard.sync.iroh.IrohInternet
import io.github.valerioisufi.pageboard.sync.ui.Collaboration
import io.github.valerioisufi.pageboard.sync.ui.createCollaboration
import io.github.valerioisufi.pageboard.sync.ui.editorExtension
import io.github.valerioisufi.pageboard.ui.extension.EditorExtension
import io.github.valerioisufi.pageboard.ui.extension.DocumentTag
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
        markdownAppendix = MarkdownAppendix { documentId -> lectureAssistant.markdownInLanguage(documentId) },
        recordings = DocumentRecordings { documentId ->
            lectureAssistant.exportableRecordings(documentId).map { rec ->
                val stem = formatRecordingTimestamp(rec.startedAt)
                ExportedRecording(
                    fileName = "$stem.${lectureAssistant.exportExtension}",
                    recordedAt = rec.startedAt,
                    hasAudio = rec.hasAudio,
                    transcriptFileName = rec.transcript?.let { "$stem.${lectureAssistant.transcriptExportExtension}" },
                    transcript = rec.transcript,
                    topic = rec.topic,
                    resolveTopic = { lectureAssistant.generateAndSaveTopic(rec.directory) },
                    writeAudio = { path, progress -> lectureAssistant.writeRecordingAudio(rec.directory, path, progress) }
                )
            }
        },
        canGenerateRecordingTopic = { lectureAssistant.canGenerateLectureTitleLocally }
    )

    /**
     * The handwriting of the notes read for the search by the reader of the platform (ML Kit on
     * Android, the ink of Windows on its desktop), in the language of the system.
     */
    private val handwriting: HandwritingSearch? = handwritingEngines().takeIf { it.text.isNotEmpty() }?.let { engines ->
        HandwritingSearch(HandwritingReading(engines), language = Locale.current.toLanguageTag())
    }

    init {
        // The lecture assistant reads the notes with the same readers first
        handwriting?.let {
            lectureAssistant.handwritingLanguage = it.language.value
            lectureAssistant.handwritingReading = it.reading
        }
    }

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
        onOpenLecture = lectureAssistant::openLectureAt,
        handwriting = handwriting
    )

    /**
     * The PDF pages drawn by PDFium, the formulas by MicroTeX, the images decoded once for every
     * window, and a text placed next to the pages it is about (the review of a lecture, a text
     * imported) by the search.
     */
    val engines: PageboardEngines = PageboardEngines(
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
        val base = search.editorExtension + lectureAssistant.editorExtension() + collaboration.editorExtension() + export.editorExtension
        // "Convert to text" on a selection of strokes, with the readers of the search
        val extension = handwriting?.let { reading ->
            base + handwritingEditorExtension(HandwritingConversion(reading.reading) { reading.language.value })
        } ?: base
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

/** The readers of handwriting of this platform. */
internal expect fun handwritingEngines(): HandwritingEngines
