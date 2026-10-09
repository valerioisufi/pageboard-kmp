package io.github.valerioisufi.drawview.lecture.transcription

import io.github.valerioisufi.drawview.lecture.TokenUsage
import io.github.valerioisufi.drawview.lecture.TranscriptSegment
import io.github.valerioisufi.drawview.lecture.audio.LECTURE_SAMPLE_RATE
import io.github.valerioisufi.drawview.lecture.audio.samplesToMs
import io.github.valerioisufi.drawview.lecture.audio.toLittleEndianBytes
import io.github.valerioisufi.drawview.lecture.gemini.AudioTranscriptionConfig
import io.github.valerioisufi.drawview.lecture.gemini.GeminiBlob
import io.github.valerioisufi.drawview.lecture.gemini.GeminiClient
import io.github.valerioisufi.drawview.lecture.gemini.GeminiException
import io.github.valerioisufi.drawview.lecture.gemini.LiveActivityDetection
import io.github.valerioisufi.drawview.lecture.gemini.LiveClientMessage
import io.github.valerioisufi.drawview.lecture.gemini.LiveConnection
import io.github.valerioisufi.drawview.lecture.gemini.LiveConnector
import io.github.valerioisufi.drawview.lecture.gemini.LiveGenerationConfig
import io.github.valerioisufi.drawview.lecture.gemini.LiveRealtimeInput
import io.github.valerioisufi.drawview.lecture.gemini.LiveRealtimeInputConfig
import io.github.valerioisufi.drawview.lecture.gemini.LiveServerMessage
import io.github.valerioisufi.drawview.lecture.gemini.LiveSetup
import io.github.valerioisufi.drawview.lecture.gemini.LiveUsage
import io.github.valerioisufi.drawview.lecture.gemini.parseDurationMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.math.max
import kotlin.math.min

/**
 * A live speech model of Gemini (Gemini 3.5 Transcribe Live) transcribing the recording while it
 * is made, over the Live API ([LiveConnector]).
 *
 * The audio is sent in pieces of 100 ms and kept until it is transcribed for good, so that nothing
 * is lost when a session ends:
 * - a session lasts 10 minutes at most: after [sessionAudioMs] of audio a new one starts at a pause
 *   (no utterance being said), from the audio not final yet; at [maxSessionAudioMs], or when the
 *   server announces its closing, in any case. The old session is ignored from then on;
 * - a connection lost is opened again, waiting longer every time (up to 30 s), and receives the
 *   audio kept (up to [MAX_KEPT_MS]);
 * - a key refused or a model unknown stops the transcription.
 *
 * The times of an utterance come from the server when it gives them, otherwise from the audio
 * sent when its first interim and its final text arrive.
 *
 * Everything happens in one coroutine, fed by [events].
 */
