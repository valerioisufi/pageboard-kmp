package io.github.valerioisufi.pageboard.lecture.model

import io.github.valerioisufi.pageboard.lecture.AssistantSettings
import io.github.valerioisufi.pageboard.lecture.LectureProblem
import io.github.valerioisufi.pageboard.lecture.LectureTask
import io.github.valerioisufi.pageboard.lecture.LocalEngine
import io.github.valerioisufi.pageboard.lecture.ModelProvider
import io.github.valerioisufi.pageboard.lecture.TaskModel
import io.github.valerioisufi.pageboard.lecture.gemini.GeminiClient
import io.github.valerioisufi.pageboard.lecture.gemini.GeminiException
import io.github.valerioisufi.pageboard.lecture.local.OllamaClient
import io.github.valerioisufi.pageboard.lecture.local.OllamaException
import io.github.valerioisufi.pageboard.ml.Embedder
import io.github.valerioisufi.pageboard.ml.EmbedderOptions
import io.github.valerioisufi.pageboard.ml.Generator
import io.github.valerioisufi.pageboard.ml.GeneratorOptions
import io.github.valerioisufi.pageboard.ml.InstalledModel
import io.github.valerioisufi.pageboard.ml.InstalledModels
import io.github.valerioisufi.pageboard.ml.LoadedModels
import io.github.valerioisufi.pageboard.ml.ModelCatalog
import io.github.valerioisufi.pageboard.ml.ModelException
import io.github.valerioisufi.pageboard.ml.ModelRuntime
import io.github.valerioisufi.pageboard.ml.ModelSpec
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
        val bothLocal = settings.withTask(LectureTask.CATCH_UP, TaskModel(ModelProvider.LOCAL))
        assertEquals(mapOf(ModelProvider.LOCAL to setOf("gemma4:e4b")), bothLocal.copy(localEngine = LocalEngine.OLLAMA).liveModels)
        assertEquals(mapOf(ModelProvider.LOCAL to setOf("gemma-4-e4b")), bothLocal.liveModels)
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
    fun theLocalRequestsGoToTheEngineOfTheSettingsWhenItIsHere() {
        val device = DeviceBackend(InstalledModels(ModelCatalog.Default, "/nowhere"), LoadedModels(NoRuntime))
        val withDevice = ModelBackends(backends.gemini, backends.local, device)
        val settings = AssistantSettings(localEngine = LocalEngine.DEVICE)
            .withTask(LectureTask.LIVE_NOTES, TaskModel(ModelProvider.LOCAL, local = "gemma4:e4b", device = "gemma-4-e2b"))
        val live = withDevice.forTask(settings, LectureTask.LIVE_NOTES)
        assertSame(device, live.backend)
        assertEquals("gemma-4-e2b", live.model)
        // Ollama chosen, or no models of the device given by the application
        assertSame(backends.local, withDevice.forTask(settings.copy(localEngine = LocalEngine.OLLAMA), LectureTask.LIVE_NOTES).backend)
        val withoutDevice = backends.forTask(settings, LectureTask.LIVE_NOTES)
        assertSame(backends.local, withoutDevice.backend)
        assertEquals("gemma4:e4b", withoutDevice.model)
    }

    private object NoRuntime : ModelRuntime {
        override fun supports(spec: ModelSpec): Boolean = false

        override suspend fun generator(model: InstalledModel, options: GeneratorOptions): Generator = throw ModelException("none")

        override suspend fun embedder(model: InstalledModel, options: EmbedderOptions): Embedder = throw ModelException("none")
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
