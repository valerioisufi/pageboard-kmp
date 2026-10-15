package io.github.valerioisufi.drawview.lecture.local

import io.github.valerioisufi.drawview.lecture.gemini.GeminiClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.post
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
 * Minimal client of Ollama (a local model server): the chat API (messages with images and an
 * answer constrained by a JSON schema, streamed so that the progress of a long answer is known)
 * and the management of the models (version of the server, models installed, downloads).
 */
public class OllamaClient(private val httpClient: HttpClient = GeminiClient.defaultHttpClient()) {

    /**
     * Sends [request] to the server at [baseUrl].
     *
     * @param onProgress called with the number of tokens written so far.
     * @throws OllamaException when the server cannot be reached or refuses the request.
     */
    internal suspend fun chat(baseUrl: String, request: OllamaChatRequest, onProgress: (Int) -> Unit = {}): OllamaChatResult {
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

    /** The version of the server at [baseUrl], or `null` when it does not answer. */
    public suspend fun version(baseUrl: String): String? = try {
        val response = httpClient.get("${baseUrl.trimEnd('/')}/api/version") { timeout { requestTimeoutMillis = 5_000 } }
        if (response.status.isSuccess()) json.decodeFromString(OllamaVersion.serializer(), response.bodyAsText()).version else null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** The models installed on the server at [baseUrl]. */
    public suspend fun models(baseUrl: String): List<OllamaModel> = request(baseUrl) {
        val response = httpClient.get("${baseUrl.trimEnd('/')}/api/tags")
        if (!response.status.isSuccess()) throw OllamaException(response.status.value, "HTTP ${response.status.value}")
        json.decodeFromString(OllamaModels.serializer(), response.bodyAsText()).models
    }

    /** What [model] can do: "completion", "vision" (reads images), "thinking"... */
    public suspend fun capabilities(baseUrl: String, model: String): List<String> = request(baseUrl) {
        val response = httpClient.post("${baseUrl.trimEnd('/')}/api/show") {
            setBody(TextContent(json.encodeToString(OllamaModelRequest.serializer(), OllamaModelRequest(model)), ContentType.Application.Json))
        }
        if (!response.status.isSuccess()) throw OllamaException(response.status.value, "HTTP ${response.status.value}")
        json.decodeFromString(OllamaModelInfo.serializer(), response.bodyAsText()).capabilities
    }

    /**
     * Downloads [model] to the server at [baseUrl].
     *
     * @param onProgress bytes downloaded and total bytes (of the parts known so far).
     */
    internal suspend fun pull(baseUrl: String, model: String, onProgress: (Long, Long) -> Unit) = request(baseUrl) {
        httpClient.preparePost("${baseUrl.trimEnd('/')}/api/pull") {
            setBody(TextContent(json.encodeToString(OllamaModelRequest.serializer(), OllamaModelRequest(model, stream = true)), ContentType.Application.Json))
            // Gigabytes: as long as it takes, as long as bytes arrive
            timeout {
                requestTimeoutMillis = Long.MAX_VALUE
                socketTimeoutMillis = 5 * 60_000L
            }
        }.execute { response ->
            if (!response.status.isSuccess()) throw OllamaException(response.status.value, response.bodyAsText().ifBlank { "HTTP ${response.status.value}" })
            val channel = response.bodyAsChannel()
            // Every part of the model (layer) reports its own progress
            val parts = LinkedHashMap<String, Pair<Long, Long>>()
            while (true) {
                val line = channel.readLine() ?: break
                if (line.isBlank()) continue
                val status = json.decodeFromString(OllamaPullStatus.serializer(), line)
                status.error?.let { throw OllamaException(0, it) }
                if (status.status == "success") return@execute
                val digest = status.digest ?: continue
                parts[digest] = status.completed to status.total
                onProgress(parts.values.sumOf { it.first }, parts.values.sumOf { it.second })
            }
            throw OllamaException(0, "The download of $model was interrupted")
        }
    }

    /** Runs [block], reporting a server that cannot be reached as an [OllamaException]. */
    private suspend fun <T> request(baseUrl: String, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: OllamaException) {
        throw e
    } catch (e: Exception) {
        throw OllamaException(0, "Ollama is not reachable at $baseUrl (${e.message ?: e::class.simpleName})")
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
internal data class OllamaMessage(
    val role: String,
    val content: String,
    /** Base64-encoded images. */
    val images: List<String>? = null
)

@Serializable
internal data class OllamaOptions(
    @SerialName("num_ctx") val numCtx: Int? = null,
    val temperature: Float? = null
)

/**
 * @param format JSON schema of the answer.
 * @param keepAlive seconds the model stays in memory after the answer (0: unloaded right away).
 */
@Serializable
internal data class OllamaChatRequest(
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

internal class OllamaChatResult(public val content: String, internal val promptTokens: Long, internal val outputTokens: Long, internal val durationMs: Long)

@Serializable
internal class OllamaVersion(val version: String = "")

/** A model installed in Ollama. */
@Serializable
public data class OllamaModel(val name: String, val size: Long = 0)

@Serializable
internal class OllamaModels(val models: List<OllamaModel> = emptyList())

@Serializable
internal class OllamaModelRequest(val model: String, val stream: Boolean? = null)

@Serializable
internal class OllamaModelInfo(val capabilities: List<String> = emptyList())

@Serializable
internal class OllamaPullStatus(
    val status: String = "",
    val digest: String? = null,
    val total: Long = 0,
    val completed: Long = 0,
    val error: String? = null
)

/**
 * Error of the local model server.
 *
 * @param statusCode HTTP status (0 when the server cannot be reached or the answer is broken).
 */
internal class OllamaException(internal val statusCode: Int, message: String) : Exception(message)
