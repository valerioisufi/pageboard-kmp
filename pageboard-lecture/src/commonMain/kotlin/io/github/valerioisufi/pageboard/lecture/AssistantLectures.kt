package io.github.valerioisufi.pageboard.lecture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.valerioisufi.pageboard.lecture.audio.AudioOutput
import io.github.valerioisufi.pageboard.platform.currentTimeMillis
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The lectures of the documents as a [LectureAssistant] shows them (Information Expert): their
 * lists, the one shown in the panel of each document, the course contexts, how each document shares
 * them, the documents with a lecture, and the notebooks of the editors shown.
 *
 * Its state is Compose state, changed on [stateContext].
 *
 * @param activeLectures the lectures recorded, imported or transcribed here now (shown as they are,
 *   never loaded again).
 * @param onSharingChanged the sharing of a document changed (its audio may be wanted now).
 */
internal class AssistantLectures(
    private val library: LectureLibrary,
    private val settingsStore: AssistantSettingsStore,
    private val models: AssistantModels,
    private val audioOutput: (() -> AudioOutput?)?,
    private val stateContext: CoroutineContext,
    private val scope: CoroutineScope,
    private val canGenerate: () -> Boolean,
    private val activeLectures: () -> List<Lecture>,
    private val onSharingChanged: suspend (documentId: String) -> Unit
) {
    private val lectureLists = mutableStateMapOf<String, List<LectureSummary>>()

    /** The lecture shown in the panel of every document. */
    val shown = mutableStateMapOf<String, Lecture>()

    /** The course contexts, by document. */
    val contexts = mutableStateMapOf<String, CourseContext>()
    private val writingContexts = mutableStateMapOf<String, Boolean>()

    /** The notebooks of the editors shown, by document. */
    val editorNotebooks = mutableMapOf<String, NotebookSource>()
    private val sharingSettings = mutableStateMapOf<String, LectureSharingSettings>()

    /** The writes of the sharing settings, one at a time (see [changeSharing]). */
    private val sharingWrites = Mutex()

    /** The documents with a lecture (see [LectureAssistant.documentsWithLectures]). */
    var documentsWithLectures: Set<String> by mutableStateOf(emptySet())
        private set

    init {
        scope.launch {
            val documents = withContext(Dispatchers.Default) { library.documentsWithLectures() }
            withContext(stateContext) { documentsWithLectures = documentsWithLectures + documents }
        }
    }

    fun lecturesOf(documentId: String): List<LectureSummary> = lectureLists[documentId].orEmpty()

    fun sharingOf(documentId: String): LectureSharingSettings = sharingSettings[documentId] ?: LectureSharingSettings()

    fun changeSharing(documentId: String, transform: (LectureSharingSettings) -> LectureSharingSettings) {
        val settings = transform(sharingOf(documentId))
        sharingSettings[documentId] = settings
        scope.launch {
            // One write at a time, of the settings as they are then: two changes made one after the
            // other are never saved in the other order
            sharingWrites.withLock {
                library.saveSharing(documentId, withContext(stateContext) { sharingOf(documentId) })
            }
            // Maybe wanted now (e.g. received at once from now on)
            onSharingChanged(documentId)
        }
    }

    /** Receives the audio of [lecture] (recorded on another device) now, even if received only on request. */
    fun requestAudio(lecture: Lecture) {
        changeSharing(lecture.documentId) { it.copy(requestedAudio = it.requestedAudio + lecture.id) }
    }

    // ==========================================================
    // Course contexts
    // ==========================================================

    fun isWritingContext(documentId: String): Boolean = writingContexts[documentId] == true

    fun canWriteContext(documentId: String): Boolean = canGenerate() && documentId in editorNotebooks

    fun editContext(documentId: String, text: String) {
        val previous = contexts[documentId]
        val context = CourseContext(text.trim(), edited = true, generatedAt = previous?.generatedAt ?: 0L, stamp = nextContextStamp(previous))
        contexts[documentId] = context
        scope.launch { library.saveContext(documentId, context) }
    }

    fun writeContext(documentId: String, onlyIfMissing: Boolean) {
        if (!canGenerate()) return
        val notebook = editorNotebooks[documentId] ?: return
        if (isWritingContext(documentId)) return
        writingContexts[documentId] = true
        scope.launch { writeContextNow(documentId, notebook, onlyIfMissing) }
    }

    /** Writes the course context of [documentId] from [notebook] (see [writeContext]), waiting for it. */
    suspend fun writeContextNow(documentId: String, notebook: NotebookSource, onlyIfMissing: Boolean) {
        withContext(stateContext) { writingContexts[documentId] = true }
        val text = try {
            if (onlyIfMissing && library.loadContext(documentId) != null) null else models.composeContext(documentId, notebook)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("Pageboard: cannot write the course context: ${e.message}")
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

    // ==========================================================
    // The lectures listed and shown
    // ==========================================================

    fun refresh(documentId: String) {
        scope.launch { refreshNow(documentId) }
    }

    suspend fun refreshNow(documentId: String) {
        val lectures = library.list(documentId)
        val context = library.loadContext(documentId)
        val sharing = library.sharingOf(documentId)
        withContext(stateContext) {
            sharingSettings[documentId] = sharing
            if (context != null && contexts[documentId] == null) contexts[documentId] = context
            lectureLists[documentId] = lectures
            documentsWithLectures = if (lectures.isEmpty()) documentsWithLectures - documentId else documentsWithLectures + documentId
            if (shown[documentId] == null) lectures.firstOrNull()?.let { show(documentId, it) }
        }
    }

    fun show(documentId: String, summary: LectureSummary) {
        val active = activeLectures().firstOrNull { it.directory == summary.directory }
        if (active != null) {
            shown[documentId] = active
            return
        }
        if (shown[documentId]?.directory == summary.directory) return
        scope.launch {
            // Shown before what another device sends is merged into it (see LectureAttachments)
            library.records.withLock {
                val record = library.load(summary.directory) ?: return@launch
                val lecture = newLecture(documentId, summary.directory, record)
                withContext(stateContext) { shown[documentId] = lecture }
            }
        }
    }

    /** The lecture of [documentId] in [directory], shown (loaded if it is not). */
    suspend fun lectureIn(documentId: String, directory: String): Lecture? {
        withContext(stateContext) { shown[documentId]?.takeIf { it.directory == directory } }?.let { return it }
        return library.records.withLock {
            val record = library.load(directory) ?: return null
            newLecture(documentId, directory, record).also { lecture -> withContext(stateContext) { shown[documentId] = lecture } }
        }
    }

    /** A lecture of [documentId] in [directory]: [record] when saved already. */
    fun newLecture(documentId: String, directory: String, record: LectureRecord?, startedAt: Long? = null) = Lecture(
        documentId = documentId,
        directory = directory,
        record = record,
        settingsStore = settingsStore,
        backends = models.backends,
        audioOutput = audioOutput,
        stateContext = stateContext,
        courseContext = { contexts[documentId]?.text.orEmpty() },
        startedAt = startedAt,
        generatesHere = canGenerate,
        device = library.deviceId,
        deviceName = library.deviceName,
        recordsLock = library.records,
        onChanged = { library.changed(documentId, it) }
    )
}
