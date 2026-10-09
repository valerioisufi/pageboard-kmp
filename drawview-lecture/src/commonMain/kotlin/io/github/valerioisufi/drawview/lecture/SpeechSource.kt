package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.audio.AudioChunk
import io.github.valerioisufi.drawview.lecture.audio.Wav
import io.github.valerioisufi.drawview.lecture.audio.samplesToMs
import io.github.valerioisufi.drawview.lecture.transcription.SpeechHints
import io.github.valerioisufi.drawview.lecture.transcription.Transcriber
import kotlinx.coroutines.CancellationException

/**
 * What was said in an update of a recording, for the request of the live suggestions: its audio
 * (the model transcribes it), or its transcript, made a chunk at a time or while it is said.
 */
internal sealed interface SpeechSource {
    /** A block of the microphone, in order (only the live transcription uses it). */
    fun onAudio(samples: ShortArray) {}

    /** The speech of [chunks] (consecutive), or `null` when there is nothing to send. */
    suspend fun speechOf(chunks: List<AudioChunk>): RequestSpeech?

    /** The recording is over: frees the speech recognition. */
    suspend fun close() {}
}

/** The audio itself: the model of the suggestions transcribes it in the same request. */
internal object AudioSpeech : SpeechSource {
    override suspend fun speechOf(chunks: List<AudioChunk>): RequestSpeech? = requestAudio(chunks, contextMs = null)
}

/**
 * The transcript of every update by [transcriber], added to the [lecture] right away (before the
 * suggestions), one segment per update.
 *
 * @param hints what helps the recognition now (the slides on screen change).
 */
internal class ChunkSpeech(
    private val lecture: Lecture,
    private val transcriber: Transcriber,
    private val hints: () -> SpeechHints
) : SpeechSource {
    override suspend fun speechOf(chunks: List<AudioChunk>): RequestSpeech? {
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

    override suspend fun close() = transcriber.close()
}

/**
 * The transcript of the new audio of some chunks: [segments] with the times of the recording, the
 * part repeating the previous chunk dropped.
 */
internal class ChunkTranscript(val audio: RequestAudio, val segments: List<TranscriptSegment>, val usage: TokenUsage?) {
    /** The segments as one, as the lecture keeps them (one per update). */
    val merged: TranscriptSegment?
        get() = if (segments.isEmpty()) null else TranscriptSegment(segments.first().startMs, segments.last().endMs, segments.joinToString(" ") { it.text.trim() })
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

/** The problem of a failed transcription: the one of the online model, or the transcription unavailable. */
internal fun transcriptionProblem(e: Throwable): LectureProblem =
    Lecture.problemOf(e).takeUnless { it is LectureProblem.Failed } ?: LectureProblem.TranscriptionUnavailable(e.message ?: e::class.simpleName.orEmpty())

/**
 * The audio of [chunks] (consecutive), the most recent within [MAX_REQUEST_AUDIO_MS]. [contextMs]:
 * leading audio already analyzed; `null` for the overlap of the first chunk with the previous one,
 * dropped when not [withContext].
 */
internal fun requestAudio(chunks: List<AudioChunk>, contextMs: Long?, withContext: Boolean = true): RequestAudio? {
    if (chunks.isEmpty()) return null
    // The most recent audio, within the budget of a request (inline data is limited)
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

/** Audio of a request (inline data: the whole request must stay under 20 MB). */
internal const val MAX_REQUEST_AUDIO_MS = 6 * 60_000L
