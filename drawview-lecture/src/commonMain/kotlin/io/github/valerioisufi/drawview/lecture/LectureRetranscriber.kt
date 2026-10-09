package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.transcription.Transcriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Transcribes a [lecture] already recorded again, from its recording, with [transcriber] (another
 * model): the new transcript takes the place of the old one as it goes, and is saved at every
 * piece, so that what was transcribed (and paid) is kept when it stops (the quota of the day, the
 * network, the application closed); the old transcript stays for the rest. The notes and the
 * review stay as they are.
 */
internal class LectureRetranscriber(
    private val lecture: Lecture,
    private val settingsStore: AssistantSettingsStore,
    private val transcriber: Transcriber
) {
    /** Runs the transcription; the lecture is [LectureStatus.IMPORTING] already. */
    suspend fun run() {
        val settings = settingsStore.settings
        val old = lecture.setStateResult { transcript.toList() }
        val segments = mutableListOf<TranscriptSegment>()
        try {
            val hints = { LecturePrompts.speechHints(settings, lecture.courseContext(), visibleSlides = emptyList()) }
            RecordingTranscriber(lecture.recording, transcriber, hints).transcribe(lecture.elapsedMs) { chunk, progress ->
                segments.addAll(chunk.segments)
                lecture.setState {
                    chunk.usage?.let { usage += it }
                    importProgress = progress
                    // The new transcript up to here, the old one after
                    val rest = old.filter { (it.startMs + it.endMs) / 2 >= chunk.endMs }
                    transcript.clear()
                    transcript.addAll(segments + rest)
                }
                lecture.persist()
            }
            lecture.setState { problem = null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lecture.setState { problem = transcriptionProblem(e) }
        } finally {
            withContext(NonCancellable) {
                transcriber.close()
                lecture.setState {
                    status = LectureStatus.FINISHED
                    importStep = null
                    importProgress = -1f
                }
                lecture.persist()
            }
        }
    }
}
