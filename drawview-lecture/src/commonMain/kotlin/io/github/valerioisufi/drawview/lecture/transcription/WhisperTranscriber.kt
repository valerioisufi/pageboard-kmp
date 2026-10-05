package io.github.valerioisufi.drawview.lecture.transcription

import io.github.valerioisufi.drawview.core.platform.PlatformFiles
import io.github.valerioisufi.drawview.lecture.AssistantSettings
import io.github.valerioisufi.drawview.lecture.TranscriptSegment
import io.github.valerioisufi.drawview.lecture.gemini.GeminiClient
import io.github.valerioisufi.drawview.lecture.platform.LocalProcess
import io.github.valerioisufi.drawview.lecture.platform.lectureDataDirectory
import io.github.valerioisufi.drawview.lecture.platform.startLocalProcess
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Transcription on this computer with whisper.cpp: the `whisper-server` program is started when
 * the first chunk is transcribed (loading the model takes a few seconds), receives every chunk on
 * its HTTP interface and is stopped at the end of the lecture ([close]).
 *
 * @param serverPath the `whisper-server` program.
 * @param modelPath the ggml model (e.g. `ggml-large-v3-turbo-q5_0.bin`).
 */
class WhisperTranscriber(
    private val serverPath: String,
    private val modelPath: String,
    private val httpClient: HttpClient = GeminiClient.defaultHttpClient(),
    private val port: Int = DEFAULT_PORT,
    private val startProcess: (List<String>) -> LocalProcess? = ::startLocalProcess
) : Transcriber {
    private val mutex = Mutex()
    private var process: LocalProcess? = null
    private val baseUrl = "http://127.0.0.1:$port"

    override suspend fun transcribe(wav: ByteArray, language: String, prompt: String): List<TranscriptSegment> = mutex.withLock {
        ensureStarted()
        val response = try {
            inference(wav, language, prompt)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Once more, on a new connection (e.g. the server restarted)
            try {
                inference(wav, language, prompt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw TranscriptionException("whisper-server does not answer (${e.message})", e)
            }
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) throw TranscriptionException("whisper-server: HTTP ${response.status.value} $body")
        parseVerboseJson(body)
    }

    private suspend fun inference(wav: ByteArray, language: String, prompt: String) = httpClient.submitFormWithBinaryData(
        url = "$baseUrl/inference",
        formData = formData {
            append("file", wav, Headers.build {
                append(HttpHeaders.ContentType, "audio/wav")
                append(HttpHeaders.ContentDisposition, "filename=\"chunk.wav\"")
            })
            append("response_format", "verbose_json")
            append("temperature", "0")
            append("language", language.ifBlank { "auto" })
            if (prompt.isNotBlank()) append("prompt", prompt.trim().take(MAX_PROMPT_CHARS))
        }
    ) {
        // The server closes a connection after a few requests (5 for cpp-httplib), and the next
        // request on it fails ("Broken pipe"): a new connection for every chunk
        header(HttpHeaders.Connection, "close")
    }

    private suspend fun ensureStarted() {
        if (process?.isAlive == true) return
        if (!SystemFileSystem.exists(Path(serverPath))) throw TranscriptionException("whisper-server not found: $serverPath")
        if (modelPath.isBlank() || !SystemFileSystem.exists(Path(modelPath))) {
            throw TranscriptionException("whisper model not found: ${modelPath.ifBlank { "(not set)" }}")
        }
        // A server left running (e.g. after a crash) on the same port is used as it is
        if (isReady()) return
        val started = try {
            startProcess(listOf(serverPath, "-m", modelPath, "--host", "127.0.0.1", "--port", "$port", "-l", "auto"))
        } catch (e: Exception) {
            throw TranscriptionException("cannot start whisper-server: ${e.message}", e)
        } ?: throw TranscriptionException("the local transcription is not available on this platform")
        process = started
        // Loading the model
        var waitedMs = 0L
        while (!isReady()) {
            if (!started.isAlive) {
                process = null
                throw TranscriptionException("whisper-server stopped: " + started.recentOutput().lines().takeLast(3).joinToString(" "))
            }
            if (waitedMs >= START_TIMEOUT_MS) {
                started.stop()
                process = null
                throw TranscriptionException("whisper-server did not start in ${START_TIMEOUT_MS / 1000} s")
            }
            delay(POLL_MS)
            waitedMs += POLL_MS
        }
    }

    private suspend fun isReady(): Boolean = try {
        httpClient.get("$baseUrl/").status.isSuccess()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    override suspend fun close() = mutex.withLock {
        process?.stop()
        process = null
    }

    @Serializable
    private class VerboseJson(val text: String = "", val segments: List<Segment> = emptyList())

    @Serializable
    private class Segment(val start: Double = 0.0, val end: Double = 0.0, val text: String = "")

    companion object {
        const val DEFAULT_PORT = 18178
        private const val START_TIMEOUT_MS = 90_000L
        private const val POLL_MS = 500L

        /** The prompt of whisper is short (224 tokens, the last ones kept). */
        private const val MAX_PROMPT_CHARS = 600

        private val json = Json { ignoreUnknownKeys = true }

        /** Where `whisper-server` is installed (Homebrew on Apple silicon and Intel, then Linux). */
        private val SERVER_LOCATIONS = listOf("/opt/homebrew/bin/whisper-server", "/usr/local/bin/whisper-server", "/usr/bin/whisper-server")

        /** Folder where the models of whisper are looked for when none is set: `whisper` next to `lectures`. */
        fun modelsDirectory(): String = lectureDataDirectory().substringBeforeLast('/') + "/whisper"

        /** The `whisper-server` program of [settings], or the one found in the usual places. */
        fun serverPath(settings: AssistantSettings): String =
            settings.whisperServer.ifBlank { SERVER_LOCATIONS.firstOrNull { PlatformFiles.exists(it) } ?: SERVER_LOCATIONS.first() }

        /** The model of [settings], or the largest one in [modelsDirectory]. */
        fun modelPath(settings: AssistantSettings): String {
            if (settings.whisperModel.isNotBlank()) return settings.whisperModel
            val directory = Path(modelsDirectory())
            if (!SystemFileSystem.exists(directory)) return ""
            return SystemFileSystem.list(directory)
                .filter { it.name.startsWith("ggml-") && it.name.endsWith(".bin") }
                .maxByOrNull { SystemFileSystem.metadataOrNull(it)?.size ?: 0L }
                ?.toString().orEmpty()
        }

        /** The transcriber of [settings]. */
        fun of(settings: AssistantSettings) = WhisperTranscriber(serverPath(settings), modelPath(settings))

        /** The segments of a `verbose_json` answer, with times in milliseconds. */
        internal fun parseVerboseJson(body: String): List<TranscriptSegment> {
            val answer = json.decodeFromString(VerboseJson.serializer(), body)
            if (answer.segments.isEmpty()) {
                return answer.text.trim().takeIf { it.isNotEmpty() }?.let { listOf(TranscriptSegment(0, 0, it)) }.orEmpty()
            }
            val segments = mutableListOf<TranscriptSegment>()
            for (segment in answer.segments) {
                val text = segment.text.trim()
                // Whisper marks the silence and the noises: not speech
                if (text.isEmpty() || NON_SPEECH.matches(text)) continue
                val last = segments.lastOrNull()
                // Whisper also ends a segment in the middle of a word ("il conc" + "etto di"): the
                // next one does not start with a space, and is the rest of the same segment
                if (last != null && !segment.text.first().isWhitespace()) {
                    segments[segments.lastIndex] = last.copy(endMs = (segment.end * 1000).toLong(), text = last.text + text)
                } else {
                    segments.add(TranscriptSegment((segment.start * 1000).toLong(), (segment.end * 1000).toLong(), text))
                }
            }
            return segments
        }

        private val NON_SPEECH = Regex("""^[\[(].*[\])]$""")
    }
}
