package io.github.valerioisufi.pageboard.lecture

import io.github.valerioisufi.pageboard.lecture.audio.AudioChunk
import io.github.valerioisufi.pageboard.lecture.audio.Wav
import io.github.valerioisufi.pageboard.lecture.audio.samplesToMs
import io.github.valerioisufi.pageboard.lecture.transcription.SpeechHints
import io.github.valerioisufi.pageboard.lecture.transcription.Transcriber
import kotlinx.coroutines.CancellationException

/**
 * The transcript of every update by [transcriber] (whisper.cpp on this computer), added to the
 * [lecture] right away (before the suggestions), one segment per update.
 *
 * @param hints what helps the recognition now (the slides on screen change).
 */
internal class ChunkSpeech(
    private val lecture: Lecture,
    private val transcriber: Transcriber,
    private val hints: () -> SpeechHints
) {
    /** The transcript of [chunks] (consecutive), or `null` when there is nothing to send. */
    suspend fun speechOf(chunks: List<AudioChunk>): RequestTranscript? {
        val transcript = try {
            transcribeChunks(transcriber, chunks, hints()) ?: return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            lecture.setState { problem = transcriptionProblem(e) }
            return null
        }
        lecture.setState {
            transcript.usage?.let { usage += it }
            transcript.merged?.let { this.transcript.add(it) }
        }
        return RequestTranscript(emptyList(), transcript.segments, transcript.audio.newStartMs, 0L, transcript.audio.endMs)
    }

    /** The recording is over: frees the speech recognition. */
    suspend fun close() = transcriber.close()
}

/**
 * The transcript of the new audio of some chunks: [segments] with the times of the recording, the
 * part repeating the previous chunk dropped.
 */
internal class ChunkTranscript(val audio: RequestAudio, val segments: List<TranscriptSegment>, val usage: TokenUsage?) {
    /** The segments as one, as the lecture keeps them (one per update). */
    val merged: TranscriptSegment?
        get() = if (segments.isEmpty()) null else TranscriptSegment(segments.first().startMs, segments.last().endMs, segments.joinToString(" ") { it.text.trim() })

    /** The segments joined in segments of about [spanMs] (the transcript of a whole recording). */
    fun grouped(spanMs: Long = GROUP_SPAN_MS): List<TranscriptSegment> {
        val groups = mutableListOf<TranscriptSegment>()
        for (segment in segments) {
            val last = groups.lastOrNull()
            groups += if (last != null && segment.endMs - last.startMs <= spanMs) {
                // Not removeLast(): on Android it is a member of API 35 (the minimum is 29)
                groups.removeAt(groups.lastIndex)
                last.copy(endMs = segment.endMs, text = last.text.trim() + " " + segment.text.trim())
            } else {
                segment.copy(text = segment.text.trim())
            }
        }
        return groups
    }

    private companion object {
        const val GROUP_SPAN_MS = 40_000L
    }
}

/**
 * Transcribes the new audio of [chunks] (consecutive) with [transcriber], with the leading overlap
 * as context when it uses it; `null` when there is no audio.
 */
internal suspend fun transcribeChunks(transcriber: Transcriber, chunks: List<AudioChunk>, hints: SpeechHints): ChunkTranscript? {
    val audio = requestAudio(chunks, contextMs = null, withContext = transcriber.usesLeadingContext) ?: return null
    val transcription = transcriber.transcribe(audio.wav, hints)
    val segments = transcription.segments
        .map { it.copy(startMs = it.startMs + audio.startMs, endMs = it.endMs + audio.startMs) }
        .filter { it.endMs > audio.newStartMs + OVERLAP_TOLERANCE_MS && it.text.isNotBlank() }
    return ChunkTranscript(audio, segments, transcription.usage)
}

/** A segment ending this little after the overlap is repeated. */
private const val OVERLAP_TOLERANCE_MS = 300L

/** The problem of a failed transcription: the transcription unavailable, unless it is another problem. */
internal fun transcriptionProblem(e: Throwable): LectureProblem =
    Lecture.problemOf(e).takeUnless { it is LectureProblem.Failed } ?: LectureProblem.TranscriptionUnavailable(e.message ?: e::class.simpleName.orEmpty())

/**
 * The audio of [chunks] (consecutive) to transcribe, the most recent within [MAX_REQUEST_AUDIO_MS]. [contextMs]:
 * leading audio already analyzed; `null` for the overlap of the first chunk with the previous one,
 * dropped when not [withContext].
 */
public fun requestAudio(chunks: List<AudioChunk>, contextMs: Long?, withContext: Boolean = true): RequestAudio? {
    if (chunks.isEmpty()) return null
    // The most recent audio, within the budget of a piece
    var budgetMs = MAX_REQUEST_AUDIO_MS
    val selected = ArrayDeque<AudioChunk>()
    for (chunk in chunks.asReversed()) {
        budgetMs -= chunk.endMs - chunk.newStartMs
        if (budgetMs < 0 && selected.isNotEmpty()) break
        selected.addFirst(chunk)
    }
    val start = if (withContext) selected.first().startSample else selected.first().newStartSample
    var end = start
    val pieces = mutableListOf<ShortArray>()
    for (chunk in selected) {
        val skip = (end - chunk.startSample).coerceIn(0L, chunk.samples.size.toLong()).toInt()
        if (skip < chunk.samples.size) {
            pieces.add(chunk.samples.copyOfRange(skip, chunk.samples.size))
            end = chunk.endSample
        }
    }
    val samples = ShortArray(pieces.sumOf { it.size })
    var offset = 0
    for (piece in pieces) {
        piece.copyInto(samples, offset)
        offset += piece.size
    }
    return RequestAudio(
        wav = Wav.encode(samples),
        startMs = samplesToMs(start),
        contextMs = contextMs ?: samplesToMs(selected.first().newStartSample - start),
        endMs = samplesToMs(end)
    )
}

/** Audio of a piece transcribed at once. */
internal const val MAX_REQUEST_AUDIO_MS = 6 * 60_000L
