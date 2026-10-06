package io.github.valerioisufi.drawview.lecture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.valerioisufi.drawview.core.attachment.DocumentAttachments
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.util.currentTimeMillis
import io.github.valerioisufi.drawview.lecture.audio.AudioCapture
import io.github.valerioisufi.drawview.lecture.audio.AudioOutput
import io.github.valerioisufi.drawview.lecture.audio.createAudioCapture
import io.github.valerioisufi.drawview.lecture.audio.createAudioOutput
import io.github.valerioisufi.drawview.lecture.gemini.GeminiClient
import io.github.valerioisufi.drawview.lecture.audio.canEncodeM4a
import io.github.valerioisufi.drawview.lecture.audio.exportRecording
import io.github.valerioisufi.drawview.lecture.handwriting.HandwritingReader
import io.github.valerioisufi.drawview.lecture.platform.ChosenFile
import io.github.valerioisufi.drawview.lecture.platform.canChooseFiles
import io.github.valerioisufi.drawview.lecture.platform.generationSupported
import io.github.valerioisufi.drawview.lecture.local.OllamaClient
import io.github.valerioisufi.drawview.lecture.model.GeminiBackend
import io.github.valerioisufi.drawview.lecture.model.ModelBackends
import io.github.valerioisufi.drawview.lecture.model.OllamaBackend
import io.github.valerioisufi.drawview.lecture.transcription.Transcriber
import io.github.valerioisufi.drawview.lecture.transcription.WhisperTranscriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * The lecture assistant of an application.
 *
 * It lives as long as the application: one lecture is recorded at a time ([recorder]), also while
 * its document is closed, and the lectures of every document are kept by the [library] and shown
 * again when the document is opened ([lecturesOf], [shownLecture]).
 *
 * Every notebook has its course context ([CourseContext]), written by the assistant from the
 * notebook when its first lecture starts ([contextOf], [writeContext], [editContext]).
 *
 * The editors show it through [io.github.valerioisufi.drawview.lecture.ui.editorExtension], the
 * document library through [io.github.valerioisufi.drawview.lecture.ui.LectureRecordingIndicator].
 *
 * The devices working on a notebook share its lectures ([attachments], for the synchronization):
 * a lecture recorded on a device without the models is transcribed and reviewed where they are
 * ([generate]).
 *
 * @param canGenerate whether the models follow the lectures here (the live notes, the review, the
 *   course context): elsewhere the lectures are recorded only (see [generationSupported]).
 */
