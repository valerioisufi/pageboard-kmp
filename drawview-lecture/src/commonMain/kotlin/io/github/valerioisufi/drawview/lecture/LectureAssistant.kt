package io.github.valerioisufi.drawview.lecture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.util.currentTimeMillis
import io.github.valerioisufi.drawview.lecture.audio.AudioCapture
import io.github.valerioisufi.drawview.lecture.audio.AudioPlayer
import io.github.valerioisufi.drawview.lecture.audio.createAudioCapture
import io.github.valerioisufi.drawview.lecture.audio.createAudioPlayer
import io.github.valerioisufi.drawview.lecture.gemini.GeminiClient
import io.github.valerioisufi.drawview.lecture.platform.lectureDataDirectory
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The lecture assistant of an application: one lecture recorded at a time, followed by Gemini,
 * in the editor of the recorded document ([lectureEditorExtension]).
 *
 * The recordings are saved in the [lectureDataDirectory]: `doc-<id>/<date>/` with `audio.wav` and
 * `lecture.json` (transcript, outline, suggestions, final review).
 */
class LectureAssistant(
    val settingsStore: AssistantSettingsStore = AssistantSettingsStore(),
    private val client: GeminiClient = GeminiClient(),
    private val captureFactory: () -> AudioCapture? = ::createAudioCapture,
    private val player: AudioPlayer? = createAudioPlayer()
) {
    /** The last lecture (being recorded, finalized or finished). */
    var session: LectureSession? by mutableStateOf(null)
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

    val isRecording: Boolean get() = session?.status == LectureStatus.RECORDING

    /** The lecture of the document [documentId], if it is the last one. */
    fun sessionFor(documentId: Int): LectureSession? = session?.takeIf { it.documentId == documentId }

    /**
     * Starts recording the lecture of the document edited by [controller].
     *
     * @return `false` when the microphone cannot be opened ([startError]) or a lecture is already
     *   being recorded.
     */
    fun start(documentId: Int, controller: DrawViewController): Boolean {
        if (isRecording) return false
        val capture = captureFactory() ?: return false
        val directory = "${lectureDataDirectory()}/doc-$documentId/${timestampName(currentTimeMillis())}"
        val newSession = LectureSession(
            documentId = documentId,
            directory = directory,
            settingsStore = settingsStore,
            notebook = ControllerNotebookSource(controller),
            client = client,
            capture = capture,
            player = player
        )
        return try {
            newSession.start()
            startError = null
            session = newSession
            isPanelOpen = true
            true
        } catch (e: Throwable) {
            startError = e.message ?: e::class.simpleName
            false
        }
    }

    /** The editor of [documentId] has been closed: its lecture stops (and is reviewed). */
    fun onEditorClosed(documentId: Int) {
        placingNote = null
        sessionFor(documentId)?.let { session ->
            session.stopPlayback()
            session.stop()
        }
    }

    fun clearStartError() {
        startError = null
    }

    @OptIn(ExperimentalTime::class)
    private fun timestampName(epochMs: Long): String {
        val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
        fun two(value: Int) = value.toString().padStart(2, '0')
        return "${t.year}-${two(t.month.ordinal + 1)}-${two(t.day)}_${two(t.hour)}-${two(t.minute)}-${two(t.second)}"
    }
}
