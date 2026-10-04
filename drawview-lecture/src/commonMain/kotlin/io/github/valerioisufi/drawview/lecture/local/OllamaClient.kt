package io.github.valerioisufi.drawview.lecture.local

import io.github.valerioisufi.drawview.lecture.gemini.GeminiClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Minimal client of the chat API of Ollama (a local model server): messages with images and an
 * answer constrained by a JSON schema, streamed so that the progress of a long answer is known.
 */
class OllamaClient(private val httpClient: HttpClient = GeminiClient.defaultHttpClient()) {

    /**
     * Sends [request] to the server at [baseUrl].
     *
     * @param onProgress called with the number of tokens written so far.
     * @throws OllamaException when the server cannot be reached or refuses the request.
     */
    suspend fun chat(baseUrl: String, request: OllamaChatRequest, onProgress: (Int) -> Unit = {}): OllamaChatResult {
        try {
            return httpClient.preparePost("${baseUrl.trimEnd('/')}/api/chat") {
                setBody(TextContent(json.encodeToString(OllamaChatRequest.serializer(), request.copy(stream = true)), ContentType.Application.Json))
                // A local model can take many minutes, mostly to read the prompt (no byte sent)
                timeout {
                    requestTimeoutMillis = 90 * 60_000L
                    socketTimeoutMillis = 30 * 60_000L
                }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val body = response.bodyAsText()
                    val message = try {
                        json.decodeFromString(OllamaChatChunk.serializer(), body).error
                    } catch (_: Exception) {
                        null
                    }
                    throw OllamaException(response.status.value, message ?: "HTTP ${response.status.value}")
                }
                val channel = response.bodyAsChannel()
                val content = StringBuilder()
                var tokens = 0
                while (true) {
                    val line = channel.readLine() ?: break
                    if (line.isBlank()) continue
                    val chunk = json.decodeFromString(OllamaChatChunk.serializer(), line)
                    chunk.error?.let { throw OllamaException(0, it) }
                    chunk.message?.content?.takeIf { it.isNotEmpty() }?.let {
                        content.append(it)
                        tokens++
                        onProgress(tokens)
                    }
                    if (chunk.done) {
                        return@execute OllamaChatResult(
                            content = content.toString(),
                            promptTokens = chunk.promptEvalCount,
                            outputTokens = chunk.evalCount,
                            durationMs = chunk.totalDuration / 1_000_000
                        )
                    }
                }
                throw OllamaException(0, "The answer of the local model was interrupted")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OllamaException) {
            throw e
        } catch (e: Exception) {
            throw OllamaException(0, "Ollama is not reachable at $baseUrl (${e.message ?: e::class.simpleName})")
        }
    }

    internal companion object {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }
    }
}

@Serializable
data class OllamaMessage(
    val role: String,
    val content: String,
    /** Base64-encoded images. */
    val images: List<String>? = null
)

@Serializable
data class OllamaOptions(
    @SerialName("num_ctx") val numCtx: Int? = null,
    val temperature: Float? = null
)

/**
 * @param format JSON schema of the answer.
 * @param keepAlive seconds the model stays in memory after the answer (0: unloaded right away).
 */
@Serializable
data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val format: JsonElement? = null,
    val stream: Boolean = true,
    val think: Boolean? = false,
    @SerialName("keep_alive") val keepAlive: Int? = null,
    val options: OllamaOptions? = null
)

@Serializable
internal data class OllamaChatChunk(
    val message: OllamaMessage? = null,
    val done: Boolean = false,
    val error: String? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Long = 0,
    @SerialName("eval_count") val evalCount: Long = 0,
    @SerialName("total_duration") val totalDuration: Long = 0
)

class OllamaChatResult(val content: String, val promptTokens: Long, val outputTokens: Long, val durationMs: Long)

/**
 * Error of the local model server.
 *
 * @param statusCode HTTP status (0 when the server cannot be reached or the answer is broken).
 */
class OllamaException(val statusCode: Int, message: String) : Exception(message)
