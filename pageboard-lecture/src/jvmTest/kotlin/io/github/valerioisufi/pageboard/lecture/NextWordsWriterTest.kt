package io.github.valerioisufi.pageboard.lecture

import io.github.valerioisufi.pageboard.lecture.testing.MemorySecrets
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
import io.github.valerioisufi.pageboard.ml.ModelDownloads
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The next words of a text being written, from the model of the device, and its setting. */
class NextWordsWriterTest {
    private val directory = Files.createTempDirectory("next-words").toFile()
    private val spec = ModelSpec(
        id = "gemma-test", name = "Gemma test", kind = ModelKind.GENERATION, inputs = setOf(ModelInput.TEXT),
        fileName = "model.litertlm", url = "https://example.invalid/model", sizeBytes = 4, sha256 = "00", memoryBytes = 1
    )
    private val installed = InstalledModels(ModelCatalog(listOf(spec)), directory.path)

    /** A model answering [answer], keeping the requests. */
    private class FakeRuntime(private val answer: String) : ModelRuntime {
        val requests = mutableListOf<GenerationRequest>()
        override fun supports(spec: ModelSpec): Boolean = true
        override suspend fun generator(model: InstalledModel, options: GeneratorOptions): Generator = object : Generator {
            override val model = model
            override val accelerator = Accelerator.CPU
            override suspend fun generate(request: GenerationRequest, onProgress: (Int) -> Unit): String {
                requests += request
                return answer
            }
            override fun close() {}
        }
        override suspend fun embedder(model: InstalledModel, options: EmbedderOptions): Embedder = throw ModelException("no embedder")
    }

    @AfterTest
    fun deleteDirectory() {
        directory.deleteRecursively()
    }

    private fun models(runtime: ModelRuntime, install: Boolean = true): DeviceModels {
        if (install) {
            File(directory, "gemma-test").mkdirs()
            File(directory, "gemma-test/model.litertlm").writeBytes(ByteArray(4))
        }
        installed.refresh()
        return DeviceModels(installed, LoadedModels(runtime), ModelDownloads(installed))
    }

    @Test
    fun theModelGivesTheNextWordsOfTheParagraph() = runBlocking {
        val runtime = FakeRuntime("\"of the matrix are real.\"\nMore text")
        val words = NextWordsWriter.nextWords("A symmetric matrix has eigenvalues: the eigenvalues", models(runtime))
        assertEquals("of the matrix are real.", words)
        val request = runtime.requests.single()
        assertEquals(16, request.maxOutputTokens)
        assertEquals(0f, request.temperature)
        assertEquals("A symmetric matrix has eigenvalues: the eigenvalues", (request.parts.single() as GenerationPart.Text).text)
    }

    @Test
    fun nothingWithoutAModelOrWithTooLittleWritten() = runBlocking {
        assertNull(NextWordsWriter.nextWords("A symmetric matrix has", models(FakeRuntime("x"), install = false)), "no model installed")
        assertNull(NextWordsWriter.nextWords("A matrix", models(FakeRuntime("is square"))), "two words only")
        assertNull(NextWordsWriter.nextWords("One two three", null))
    }

    @Test
    fun theAnswerIsCleaned() {
        assertEquals("are real", NextWordsWriter.cleaned("the eigenvalues are real", "Of a symmetric matrix the eigenvalues"), "the text written again left out")
        assertNull(NextWordsWriter.cleaned("the eigenvalues", "Of a symmetric matrix the eigenvalues"), "nothing new")
        assertNull(NextWordsWriter.cleaned("  \n ", "anything"))
        assertEquals("one two three four five six seven eight", NextWordsWriter.cleaned("one two three four five six seven eight nine ten", "x y z"))
    }

    @Test
    fun theSettingIsOffInTheSettingsOfBeforeAndKeptOnceChosen() {
        val file = File(directory, "assistant-settings.json")
        file.writeText(javaClass.getResource("/settings/assistant-settings-v2.json")!!.readText())
        val store = AssistantSettingsStore(file.absolutePath, MemorySecrets(), canTranscribe = true)
        assertFalse(store.settings.writingFromModel)
        assertEquals(LocalEngine.DEVICE, store.settings.localEngine, "the rest read as it was")
        assertEquals(45, store.settings.chunkSeconds)

        store.update(store.settings.copy(writingFromModel = true))
        assertTrue(AssistantSettingsStore(file.absolutePath, MemorySecrets(), canTranscribe = true).settings.writingFromModel)
    }
}