class GeminiLiveTranscriber(
    private val connector: LiveConnector,
    private val apiKey: () -> String,
    private val model: String,
    private val sessionAudioMs: Long = SESSION_AUDIO_MS,
    private val maxSessionAudioMs: Long = MAX_SESSION_AUDIO_MS,
    private val retryUnitMs: Long = 1_000L,
    private val activityDetection: LiveActivityDetection? = QUIET_SPEECH
) : StreamingTranscriber {

    private sealed interface Event {
        class Audio(val samples: ShortArray) : Event
        data object AudioEnd : Event
        class Message(val session: Session, val text: String) : Event
        class Closed(val session: Session, val error: GeminiException?) : Event
        data object Reconnect : Event
    }

    /** A session of the Live API, from [startSample] of the recording. */
    private class Session(val startSample: Long, val languages: List<String>) {
        var connection: LiveConnection? = null
        var job: Job? = null

        /** The setup is done: the audio can be sent. */
        var ready = false

        /** The next sample of the recording to send. */
        var sentSample = startSample

        /** The end of the audio was sent. */
        var endSent = false

        /** Where the utterance being said started (estimated), if one is. */
        var utteranceStart: Long? = null
        var usage: LiveUsage? = null
        var reported = false
    }

    private val events = Channel<Event>(Channel.UNLIMITED)

    /** The soft speech made louder for the detection of the server (the recording stays as it is). */
    private val loudness = SpeechLevel()
    private lateinit var scope: CoroutineScope
    private lateinit var hints: () -> SpeechHints
    private lateinit var listener: StreamingTranscriber.Listener
    private var loop: Job? = null

    /** Milliseconds of the recording transcribed for good (or with nothing to transcribe). */
    private val committedMs = MutableStateFlow(0L)

    // Owned by the coroutine of the events
    private val kept = ArrayDeque<ShortArray>()
    private var keptStart = 0L
    private var received = 0L
    private var current: Session? = null
    private var committed = 0L
    private var lastFinalEnd = 0L
    private var audioEnded = false
    private var stopped = false

    @Volatile
    private var closing = false
    private var attempts = 0
    private var withoutLanguages = false
    private var problemShown = false

    override fun start(scope: CoroutineScope, hints: () -> SpeechHints, listener: StreamingTranscriber.Listener) {
        this.scope = scope
        this.hints = hints
        this.listener = listener
        loop = scope.launch {
            open(from = 0L)
            for (event in events) {
                try {
                    handle(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println("DrawView: live transcription: ${e.message}")
                }
                committedMs.value = samplesToMs(effectiveCommitted())
            }
        }
    }

    override fun push(samples: ShortArray) {
        events.trySend(Event.Audio(samples))
    }

    override fun endOfAudio() {
        events.trySend(Event.AudioEnd)
    }

    override suspend fun awaitFinal(untilMs: Long, timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { committedMs.first { it >= untilMs - FINAL_TOLERANCE_MS } }
    }

    override suspend fun close() {
        closing = true
        events.close()
        loop?.join()
        current?.let { session ->
            session.connection?.close()
            session.job?.cancel()
            reportUsage(session)
        }
        current = null
        listener.onInterim("")
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Audio -> {
                kept.addLast(event.samples)
                received += event.samples.size
                keepAtMost(MAX_KEPT_MS)
                val session = current ?: return
                if (!session.ready) return
                send(session)
                val audioMs = samplesToMs(session.sentSample - session.startSample)
                // A new session before the server ends this one, at a pause when possible
                if ((audioMs >= sessionAudioMs && session.utteranceStart == null) || audioMs >= maxSessionAudioMs) rotate()
            }
            Event.AudioEnd -> {
                audioEnded = true
                current?.takeIf { it.ready }?.let { endAudio(it) }
            }
            is Event.Message -> onMessage(event.session, event.text)
            is Event.Closed -> onClosed(event.session, event.error)
            Event.Reconnect -> if (current == null && !stopped && !closing) open(from = committed)
        }
    }

    private suspend fun onMessage(session: Session, text: String) {
        val message = try {
            GeminiClient.json.decodeFromString(LiveServerMessage.serializer(), text)
        } catch (_: Exception) {
            return
        }
        message.usageMetadata?.let { session.usage = it }
        // A session replaced: its words come from the new one
        if (session !== current) return
        if (message.setupComplete != null) {
            session.ready = true
            attempts = 0
            if (problemShown) {
                problemShown = false
                listener.onProblem(null)
            }
            send(session)
            if (audioEnded) endAudio(session)
        }
        // Where the server hears the speech start and end: the start of the utterance, the audio final up to its end
        message.voiceActivity?.let { activity ->
            val offset = parseDurationMs(activity.audioOffset) ?: return@let
            val at = session.startSample + msToSamples(offset)
            if (activity.isStart) session.utteranceStart = max(lastFinalEnd, at)
            if (activity.isEnd && session.utteranceStart == null) committed = max(committed, at)
        }
        val content = message.serverContent
        content?.interimInputTranscription?.let { interim ->
            if (session.utteranceStart == null) session.utteranceStart = max(lastFinalEnd, session.sentSample - INTERIM_LAG_SAMPLES)
            listener.onInterim(interim.text.trim())
        }
        content?.inputTranscription?.let { final ->
            val startOffset = parseDurationMs(final.startOffset)
            val endOffset = parseDurationMs(final.endOffset)
            val start: Long
            val end: Long
            if (startOffset != null && endOffset != null) {
                start = session.startSample + msToSamples(startOffset)
                end = session.startSample + msToSamples(endOffset)
            } else {
                start = max(lastFinalEnd, session.utteranceStart ?: lastFinalEnd)
                end = max(start, session.sentSample - FINAL_LAG_SAMPLES)
            }
            session.utteranceStart = null
            lastFinalEnd = max(lastFinalEnd, end)
            committed = max(committed, end)
            val words = final.text.trim()
            if (words.isNotEmpty()) listener.onFinal(TranscriptSegment(samplesToMs(start), samplesToMs(end), words))
            listener.onInterim("")
            dropCommitted()
        }
        // The server will close the connection soon: a new session now
        if (message.goAway != null) rotate()
    }

    private suspend fun onClosed(session: Session, error: GeminiException?) {
        reportUsage(session)
        if (session !== current || closing) return
        current = null
        // Closed after the end of the audio: done
        if (error == null && session.endSent) return
        if (error != null && !error.isRetryable && !error.isAuthenticationError && session.languages.isNotEmpty() &&
            error.message.orEmpty().contains("language", ignoreCase = true)
        ) {
            // A language the model does not know: recognized instead
            withoutLanguages = true
            open(from = committed)
            return
        }
        if (error != null && !error.isRetryable) {
            stopped = true
            kept.clear()
            committed = received
            listener.onInterim("")
            listener.onProblem(error)
            return
        }
        attempts++
        val waitMs = min(MAX_RETRY_MS, retryUnitMs shl min(attempts - 1, 5))
        problemShown = true
        listener.onProblem(error ?: GeminiException(0, "the connection was closed"), ((waitMs + 999) / 1000).toInt())
        scope.launch {
            delay(waitMs)
            events.trySend(Event.Reconnect)
        }
    }

    /** A new session from what is not final yet; the current one is left. */
    private fun rotate() {
        val old = current ?: return
        val from = if (old.utteranceStart == null) effectiveCommitted() else committed
        old.connection?.let { connection -> scope.launch { connection.close() } }
        current = null
        open(from)
    }

    /** Opens a session sending the audio from [from] (what is still kept of it). */
    private fun open(from: Long) {
        val session = Session(max(from, keptStart), if (withoutLanguages) emptyList() else hints().locales)
        current = session
        val vocabulary = hints().vocabulary.take(GeminiSpeechTranscriber.MAX_VOCABULARY)
        session.job = scope.launch {
            try {
                val connection = connector.connect(apiKey())
                session.connection = connection
                val setup = LiveSetup(
                    model = "models/" + model.removePrefix("models/"),
                    generationConfig = LiveGenerationConfig.TEXT,
                    inputAudioTranscription = AudioTranscriptionConfig(
                        languageCodes = session.languages,
                        customVocabulary = vocabulary.ifEmpty { null },
                        mode = GeminiSpeechTranscriber.MODE
                    ),
                    realtimeInputConfig = activityDetection?.let { LiveRealtimeInputConfig(automaticActivityDetection = it) }
                )
                connection.send(GeminiClient.json.encodeToString(LiveClientMessage.serializer(), LiveClientMessage(setup = setup)))
                for (message in connection.incoming) events.send(Event.Message(session, message))
                events.send(Event.Closed(session, connection.closeError()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: GeminiException) {
                events.trySend(Event.Closed(session, e))
            } catch (e: Exception) {
                events.trySend(Event.Closed(session, GeminiException(0, e.message ?: e::class.simpleName.orEmpty())))
            }
        }
    }

    /** Sends the audio received and not sent yet to [session], 100 ms at a time. */
    private suspend fun send(session: Session) {
        val connection = session.connection ?: return
        if (session.sentSample < keptStart) session.sentSample = keptStart
        while (session.sentSample < received) {
            val to = min(received, session.sentSample + FRAME_SAMPLES)
            val pcm = loudness.level(keptSamples(session.sentSample, to)).toLittleEndianBytes()
            val input = LiveRealtimeInput(audio = GeminiBlob(MIME_TYPE, Base64.encode(pcm)))
            connection.send(GeminiClient.json.encodeToString(LiveClientMessage.serializer(), LiveClientMessage(realtimeInput = input)))
            session.sentSample = to
        }
    }

    private suspend fun endAudio(session: Session) {
        if (session.endSent) return
        send(session)
        session.connection?.send(GeminiClient.json.encodeToString(LiveClientMessage.serializer(), LiveClientMessage(realtimeInput = LiveRealtimeInput(audioStreamEnd = true))))
        session.endSent = true
    }

    /**
     * The end of the audio transcribed for good: the last final text, or (no utterance being said)
     * the audio sent but its last second (an utterance may have started without its interim yet);
     * everything when the audio is over and nothing is being said, or when the transcription stopped.
     */
    private fun effectiveCommitted(): Long {
        if (stopped) return received
        val session = current ?: return committed
        if (session.utteranceStart != null) return committed
        if (audioEnded && session.endSent && session.sentSample >= received) return received
        return max(committed, session.sentSample - INTERIM_LAG_SAMPLES)
    }

    /**
     * The tokens of a session ended: those of the server, or (it gives none, August 2026) its audio
     * at [AUDIO_TOKENS_PER_SECOND].
     */
    private suspend fun reportUsage(session: Session) {
        if (session.reported) return
        session.reported = true
        val usage = session.usage
        val tokens = if (usage != null) {
            TokenUsage(requests = 1, promptTokens = usage.promptTokenCount, outputTokens = usage.responseTokenCount)
        } else {
            val audioTokens = samplesToMs(session.sentSample - session.startSample) * AUDIO_TOKENS_PER_SECOND / 1000
            if (audioTokens == 0L) return
            TokenUsage(requests = 1, promptTokens = audioTokens, promptByModality = mapOf("AUDIO" to audioTokens))
        }
        listener.onUsage(tokens)
    }

    /** The samples kept from [from] to [to] (both within the kept audio). */
    private fun keptSamples(from: Long, to: Long): ShortArray {
        val result = ShortArray((to - from).toInt())
        var position = keptStart
        var written = 0
        for (block in kept) {
            val blockEnd = position + block.size
            if (blockEnd > from && position < to) {
                val start = (max(from, position) - position).toInt()
                val end = (min(to, blockEnd) - position).toInt()
                block.copyInto(result, written, start, end)
                written += end - start
            }
            position = blockEnd
            if (position >= to) break
        }
        return result
    }

    /** Drops the audio transcribed for good (but what the current session still has to send). */
    private fun dropCommitted() {
        val keepFrom = min(committed, current?.sentSample ?: committed)
        while (kept.isNotEmpty() && keptStart + kept.first().size <= keepFrom) {
            keptStart += kept.removeFirst().size
        }
    }

    /** At most [ms] of audio kept: the oldest is lost (a connection down for long). */
    private fun keepAtMost(ms: Long) {
        val limit = received - msToSamples(ms)
        while (kept.isNotEmpty() && keptStart + kept.first().size <= limit) {
            keptStart += kept.removeFirst().size
        }
        if (committed < keptStart) committed = keptStart
    }

    private fun msToSamples(ms: Long): Long = ms * LECTURE_SAMPLE_RATE / 1000

    companion object {
        /**
         * The detection of the speech of the server, readier to hear it start: a lecturer speaking
         * softly (walking away from the microphone, a question of a student) is speech too.
         */
        val QUIET_SPEECH = LiveActivityDetection(startOfSpeechSensitivity = "START_SENSITIVITY_HIGH", endOfSpeechSensitivity = "END_SENSITIVITY_LOW")

        /** A new session after this much audio, at a pause (a session lasts 10 minutes at most). */
        const val SESSION_AUDIO_MS = 9 * 60_000L
        const val MAX_SESSION_AUDIO_MS = 9 * 60_000L + 30_000L

        /** The tokens of the audio of the speech models (measured on the model for recorded audio). */
        const val AUDIO_TOKENS_PER_SECOND = 25L

        /** Audio kept while the connection is down. */
        const val MAX_KEPT_MS = 3 * 60_000L
        private const val MAX_RETRY_MS = 30_000L
        private const val FRAME_SAMPLES = LECTURE_SAMPLE_RATE / 10
        private const val MIME_TYPE = "audio/pcm;rate=16000"

        /** The first interim of an utterance arrives about this late after it started. */
        private const val INTERIM_LAG_SAMPLES = LECTURE_SAMPLE_RATE.toLong()

        /** The final text of an utterance arrives after a pause of about this much. */
        private const val FINAL_LAG_SAMPLES = LECTURE_SAMPLE_RATE * 7L / 10

        /** What was said this little before the moment waited for may still be in the next utterance. */
        private const val FINAL_TOLERANCE_MS = 1_000L
    }
}

/**
 * Makes the soft speech louder, a piece of 100 ms at a time: the gain follows the level of the
 * last half second (up to [MAX_GAIN]), so that a lecturer speaking softly is heard by the detection
 * of the speech of the server as the loud parts are; the loud parts stay as they are.
 */
internal class SpeechLevel {
    private val recent = ArrayDeque<Double>()
    private var gain = 1.0

    fun level(samples: ShortArray): ShortArray {
        if (samples.isEmpty()) return samples
        var sum = 0.0
        for (sample in samples) sum += sample.toDouble() * sample
        recent.addLast(sum / samples.size)
        while (recent.size > WINDOW) recent.removeFirst()
        val rms = kotlin.math.sqrt(recent.average())
        val target = (TARGET_RMS / rms.coerceAtLeast(1.0)).coerceIn(1.0, MAX_GAIN)
        val from = gain
        gain += (target - gain) * SMOOTHING
        // From the previous gain to the new one along the piece: no step
        return ShortArray(samples.size) { i ->
            val g = from + (gain - from) * i / samples.size
            (samples[i] * g).coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()
        }
    }

    private companion object {
        const val WINDOW = 5
        const val TARGET_RMS = 1_500.0
        const val MAX_GAIN = 4.0
        const val SMOOTHING = 0.5
    }
}
