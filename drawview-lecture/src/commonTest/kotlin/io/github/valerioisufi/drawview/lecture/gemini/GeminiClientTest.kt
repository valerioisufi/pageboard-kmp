package io.github.valerioisufi.drawview.lecture.gemini

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeminiClientTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(handler: MockRequestHandler) = GeminiClient(HttpClient(MockEngine(handler)))

    @Test
    fun generateContentSendsTheKeyAndReturnsTheAnswerWithoutThoughts() = runTest {
        var url = ""
        var key: String? = null
        var body = ""
        val client = client { request ->
            url = request.url.toString()
            key = request.headers["x-goog-api-key"]
            body = request.body.toByteArray().decodeToString()
            respond(
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"thinking...","thought":true},{"text":"{\"a\":"},{"text":"1}"}]},"finishReason":"STOP"}],
                   "usageMetadata":{"promptTokenCount":1200,"candidatesTokenCount":30,"cachedContentTokenCount":800,"totalTokenCount":1230}}""",
                HttpStatusCode.OK,
                jsonHeaders
            )
        }
        val response = client.generateContent(
            "key-123",
            "models/gemini-3.8-flash",
            GenerateContentRequest(contents = listOf(GeminiContent(role = "user", parts = listOf(GeminiPart.text("hi")))))
        )
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent", url)
        assertEquals("key-123", key)
        assertFalse(url.contains("key-123"))
        assertTrue(body.contains("\"parts\":[{\"text\":\"hi\"}]"), body)
        assertEquals("{\"a\":1}", response.text())
        assertEquals(800, response.usageMetadata?.cachedContentTokenCount)
    }

    @Test
    fun errorsAreClassified() = runTest {
        var status = HttpStatusCode.TooManyRequests
        var message = "Resource has been exhausted"
        val client = client {
            respond("""{"error":{"code":${status.value},"message":"$message","status":"X"}}""", status, jsonHeaders)
        }
        val request = GenerateContentRequest(contents = emptyList())

        val rateLimited = assertFailsWith<GeminiException> { client.generateContent("k", "m", request) }
        assertEquals(429, rateLimited.statusCode)
        assertEquals("Resource has been exhausted", rateLimited.message)
        assertTrue(rateLimited.isRetryable)
        assertFalse(rateLimited.isAuthenticationError)

        status = HttpStatusCode.BadRequest
        message = "API key not valid. Please pass a valid API key."
        val invalidKey = assertFailsWith<GeminiException> { client.generateContent("k", "m", request) }
        assertFalse(invalidKey.isRetryable)
        assertTrue(invalidKey.isAuthenticationError)

        status = HttpStatusCode.ServiceUnavailable
        assertTrue(assertFailsWith<GeminiException> { client.generateContent("k", "m", request) }.isRetryable)
    }
}
