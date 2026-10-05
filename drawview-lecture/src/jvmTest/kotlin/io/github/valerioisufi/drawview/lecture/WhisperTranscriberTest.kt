package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.platform.LocalProcess
import io.github.valerioisufi.drawview.lecture.transcription.TranscriptionException
import io.github.valerioisufi.drawview.lecture.transcription.WhisperTranscriber
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WhisperTranscriberTest {
    private val directory: File = Files.createTempDirectory("whisper").toFile()
    private val server = File(directory, "whisper-server").apply { writeText("") }
    private val model = File(directory, "ggml-base.bin").apply { writeText("") }

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private class FakeProcess : LocalProcess {
        var stopped = false
        override val isAlive get() = !stopped
        override fun recentOutput() = ""
        override fun stop() {
            stopped = true
        }
    }

    @Test
    fun theServerIsStartedOnceAndAnswersWithSegments() = runBlocking<Unit> {
        var ready = false
        var inference = ""
        val commands = mutableListOf<List<String>>()
        val process = FakeProcess()
        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Get -> if (ready) respond("<html/>", HttpStatusCode.OK) else throw IllegalStateException("Connection refused")
                request.url.encodedPath == "/inference" -> {
                    inference = request.body.toByteArray().decodeToString()
                    respond(
                        """{"text":"Ciao a tutti","segments":[{"start":0.0,"end":1.5,"text":" Ciao a tutti"},{"start":1.5,"end":3.0,"text":" [Musica]"},{"start":3.0,"end":4.25,"text":" oggi i limiti"}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val transcriber = WhisperTranscriber(server.path, model.path, HttpClient(engine), port = 1234) { command ->
            commands.add(command)
            ready = true
            process
        }
        val segments = transcriber.transcribe(byteArrayOf(1, 2, 3), "it")
        assertEquals(listOf(TranscriptSegment(0, 1500, "Ciao a tutti"), TranscriptSegment(3000, 4250, "oggi i limiti")), segments)
        assertEquals(listOf(server.path, "-m", model.path, "--host", "127.0.0.1", "--port", "1234", "-l", "auto"), commands.single())
        assertTrue(inference.contains("language"), inference)
        assertTrue(inference.contains("verbose_json"))

        // Already running: not started again
        transcriber.transcribe(byteArrayOf(4), "it")
        assertEquals(1, commands.size)
        transcriber.close()
        assertTrue(process.stopped)
    }

    @Test
    fun wordsSplitBetweenSegmentsAreJoinedAndAFailedRequestIsRepeated() = runBlocking<Unit> {
        var requests = 0
        var inference = ""
        var connection: String? = null
        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Get -> respond("<html/>", HttpStatusCode.OK)
                // The connection closed by the server after a few requests
                ++requests == 1 -> throw IOException("Broken pipe")
                else -> {
                    inference = request.body.toByteArray().decodeToString()
                    connection = request.headers[HttpHeaders.Connection]
                    respond(
                        """{"segments":[{"start":0.0,"end":2.0,"text":" definito il conc"},{"start":2.0,"end":3.5,"text":"etto di integrità,"},""" +
                            """{"start":3.5,"end":5.0,"text":" se vi ric"},{"start":5.0,"end":5.5,"text":"ordate."}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        }
        // A server already running on the port
        val transcriber = WhisperTranscriber(server.path, model.path, HttpClient(engine), port = 1234) { null }
        val segments = transcriber.transcribe(byteArrayOf(1), "it", "Sicurezza: MAC e funzioni hash")
        assertEquals(
            listOf(TranscriptSegment(0, 3500, "definito il concetto di integrità,"), TranscriptSegment(3500, 5500, "se vi ricordate.")),
            segments
        )
        assertEquals(2, requests)
        assertTrue(inference.contains("Sicurezza: MAC e funzioni hash"), inference)
        assertEquals("close", connection)
    }

    @Test
    fun aMissingModelIsReported() = runBlocking<Unit> {
        val transcriber = WhisperTranscriber(server.path, File(directory, "missing.bin").path, HttpClient(MockEngine { respond("") })) { null }
        val error = assertFailsWith<TranscriptionException> { transcriber.transcribe(byteArrayOf(1), "auto") }
        assertTrue(error.message.orEmpty().contains("whisper model not found"))
    }
}
