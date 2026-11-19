package io.github.valerioisufi.pageboard.lecture.gemini

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.delete
import io.ktor.client.request.patch
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

/**
 * Minimal REST client of the Gemini API: `generateContent` and the explicit caches.
 *
 * The API key is passed to every call (it is read from the settings, which may change).
 */
public class GeminiClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val baseUrl: String = DEFAULT_BASE_URL
) {
    internal suspend fun generateContent(apiKey: String, model: String, request: GenerateContentRequest): GenerateContentResponse {
        val response = httpClient.post("$baseUrl/v1beta/models/${model.removePrefix("models/").trim()}:generateContent") {
            header(API_KEY_HEADER, apiKey)
            setBody(TextContent(json.encodeToString(GenerateContentRequest.serializer(), request), ContentType.Application.Json))
        }
        val body = response.checked()
        val result = json.decodeFromString(GenerateContentResponse.serializer(), body)
        result.promptFeedback?.blockReason?.let { throw GeminiException(400, "Request blocked: $it") }
        return result
    }

    /** Creates an explicit cache of [request]: its tokens are billed once, then read at a tenth of the price. */
    internal suspend fun createCache(apiKey: String, request: CreateCachedContentRequest): GeminiCachedContent {
        val response = httpClient.post("$baseUrl/v1beta/cachedContents") {
            header(API_KEY_HEADER, apiKey)
            setBody(TextContent(json.encodeToString(CreateCachedContentRequest.serializer(), request), ContentType.Application.Json))
        }
        return json.decodeFromString(GeminiCachedContent.serializer(), response.checked())
    }

    /** Keeps the cache [name] for [ttlSeconds] from now. */
    internal suspend fun extendCache(apiKey: String, name: String, ttlSeconds: Int) {
        val response = httpClient.patch("$baseUrl/v1beta/$name") {
            header(API_KEY_HEADER, apiKey)
            setBody(TextContent(json.encodeToString(CachedContentTtl.serializer(), CachedContentTtl("${ttlSeconds}s")), ContentType.Application.Json))
        }
        response.checked()
    }

    internal suspend fun deleteCache(apiKey: String, name: String) {
        val response = httpClient.delete("$baseUrl/v1beta/$name") {
            header(API_KEY_HEADER, apiKey)
        }
        response.checked()
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

    public companion object {
        internal const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
        private const val API_KEY_HEADER = "x-goog-api-key"

        internal val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
        }

        internal fun defaultHttpClient(): HttpClient = HttpClient {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 20_000
                // Long thinking: the answers can take minutes
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
internal class GeminiException(internal val statusCode: Int, message: String) : Exception(message) {
    /** Rate limits, timeouts and server errors are temporary: the request can be repeated. */
    val isRetryable: Boolean get() = statusCode == 0 || statusCode == 408 || statusCode == 429 || statusCode >= 500

    /** The key is missing, wrong or not allowed to use the model (an invalid key is a 400). */
    val isAuthenticationError: Boolean
        get() = statusCode == 401 || statusCode == 403 ||
            (statusCode == 400 && message.orEmpty().contains("API key", ignoreCase = true))

    /**
     * How long the API asks to wait before the next request ("Please retry in 39.9s.", "Please retry
     * in 7h39m28.5s."), if it says.
     */
    val retryAfterMs: Long?
        get() {
            val match = RETRY_IN.find(message.orEmpty()) ?: return null
            val (hours, minutes, seconds) = match.destructured
            if (hours.isEmpty() && minutes.isEmpty() && seconds.isEmpty()) return null
            return (hours.toLongOrNull() ?: 0) * 3_600_000 + (minutes.toLongOrNull() ?: 0) * 60_000 + ((seconds.toDoubleOrNull() ?: 0.0) * 1000).toLong()
        }

    /** The requests of the day allowed by the plan are over (they come back the next day). */
    val isDailyQuota: Boolean
        get() = statusCode == 429 && (message.orEmpty().contains("per_day", ignoreCase = true) || (retryAfterMs ?: 0) > DAILY_WAIT_MS)

    /** The model the quota is of ("model: gemini-3.5-transcribe"), if the API says. */
    val quotaModel: String?
        get() = MODEL.find(message.orEmpty())?.groupValues?.get(1)

    private companion object {
        val RETRY_IN = Regex("""retry in\s+(?:(\d+)h)?(?:(\d+)m(?!s))?(?:([0-9]+(?:\.[0-9]+)?)s)?""", RegexOption.IGNORE_CASE)
        val MODEL = Regex("""model:\s*([\w.\-]+)""")

        /** A wait longer than this: the quota of the day. */
        const val DAILY_WAIT_MS = 30 * 60_000L
    }
}
