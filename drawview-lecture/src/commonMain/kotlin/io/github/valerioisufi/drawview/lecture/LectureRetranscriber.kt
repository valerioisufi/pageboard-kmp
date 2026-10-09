package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.transcription.Transcriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Transcribes a [lecture] already recorded again, from its recording, with [transcriber] (another
 * model): the new transcript replaces the old one when it is complete, the old one stays when it
 * fails. The notes and the review stay as they are.
 */
internal class LectureRetranscriber(
    private val lecture: Lecture,
    private val settingsStore: AssistantSettingsStore,
    private val transcriber: Transcriber
) {
    /** Runs the transcription; the lecture is [LectureStatus.IMPORTING] already. */
    suspend fun run() {
        val settings = settingsStore.settings
        val segments = mutableListOf<TranscriptSegment>()
        var tokens = TokenUsage()
        try {
            val hints = { LecturePrompts.speechHints(settings, lecture.courseContext(), visibleSlides = emptyList()) }
            RecordingTranscriber(lecture.recording, transcriber, hints).transcribe(lecture.elapsedMs) { chunk, progress ->
                chunk.segment?.let { segments.add(it) }
                chunk.usage?.let { tokens += it }
                lecture.setState { importProgress = progress }
            }
            lecture.setState {
                transcript.clear()
                transcript.addAll(segments)
                problem = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lecture.setState { problem = transcriptionProblem(e) }
        } finally {
            withContext(NonCancellable) {
                transcriber.close()
                lecture.setState {
                    usage += tokens
                    status = LectureStatus.FINISHED
                    importStep = null
                    importProgress = -1f
                }
                lecture.persist()
            }
        }
    }
}
