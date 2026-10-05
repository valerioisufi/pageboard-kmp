package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.audio.Wav
import io.github.valerioisufi.drawview.lecture.transcription.TranscriptionException
import io.github.valerioisufi.drawview.lecture.transcription.WhisperEngine
import io.github.valerioisufi.drawview.lecture.transcription.WhisperNative
import io.github.valerioisufi.drawview.lecture.transcription.WhisperTranscriber
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WhisperTranscriberTest {
    private val directory: File = Files.createTempDirectory("whisper").toFile()
    private val model = File(directory, "ggml-base.bin").apply { writeText("") }

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private class FakeEngine(private val answer: String) : WhisperEngine {
        val loaded = mutableListOf<String>()
        val prompts = mutableListOf<String>()
        var samples = FloatArray(0)
        var freed = 0L

        override fun load(modelPath: String, useGpu: Boolean): Long {
            loaded.add(modelPath)
            return 42L
        }

        override fun transcribe(handle: Long, samples: FloatArray, language: String, prompt: String, threads: Int): ByteArray {
            this.samples = samples
            prompts.add("$language|$prompt")
            return answer.encodeToByteArray()
        }

        override fun abort(handle: Long) {}

        override fun free(handle: Long) {
            freed = handle
        }
    }

    @Test
    fun theModelIsLoadedOnceAndTheSegmentsAreJoined() = runBlocking<Unit> {
        val engine = FakeEngine(
            """{"segments":[{"start":0.0,"end":2.0,"text":" definito il conc"},{"start":2.0,"end":3.5,"text":"etto di integrità,"},""" +
                """{"start":3.5,"end":4.0,"text":" [Musica]"},{"start":4.0,"end":5.5,"text":" se vi ricordate."}]}"""
        )
        val transcriber = WhisperTranscriber(model.path, engine)
        val wav = Wav.encode(shortArrayOf(0, 16384, -32768))
        val segments = transcriber.transcribe(wav, "it", "Sicurezza: MAC e funzioni hash")
        assertEquals(
            listOf(TranscriptSegment(0, 3500, "definito il concetto di integrità,"), TranscriptSegment(4000, 5500, "se vi ricordate.")),
            segments
        )
        assertContentEquals(floatArrayOf(0f, 0.5f, -1f), engine.samples)
        assertEquals(listOf("it|Sicurezza: MAC e funzioni hash"), engine.prompts)

        transcriber.transcribe(wav, "", "")
        assertEquals(listOf(model.path), engine.loaded)
        assertEquals("auto|", engine.prompts.last())
        transcriber.close()
        assertEquals(42L, engine.freed)
    }

    @Test
    fun aMissingModelOrPlatformIsReported() = runBlocking<Unit> {
        val missing = WhisperTranscriber(File(directory, "missing.bin").path, FakeEngine("{}"))
        assertTrue(assertFailsWith<TranscriptionException> { missing.transcribe(Wav.encode(ShortArray(1)), "it") }.message.orEmpty().contains("not downloaded"))
        val unsupported = WhisperTranscriber(model.path, engine = null)
        assertTrue(assertFailsWith<TranscriptionException> { unsupported.transcribe(Wav.encode(ShortArray(1)), "it") }.message.orEmpty().contains("not available"))
    }

    @Test
    fun whisperCppIsBuiltIntoTheDesktop() {
        assertTrue(WhisperNative.isAvailable)
        // Not a model: refused without crashing
        assertEquals(0L, WhisperNative.load(model.path, useGpu = false))
    }
}