class LectureAssistant(
    val settingsStore: AssistantSettingsStore = AssistantSettingsStore(),
    private val client: GeminiClient = GeminiClient(),
    private val localClient: OllamaClient = OllamaClient(),
    private val captureFactory: () -> AudioCapture? = ::createAudioCapture,
    private val audioOutput: (() -> AudioOutput?)? = ::createAudioOutput.takeIf { createAudioOutput() != null },
    val library: LectureLibrary = LectureLibrary(),
    private val stateContext: CoroutineContext = Dispatchers.Main,
    private val transcriberFactory: (AssistantSettings) -> Transcriber? = { WhisperTranscriber.of(it) },
    val canGenerate: Boolean = generationSupported()
) {
    private val backends = ModelBackends(
        gemini = GeminiBackend(client) { settingsStore.apiKey },
        local = OllamaBackend(localClient) { settingsStore.settings.localUrl }
    )

    /** The models on this computer (Ollama, whisper.cpp), for the settings. */
    val localModels = LocalModels(settingsStore, localClient, stateContext = stateContext)

    /** The last recording (being recorded, being reviewed, or finished). */
    var recorder: LectureRecorder? by mutableStateOf(null)
        private set

    var isPanelOpen: Boolean by mutableStateOf(false)

    /** The suggestion the student is placing in the notebook, if any. */
    var placingNote: NoteSuggestion? by mutableStateOf(null)
        internal set

    /** The lecture being imported, if any. */
    var importing: Lecture? by mutableStateOf(null)
        private set

    /** Why the last import failed (the file is not audio), if it did. */
    var importError: String? by mutableStateOf(null)
        private set

    /** The last export of an audio. */
    var exportResult: ExportResult? by mutableStateOf(null)
        private set

    /** Why the last recording could not start, if it could not. */
    var startError: String? by mutableStateOf(null)
        private set

    /** The last recording could not start: the microphone is not allowed. */
    var microphoneDenied: Boolean by mutableStateOf(false)
        private set

    /** Asks for the permission to use the microphone (set by [io.github.valerioisufi.drawview.lecture.ui.rememberLectureAssistant]). */
    internal var requestMicrophone: suspend () -> Boolean = { true }

    /** Whether the platform can record (the desktop, for now). */
    val isRecordingSupported: Boolean by lazy { captureFactory() != null }

    /** The recording not finished yet (recording or reviewing), if any. */
    val activeRecorder: LectureRecorder? get() = recorder?.takeIf { it.lecture.status != LectureStatus.FINISHED }

    val isRecording: Boolean get() = recorder?.isRecording == true

    private val lectureLists = mutableStateMapOf<String, List<LectureSummary>>()
    private val shownLectures = mutableStateMapOf<String, Lecture>()
    private val contexts = mutableStateMapOf<String, CourseContext>()
    private val writingContexts = mutableStateMapOf<String, Boolean>()
    private val editorNotebooks = mutableMapOf<String, NotebookSource>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The lectures of the notebooks as items beside them, for the synchronization with the other devices. */
    val attachments: DocumentAttachments by lazy { LectureAttachments(sharing, scope) }

    private val sharing = object : LectureAssistantLibrary {
        override val files get() = library

        override suspend fun loadedLecture(directory: String): Lecture? = withContext(stateContext) {
            listOfNotNull(recorder?.lecture, importing).plus(shownLectures.values).firstOrNull { it.directory == directory }
        }

        override suspend fun isActiveHere(directory: String): Boolean = withContext(stateContext) {
            activeRecorder?.lecture?.directory == directory || importing?.directory == directory
        }

        override suspend fun onReceived(documentId: String) = refreshNow(documentId)

        override suspend fun onContextReceived(documentId: String, context: CourseContext) = withContext(stateContext) {
            contexts[documentId] = context
        }

        override suspend fun onDeleted(documentId: String, directory: String) = withContext(stateContext) {
            val shown = shownLectures[documentId]
            if (shown?.directory == directory) {
                shown.stopPlayback()
                shownLectures.remove(documentId)
            }
        }
    }

    init {
        LectureRecordings.register(this)
    }

    /** The lectures of [documentId] (loaded by [refresh]), the most recent first. */
    fun lecturesOf(documentId: String): List<LectureSummary> = lectureLists[documentId].orEmpty()

    /** The lecture shown in the panel of [documentId]: the one being recorded, or the last one. */
    fun shownLecture(documentId: String): Lecture? = shownLectures[documentId]

    /** The recorder of [documentId], if it is the one being recorded or reviewed. */
    fun recorderOf(documentId: String): LectureRecorder? = activeRecorder?.takeIf { it.lecture.documentId == documentId }

    /**
     * The editor of [documentId] is shown: the recording of the document (if any) sees its pages
     * again, and its lectures are loaded.
     */
    fun onEditorShown(documentId: String, notebook: NotebookSource) {
        editorNotebooks[documentId] = notebook
        recorderOf(documentId)?.attachNotebook(notebook)
        refresh(documentId)
    }

    /** The editor of [documentId] is closed: the recording goes on, without its pages. */
    fun onEditorHidden(documentId: String, notebook: NotebookSource) {
        if (editorNotebooks[documentId] === notebook) editorNotebooks.remove(documentId)
        recorderOf(documentId)?.detachNotebook(notebook)
        placingNote = null
        shownLectures[documentId]?.stopPlayback()
    }

    /** The course context of [documentId] (loaded by [refresh]), if any. */
    fun contextOf(documentId: String): CourseContext? = contexts[documentId]

    /** The course context of [documentId] is being written. */
    fun isWritingContext(documentId: String): Boolean = writingContexts[documentId] == true

    /** Whether the context of [documentId] can be written: its notebook is open (and the models are here). */
    fun canWriteContext(documentId: String): Boolean = canGenerate && documentId in editorNotebooks

    /** The student changed the course context of [documentId]: it is not replaced automatically. */
    fun editContext(documentId: String, text: String) {
        val previous = contexts[documentId]
        val context = CourseContext(text.trim(), edited = true, generatedAt = previous?.generatedAt ?: 0L, stamp = nextContextStamp(previous))
        contexts[documentId] = context
        scope.launch { library.saveContext(documentId, context) }
    }

    /**
     * Writes the course context of [documentId] from its notebook (open), with the model of the
     * live suggestions; [onlyIfMissing]: not when the notebook has one already.
     */
    fun writeContext(documentId: String, onlyIfMissing: Boolean = false) {
        if (!canGenerate) return
        val notebook = editorNotebooks[documentId] ?: return
        if (isWritingContext(documentId)) return
        writingContexts[documentId] = true
        scope.launch { writeContextNow(documentId, notebook, onlyIfMissing) }
    }

    /** Writes the course context of [documentId] from [notebook] (see [writeContext]), waiting for it. */
    private suspend fun writeContextNow(documentId: String, notebook: NotebookSource, onlyIfMissing: Boolean) {
        withContext(stateContext) { writingContexts[documentId] = true }
        val text = try {
            if (onlyIfMissing && library.loadContext(documentId) != null) null else composeContext(documentId, notebook)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("DrawView: cannot write the course context: ${e.message}")
            null
        }
        val context = text?.takeIf { it.isNotBlank() }?.let {
            CourseContext(it, edited = false, generatedAt = currentTimeMillis(), stamp = nextContextStamp(library.loadContext(documentId)))
        }
        if (context != null) library.saveContext(documentId, context)
        withContext(stateContext) {
            writingContexts.remove(documentId)
            if (context != null) contexts[documentId] = context
        }
    }

    /** The stamp of a course context written here after [previous]. */
    private fun nextContextStamp(previous: CourseContext?) = (previous?.stamp ?: Stamp.NONE).next(library.deviceId, currentTimeMillis())

    /** The course context written by the model of the live suggestions, from the whole notebook. */
    private suspend fun composeContext(documentId: String, notebook: NotebookSource): String? {
        val task = settingsStore.settings.task(LectureTask.LIVE_NOTES)
        if (task.provider == ModelProvider.GEMINI && !settingsStore.hasApiKey) return null
        val pages = notebook.allPages()
        if (pages.isEmpty()) return null
        // With the handwriting already transcribed
        val reader = HandwritingReader(library.documentDirectory(documentId) + "/handwriting.json", backends) { library.changed(documentId, null) }
        val blocks = notebook.inkBlocks(pages.filter { it.hasInk }.map { it.index })
        val filled = pages.map { page -> reader.fill(page, blocks.filter { it.pageIndex == page.index }) }
        val prompt = LecturePrompts.courseContextPrompt(settingsStore.settings, notebook.documentTitle, filled)
        val answer = when (task.provider) {
            ModelProvider.GEMINI -> backends.gemini.generate(prompt, task.gemini)
            ModelProvider.LOCAL -> backends.local.generate(prompt, task.local, OllamaBackend.LIVE_KEEP_LOADED_SECONDS) {}
        }
        return LectureAnalysis.parseCourseContext(answer.text)
    }

    /** Lists the lectures of [documentId] again, showing the last one when none is shown. */
    fun refresh(documentId: String) {
        scope.launch { refreshNow(documentId) }
    }

    private suspend fun refreshNow(documentId: String) {
        val lectures = library.list(documentId)
        val context = library.loadContext(documentId)
        withContext(stateContext) {
            if (context != null && contexts[documentId] == null) contexts[documentId] = context
            lectureLists[documentId] = lectures
            if (shownLectures[documentId] == null) lectures.firstOrNull()?.let { show(documentId, it) }
        }
    }

    /** Shows the lecture [summary] in the panel of [documentId]. */
    fun show(documentId: String, summary: LectureSummary) {
        val active = importing?.takeIf { it.directory == summary.directory } ?: recorder?.lecture
        if (active != null && active.directory == summary.directory) {
            shownLectures[documentId] = active
            return
        }
        if (shownLectures[documentId]?.directory == summary.directory) return
        scope.launch {
            // Shown before what another device sends is merged into it (see LectureAttachments)
            library.records.withLock {
                val record = library.load(summary.directory) ?: return@launch
                val lecture = newLecture(documentId, summary.directory, record)
                withContext(stateContext) { shownLectures[documentId] = lecture }
            }
        }
    }

    /** A lecture of [documentId] in [directory]: [record] when saved already. */
    private fun newLecture(documentId: String, directory: String, record: LectureRecord?, startedAt: Long? = null) = Lecture(
        documentId = documentId,
        directory = directory,
        record = record,
        settingsStore = settingsStore,
        backends = backends,
        audioOutput = audioOutput,
        stateContext = stateContext,
        courseContext = { contextOf(documentId)?.text.orEmpty() },
        startedAt = startedAt,
        generates = canGenerate,
        device = library.deviceId,
        deviceName = library.deviceName,
        recordsLock = library.records,
        onChanged = { library.changed(documentId, it) }
    )

    /**
     * Starts recording a lecture of the document edited by [controller], once allowed to use the
     * microphone ([microphoneDenied] otherwise).
     */
    fun record(documentId: String, controller: DrawViewController) {
        if (isBusy) return
        scope.launch {
            withContext(stateContext) {
                val allowed = requestMicrophone()
                microphoneDenied = !allowed
                if (allowed) start(documentId, controller)
            }
        }
    }

    /**
     * Starts recording a lecture of the document edited by [controller] (the microphone allowed).
     *
     * @return `false` when another lecture is being recorded, or the microphone cannot be opened
     *   ([startError]).
     */
    fun start(documentId: String, controller: DrawViewController): Boolean {
        val notebook = editorNotebooks[documentId] ?: ControllerNotebookSource(controller).also { editorNotebooks[documentId] = it }
        return start(documentId, controller.document.title, notebook)
    }

    /** Starts recording a lecture of the document [documentId] titled [title], seen through [notebook]. */
    internal fun start(documentId: String, title: String, notebook: NotebookSource): Boolean {
        if (isBusy) return false
        val capture = captureFactory() ?: return false
        val startedAt = currentTimeMillis()
        val lecture = newLecture(documentId, library.newLectureDirectory(documentId, startedAt), record = null, startedAt = startedAt)
            .apply { documentTitle = title }
        val settings = settingsStore.settings
        val transcriber = if (!canGenerate || settings.sendsAudio) null else transcriberFactory(settings)
        val newRecorder = LectureRecorder(
            lecture, settingsStore, capture, notebook, transcriber, analyzes = canGenerate, onFinished = { refresh(documentId) }
        )
        return try {
            newRecorder.start()
            startError = null
            recorder = newRecorder
            shownLectures[documentId] = lecture
            isPanelOpen = true
            refresh(documentId)
            // The first lecture of the notebook: its course context
            if (contexts[documentId] == null) writeContext(documentId, onlyIfMissing = true)
            true
        } catch (e: Throwable) {
            startError = e.message ?: e::class.simpleName
            false
        }
    }

    /** Whether a recording or an import is in progress (one at a time). */
    val isBusy: Boolean get() = activeRecorder != null || importing != null

    /** Whether recordings can be imported and exported here (the desktop). */
    val canImportAndExport: Boolean get() = canChooseFiles

    /**
     * Imports [file], a recording made elsewhere (a phone, a recorder), as a lecture of
     * [documentId]: converted, transcribed and reviewed ([LectureImporter]), in the background.
     *
     * @return `false` when a recording or an import is in progress.
     */
    fun importRecording(documentId: String, controller: DrawViewController, file: ChosenFile): Boolean {
        if (isBusy) return false
        val startedAt = file.lastModified ?: currentTimeMillis()
        val lecture = newLecture(documentId, library.newLectureDirectory(documentId, startedAt), record = null, startedAt = startedAt).apply {
            documentTitle = controller.document.title
            status = LectureStatus.IMPORTING
        }
        val notebook = editorNotebooks[documentId] ?: ControllerNotebookSource(controller).also { editorNotebooks[documentId] = it }
        importing = lecture
        importError = null
        shownLectures[documentId] = lecture
        isPanelOpen = true
        scope.launch {
            try {
                // The course context first: it helps the transcription
                if (contexts[documentId] == null) writeContextNow(documentId, notebook, onlyIfMissing = true)
                val pages = try {
                    notebook.allPages()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    emptyList()
                }
                val pageIds = withContext(stateContext) { controller.document.pages.map { it.id } }
                val settings = settingsStore.settings
                val transcriber = if (settings.sendsAudio) null else transcriberFactory(settings)
                LectureImporter(lecture, settingsStore, transcriber).run(file.path, pages, pageIds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Not an audio file: no lecture
                library.delete(lecture.directory)
                withContext(stateContext) {
                    importError = e.message ?: e::class.simpleName
                    if (shownLectures[documentId] === lecture) shownLectures.remove(documentId)
                }
            } finally {
                withContext(NonCancellable + stateContext) { importing = null }
                refresh(documentId)
            }
        }
        return true
    }

    /**
     * Writes what the models write for [lecture] (finished), with [provider]: its review, and
     * before it its transcript when it has none (recorded on a device without the models: the
     * device that recorded it gets them too).
     *
     * @return `false` when a recording or an import is in progress, or the models are not here.
     */
    fun generate(lecture: Lecture, provider: ModelProvider): Boolean {
        if (!canGenerate || !lecture.canGenerateReview) return false
        if (lecture.transcript.isNotEmpty() || !lecture.hasAudio) {
            lecture.generateFinalReview(provider)
            return true
        }
        if (isBusy) return false
        importing = lecture
        importError = null
        scope.launch {
            try {
                val settings = settingsStore.settings
                val transcriber = if (settings.sendsAudio) null else transcriberFactory(settings)
                LectureImporter(lecture, settingsStore, transcriber).transcribeAndReview(provider)
            } finally {
                withContext(NonCancellable + stateContext) { importing = null }
                refresh(lecture.documentId)
            }
        }
        return true
    }

    /** The audio of [lecture] saved in [target]: as m4a where the platform encodes it, otherwise as WAV. */
    fun exportAudio(lecture: Lecture, target: String) {
        exportResult = null
        scope.launch {
            val result = try {
                val saved = withContext(Dispatchers.IO) { exportRecording(lecture.recording, target) }
                ExportResult(saved.substringAfterLast('/'), failure = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ExportResult(target.substringAfterLast('/'), failure = e.message ?: e::class.simpleName.orEmpty())
            }
            withContext(stateContext) { exportResult = result }
        }
    }

    /** The extension of the exported audio: m4a where the platform encodes it. */
    val exportExtension: String get() = if (canEncodeM4a()) "m4a" else "wav"

    fun clearImportAndExportMessages() {
        importError = null
        exportResult = null
    }

    /**
     * Deletes the lecture [summary] (audio included), here and on the devices working on the
     * notebook; not the one being recorded (here or elsewhere).
     */
    fun delete(documentId: String, summary: LectureSummary) {
        if (activeRecorder?.lecture?.directory == summary.directory || importing?.directory == summary.directory || summary.activeElsewhere) return
        if (shownLectures[documentId]?.directory == summary.directory) {
            shownLectures[documentId]?.stopPlayback()
            shownLectures.remove(documentId)
        }
        scope.launch {
            // Not merged meanwhile with the one of another device
            library.records.withLock { library.delete(summary.directory) }
            refreshNow(documentId)
        }
    }

    fun clearStartError() {
        startError = null
    }

    /** Ends the recording right away (the application is exiting); see [LectureRecorder.finishNow]. */
    suspend fun finishRecording() {
        activeRecorder?.finishNow()
    }
}

/**
 * The lecture assistants of the process: the application ends their recordings before exiting
 * ([finishAll]), and may ask a confirmation while one is recording ([isRecording]).
 */
object LectureRecordings {
    private val assistants = mutableListOf<LectureAssistant>()

    internal fun register(assistant: LectureAssistant) {
        assistants.add(assistant)
    }

    /** A lecture is being recorded, reviewed or imported. */
    val isRecording: Boolean get() = assistants.any { it.isBusy }

    /** Saves the recordings in progress (the final reviews are left to be written later). */
    suspend fun finishAll() {
        assistants.forEach { it.finishRecording() }
    }
}

/** An audio exported: the name of the file, and why it failed, if it did. */
class ExportResult(val fileName: String, val failure: String?)
