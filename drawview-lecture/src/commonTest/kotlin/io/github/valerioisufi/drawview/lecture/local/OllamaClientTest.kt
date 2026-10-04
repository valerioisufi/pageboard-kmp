package io.github.valerioisufi.drawview.lecture.local

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OllamaClientTest {
    private val request = OllamaChatRequest(
        model = "gemma4:12b",
        messages = listOf(OllamaMessage("user", "Ciao", images = listOf("AQID"))),
        keepAlive = 0
    )

    @Test
    fun streamedAnswerIsAssembledWithItsProgress() = runTest {
        var url = ""
        var body = ""
        val client = OllamaClient(HttpClient(MockEngine { call ->
            url = call.url.toString()
            body = call.body.toByteArray().decodeToString()
            respond(
                """
                {"message":{"role":"assistant","content":"{\"a\":"},"done":false}
                {"message":{"role":"assistant","content":"1}"},"done":false}
                {"message":{"role":"assistant","content":""},"done":true,"prompt_eval_count":120,"eval_count":2,"total_duration":3000000000}
                """.trimIndent(),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/x-ndjson")
            )
        }))
        val progress = mutableListOf<Int>()
        val result = client.chat("http://localhost:11434/", request) { progress.add(it) }

        assertEquals("http://localhost:11434/api/chat", url)
        assertTrue(body.contains("\"stream\":true"), body)
        assertTrue(body.contains("\"keep_alive\":0"))
        assertTrue(body.contains("\"think\":false"))
        assertTrue(body.contains("\"images\":[\"AQID\"]"))
        assertEquals("{\"a\":1}", result.content)
        assertEquals(listOf(1, 2), progress)
        assertEquals(120, result.promptTokens)
        assertEquals(3000, result.durationMs)
    }

    @Test
    fun missingModelsAndUnreachableServersAreReported() = runTest {
        val missing = OllamaClient(HttpClient(MockEngine {
            respond("""{"error":"model 'gemma4:12b' not found"}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
        }))
        val notFound = assertFailsWith<OllamaException> { missing.chat("http://localhost:11434", request) }
        assertEquals(404, notFound.statusCode)
        assertEquals("model 'gemma4:12b' not found", notFound.message)

        val down = OllamaClient(HttpClient(MockEngine { throw IllegalStateException("Connection refused") }))
        val unreachable = assertFailsWith<OllamaException> { down.chat("http://localhost:11434", request) }
        assertEquals(0, unreachable.statusCode)
        assertTrue(unreachable.message.orEmpty().contains("not reachable"))
    }
}
