package io.github.valerioisufi.pageboard.lecture

import io.github.valerioisufi.pageboard.lecture.model.DeviceBackend
import io.github.valerioisufi.pageboard.lecture.model.ModelPrompt
import io.github.valerioisufi.pageboard.lecture.model.ModelUse
import io.github.valerioisufi.pageboard.lecture.model.PromptPart
import io.github.valerioisufi.pageboard.lecture.model.SchemaMode
import io.github.valerioisufi.pageboard.ml.Accelerator
import io.github.valerioisufi.pageboard.ml.Embedder
import io.github.valerioisufi.pageboard.ml.EmbedderOptions
import io.github.valerioisufi.pageboard.ml.GenerationPart
import io.github.valerioisufi.pageboard.ml.GenerationRequest
import io.github.valerioisufi.pageboard.ml.Generator
import io.github.valerioisufi.pageboard.ml.GeneratorOptions
import io.github.valerioisufi.pageboard.ml.InstalledModel
import io.github.valerioisufi.pageboard.ml.InstalledModels
import io.github.valerioisufi.pageboard.ml.LoadedModels
import io.github.valerioisufi.pageboard.ml.ModelCatalog
import io.github.valerioisufi.pageboard.ml.ModelException
import io.github.valerioisufi.pageboard.ml.ModelInput
import io.github.valerioisufi.pageboard.ml.ModelKind
import io.github.valerioisufi.pageboard.ml.ModelRuntime
import io.github.valerioisufi.pageboard.ml.ModelSpec
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class DeviceBackendTest {
    private val directory = Files.createTempDirectory("device-backend").toFile()
    private val spec = ModelSpec(
        id = "gemma-test", name = "Gemma test", kind = ModelKind.GENERATION,
        inputs = setOf(ModelInput.TEXT, ModelInput.IMAGE), fileName = "model.litertlm", url = "https://example.invalid/model",
        sizeBytes = 4, sha256 = "00", memoryBytes = 1, maxImageTokens = 1120
    )
    private val installed = InstalledModels(ModelCatalog(listOf(spec)), directory.path)

    /** A runtime answering [answer], keeping the requests and the options it was given. */
    private class FakeRuntime(var answer: String) : ModelRuntime {
        val requests = mutableListOf<GenerationRequest>()
        val options = mutableListOf<GeneratorOptions>()
        val events = mutableListOf<String>()

        override fun supports(spec: ModelSpec): Boolean = true

        override suspend fun generator(model: InstalledModel, options: GeneratorOptions): Generator {
            this.options.add(options)
            events.add("load")
            return object : Generator {
                override val model = model
                override val accelerator = Accelerator.CPU

                override suspend fun generate(request: GenerationRequest, onProgress: (Int) -> Unit): String {
                    requests.add(request)
                    return answer
                }

                override fun close() {
                    events.add("close")
                }
            }
        }

        override suspend fun embedder(model: InstalledModel, options: EmbedderOptions): Embedder = throw ModelException("no embedder")
    }

    private val schema = buildJsonObject {
        put("type", "object")
        putJsonArray("required") { add("blocks") }
    }

    private fun prompt(withImage: Boolean) = ModelPrompt(
        system = "Read the notes",
        parts = listOfNotNull(PromptPart.Text("Block 1:"), if (withImage) PromptPart.Image(byteArrayOf(1, 2)) else null, PromptPart.Text("End")),
        schema = schema,
        maxOutputTokens = 1000,
        thinkingLevel = "low"
    )

    @AfterTest
    fun deleteDirectory() {
        directory.deleteRecursively()
    }

    private fun install() {
        File(directory, "gemma-test").mkdirs()
        File(directory, "gemma-test/model.litertlm").writeBytes(ByteArray(4))
        installed.refresh()
    }

    @Test
    fun aModelOfTheCatalogAnswersTheSchemaAskedAndItsJsonIsTakenOut() = runBlocking<Unit> {
        install()
        val runtime = FakeRuntime("""Here it is: ```json
            {"type": "object", "required": ["blocks"]}
            {"blocks": [{"image": 1, "text": "x^2"}]}
            ```""")
        val loaded = LoadedModels(runtime)
        val backend = DeviceBackend(installed, loaded)
        val answer = backend.generate(prompt(withImage = true), "gemma-test", ModelUse.LIVE)
        assertEquals("""{"blocks": [{"image": 1, "text": "x^2"}]}""", answer.text)
        assertNull(answer.usage)
        val request = runtime.requests.single()
        assertTrue(request.system.contains("follows this JSON Schema"), request.system)
        assertNull(request.jsonSchema)
        assertEquals(listOf("Block 1:", null, "End"), request.parts.map { (it as? GenerationPart.Text)?.text })
        val options = runtime.options.single()
        assertTrue(options.images)
        assertEquals(DeviceBackend.MAX_IMAGE_TOKENS, options.imageTokens)
        // Kept for the next request of the lecture, freed at its end
        assertEquals(setOf("gemma-test"), loaded.loaded.value)
        backend.release(setOf("gemma-test"))
        assertEquals(listOf("load", "close"), runtime.events)
    }

    @Test
    fun theSchemaCanBeEnforcedAndAModelForOnceIsFreedAtOnce() = runBlocking<Unit> {
        install()
        val runtime = FakeRuntime("""{"blocks": []}""")
        val loaded = LoadedModels(runtime)
        val backend = DeviceBackend(installed, loaded, SchemaMode.ENFORCED)
        assertEquals("""{"blocks": []}""", backend.generate(prompt(withImage = false), "gemma-test", ModelUse.ONCE).text)
        val request = runtime.requests.single()
        assertEquals("Read the notes", request.system)
        assertEquals(schema.toString(), request.jsonSchema)
        assertEquals(listOf("Block 1:\n\nEnd"), request.parts.map { (it as GenerationPart.Text).text })
        assertEquals(0, runtime.options.single().imageTokens)
        assertEquals(emptySet(), loaded.loaded.value)
    }

    @Test
    fun aModelNotDownloadedIsAProblemOfTheLocalModels() = runBlocking<Unit> {
        val backend = DeviceBackend(installed, LoadedModels(FakeRuntime("")))
        val error = assertFailsWith<ModelException> { backend.generate(prompt(withImage = false), "gemma-test") }
        assertEquals(false, backend.isRetryable(error))
        assertIs<LectureProblem.LocalModelUnavailable>(Lecture.problemOf(error))
        assertNull(backend.isRetryable(IllegalArgumentException()))
        assertNull(backend.unavailable())
        assertNull(backend.newPromptCache())
    }
}
