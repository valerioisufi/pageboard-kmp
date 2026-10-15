package io.github.valerioisufi.drawview.lecture.transcription

import io.github.valerioisufi.drawview.lecture.AssistantSettings
import io.github.valerioisufi.drawview.lecture.LectureAnalysis
import io.github.valerioisufi.drawview.lecture.LecturePrompts
import io.github.valerioisufi.drawview.lecture.TranscriptSegment
import io.github.valerioisufi.drawview.lecture.audio.Wav
import io.github.valerioisufi.drawview.lecture.audio.samplesToMs
import io.github.valerioisufi.drawview.lecture.gemini.GeminiException
import io.github.valerioisufi.drawview.lecture.model.GeminiBackend
import kotlinx.coroutines.delay

/**
 * A general model of Gemini transcribing a piece of audio in a request of its own, with
 * instructions and the course context ([LecturePrompts.transcriptionOnlyPrompt]). It gives no
 * times: the piece is one segment.
 */
internal class GeminiPromptTranscriber(
    private val backend: GeminiBackend,
    private val model: String,
    private val settings: () -> AssistantSettings,
    private val retryDelayMs: Long = 2_000L
) : Transcriber {
    override val parallelism: Int get() = 2

    override suspend fun transcribe(wav: ByteArray, hints: SpeechHints): Transcription {
        val prompt = LecturePrompts.transcriptionOnlyPrompt(settings(), hints.courseContext, wav)
        var attempt = 0
        while (true) {
            try {
                val answer = backend.generate(prompt, model)
                val text = LectureAnalysis.parseTranscript(answer.text).trim()
                val durationMs = samplesToMs(((wav.size - Wav.HEADER_SIZE).coerceAtLeast(0) / 2).toLong())
                return Transcription(if (text.isEmpty()) emptyList() else listOf(TranscriptSegment(0, durationMs, text)), answer.usage)
            } catch (e: GeminiException) {
                attempt++
                delay(retryDelay(e, attempt, retryDelayMs) ?: throw e)
            }
        }
    }
}
