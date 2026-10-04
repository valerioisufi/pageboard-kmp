package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.audio.AudioCapture
import io.github.valerioisufi.drawview.lecture.audio.LECTURE_SAMPLE_RATE
import io.github.valerioisufi.drawview.lecture.audio.Wav
import io.github.valerioisufi.drawview.lecture.gemini.GeminiClient
import io.github.valerioisufi.drawview.lecture.local.OllamaClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A whole lecture against a fake Gemini API: microphone -> chunks -> live analysis -> final review.
 */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class LectureSessionTest {
    private val directory: File = Files.createTempDirectory("lecture-test").toFile()
    private val stateContext = newSingleThreadContext("lecture-state")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
        stateContext.close()
    }

    private class FakeMicrophone : AudioCapture {
        var onSamples: ((ShortArray) -> Unit)? = null
        override fun start(onSamples: (ShortArray) -> Unit) {
            this.onSamples = onSamples
        }

        override fun stop() {
            onSamples = null
        }

        fun speak(seconds: Double, loud: Boolean, random: Random, quietLevel: Int = 0) {
            val total = (seconds * LECTURE_SAMPLE_RATE).toInt()
            var sent = 0
            while (sent < total) {
                val size = minOf(LECTURE_SAMPLE_RATE / 10, total - sent)
                onSamples?.invoke(
                    ShortArray(size) {
                        when {
                            loud -> random.nextInt(-6000, 6000).toShort()
                            quietLevel > 0 -> random.nextInt(-quietLevel, quietLevel).toShort()
                            else -> 0
                        }
                    }
                )
                sent += size
            }
        }
    }

    private class FakeNotebook : NotebookSource {
        override val documentTitle = "Analisi 1"
        override suspend fun strokeIds() = setOf("old")
        override suspend fun capture(knownStrokeIds: Set<String>, neighborPages: Int) = NotebookSnapshot(
            documentTitle = documentTitle,
            pageCount = 3,
            visiblePageIndexes = listOf(1),
            pages = listOf(NotebookPage(1, visible = true, slide = null, image = PageImage(1, byteArrayOf(1, 2, 3), isCrop = true), typedTexts = emptyList())),
            newWriting = emptyList(),
            slides = emptyList(),
            strokeIds = setOf("old", "new")
        )

        override suspend fun renderPages(pageIndexes: List<Int>) = pageIndexes.map { PageImage(it, byteArrayOf(9)) }
    }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun geminiAnswer(payload: String) = buildJsonObject {
        put("candidates", buildJsonArray {
            add(buildJsonObject {
                put("content", buildJsonObject {
                    put("role", "model")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", payload) }) })
                })
            })
        })
        put("usageMetadata", buildJsonObject {
            put("promptTokenCount", 1000)
            put("candidatesTokenCount", 100)
            put("promptTokensDetails", buildJsonArray {
                add(buildJsonObject {
                    put("modality", "TEXT")
                    put("tokenCount", 400)
                })
                add(buildJsonObject {
                    put("modality", "AUDIO")
                    put("tokenCount", 600)
                })
            })
        })
    }.toString()

    @Test
    fun recordsAnalyzesAndReviewsALecture() = runBlocking<Unit> {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val chunkAnswer = """{"transcript":"Il limite di f per x che tende ad a","currentTopic":"Limiti","professorPage":3,"outline":"- Limiti",
            "notes":[{"kind":"DEFINITION","title":"Definizione di limite","content":"Per ogni ε esiste δ","page":2,"importance":3,"atSeconds":10}]}"""
        val reviewAnswer = """{"notesMarkdown":"# Limiti\n- Definizione","missing":[{"kind":"EXAM_HINT","title":"Esercizio d'esame","content":"Calcolare un limite notevole","importance":3,"atSeconds":30}]}"""
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith(":generateContent") -> {
                    val body = request.body.toByteArray().decodeToString()
                    requests.add(body)
                    val answer = if (body.contains("The lecture is over")) reviewAnswer else chunkAnswer
                    respond(geminiAnswer(answer), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                path == "/upload/v1beta/files" -> respond("{}", HttpStatusCode.OK, headersOf("X-Goog-Upload-URL", "https://upload.test/1"))
                path == "/1" -> respond(
                    """{"file":{"name":"files/rec","uri":"https://x/files/rec","mimeType":"audio/wav","state":"ACTIVE"}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json")
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath).apply {
            update(settings.copy(apiKey = "test-key", model = "test-model"))
        }
        val microphone = FakeMicrophone()
        val session = LectureSession(
            documentId = 7,
            directory = File(directory, "lecture").absolutePath,
            settingsStore = settingsStore,
            notebook = FakeNotebook(),
            client = GeminiClient(HttpClient(engine)),
            capture = microphone,
            player = null,
            stateContext = stateContext
        )
        session.start()

        val random = Random(5)
        // 61 s of speech, a pause (cut of the first chunk), 4 s more
        microphone.speak(61.0, loud = true, random = random)
        microphone.speak(1.0, loud = false, random = random)
        microphone.speak(4.0, loud = true, random = random)

        withTimeout(20_000) {
            while (session.transcript.isEmpty() || session.suggestions.isEmpty()) delay(20)
        }
        assertEquals(1, requests.size)
        assertTrue(requests[0].contains("- Page 2 (visible): blank paper, with notes of the student (image: the written area)."))
        assertTrue(requests[0].contains("Update the outline in this answer."))
        assertTrue(requests[0].contains("\"mimeType\":\"audio/wav\""))
        val note = session.suggestions.single()
        assertEquals("Definizione di limite", note.title)
        assertEquals(1, note.pageIndex)
        assertEquals(10_000L, note.atMs)
        assertEquals(2, session.professorPageIndex)

        session.updateSuggestion(note.id, SuggestionStatus.INSERTED)
        session.stop()
        withTimeout(20_000) {
            while (session.status != LectureStatus.FINISHED) delay(20)
        }

        // The last seconds, then the final review
        assertEquals(3, requests.size)
        assertTrue(requests[1].contains("[p. 2, 0:10, inserted] Definizione di limite"), requests[1])
        // The outline was rewritten a few seconds before
        assertTrue(requests[1].contains("The outline is not updated in this answer."))
        assertTrue(requests[2].contains("https://x/files/rec"))
        assertEquals(2, session.transcript.size)
        // The second chunk repeated the same note: not duplicated
        assertEquals(listOf("Definizione di limite", "Esercizio d'esame"), session.suggestions.map { it.title })
        assertTrue(session.suggestions.last().fromFinalReview)
        assertEquals(ReviewStatus.DONE, session.reviewStatus)
        assertEquals("# Limiti\n- Definizione", session.finalReview)
        assertEquals(3, session.usage.requests)
        assertEquals(mapOf("TEXT" to 1200L, "AUDIO" to 1800L), session.usage.promptByModality)
        assertEquals(ReviewProvider.GEMINI, session.reviewProvider)
        assertEquals("test-model", session.reviewModel)
        assertEquals(66_000L, session.elapsedMs)

        val wav = File(directory, "lecture/audio.wav")
        assertEquals(Wav.HEADER_SIZE + 66L * LECTURE_SAMPLE_RATE * 2, wav.length())
        assertFalse(File(directory, "lecture/audio.pcm").exists())
        val record = Json { ignoreUnknownKeys = true }.decodeFromString(
            LectureRecord.serializer(),
            File(directory, "lecture/lecture.json").readText()
        )
        assertEquals(7, record.documentId)
        assertEquals(SuggestionStatus.INSERTED, record.suggestions.first().status)
        assertNotNull(record.finalReview)
    }

    @Test
    fun withoutAKeyTheRecordingGoesOn() = runBlocking<Unit> {
        val engine = MockEngine { respond("", HttpStatusCode.InternalServerError) }
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath)
        val microphone = FakeMicrophone()
        val session = LectureSession(
            documentId = 1,
            directory = File(directory, "lecture").absolutePath,
            settingsStore = settingsStore,
            notebook = FakeNotebook(),
            client = GeminiClient(HttpClient(engine)),
            capture = microphone,
            player = null,
            stateContext = stateContext
        )
        // The key of the environment, if any, would be used: not a test of this machine
        if (settingsStore.hasApiKey) return@runBlocking
        session.start()
        microphone.speak(62.0, loud = true, random = Random(1))
        microphone.speak(1.0, loud = false, random = Random(1))
        withTimeout(20_000) {
            while (session.problem == null) delay(20)
        }
        assertEquals(LectureProblem.MissingApiKey, session.problem)
        session.stop()
        withTimeout(20_000) {
            while (session.status != LectureStatus.FINISHED) delay(20)
        }
        assertTrue(File(directory, "lecture/audio.wav").length() > Wav.HEADER_SIZE)
    }

    private fun session(settingsStore: AssistantSettingsStore, engine: MockEngine, microphone: FakeMicrophone) = LectureSession(
        documentId = 3,
        directory = File(directory, "lecture").absolutePath,
        settingsStore = settingsStore,
        notebook = FakeNotebook(),
        client = GeminiClient(HttpClient(engine)),
        capture = microphone,
        player = null,
        stateContext = stateContext,
        localClient = OllamaClient(HttpClient(engine))
    )

    private suspend fun awaitFinished(session: LectureSession) = withTimeout(20_000) {
        while (session.status != LectureStatus.FINISHED) delay(20)
    }

    @Test
    fun minutesWithoutSpeechAreNotAnalyzed() = runBlocking<Unit> {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val engine = MockEngine { request ->
            requests.add(request.url.encodedPath)
            respond("", HttpStatusCode.InternalServerError)
        }
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath).apply {
            update(settings.copy(apiKey = "test-key", reviewProvider = ReviewProvider.LOCAL))
        }
        val microphone = FakeMicrophone()
        val session = session(settingsStore, engine, microphone)
        session.start()
        // A minute of room noise, no speech
        microphone.speak(61.0, loud = false, random = Random(2), quietLevel = 60)
        withTimeout(20_000) {
            while (session.usage.skippedUpdates == 0) delay(20)
        }
        session.stop()
        awaitFinished(session)

        assertTrue(requests.isEmpty(), requests.toString())
        assertEquals(1, session.usage.skippedUpdates)
        // Nothing to review locally without a transcript
        assertEquals(LectureProblem.NoTranscript, session.problem)
        assertEquals(ReviewStatus.FAILED, session.reviewStatus)
    }

    @Test
    fun theLocalModelWritesTheReviewWithoutTheRecording() = runBlocking<Unit> {
        val geminiRequests = Collections.synchronizedList(mutableListOf<String>())
        var ollamaRequest = ""
        val chunkAnswer = """{"transcript":"Il teorema ponte","currentTopic":"Limiti","outline":"- Limiti","notes":[]}"""
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val body = request.body.toByteArray().decodeToString()
            when {
                path.endsWith(":generateContent") -> {
                    geminiRequests.add(body)
                    if (body.contains("mediaResolution")) {
                        // An API version without the parameter
                        respond(
                            """{"error":{"code":400,"message":"Invalid JSON payload received. Unknown name \"mediaResolution\" at 'generation_config': Cannot find field.","status":"INVALID_ARGUMENT"}}""",
                            HttpStatusCode.BadRequest,
                            jsonHeaders
                        )
                    } else {
                        respond(geminiAnswer(chunkAnswer), HttpStatusCode.OK, jsonHeaders)
                    }
                }
                path == "/api/chat" -> {
                    ollamaRequest = body
                    val lines = listOf(
                        """{"message":{"role":"assistant","content":"{\"notesMarkdown\":\"# Limiti\""},"done":false}""",
                        """{"message":{"role":"assistant","content":",\"missing\":[]}"},"done":false}""",
                        """{"message":{"role":"assistant","content":""},"done":true,"prompt_eval_count":5000,"eval_count":2,"total_duration":1000000}"""
                    )
                    respond(lines.joinToString("\n") + "\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/x-ndjson"))
                }
                else -> respond("unexpected $path", HttpStatusCode.NotFound)
            }
        }
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath).apply {
            update(settings.copy(apiKey = "test-key", reviewProvider = ReviewProvider.LOCAL, localUrl = "http://ollama.test"))
        }
        val microphone = FakeMicrophone()
        val session = session(settingsStore, engine, microphone)
        session.start()
        val random = Random(4)
        microphone.speak(61.0, loud = true, random = random)
        microphone.speak(1.0, loud = false, random = random)
        withTimeout(20_000) {
            while (session.transcript.isEmpty()) delay(20)
        }
        session.stop()
        awaitFinished(session)

        // Refused once with the media resolution, then sent without it
        assertEquals(2, geminiRequests.size)
        assertFalse(geminiRequests[1].contains("mediaResolution"))
        // The local review: transcript and notes, no upload of the recording
        assertTrue(ollamaRequest.contains("\"model\":\"gemma4:12b\""), ollamaRequest)
        assertTrue(ollamaRequest.contains("[0:00] Il teorema ponte"))
        assertTrue(ollamaRequest.contains("\"images\":[\"AQID\"]"))
        assertTrue(ollamaRequest.contains("\"keep_alive\":0"))
        assertTrue(ollamaRequest.contains("\"format\":{"))
        assertEquals("# Limiti", session.finalReview)
        assertEquals(ReviewProvider.LOCAL, session.reviewProvider)
        assertEquals("gemma4:12b", session.reviewModel)
        assertEquals(ReviewStatus.DONE, session.reviewStatus)
        // The local review is free: only the live update is billed
        assertEquals(1, session.usage.requests)
    }
}
