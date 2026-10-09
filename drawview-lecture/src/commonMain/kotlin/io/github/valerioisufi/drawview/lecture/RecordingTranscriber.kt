package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.audio.AudioChunk
import io.github.valerioisufi.drawview.lecture.audio.RecordingFile
import io.github.valerioisufi.drawview.lecture.audio.SpeechChunker
import io.github.valerioisufi.drawview.lecture.audio.isSilent
import io.github.valerioisufi.drawview.lecture.audio.samplesToMs
import io.github.valerioisufi.drawview.lecture.transcription.SpeechHints
import io.github.valerioisufi.drawview.lecture.transcription.Transcriber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Transcribes a whole [recording] (a lecture imported, or transcribed again) with [transcriber]:
 * in chunks of about a minute cut at the pauses (with the overlap whisper uses as context), up to
 * [Transcriber.parallelism] at the same time, delivered in order.
 *
 * @param hints what helps the recognition (the course: no slide is on screen).
 */
internal class RecordingTranscriber(
    private val recording: RecordingFile,
    private val transcriber: Transcriber,
    private val hints: () -> SpeechHints
) {
    /** A chunk transcribed: its text as one segment (`null`: nothing said), its tokens, where it ends. */
    class Chunk(val segment: TranscriptSegment?, val usage: TokenUsage?, val endMs: Long)

    /**
     * Transcribes the recording of [durationMs], giving every chunk to [onChunk] in order.
     *
     * @throws Exception the error of the transcription (the chunks given so far stay valid).
     */
    suspend fun transcribe(durationMs: Long, onChunk: suspend (Chunk, progress: Float) -> Unit) = coroutineScope {
        val chunker = SpeechChunker(targetSeconds = CHUNK_SECONDS, overlapSeconds = if (transcriber.usesLeadingContext) LectureRecorder.OVERLAP_SECONDS else 0)
        val pending = ArrayDeque<Deferred<Chunk>>()

        suspend fun deliverFirst() {
            val chunk = pending.removeFirst().await()
            onChunk(chunk, if (durationMs > 0) (chunk.endMs.toFloat() / durationMs).coerceIn(0f, 1f) else 1f)
        }

        suspend fun submit(chunk: AudioChunk) {
            while (pending.size >= transcriber.parallelism.coerceAtLeast(1)) deliverFirst()
            pending.addLast(
                if (isSilent(chunk.samples, chunk.overlapSamples)) CompletableDeferred(Chunk(null, null, chunk.endMs))
                else async { transcribe(chunk) }
            )
        }

        var readMs = 0L
        while (true) {
            val block = withContext(Dispatchers.IO) { recording.readSegment(readMs, BLOCK_MS) } ?: break
            if (block.isEmpty()) break
            readMs += samplesToMs(block.size.toLong())
            for (chunk in chunker.push(block)) submit(chunk)
        }
        chunker.cutNow(minSeconds = 1f)?.let { submit(it) }
        while (pending.isNotEmpty()) deliverFirst()
    }

    private suspend fun transcribe(chunk: AudioChunk): Chunk {
        val transcript = transcribeChunks(transcriber, listOf(chunk), hints())
        return Chunk(transcript?.merged, transcript?.usage, chunk.endMs)
    }

    private companion object {
        const val CHUNK_SECONDS = 60
        const val BLOCK_MS = 10_000L
    }
}
