package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.local.OllamaClient
import io.github.valerioisufi.drawview.lecture.testing.MemorySecrets
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class LocalModelsTest {
    private val directory: File = Files.createTempDirectory("models").toFile()
    private val stateContext = newSingleThreadContext("models-state")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
        stateContext.close()
    }

    private fun models(engine: MockEngine) = LocalModels(
        AssistantSettingsStore(File(directory, "settings.json").absolutePath, MemorySecrets()),
        OllamaClient(HttpClient(engine)),
        HttpClient(engine),
        modelsDirectory = File(directory, "whisper").absolutePath,
        stateContext = stateContext
    )

    @Test
    fun aWhisperModelIsDownloadedNextToTheOthers() = runBlocking<Unit> {
        val bytes = ByteArray(700_000) { it.toByte() }
        val engine = MockEngine { call ->
            when {
                call.url.toString().endsWith("ggml-base.bin") ->
                    respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, bytes.size.toString()))
                // Ollama not running
                else -> throw IllegalStateException("Connection refused")
            }
        }
        val models = models(engine)
        models.downloadWhisper(WhisperModel.BASE)
        withTimeout(10_000) { while (WhisperModel.BASE !in models.whisperModels) delay(20) }
        val file = File(directory, "whisper/ggml-base.bin")
        assertTrue(file.readBytes().contentEquals(bytes))
        assertFalse(File(directory, "whisper/ggml-base.bin.part").exists())
        withTimeout(10_000) { while (models.downloads.isNotEmpty() || models.ollamaState == LocalModels.OllamaState.CHECKING) delay(20) }
        assertEquals(LocalModels.OllamaState.NOT_FOUND, models.ollamaState)
    }

    @Test
    fun aFailedDownloadLeavesNoModel() = runBlocking<Unit> {
        val engine = MockEngine { call ->
            if (call.url.toString().endsWith("ggml-small.bin")) respond("not found", HttpStatusCode.NotFound)
            else throw IllegalStateException("Connection refused")
        }
        val models = models(engine)
        models.downloadWhisper(WhisperModel.SMALL)
        withTimeout(10_000) { while (models.downloadError == null) delay(20) }
        assertEquals("ggml-small.bin", models.downloadError?.first)
        assertFalse(File(directory, "whisper/ggml-small.bin").exists())
        assertFalse(WhisperModel.SMALL in models.whisperModels)
    }
}
