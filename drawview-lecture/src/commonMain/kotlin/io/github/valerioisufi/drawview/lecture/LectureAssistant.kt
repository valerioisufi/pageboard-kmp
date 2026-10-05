package io.github.valerioisufi.drawview.lecture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.util.currentTimeMillis
import io.github.valerioisufi.drawview.lecture.audio.AudioCapture
import io.github.valerioisufi.drawview.lecture.audio.AudioPlayer
import io.github.valerioisufi.drawview.lecture.audio.createAudioCapture
import io.github.valerioisufi.drawview.lecture.audio.createAudioPlayer
import io.github.valerioisufi.drawview.lecture.gemini.GeminiClient
import io.github.valerioisufi.drawview.lecture.handwriting.HandwritingReader
import io.github.valerioisufi.drawview.lecture.local.OllamaClient
import io.github.valerioisufi.drawview.lecture.model.GeminiBackend
import io.github.valerioisufi.drawview.lecture.model.ModelBackends
import io.github.valerioisufi.drawview.lecture.model.OllamaBackend
import io.github.valerioisufi.drawview.lecture.transcription.Transcriber
import io.github.valerioisufi.drawview.lecture.transcription.WhisperTranscriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 */
class LectureAssistant(
    val settingsStore: AssistantSettingsStore = AssistantSettingsStore(),
    private val client: GeminiClient = GeminiClient(),
    private val localClient: OllamaClient = OllamaClient(),
    private val captureFactory: () -> AudioCapture? = ::createAudioCapture,
    private val player: AudioPlayer? = createAudioPlayer(),
    val library: LectureLibrary = LectureLibrary(),
    private val stateContext: CoroutineContext = Dispatchers.Main,
    private val transcriberFactory: (AssistantSettings) -> Transcriber? = { WhisperTranscriber.of(it) }
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

    /** Why the last recording could not start, if it could not. */
    var startError: String? by mutableStateOf(null)
        private set

    /** Whether the platform can record (the desktop, for now). */
    val isRecordingSupported: Boolean by lazy { captureFactory() != null }

    /** The recording not finished yet (recording or reviewing), if any. */
    val activeRecorder: LectureRecorder? get() = recorder?.takeIf { it.lecture.status != LectureStatus.FINISHED }

    val isRecording: Boolean get() = recorder?.isRecording == true

    private val lectureLists = mutableStateMapOf<Int, List<LectureSummary>>()
    private val shownLectures = mutableStateMapOf<Int, Lecture>()
    private val contexts = mutableStateMapOf<Int, CourseContext>()
    private val writingContexts = mutableStateMapOf<Int, Boolean>()
    private val editorNotebooks = mutableMapOf<Int, NotebookSource>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        LectureRecordings.register(this)
    }

    /** The lectures of [documentId] (loaded by [refresh]), the most recent first. */
    fun lecturesOf(documentId: Int): List<LectureSummary> = lectureLists[documentId].orEmpty()

    /** The lecture shown in the panel of [documentId]: the one being recorded, or the last one. */
    fun shownLecture(documentId: Int): Lecture? = shownLectures[documentId]

    /** The recorder of [documentId], if it is the one being recorded or reviewed. */
    fun recorderOf(documentId: Int): LectureRecorder? = activeRecorder?.takeIf { it.lecture.documentId == documentId }

    /**
     * The editor of [documentId] is shown: the recording of the document (if any) sees its pages
     * again, and its lectures are loaded.
     */
    fun onEditorShown(documentId: Int, notebook: NotebookSource) {
        editorNotebooks[documentId] = notebook
        recorderOf(documentId)?.attachNotebook(notebook)
        refresh(documentId)
    }

    /** The editor of [documentId] is closed: the recording goes on, without its pages. */
    fun onEditorHidden(documentId: Int, notebook: NotebookSource) {
        if (editorNotebooks[documentId] === notebook) editorNotebooks.remove(documentId)
        recorderOf(documentId)?.detachNotebook(notebook)
        placingNote = null
        shownLectures[documentId]?.stopPlayback()
    }

    /** The course context of [documentId] (loaded by [refresh]), if any. */
    fun contextOf(documentId: Int): CourseContext? = contexts[documentId]

    /** The course context of [documentId] is being written. */
    fun isWritingContext(documentId: Int): Boolean = writingContexts[documentId] == true

    /** Whether the context of [documentId] can be written: its notebook is open. */
    fun canWriteContext(documentId: Int): Boolean = documentId in editorNotebooks

    /** The student changed the course context of [documentId]: it is not replaced automatically. */
    fun editContext(documentId: Int, text: String) {
        val context = CourseContext(text.trim(), edited = true, generatedAt = contexts[documentId]?.generatedAt ?: 0L)
        contexts[documentId] = context
        scope.launch { library.saveContext(documentId, context) }
    }

    /**
     * Writes the course context of [documentId] from its notebook (open), with the model of the
     * live suggestions; [onlyIfMissing]: not when the notebook has one already.
     */
    fun writeContext(documentId: Int, onlyIfMissing: Boolean = false) {
        val notebook = editorNotebooks[documentId] ?: return
        if (isWritingContext(documentId)) return
        writingContexts[documentId] = true
        scope.launch {
            val text = try {
                if (onlyIfMissing && library.loadContext(documentId) != null) null else composeContext(documentId, notebook)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                println("DrawView: cannot write the course context: ${e.message}")
                null
            }
            val context = text?.takeIf { it.isNotBlank() }?.let { CourseContext(it, edited = false, generatedAt = currentTimeMillis()) }
            if (context != null) library.saveContext(documentId, context)
            withContext(stateContext) {
                writingContexts.remove(documentId)
                if (context != null) contexts[documentId] = context
            }
        }
    }

    /** The course context written by the model of the live suggestions, from the whole notebook. */
    private suspend fun composeContext(documentId: Int, notebook: NotebookSource): String? {
        val task = settingsStore.settings.task(LectureTask.LIVE_NOTES)
        if (task.provider == ModelProvider.GEMINI && !settingsStore.hasApiKey) return null
        val pages = notebook.allPages()
        if (pages.isEmpty()) return null
        // With the handwriting already transcribed
        val reader = HandwritingReader(library.documentDirectory(documentId) + "/handwriting.json", backends)
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
    fun refresh(documentId: Int) {
        scope.launch {
            val lectures = library.list(documentId)
            val context = library.loadContext(documentId)
            withContext(stateContext) {
                if (context != null && contexts[documentId] == null) contexts[documentId] = context
                lectureLists[documentId] = lectures
                if (shownLectures[documentId] == null) lectures.firstOrNull()?.let { show(documentId, it) }
            }
        }
    }

    /** Shows the lecture [summary] in the panel of [documentId]. */
    fun show(documentId: Int, summary: LectureSummary) {
        val active = recorder?.lecture
        if (active != null && active.directory == summary.directory) {
            shownLectures[documentId] = active
            return
        }
        if (shownLectures[documentId]?.directory == summary.directory) return
        scope.launch {
            val record = library.load(summary.directory) ?: return@launch
            val lecture = Lecture(documentId, summary.directory, record, settingsStore, backends, player, stateContext) { contextOf(documentId)?.text.orEmpty() }
            withContext(stateContext) { shownLectures[documentId] = lecture }
        }
    }

    /**
     * Starts recording a lecture of the document edited by [controller].
     *
     * @return `false` when another lecture is being recorded, or the microphone cannot be opened
     *   ([startError]).
     */
    fun start(documentId: Int, controller: DrawViewController): Boolean {
        if (activeRecorder != null) return false
        val capture = captureFactory() ?: return false
        val startedAt = currentTimeMillis()
        val lecture = Lecture(
            documentId = documentId,
            directory = library.newLectureDirectory(documentId, startedAt),
            record = null,
            settingsStore = settingsStore,
            backends = backends,
            player = player,
            stateContext = stateContext,
            courseContext = { contextOf(documentId)?.text.orEmpty() }
        ).apply { documentTitle = controller.document.title }
        val notebook = editorNotebooks[documentId] ?: ControllerNotebookSource(controller).also { editorNotebooks[documentId] = it }
        val settings = settingsStore.settings
        val transcriber = if (settings.sendsAudio) null else transcriberFactory(settings)
        val newRecorder = LectureRecorder(lecture, settingsStore, capture, notebook, transcriber, onFinished = { refresh(documentId) })
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

    /** Deletes the lecture [summary] (audio included); not the one being recorded. */
    fun delete(documentId: Int, summary: LectureSummary) {
        if (activeRecorder?.lecture?.directory == summary.directory) return
        if (shownLectures[documentId]?.directory == summary.directory) {
            shownLectures[documentId]?.stopPlayback()
            shownLectures.remove(documentId)
        }
        scope.launch {
            library.delete(summary.directory)
            refresh(documentId)
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

    /** A lecture is being recorded or reviewed. */
    val isRecording: Boolean get() = assistants.any { it.activeRecorder != null }

    /** Saves the recordings in progress (the final reviews are left to be written later). */
    suspend fun finishAll() {
        assistants.forEach { it.finishRecording() }
    }
}
