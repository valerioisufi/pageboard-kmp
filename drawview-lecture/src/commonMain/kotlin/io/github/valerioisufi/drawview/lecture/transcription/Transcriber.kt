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
public class SpeechHints(
    public val language: String = "auto",
    internal val locales: List<String> = emptyList(),
    internal val prompt: String = "",
    public val vocabulary: List<String> = emptyList(),
    internal val courseContext: String = ""
)

/**
 * The transcript of a piece of audio.
 *
 * @param segments times from the start of the audio (one segment for the models without times).
 * @param usage the tokens billed (the online models), `null` on this computer.
 */
public class Transcription(public val segments: List<TranscriptSegment>, public val usage: TokenUsage? = null)

/** Transcribes the audio of a lecture a piece at a time. */
public interface Transcriber {
    /**
     * Whether the audio before the new part is given as context: its segments are dropped by their
     * times. The models without times receive the new audio only (it is cut at a pause).
     */
    public val usesLeadingContext: Boolean get() = false

    /** Pieces transcribed at the same time when a whole recording is transcribed. */
    public val parallelism: Int get() = 1

    /** The length of a piece when a whole recording is transcribed (cut at a pause). */
    public val pieceSeconds: Int get() = 60

    /**
     * Transcribes [wav] (16-bit mono PCM at 16 kHz).
     *
     * @throws TranscriptionException when the transcription cannot be done (or the error of the
     *   online model, e.g. a key refused).
     */
    public suspend fun transcribe(wav: ByteArray, hints: SpeechHints): Transcription

    /** Frees the resources (e.g. the model loaded). */
    public suspend fun close() {}
}

internal class TranscriptionException(message: String, cause: Throwable? = null) : Exception(message, cause)
