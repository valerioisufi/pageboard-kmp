package io.github.valerioisufi.pageboard.lecture.model

import io.github.valerioisufi.pageboard.lecture.gemini.GeminiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeminiPromptCacheTest {
    /** A call of the API, as the mock received it. */
    private class Call(val method: HttpMethod, val path: String, val body: String)

    /** The Gemini API, with its answers to the creation of a cache and to a request changeable. */
    private class FakeGemini {
        val calls = mutableListOf<Call>()
        var created = 0
        var createAnswer: Pair<HttpStatusCode, String>? = null
        var generateAnswer: ((Call) -> Pair<HttpStatusCode, String>)? = null

        val client = GeminiClient(HttpClient(MockEngine { request -> answer(request) }))

        private suspend fun MockRequestHandleScope.answer(request: HttpRequestData): HttpResponseData {
            val call = Call(request.method, request.url.encodedPath, request.body.toByteArray().decodeToString())
            calls.add(call)
            val (status, body) = when {
                call.method == HttpMethod.Post && call.path.endsWith("/cachedContents") ->
                    createAnswer ?: (HttpStatusCode.OK to """{"name":"cachedContents/c${++created}","usageMetadata":{"totalTokenCount":6300}}""")
                call.path.endsWith(":generateContent") -> generateAnswer?.invoke(call) ?: (HttpStatusCode.OK to ANSWER)
                else -> HttpStatusCode.OK to "{}"
            }
            return respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        fun generations() = calls.filter { it.path.endsWith(":generateContent") }
        fun creations() = calls.filter { it.method == HttpMethod.Post && it.path.endsWith("/cachedContents") }
        fun of(method: HttpMethod) = calls.filter { it.method == method }
    }

    private val schema = buildJsonObject { put("type", "object") }

    private fun prompt(transcript: String, course: String = "## Course\nAnalisi 1", cachedParts: Int = 2) = ModelPrompt(
        system = "You are a note-taking assistant",
        parts = listOf(PromptPart.Text(course), PromptPart.Text("## Slides of the notebook\nTeorema ponte"), PromptPart.Text("## New transcript\n$transcript")),
        schema = schema,
        maxOutputTokens = 1000,
        thinkingLevel = "low",
        cachedParts = cachedParts
    )

    private fun backendOf(fake: FakeGemini) = GeminiBackend(fake.client) { "key" }

    @Test
    fun theStartIsCachedOnceThenOnlyTheRestIsSent() = runTest {
        val fake = FakeGemini()
        val backend = backendOf(fake)
        val cache = GeminiPromptCache(fake.client)
        val first = backend.generate(prompt("Il teorema ponte"), "gemini-3.8-flash", cache)
        backend.generate(prompt("La derivata"), "gemini-3.8-flash", cache)

        val creation = fake.creations().single()
        assertTrue(creation.body.contains("\"model\":\"models/gemini-3.8-flash\""), creation.body)
        assertTrue(creation.body.contains("You are a note-taking assistant") && creation.body.contains("Teorema ponte"), creation.body)
        assertFalse(creation.body.contains("Il teorema ponte"), creation.body)
        assertTrue(creation.body.contains("\"ttl\":\"600s\""), creation.body)

        for (generation in fake.generations()) {
            assertTrue(generation.body.contains("\"cachedContent\":\"cachedContents/c1\""), generation.body)
            assertFalse(generation.body.contains("systemInstruction"), generation.body)
            assertFalse(generation.body.contains("Slides of the notebook"), generation.body)
            assertTrue(generation.body.contains("New transcript"), generation.body)
        }
        // Extended at the second request
        assertEquals(listOf("/v1beta/cachedContents/c1"), fake.of(HttpMethod.Patch).map { it.path })
        // The tokens of the creation count once, with the answer of the first request
        assertEquals(1200 + 6300L, first.usage?.promptTokens)
        assertEquals(800L, first.usage?.cachedTokens)

        cache.clear("key")
        assertEquals(listOf("/v1beta/cachedContents/c1"), fake.of(HttpMethod.Delete).map { it.path })
    }

    @Test
    fun aStartRefusedIsSentWholeAndNotAskedAgain() = runTest {
        val fake = FakeGemini()
        fake.createAnswer = HttpStatusCode.BadRequest to """{"error":{"code":400,"message":"Cached content is too small. total_token_count=1300, min_total_token_count=4096"}}"""
        val backend = backendOf(fake)
        val cache = GeminiPromptCache(fake.client)
        val answer = backend.generate(prompt("Il teorema ponte"), "gemini-3.8-flash", cache)
        backend.generate(prompt("La derivata"), "gemini-3.8-flash", cache)

        assertEquals(1, fake.creations().size)
        for (generation in fake.generations()) {
            assertFalse(generation.body.contains("cachedContent"), generation.body)
            assertTrue(generation.body.contains("systemInstruction") && generation.body.contains("Slides of the notebook"), generation.body)
        }
        assertEquals(1200L, answer.usage?.promptTokens)
    }

    @Test
    fun aStartThatChangesGetsANewCacheAndTheOldOneIsDeleted() = runTest {
        val fake = FakeGemini()
        val backend = backendOf(fake)
        val cache = GeminiPromptCache(fake.client)
        backend.generate(prompt("Il teorema ponte"), "gemini-3.8-flash", cache)
        // The course context written meanwhile
        backend.generate(prompt("La derivata", course = "## Course\nAnalisi 1, prof. Rossi"), "gemini-3.8-flash", cache)

        assertEquals(2, fake.creations().size)
        assertEquals(listOf("/v1beta/cachedContents/c1"), fake.of(HttpMethod.Delete).map { it.path })
        assertTrue(fake.generations().last().body.contains("\"cachedContent\":\"cachedContents/c2\""))
    }

    @Test
    fun aCacheGoneIsSentWholeThenCreatedAgain() = runTest {
        val fake = FakeGemini()
        val backend = backendOf(fake)
        val cache = GeminiPromptCache(fake.client)
        backend.generate(prompt("Il teorema ponte"), "gemini-3.8-flash", cache)
        fake.generateAnswer = { call ->
            if (call.body.contains("cachedContents/c1")) HttpStatusCode.NotFound to """{"error":{"code":404,"message":"CachedContent not found (or permission denied)"}}"""
            else HttpStatusCode.OK to ANSWER
        }
        val answer = backend.generate(prompt("La derivata"), "gemini-3.8-flash", cache)
        assertEquals("{}", answer.text)
        assertFalse(fake.generations().last().body.contains("cachedContent"))

        backend.generate(prompt("Le derivate parziali"), "gemini-3.8-flash", cache)
        assertEquals(2, fake.creations().size)
        assertTrue(fake.generations().last().body.contains("\"cachedContent\":\"cachedContents/c2\""))
    }

    @Test
    fun aPromptWithoutAStartUsesNoCache() = runTest {
        val fake = FakeGemini()
        val cache = GeminiPromptCache(fake.client)
        backendOf(fake).generate(prompt("Il teorema ponte", cachedParts = 0), "gemini-3.8-flash", cache)
        cache.clear("key")
        assertEquals(listOf(HttpMethod.Post), fake.calls.map { it.method })
        assertTrue(fake.generations().single().body.contains("systemInstruction"))
    }

    private companion object {
        const val ANSWER =
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"{}"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":1200,"candidatesTokenCount":30,"cachedContentTokenCount":800}}"""
    }
}
