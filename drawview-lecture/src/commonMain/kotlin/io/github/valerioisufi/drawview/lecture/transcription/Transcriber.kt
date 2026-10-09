package io.github.valerioisufi.drawview.lecture.transcription

import io.github.valerioisufi.drawview.lecture.TokenUsage
import io.github.valerioisufi.drawview.lecture.TranscriptSegment

/**
 * What helps recognizing the words of a lecture: each model uses what it can.
 *
 * @param language ISO code of the language spoken ("it", "en", ...) or "auto".
 * @param locales the same language in BCP-47 for the speech models of Gemini (empty: recognized).
 * @param prompt the slides on screen and the course, in a few words (whisper).
 * @param vocabulary the terms of the course and of the slides (the speech models of Gemini).
 * @param courseContext the course context (a general model of Gemini).
 */
class SpeechHints(
    val language: String = "auto",
    val locales: List<String> = emptyList(),
    val prompt: String = "",
    val vocabulary: List<String> = emptyList(),
    val courseContext: String = ""
)

/**
 * The transcript of a piece of audio.
 *
 * @param segments times from the start of the audio (one segment for the models without times).
 * @param usage the tokens billed (the online models), `null` on this computer.
 */
class Transcription(val segments: List<TranscriptSegment>, val usage: TokenUsage? = null)

/** Transcribes the audio of a lecture a piece at a time. */
interface Transcriber {
    /**
     * Whether the audio before the new part is given as context: its segments are dropped by their
     * times. The models without times receive the new audio only (it is cut at a pause).
     */
    val usesLeadingContext: Boolean get() = false

    /** Pieces transcribed at the same time when a whole recording is transcribed. */
    val parallelism: Int get() = 1

    /** The length of a piece when a whole recording is transcribed (cut at a pause). */
    val pieceSeconds: Int get() = 60

    /**
     * Transcribes [wav] (16-bit mono PCM at 16 kHz).
     *
     * @throws TranscriptionException when the transcription cannot be done (or the error of the
     *   online model, e.g. a key refused).
     */
    suspend fun transcribe(wav: ByteArray, hints: SpeechHints): Transcription

    /** Frees the resources (e.g. the model loaded). */
    suspend fun close() {}
}

class TranscriptionException(message: String, cause: Throwable? = null) : Exception(message, cause)
