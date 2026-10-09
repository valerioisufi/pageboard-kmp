package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.audio.AudioChunk
import io.github.valerioisufi.drawview.lecture.audio.Wav
import io.github.valerioisufi.drawview.lecture.audio.samplesToMs
import io.github.valerioisufi.drawview.lecture.transcription.SpeechHints
import io.github.valerioisufi.drawview.lecture.transcription.StreamingTranscriber
import io.github.valerioisufi.drawview.lecture.transcription.Transcriber
import kotlinx.coroutines.CancellationException

/**
 * What was said in an update of a recording, for the request of the live suggestions: its audio
 * (the model transcribes it), or its transcript, made a chunk at a time ([ChunkSpeech]) or while it
 * is said ([StreamSpeech]).
 */
internal sealed interface SpeechSource {
    /** The recording starts. */
    fun start() {}

    /** A block of the microphone, in order (only the live transcription uses it). */
    fun onAudio(samples: ShortArray) {}

    /** The microphone stopped: what was said becomes final. */
    fun onAudioEnd() {}

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
 * The transcript made while the lecturer speaks by [stream]: every utterance is added to the
 * [lecture] when final, the one being said is shown ([Lecture.interimTranscript]); an update waits
 * a little for the words said until its end, and takes the utterances not taken yet.
 */
internal class StreamSpeech(
    private val lecture: Lecture,
    private val stream: StreamingTranscriber,
    private val hints: () -> SpeechHints
) : SpeechSource {
    /** Utterances of the transcript taken by the updates (the analysis coroutine only). */
    private var taken = 0

    override fun start() = stream.start(lecture.scope, hints, listener)

    override fun onAudio(samples: ShortArray) = stream.push(samples)

    override fun onAudioEnd() = stream.endOfAudio()

    override suspend fun speechOf(chunks: List<AudioChunk>): RequestSpeech? {
        if (chunks.isEmpty()) return null
        val endMs = chunks.last().endMs
        stream.awaitFinal(endMs, AWAIT_FINAL_MS)
        val segments = lecture.setStateResult {
            transcript.drop(taken).takeWhile { it.startMs < endMs }.also { taken += it.size }
        }
        if (segments.isEmpty()) return null
        val startMs = minOf(chunks.first().newStartMs, segments.first().startMs)
        return RequestTranscript(emptyList(), segments, startMs, 0L, maxOf(endMs, segments.last().endMs))
    }

    override suspend fun close() = stream.close()

    private val listener = object : StreamingTranscriber.Listener {
        override suspend fun onFinal(segment: TranscriptSegment) = lecture.setState {
            transcript.add(segment)
            interimTranscript = ""
        }

        override suspend fun onInterim(text: String) = lecture.setState { interimTranscript = text }

        override suspend fun onUsage(usage: TokenUsage) = lecture.setState { this.usage += usage }

        override suspend fun onProblem(error: Exception?, retryInSeconds: Int) = lecture.setState {
            problem = when {
                error == null -> problem.takeUnless { it is LectureProblem.Retrying || it is LectureProblem.TranscriptionUnavailable }
                retryInSeconds > 0 -> LectureProblem.Retrying(error.message ?: error::class.simpleName.orEmpty(), retryInSeconds)
                else -> transcriptionProblem(error)
            }
        }
    }

    private companion object {
        /** The final text of the last words arrives a second or two after a pause. */
        const val AWAIT_FINAL_MS = 6_000L
    }
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
                groups.removeLast()
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
