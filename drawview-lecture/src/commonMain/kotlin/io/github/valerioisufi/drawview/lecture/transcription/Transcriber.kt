package io.github.valerioisufi.drawview.lecture.transcription

import io.github.valerioisufi.drawview.lecture.TranscriptSegment

/** Transcribes the audio of the lecture on this computer. */
interface Transcriber {
    /**
     * Transcribes [wav] (16-bit mono PCM at 16 kHz).
     *
     * @param language ISO code of the language spoken ("it", "en", ...) or "auto".
     * @param prompt the course and its terminology, to recognize its words (may be empty).
     * @return the segments, with times from the start of [wav].
     * @throws TranscriptionException when the transcription cannot be done.
     */
    suspend fun transcribe(wav: ByteArray, language: String, prompt: String = ""): List<TranscriptSegment>

    /** Frees the resources (e.g. stops the transcription server). */
    suspend fun close() {}
}

class TranscriptionException(message: String, cause: Throwable? = null) : Exception(message, cause)
