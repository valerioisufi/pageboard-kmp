package io.github.valerioisufi.pageboard.lecture.transcription

import io.github.valerioisufi.pageboard.lecture.TokenUsage
import io.github.valerioisufi.pageboard.lecture.TranscriptSegment

/**
 * What helps whisper.cpp recognize the words of a lecture.
 *
 * @param language ISO code of the language spoken ("it", "en", ...) or "auto".
 * @param prompt the slides on screen and the course, in a few words.
 */
public class SpeechHints(
    public val language: String = "auto",
    internal val prompt: String = ""
)

/**
 * The transcript of a piece of audio.
 *
 * @param segments times from the start of the audio (one segment for the models without times).
 * @param usage the tokens billed, `null` on this computer.
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
     * @throws TranscriptionException when the transcription cannot be done.
     */
    public suspend fun transcribe(wav: ByteArray, hints: SpeechHints): Transcription

    /** Frees the resources (e.g. the model loaded). */
    public suspend fun close() {}
}

internal class TranscriptionException(message: String, cause: Throwable? = null) : Exception(message, cause)
