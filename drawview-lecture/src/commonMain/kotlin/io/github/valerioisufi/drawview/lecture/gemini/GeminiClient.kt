package io.github.valerioisufi.drawview.lecture.gemini

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

/**
 * Minimal REST client of the Gemini API: `generateContent` and the Files API.
 *
 * The API key is passed to every call (it is read from the settings, which may change).
 */
class GeminiClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val baseUrl: String = DEFAULT_BASE_URL
) {
    suspend fun generateContent(apiKey: String, model: String, request: GenerateContentRequest): GenerateContentResponse {
        val response = httpClient.post("$baseUrl/v1beta/models/${model.removePrefix("models/").trim()}:generateContent") {
            header(API_KEY_HEADER, apiKey)
            setBody(TextContent(json.encodeToString(GenerateContentRequest.serializer(), request), ContentType.Application.Json))
        }
        val body = response.checked()
        val result = json.decodeFromString(GenerateContentResponse.serializer(), body)
        result.promptFeedback?.blockReason?.let { throw GeminiException(400, "Request blocked: $it") }
        return result
    }

    /**
     * Uploads [bytes] with the resumable protocol of the Files API and waits until the file can be
     * used in the requests.
     */
    suspend fun uploadFile(apiKey: String, bytes: ByteArray, mimeType: String, displayName: String): GeminiFile {
        val start = httpClient.post("$baseUrl/upload/v1beta/files") {
            header(API_KEY_HEADER, apiKey)
            header("X-Goog-Upload-Protocol", "resumable")
            header("X-Goog-Upload-Command", "start")
            header("X-Goog-Upload-Header-Content-Length", bytes.size.toString())
            header("X-Goog-Upload-Header-Content-Type", mimeType)
            setBody(
                TextContent(
                    json.encodeToString(GeminiUploadStart.serializer(), GeminiUploadStart(GeminiUploadName(displayName))),
                    ContentType.Application.Json
                )
            )
        }
        start.checked()
        val uploadUrl = start.headers["X-Goog-Upload-URL"]
            ?: throw GeminiException(0, "The Files API did not return an upload URL")

        val upload = httpClient.post(uploadUrl) {
            header(API_KEY_HEADER, apiKey)
            header("X-Goog-Upload-Offset", "0")
            header("X-Goog-Upload-Command", "upload, finalize")
            setBody(ByteArrayContent(bytes, ContentType.parse(mimeType)))
        }
        var file = json.decodeFromString(GeminiFileEnvelope.serializer(), upload.checked()).file

        // Large files (audio, PDF) are processed before they can be used
        var waitedMs = 0L
        while (file.state == "PROCESSING" && waitedMs < MAX_PROCESSING_WAIT_MS) {
            delay(PROCESSING_POLL_MS)
            waitedMs += PROCESSING_POLL_MS
            file = getFile(apiKey, file.name)
        }
        if (file.state == "FAILED") throw GeminiException(0, "The Files API could not process $displayName")
        return file
    }

    suspend fun getFile(apiKey: String, name: String): GeminiFile {
        val response = httpClient.get("$baseUrl/v1beta/$name") {
            header(API_KEY_HEADER, apiKey)
        }
        return json.decodeFromString(GeminiFile.serializer(), response.checked())
    }

    private suspend fun HttpResponse.checked(): String {
        val body = bodyAsText()
        if (!status.isSuccess()) {
            val message = try {
                json.decodeFromString(GeminiErrorEnvelope.serializer(), body).error?.message
            } catch (_: Exception) {
                null
            }
            throw GeminiException(status.value, message?.takeIf { it.isNotBlank() } ?: "HTTP ${status.value}")
        }
        return body
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
        private const val API_KEY_HEADER = "x-goog-api-key"
        private const val PROCESSING_POLL_MS = 2_000L
        private const val MAX_PROCESSING_WAIT_MS = 180_000L

        internal val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
        }

        fun defaultHttpClient(): HttpClient = HttpClient {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 20_000
                // Long audio and thinking: the answers can take minutes
                requestTimeoutMillis = 10 * 60_000
                socketTimeoutMillis = 5 * 60_000
            }
        }
    }
}

/**
 * Error of the Gemini API.
 *
 * @param statusCode HTTP status (0 for the errors without a response).
 */
class GeminiException(val statusCode: Int, message: String) : Exception(message) {
    /** Rate limits, timeouts and server errors are temporary: the request can be repeated. */
    val isRetryable: Boolean get() = statusCode == 0 || statusCode == 408 || statusCode == 429 || statusCode >= 500

    /** The key is missing, wrong or not allowed to use the model (an invalid key is a 400). */
    val isAuthenticationError: Boolean
        get() = statusCode == 401 || statusCode == 403 ||
            (statusCode == 400 && message.orEmpty().contains("API key", ignoreCase = true))
}
