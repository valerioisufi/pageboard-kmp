package io.github.valerioisufi.pageboard.lecture

import io.github.valerioisufi.pageboard.lecture.audio.AudioChunk
import io.github.valerioisufi.pageboard.lecture.audio.LECTURE_SAMPLE_RATE
import io.github.valerioisufi.pageboard.lecture.audio.RecordingFile
import io.github.valerioisufi.pageboard.lecture.audio.SpeechChunker
import io.github.valerioisufi.pageboard.lecture.audio.isSilent
import io.github.valerioisufi.pageboard.lecture.transcription.SpeechHints
import io.github.valerioisufi.pageboard.lecture.transcription.Transcriber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Transcribes a whole [recording] (a lecture imported, or transcribed again) with [transcriber]:
 * in pieces of [Transcriber.pieceSeconds] cut at the pauses (with the overlap whisper uses as
 * context), up to [Transcriber.parallelism] at the same time, delivered in order.
 *
 * @param hints what helps the recognition (the course: no slide is on screen).
 * @param gate what makes it wait between two pieces (see [TranscriptionGate]): the pieces in flight
 *   are delivered, the transcriber is closed (its model freed, loaded again at the next piece), and
 *   it goes on from the next piece when the gate opens. Nothing transcribed is lost.
 * @param onPaused told when it starts waiting at the gate (`true`) and when it goes on (`false`).
 */
internal class RecordingTranscriber(
    private val recording: RecordingFile,
    private val transcriber: Transcriber,
    private val gate: TranscriptionGate? = null,
    private val onPaused: suspend (Boolean) -> Unit = {},
    private val hints: () -> SpeechHints
) {
    /** A piece transcribed: its text in segments of half a minute or so (none: nothing said), its tokens, where it ends. */
    class Chunk(val segments: List<TranscriptSegment>, val usage: TokenUsage?, val endMs: Long)

    /**
     * Transcribes the recording of [durationMs], giving every chunk to [onChunk] in order.
     *
     * @throws Exception the error of the transcription (the chunks given so far stay valid).
     */
    suspend fun transcribe(durationMs: Long, onChunk: suspend (Chunk, progress: Float) -> Unit) = coroutineScope {
        val chunker = SpeechChunker(
            targetSeconds = transcriber.pieceSeconds,
            maxSeconds = transcriber.pieceSeconds + MAX_EXTRA_SECONDS,
            overlapSeconds = if (transcriber.usesLeadingContext) LectureRecorder.OVERLAP_SECONDS else 0
        )
        val pending = ArrayDeque<Deferred<Chunk>>()

        suspend fun deliverFirst() {
            val chunk = pending.removeFirst().await()
            onChunk(chunk, if (durationMs > 0) (chunk.endMs.toFloat() / durationMs).coerceIn(0f, 1f) else 1f)
        }

        suspend fun submit(chunk: AudioChunk) {
            while (pending.size >= transcriber.parallelism.coerceAtLeast(1)) deliverFirst()
            if (gate?.isClosed == true) {
                while (pending.isNotEmpty()) deliverFirst()
                transcriber.close()
                onPaused(true)
                gate.awaitOpen()
                onPaused(false)
            }
            pending.addLast(
                if (isSilent(chunk.samples, chunk.overlapSamples)) CompletableDeferred(Chunk(emptyList(), null, chunk.endMs))
                else async { transcribe(chunk) }
            )
        }

        var readSamples = 0L
        while (true) {
            val block = withContext(Dispatchers.IO) { recording.readSamples(readSamples, BLOCK_SAMPLES) } ?: break
            if (block.isEmpty()) break
            readSamples += block.size
            for (chunk in chunker.push(block)) submit(chunk)
        }
        chunker.cutNow(minSeconds = 1f)?.let { submit(it) }
        while (pending.isNotEmpty()) deliverFirst()
    }

    private suspend fun transcribe(chunk: AudioChunk): Chunk {
        val transcript = transcribeChunks(transcriber, listOf(chunk), hints())
        return Chunk(transcript?.grouped().orEmpty(), transcript?.usage, chunk.endMs)
    }

    private companion object {
        /** A piece goes on until a pause at most this much after its length. */
        const val MAX_EXTRA_SECONDS = 30
        const val BLOCK_SAMPLES = 10L * LECTURE_SAMPLE_RATE
    }
}
