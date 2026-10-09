package io.github.valerioisufi.drawview.lecture.transcription

import io.github.valerioisufi.drawview.lecture.TranscriptSegment
import io.github.valerioisufi.drawview.lecture.audio.Wav
import io.github.valerioisufi.drawview.lecture.audio.samplesToMs
import io.github.valerioisufi.drawview.lecture.gemini.AudioTranscriptionConfig
import io.github.valerioisufi.drawview.lecture.gemini.GeminiException
import io.github.valerioisufi.drawview.lecture.model.GeminiBackend
import kotlinx.coroutines.delay

/**
 * A speech model of Gemini (Gemini 3.5 Transcribe) transcribing a piece of audio in a request:
 * the language spoken and the terms of the course help it, the text comes clean ([MODE]). It gives
 * no times: the piece is one segment.
 */
class GeminiSpeechTranscriber(
    private val backend: GeminiBackend,
    private val model: String,
    private val retryDelayMs: Long = 2_000L
) : Transcriber {
    override val parallelism: Int get() = PARALLEL_REQUESTS

    override suspend fun transcribe(wav: ByteArray, hints: SpeechHints): Transcription {
        var locales = hints.locales
        var attempt = 0
        while (true) {
            try {
                val config = AudioTranscriptionConfig(
                    languageCodes = locales,
                    customVocabulary = hints.vocabulary.take(MAX_VOCABULARY).ifEmpty { null },
                    mode = MODE
                )
                val answer = backend.transcribe(wav, model, config)
                val text = answer.text.trim()
                val durationMs = samplesToMs(((wav.size - Wav.HEADER_SIZE).coerceAtLeast(0) / 2).toLong())
                return Transcription(if (text.isEmpty()) emptyList() else listOf(TranscriptSegment(0, durationMs, text)), answer.usage)
            } catch (e: GeminiException) {
                // A language the model does not know: recognized instead
                if (e.statusCode == 400 && locales.isNotEmpty() && e.message.orEmpty().contains("language", ignoreCase = true)) {
                    locales = emptyList()
                    continue
                }
                attempt++
                if (!e.isRetryable || attempt >= MAX_ATTEMPTS) throw e
                delay((retryDelayMs shl (attempt - 1)).coerceAtMost(30_000L))
            }
        }
    }

    companion object {
        /** Without hesitations and filler words, numbers and lists formatted: text for notes. */
        const val MODE = "SMART"

        /** The vocabulary works best within 100 terms. */
        const val MAX_VOCABULARY = 100
        const val PARALLEL_REQUESTS = 4
        private const val MAX_ATTEMPTS = 4
    }
}
