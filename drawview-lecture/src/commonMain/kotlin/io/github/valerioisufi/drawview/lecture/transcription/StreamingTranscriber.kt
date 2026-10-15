package io.github.valerioisufi.drawview.lecture.transcription

import io.github.valerioisufi.drawview.lecture.TokenUsage
import io.github.valerioisufi.drawview.lecture.TranscriptSegment
import kotlinx.coroutines.CoroutineScope

/** Transcribes the audio of a lecture while it is recorded: the words arrive while they are said. */
internal interface StreamingTranscriber {
    /** What the transcription gives, from its coroutine. */
    interface Listener {
        /** An utterance, final, with the times of the recording. */
        suspend fun onFinal(segment: TranscriptSegment)

        /** The utterance being said, still changing (empty: none). */
        suspend fun onInterim(text: String)

        /** The tokens billed. */
        suspend fun onUsage(usage: TokenUsage)

        /**
         * A problem: temporary, tried again in [retryInSeconds], or not ([retryInSeconds] 0: the
         * transcription stops); `null` when it is solved.
         */
        suspend fun onProblem(error: Exception?, retryInSeconds: Int = 0)
    }

    /**
     * Starts transcribing in [scope].
     *
     * @param hints what helps the recognition, read at every new session (the slides on screen change).
     */
    fun start(scope: CoroutineScope, hints: () -> SpeechHints, listener: Listener)

    /** The next block of the recording (16-bit mono at 16 kHz), in order; it never waits. */
    fun push(samples: ShortArray)

    /** The audio is over: what was said becomes final. */
    fun endOfAudio()

    /** Waits, at most [timeoutMs], until what was said up to [untilMs] of the recording is final. */
    suspend fun awaitFinal(untilMs: Long, timeoutMs: Long)

    /** Stops the transcription and closes its connection. */
    suspend fun close()
}
