package io.github.valerioisufi.drawview.lecture.gemini

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// The Live API of Gemini (BidiGenerateContent over a WebSocket), as used to transcribe.

/** A message of the client: the setup first, then the audio. */
@Serializable
internal data class LiveClientMessage(val setup: LiveSetup? = null, val realtimeInput: LiveRealtimeInput? = null)

/**
 * The setup of a session transcribing the audio sent ([inputAudioTranscription]): the model
 * answers with text ([LiveGenerationConfig.TEXT]), which a transcription model does not write.
 * No default value: the JSON of the client leaves them out.
 */
@Serializable
internal data class LiveSetup(
    val model: String,
    val generationConfig: LiveGenerationConfig,
    val inputAudioTranscription: AudioTranscriptionConfig,
    val realtimeInputConfig: LiveRealtimeInputConfig? = null
)

@Serializable
internal data class LiveGenerationConfig(val responseModalities: List<String>) {
    companion object {
        val TEXT = LiveGenerationConfig(listOf("TEXT"))
    }
}

/**
 * How the server finds the speech in the audio sent ([automaticActivityDetection]).
 *
 * @param interimTranscriptTimestampEnabled the times of the interim transcripts too.
 */
@Serializable
internal data class LiveRealtimeInputConfig(
    val automaticActivityDetection: LiveActivityDetection? = null,
    val interimTranscriptTimestampEnabled: Boolean? = null
)

/**
 * The detection of the speech of the server: how readily it hears speech starting
 * (`START_SENSITIVITY_HIGH`/`_LOW`) and ending (`END_SENSITIVITY_HIGH`/`_LOW`).
 */
@Serializable
internal data class LiveActivityDetection(
    val disabled: Boolean? = null,
    val startOfSpeechSensitivity: String? = null,
    val endOfSpeechSensitivity: String? = null,
    val prefixPaddingMs: Int? = null,
    val silenceDurationMs: Int? = null
)

/** Audio (PCM 16 kHz, base64) or the end of the audio: what was sent becomes final. */
@Serializable
internal data class LiveRealtimeInput(val audio: GeminiBlob? = null, val audioStreamEnd: Boolean? = null)

/** A message of the server. */
@Serializable
internal data class LiveServerMessage(
    val setupComplete: JsonObject? = null,
    val serverContent: LiveServerContent? = null,
    val voiceActivity: LiveVoiceActivity? = null,
    val goAway: LiveGoAway? = null,
    val usageMetadata: LiveUsage? = null
)

/**
 * The speech found by the server ([type]: `ACTIVITY_START`, `ACTIVITY_END`), at [audioOffset] from
 * the start of the audio of the session ("7.240s").
 */
@Serializable
internal data class LiveVoiceActivity(val type: String = "", val audioOffset: String? = null) {
    val isStart: Boolean get() = type == "ACTIVITY_START"
    val isEnd: Boolean get() = type == "ACTIVITY_END"
}

@Serializable
internal data class LiveServerContent(
    /** Final: the text of an utterance, sent at a pause or at the end of the audio. */
    val inputTranscription: LiveTranscription? = null,
    /** Interim: the utterance being said, still changing. */
    val interimInputTranscription: LiveTranscription? = null,
    val turnComplete: Boolean? = null
)

/** @param startOffset from the start of the audio of the session ("12.300s"), when given. */
@Serializable
internal data class LiveTranscription(
    val text: String = "",
    val languageCode: String? = null,
    val startOffset: String? = null,
    val endOffset: String? = null
)

/** The server will close the connection in [timeLeft] ("10s"). */
@Serializable
internal data class LiveGoAway(val timeLeft: String? = null)

@Serializable
internal data class LiveUsage(
    val promptTokenCount: Long = 0,
    val responseTokenCount: Long = 0,
    val totalTokenCount: Long = 0
)

/** "1.250s" (a protobuf Duration in JSON) in milliseconds, `null` when not one. */
internal fun parseDurationMs(value: String?): Long? {
    val seconds = value?.trim()?.removeSuffix("s")?.toDoubleOrNull() ?: return null
    return (seconds * 1000).toLong()
}

/** A connection of the Live API: messages in JSON both ways. */
internal interface LiveConnection {
    suspend fun send(message: String)

    /** The messages of the server; it ends when the connection closes ([closeError]). */
    val incoming: ReceiveChannel<String>

    /** Why the connection closed: the error of the server, `null` when it closed normally. */
    suspend fun closeError(): GeminiException?

    suspend fun close()
}

/** Opens [LiveConnection]s. */
internal fun interface LiveConnector {
    /** @throws GeminiException when the connection cannot be opened. */
    suspend fun connect(apiKey: String): LiveConnection
}

/** The Live API of Gemini over the WebSocket of Ktor. */
internal class GeminiLiveClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val url: String = DEFAULT_URL
) : LiveConnector {
    override suspend fun connect(apiKey: String): LiveConnection {
        val session = try {
            httpClient.webSocketSession(url) { header(API_KEY_HEADER, apiKey) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw GeminiException(statusOf(e.message.orEmpty()), "Cannot connect to the Live API: ${e.message ?: e::class.simpleName}")
        }
        return KtorLiveConnection(session)
    }

    private class KtorLiveConnection(private val session: DefaultClientWebSocketSession) : LiveConnection {
        override suspend fun send(message: String) = session.send(Frame.Text(message))

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        override val incoming: ReceiveChannel<String> = session.produce {
            session.incoming.consumeEach { frame ->
                // The server sends its JSON in binary frames too
                when (frame) {
                    is Frame.Text -> send(frame.readText())
                    is Frame.Binary -> send(frame.readBytes().decodeToString())
                    else -> {}
                }
            }
        }

        override suspend fun closeError(): GeminiException? {
            val reason = withTimeoutOrNull(2_000) { session.closeReason.await() } ?: return null
            if (reason.code == CloseReason.Codes.NORMAL.code) return null
            return GeminiException(statusOf(reason.message, reason.code), reason.message.ifBlank { "Live API closed (${reason.code})" })
        }

        override suspend fun close() {
            try {
                session.close()
            } catch (_: Exception) {
                // Closed already
            }
        }
    }

    companion object {
        const val DEFAULT_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val API_KEY_HEADER = "x-goog-api-key"

        fun defaultHttpClient(): HttpClient = HttpClient { install(WebSockets) }

        /**
         * The HTTP status matching a closing of the server: the quota (429), a key refused (403), a
         * request not valid (a model or a language unknown, 400), otherwise a temporary error (503).
         */
        internal fun statusOf(message: String, code: Short? = null): Int = when {
            Regex("quota|exhausted|rate limit", RegexOption.IGNORE_CASE).containsMatchIn(message) -> 429
            Regex("API key|permission|unauthori[sz]ed|UNAUTHENTICATED|403|401", RegexOption.IGNORE_CASE).containsMatchIn(message) -> 403
            Regex("not found|not supported|INVALID_ARGUMENT|invalid|unknown", RegexOption.IGNORE_CASE).containsMatchIn(message) -> 400
            code == CloseReason.Codes.VIOLATED_POLICY.code -> 400
            else -> 503
        }
    }
}
