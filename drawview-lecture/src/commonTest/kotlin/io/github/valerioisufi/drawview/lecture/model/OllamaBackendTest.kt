package io.github.valerioisufi.drawview.lecture.model

import io.github.valerioisufi.drawview.lecture.local.OllamaClient
import io.github.valerioisufi.drawview.lecture.local.OllamaException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OllamaBackendTest {
    private val schema = buildJsonObject { put("type", "object") }

    @Test
    fun imagesAreAttachedWhereTheTextMarksThem() = runTest {
        var body = ""
        val backend = OllamaBackend(OllamaClient(HttpClient(MockEngine { request ->
            body = request.body.toByteArray().decodeToString()
            respond(
                """{"message":{"role":"assistant","content":"{}"},"done":true}""" + "\n",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/x-ndjson")
            )
        }))) { "http://localhost:11434" }
        val prompt = ModelPrompt(
            system = "Be brief",
            parts = listOf(PromptPart.Text("Page 1:"), PromptPart.Image(byteArrayOf(1, 2, 3)), PromptPart.Text("End")),
            schema = schema,
            maxOutputTokens = 1000,
            thinkingLevel = "low"
        )
        val answer = backend.generate(prompt, "gemma4:e4b")
        assertEquals("{}", answer.text)
        assertNull(answer.usage)
        assertTrue(body.contains("Page 1:\\n\\n[attached image 1]\\n\\nEnd"), body)
        assertTrue(body.contains("\"images\":[\"AQID\"]"))
        assertTrue(body.contains("\"content\":\"Be brief\""))
        assertTrue(body.contains("\"keep_alive\":0"))
        assertTrue(body.contains("\"num_ctx\":8192"))
    }

    @Test
    fun audioIsRefused() = runTest {
        val backend = OllamaBackend(OllamaClient(HttpClient(MockEngine { respond("") }))) { "http://localhost:11434" }
        val prompt = ModelPrompt("s", listOf(PromptPart.Audio(byteArrayOf(1))), schema, 100, "low")
        assertFailsWith<OllamaException> { backend.generate(prompt, "gemma4:e4b") }
    }
}
