package io.github.valerioisufi.drawview.lecture.transcription

import io.github.valerioisufi.drawview.platform.currentTimeMillis
import io.github.valerioisufi.drawview.lecture.TranscriptSegment
import io.github.valerioisufi.drawview.lecture.audio.Wav
import io.github.valerioisufi.drawview.lecture.audio.samplesToMs
import io.github.valerioisufi.drawview.lecture.gemini.AudioTranscriptionConfig
import io.github.valerioisufi.drawview.lecture.gemini.GeminiException
import io.github.valerioisufi.drawview.lecture.model.GeminiBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A speech model of Gemini (Gemini 3.5 Transcribe) transcribing a piece of audio in a request:
 * the language spoken and the terms of the course help it, the text comes clean ([MODE]).
 *
 * The plans allow few requests a day and few tokens a minute for the models in preview (100 and
 * 10,000 in August 2026): a whole recording goes in pieces of [PIECE_SECONDS] (about 7,500
 * tokens), one at a time, and once the API said how many tokens a minute it allows, the requests
 * wait to stay within them. It gives no times: the sentences of a long piece get times in
 * proportion to their length ([timedSentences]).
 *
 * @param now the clock of the pace of the requests.
 */
class GeminiSpeechTranscriber(
    private val backend: GeminiBackend,
    private val model: String,
    private val retryDelayMs: Long = 2_000L,
    private val now: () -> Long = ::currentTimeMillis
) : Transcriber {
    override val parallelism: Int get() = 1

    override val pieceSeconds: Int get() = PIECE_SECONDS

    private val pace = Mutex()

    /** The input tokens a minute the API allows, once it said (see [GeminiException.tokensPerMinuteLimit]). */
    private var tokensPerMinute: Long? = null

    /** The requests of the last minute: when, and their tokens. */
    private val sent = ArrayDeque<Pair<Long, Long>>()

    override suspend fun transcribe(wav: ByteArray, hints: SpeechHints): Transcription {
        val durationMs = samplesToMs(((wav.size - Wav.HEADER_SIZE).coerceAtLeast(0) / 2).toLong())
        val tokens = durationMs * AUDIO_TOKENS_PER_SECOND / 1000 + 1
        var locales = hints.locales
        var attempt = 0
        while (true) {
            try {
                waitForTokens(tokens)
                val config = AudioTranscriptionConfig(
                    languageCodes = locales,
                    customVocabulary = hints.vocabulary.take(MAX_VOCABULARY).ifEmpty { null },
                    mode = MODE
                )
                val answer = backend.transcribe(wav, model, config)
                return Transcription(timedSentences(answer.text, durationMs), answer.usage)
            } catch (e: GeminiException) {
                // A language the model does not know: recognized instead
                if (e.statusCode == 400 && locales.isNotEmpty() && e.message.orEmpty().contains("language", ignoreCase = true)) {
                    locales = emptyList()
                    continue
                }
                e.tokensPerMinuteLimit?.let { limit -> pace.withLock { tokensPerMinute = limit } }
                attempt++
                delay(retryDelay(e, attempt, retryDelayMs) ?: throw e)
            }
        }
    }

    /** Waits until a request of [tokens] stays within the tokens a minute allowed, then counts it. */
    private suspend fun waitForTokens(tokens: Long) {
        while (true) {
            val waitMs = pace.withLock {
                val time = now()
                while (sent.isNotEmpty() && time - sent.first().first >= MINUTE_MS) sent.removeFirst()
                val limit = tokensPerMinute?.let { it * 95 / 100 }
                if (limit == null || sent.isEmpty() || sent.sumOf { it.second } + tokens <= limit) {
                    sent.addLast(time to tokens)
                    0L
                } else {
                    MINUTE_MS - (time - sent.first().first) + 100
                }
            }
            if (waitMs == 0L) return
            delay(waitMs)
        }
    }

    companion object {
        /** Without hesitations and filler words, numbers and lists formatted: text for notes. */
        const val MODE = "SMART"

        /** The vocabulary works best within 100 terms. */
        const val MAX_VOCABULARY = 100

        /** A piece of a recording in a request: five minutes, some 7,500 tokens. */
        const val PIECE_SECONDS = 300

        /** The tokens of the audio (measured: 1,500 a minute). */
        const val AUDIO_TOKENS_PER_SECOND = 25L

        /** A piece is split in segments of about this length (the times of the transcript). */
        private const val SEGMENT_MS = 30_000L
        private const val MINUTE_MS = 60_000L

        private val SENTENCE_END = Regex("""(?<=[.!?…])\s+""")

        /**
         * The text of a piece of [durationMs] in segments of about [SEGMENT_MS] of whole sentences,
         * with times in proportion to the length of their text (the model gives none).
         */
        internal fun timedSentences(text: String, durationMs: Long): List<TranscriptSegment> {
            val clean = text.trim()
            if (clean.isEmpty()) return emptyList()
            if (durationMs <= SEGMENT_MS * 3 / 2) return listOf(TranscriptSegment(0, durationMs, clean))
            val sentences = clean.split(SENTENCE_END).filter { it.isNotBlank() }
            val total = sentences.sumOf { it.length }.coerceAtLeast(1)
            val segments = mutableListOf<TranscriptSegment>()
            var chars = 0
            var startMs = 0L
            val current = StringBuilder()
            for ((i, sentence) in sentences.withIndex()) {
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence.trim())
                chars += sentence.length
                val endMs = if (i == sentences.lastIndex) durationMs else durationMs * chars / total
                if (endMs - startMs >= SEGMENT_MS || i == sentences.lastIndex) {
                    segments.add(TranscriptSegment(startMs, endMs, current.toString()))
                    current.clear()
                    startMs = endMs
                }
            }
            return segments
        }
    }
}

/**
 * How long to wait before repeating a request failed with [e] for the [attempt]-th time, `null`
 * when it is not to be repeated: the wait the API asks for when the requests are too many (when
 * short: not the quota of the day), otherwise longer every time (up to [MAX_ATTEMPTS] times).
 */
internal fun retryDelay(e: GeminiException, attempt: Int, baseMs: Long): Long? {
    if (!e.isRetryable || e.isDailyQuota) return null
    val asked = e.retryAfterMs
    if (e.statusCode == 429 && asked != null) {
        return if (attempt < MAX_RATE_LIMITED_ATTEMPTS && asked <= MAX_RATE_WAIT_MS) asked + RATE_LIMIT_MARGIN_MS else null
    }
    return if (attempt < MAX_ATTEMPTS) (baseMs shl (attempt - 1)).coerceAtMost(30_000L) else null
}

private const val MAX_ATTEMPTS = 4
private const val MAX_RATE_LIMITED_ATTEMPTS = 10
private const val MAX_RATE_WAIT_MS = 2 * 60_000L
private const val RATE_LIMIT_MARGIN_MS = 500L
