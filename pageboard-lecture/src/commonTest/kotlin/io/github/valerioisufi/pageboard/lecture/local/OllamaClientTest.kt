package io.github.valerioisufi.pageboard.lecture.local

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
    fun theModelsAreListedAndDownloaded() = runTest {
        val bodies = mutableListOf<String>()
        val client = OllamaClient(HttpClient(MockEngine { call ->
            bodies.add(call.body.toByteArray().decodeToString())
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when (call.url.encodedPath) {
                "/api/version" -> respond("""{"version":"0.35.1"}""", HttpStatusCode.OK, json)
                "/api/tags" -> respond("""{"models":[{"name":"gemma4:e4b","size":9600000000,"digest":"c6"},{"name":"qwen3:4b","size":2500000000}]}""", HttpStatusCode.OK, json)
                "/api/show" -> respond("""{"capabilities":["completion","vision"],"details":{}}""", HttpStatusCode.OK, json)
                "/api/pull" -> respond(
                    """
                    {"status":"pulling manifest"}
                    {"status":"pulling a1","digest":"sha256:a1","total":1000,"completed":500}
                    {"status":"pulling b2","digest":"sha256:b2","total":3000,"completed":0}
                    {"status":"pulling a1","digest":"sha256:a1","total":1000,"completed":1000}
                    {"status":"verifying sha256 digest"}
                    {"status":"success"}
                    """.trimIndent(),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/x-ndjson")
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }))
        assertEquals("0.35.1", client.version("http://localhost:11434"))
        assertEquals(listOf(OllamaModel("gemma4:e4b", 9_600_000_000), OllamaModel("qwen3:4b", 2_500_000_000)), client.models("http://localhost:11434"))
        assertEquals(listOf("completion", "vision"), client.capabilities("http://localhost:11434", "gemma4:e4b"))
        assertTrue(bodies.last().contains("\"model\":\"gemma4:e4b\""))

        val progress = mutableListOf<Pair<Long, Long>>()
        client.pull("http://localhost:11434", "gemma4:12b") { done, total -> progress.add(done to total) }
        assertEquals(listOf(500L to 1000L, 500L to 4000L, 1000L to 4000L), progress)
        assertTrue(bodies.last().contains("\"stream\":true"))
    }

    @Test
    fun aServerThatIsNotRunningHasNoVersion() = runTest {
        val client = OllamaClient(HttpClient(MockEngine { throw IllegalStateException("Connection refused") }))
        assertEquals(null, client.version("http://localhost:11434"))
        assertFailsWith<OllamaException> { client.models("http://localhost:11434") }
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
