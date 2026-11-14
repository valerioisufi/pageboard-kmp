package io.github.valerioisufi.drawview.lecture.model

import io.github.valerioisufi.drawview.lecture.AssistantSettings
import io.github.valerioisufi.drawview.lecture.LectureProblem
import io.github.valerioisufi.drawview.lecture.LectureTask
import io.github.valerioisufi.drawview.lecture.ModelProvider
import io.github.valerioisufi.drawview.lecture.TaskModel
import io.github.valerioisufi.drawview.lecture.gemini.GeminiClient
import io.github.valerioisufi.drawview.lecture.gemini.GeminiException
import io.github.valerioisufi.drawview.lecture.local.OllamaClient
import io.github.valerioisufi.drawview.lecture.local.OllamaException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ModelBackendsTest {
    private val bodies = mutableListOf<String>()
    private var key = "key"
    private val engine = MockEngine { request ->
        bodies.add(request.body.toByteArray().decodeToString())
        respond(
            """{"candidates":[{"content":{"parts":[{"text":"An answer"}]}}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":3}}""",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json")
        )
    }
    private val backends = ModelBackends(
        GeminiBackend(GeminiClient(HttpClient(engine))) { key },
        OllamaBackend(OllamaClient(HttpClient(engine))) { "http://localhost:11434" }
    )

    @Test
    fun theSettingsChooseTheBackendAndTheModelOfATask() {
        val settings = AssistantSettings()
            .withTask(LectureTask.LIVE_NOTES, TaskModel(ModelProvider.LOCAL, gemini = "gemini-x", local = "gemma4:e4b"))
            .withTask(LectureTask.FINAL_REVIEW, TaskModel(ModelProvider.GEMINI, gemini = "gemini-y", local = "gemma4:12b"))
        val live = backends.forTask(settings, LectureTask.LIVE_NOTES)
        assertSame(backends.local, live.backend)
        assertEquals("gemma4:e4b", live.model)
        val review = backends.forTask(settings, LectureTask.FINAL_REVIEW)
        assertSame(backends.gemini, review.backend)
        assertEquals("gemini-y", review.model)
        // The review claimed by another provider takes the model of that provider
        assertEquals("gemma4:12b", backends.forReview(settings, ModelProvider.LOCAL).model)
        assertEquals(mapOf(ModelProvider.LOCAL to setOf("gemma4:e4b")), settings.withTask(LectureTask.CATCH_UP, TaskModel(ModelProvider.LOCAL)).liveModels)
    }

    @Test
    fun whatDiffersByProviderIsAMemberOfItsBackend() {
        assertEquals(2, backends.gemini.limits.imagePages)
        assertEquals(1, backends.local.limits.imagePages)
        assertEquals(6, backends.gemini.limits.handwritingBatch)
        assertEquals(3, backends.local.limits.handwritingBatch)
        assertNotNull(backends.gemini.newPromptCache())
        assertNull(backends.local.newPromptCache())
        assertNull(backends.gemini.unavailable())
        key = ""
        assertEquals(LectureProblem.MissingApiKey, backends.gemini.unavailable())
        assertNull(backends.local.unavailable())
        assertEquals(true, backends.gemini.isRetryable(GeminiException(503, "busy")))
        assertEquals(false, backends.gemini.isRetryable(GeminiException(400, "bad")))
        assertNull(backends.gemini.isRetryable(OllamaException(500, "error")))
        assertEquals(true, backends.local.isRetryable(OllamaException(500, "error")))
        assertEquals(false, backends.local.isRetryable(OllamaException(404, "missing")))
        assertNull(backends.local.isRetryable(IllegalArgumentException()))
    }

    @Test
    fun anAnswerWithoutASchemaIsText() = runTest {
        val prompt = ModelPrompt("Answer briefly", listOf(PromptPart.Text("What is a limit?")), schema = null, maxOutputTokens = 4096, thinkingLevel = "low")
        val answer = TaskBackend(backends.gemini, "gemini-x").generate(prompt)
        assertEquals("An answer", answer.text)
        assertEquals(10, answer.usage?.promptTokens)
        val body = bodies.single()
        assertFalse(body.contains("responseMimeType"), body)
        assertFalse(body.contains("responseJsonSchema"), body)
        assertTrue(body.contains("\"maxOutputTokens\":4096"), body)
        assertTrue(body.contains("Answer briefly"), body)
    }
}
