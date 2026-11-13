package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.local.OllamaClient
import io.github.valerioisufi.drawview.lecture.testing.MemorySecrets
import io.github.valerioisufi.drawview.lecture.transcription.EncoderArchive
import io.github.valerioisufi.drawview.lecture.transcription.JvmEncoderArchive
import io.github.valerioisufi.drawview.lecture.transcription.WhisperEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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

    private fun models(
        engine: MockEngine,
        archive: EncoderArchive? = null,
        whisper: WhisperEngine? = null,
        encoderSha256: (WhisperModel) -> String = { it.encoderSha256 }
    ) = LocalModels(
        AssistantSettingsStore(File(directory, "settings.json").absolutePath, MemorySecrets()),
        OllamaClient(HttpClient(engine)),
        HttpClient(engine),
        modelsDirectory = File(directory, "whisper").absolutePath,
        stateContext = stateContext,
        encoderArchive = archive,
        whisperEngine = whisper,
        encoderSha256 = encoderSha256
    )

    /** The loads of the model, to see the encoder prepared. */
    private class RecordingEngine : WhisperEngine {
        val loaded = mutableListOf<String>()
        var freed = 0

        override fun load(modelPath: String, useGpu: Boolean): Long {
            loaded += modelPath
            return 1L
        }

        override fun transcribe(handle: Long, samples: FloatArray, language: String, prompt: String, threads: Int): ByteArray? = null
        override fun abort(handle: Long) {}
        override fun free(handle: Long) {
            freed++
        }
    }

    /** A zip of [entries] (name to bytes), as the encoders are published. */
    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val encoder = zip(
        listOf(
            "ggml-base-encoder.mlmodelc/" to ByteArray(0),
            "ggml-base-encoder.mlmodelc/coremldata.bin" to ByteArray(319) { 1 },
            "ggml-base-encoder.mlmodelc/model.mil" to "program(1.0)".encodeToByteArray(),
            "ggml-base-encoder.mlmodelc/weights/weight.bin" to ByteArray(300_000) { it.toByte() },
            "__MACOSX/ggml-base-encoder.mlmodelc/._weights" to ByteArray(163)
        )
    )

    private fun serving(vararg files: Pair<String, ByteArray>) = MockEngine { call ->
        val file = files.firstOrNull { call.url.toString().endsWith("/" + it.first) }
        when {
            file != null -> respond(file.second, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, file.second.size.toString()))
            call.url.toString().contains("huggingface") -> respond("not found", HttpStatusCode.NotFound)
            // Ollama not running
            else -> throw IllegalStateException("Connection refused")
        }
    }

    @Test
    fun theEncoderFollowsTheModelOnTheNeuralEngineAndIsPrepared() = runBlocking<Unit> {
        val whisper = RecordingEngine()
        val models = models(
            serving("ggml-base.bin" to ByteArray(1_000) { 7 }, "ggml-base-encoder.mlmodelc.zip" to encoder),
            archive = JvmEncoderArchive,
            whisper = whisper,
            encoderSha256 = { sha256(encoder) }
        )
        assertTrue(models.usesNeuralEngine)
        models.downloadWhisper(WhisperModel.BASE)
        withTimeout(10_000) {
            while (WhisperModel.BASE !in models.encoders || models.downloads.isNotEmpty() || models.preparing.isNotEmpty()) delay(20)
        }
        assertEquals(null, models.downloadError)
        val extracted = File(directory, "whisper/ggml-base-encoder.mlmodelc")
        assertEquals(319, File(extracted, "coremldata.bin").length().toInt())
        assertEquals(300_000, File(extracted, "weights/weight.bin").length().toInt())
        assertEquals(setOf("coremldata.bin", "model.mil", "weights"), extracted.list()!!.toSet())
        // Nothing else left next to the models
        assertEquals(setOf("ggml-base.bin", "ggml-base-encoder.mlmodelc"), File(directory, "whisper").list()!!.toSet())
        // Loaded once, for Core ML to compile the encoder, and freed
        assertEquals(listOf(File(directory, "whisper/ggml-base.bin").absolutePath), whisper.loaded)
        assertEquals(1, whisper.freed)
    }

    @Test
    fun withoutANeuralEngineOnlyTheModelIsDownloaded() = runBlocking<Unit> {
        val models = models(serving("ggml-base.bin" to ByteArray(1_000) { 7 }, "ggml-base-encoder.mlmodelc.zip" to encoder))
        assertFalse(models.usesNeuralEngine)
        models.downloadWhisper(WhisperModel.BASE)
        withTimeout(10_000) { while (WhisperModel.BASE !in models.whisperModels || models.downloads.isNotEmpty()) delay(20) }
        models.downloadEncoder(WhisperModel.BASE)
        assertTrue(models.downloads.isEmpty())
        assertEquals(setOf("ggml-base.bin"), File(directory, "whisper").list()!!.toSet())
    }

    @Test
    fun anEncoderNotTheOneExpectedIsRefusedAndDeleted() = runBlocking<Unit> {
        val whisper = RecordingEngine()
        val models = models(serving("ggml-base-encoder.mlmodelc.zip" to encoder), archive = JvmEncoderArchive, whisper = whisper)
        models.downloadEncoder(WhisperModel.BASE)
        withTimeout(10_000) { while (models.downloadError == null) delay(20) }
        assertEquals("ggml-base-encoder.mlmodelc", models.downloadError?.first)
        withTimeout(10_000) { while (models.downloads.isNotEmpty()) delay(20) }
        assertEquals(emptySet(), File(directory, "whisper").list()!!.toSet())
        assertFalse(WhisperModel.BASE in models.encoders)
        assertTrue(whisper.loaded.isEmpty())
    }

    @Test
    fun anEncoderGoingOutOfItsDirectoryLeavesNothing() = runBlocking<Unit> {
        val evil = zip(
            listOf(
                "ggml-base-encoder.mlmodelc/coremldata.bin" to ByteArray(10),
                "ggml-base-encoder.mlmodelc/../../evil.bin" to ByteArray(10)
            )
        )
        val models = models(serving("ggml-base-encoder.mlmodelc.zip" to evil), archive = JvmEncoderArchive, encoderSha256 = { sha256(evil) })
        models.downloadEncoder(WhisperModel.BASE)
        withTimeout(10_000) { while (models.downloadError == null) delay(20) }
        withTimeout(10_000) { while (models.downloads.isNotEmpty()) delay(20) }
        assertEquals(emptySet(), File(directory, "whisper").list()!!.toSet())
        assertFalse(File(directory, "evil.bin").exists())
    }

    @Test
    fun anEncoderNotFoundLeavesNothing() = runBlocking<Unit> {
        val models = models(serving(), archive = JvmEncoderArchive)
        models.downloadEncoder(WhisperModel.SMALL)
        withTimeout(10_000) { while (models.downloadError == null) delay(20) }
        withTimeout(10_000) { while (models.downloads.isNotEmpty()) delay(20) }
        assertEquals("ggml-small-encoder.mlmodelc", models.downloadError?.first)
        assertEquals(emptySet(), File(directory, "whisper").list().orEmpty().toSet())
    }

    @Test
    fun theTwoTurboModelsShareTheirEncoder() {
        assertEquals("ggml-large-v3-turbo-encoder.mlmodelc", WhisperModel.LARGE_V3_TURBO_Q5.encoderFileName)
        assertEquals("ggml-large-v3-turbo-encoder.mlmodelc", WhisperModel.LARGE_V3_TURBO.encoderFileName)
        assertEquals("ggml-base-encoder.mlmodelc", WhisperModel.BASE.encoderFileName)
        assertEquals("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-encoder.mlmodelc.zip", WhisperModel.SMALL.encoderUrl)
    }

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
