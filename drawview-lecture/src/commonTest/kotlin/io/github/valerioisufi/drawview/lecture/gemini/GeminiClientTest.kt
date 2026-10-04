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

    @Test
    fun uploadUsesTheResumableProtocolAndWaitsForProcessing() = runTest {
        val bytes = ByteArray(100) { it.toByte() }
        val calls = mutableListOf<String>()
        var uploaded = ByteArray(0)
        val client = client { request ->
            val path = request.url.encodedPath
            calls.add("${request.method.value} $path")
            when {
                path == "/upload/v1beta/files" -> {
                    assertEquals("resumable", request.headers["X-Goog-Upload-Protocol"])
                    assertEquals("start", request.headers["X-Goog-Upload-Command"])
                    assertEquals("100", request.headers["X-Goog-Upload-Header-Content-Length"])
                    assertEquals("application/pdf", request.headers["X-Goog-Upload-Header-Content-Type"])
                    assertTrue(request.body.toByteArray().decodeToString().contains("\"displayName\":\"slides.pdf\""))
                    respond("{}", HttpStatusCode.OK, headersOf("X-Goog-Upload-URL", "https://upload.example.test/session/1"))
                }
                path == "/session/1" -> {
                    assertEquals("upload, finalize", request.headers["X-Goog-Upload-Command"])
                    uploaded = request.body.toByteArray()
                    respond("""{"file":{"name":"files/abc","uri":"https://x/files/abc","mimeType":"application/pdf","state":"PROCESSING"}}""", HttpStatusCode.OK, jsonHeaders)
                }
                request.method == HttpMethod.Get && path == "/v1beta/files/abc" ->
                    respond("""{"name":"files/abc","uri":"https://x/files/abc","mimeType":"application/pdf","state":"ACTIVE"}""", HttpStatusCode.OK, jsonHeaders)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val file = client.uploadFile("k", bytes, "application/pdf", "slides.pdf")
        assertEquals("ACTIVE", file.state)
        assertEquals("https://x/files/abc", file.uri)
        assertContentEquals(bytes, uploaded)
        assertEquals(listOf("POST /upload/v1beta/files", "POST /session/1", "GET /v1beta/files/abc"), calls)
    }
}
