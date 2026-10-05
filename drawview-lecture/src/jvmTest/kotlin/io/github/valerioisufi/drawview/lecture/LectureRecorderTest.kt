package io.github.valerioisufi.drawview.lecture

import androidx.compose.ui.geometry.Rect
import io.github.valerioisufi.drawview.lecture.audio.AudioCapture
import io.github.valerioisufi.drawview.lecture.audio.LECTURE_SAMPLE_RATE
import io.github.valerioisufi.drawview.lecture.audio.Wav
import io.github.valerioisufi.drawview.lecture.gemini.GeminiClient
import io.github.valerioisufi.drawview.lecture.handwriting.InkBlock
import io.github.valerioisufi.drawview.lecture.local.OllamaClient
import io.github.valerioisufi.drawview.lecture.model.GeminiBackend
import io.github.valerioisufi.drawview.lecture.model.ModelBackends
import io.github.valerioisufi.drawview.lecture.model.OllamaBackend
import io.github.valerioisufi.drawview.lecture.transcription.Transcriber
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
import kotlinx.coroutines.withContext
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
 * Whole lectures against fake model servers: microphone -> chunks -> live analysis -> final
 * review, with the notebook closed and opened again, the application exiting, the reload.
 */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class LectureRecorderTest {
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
        override suspend fun capture(knownStrokeIds: Set<String>, textPages: Int, imagePages: Int) = NotebookSnapshot(
            documentTitle = documentTitle,
            pageCount = 3,
            visiblePageIndexes = listOf(1),
            pages = listOf(
                PageContent(1, dbId = 11, slide = SlideText(1, 0, "Limiti"), hasInk = true),
                PageContent(2, dbId = 12, slide = SlideText(1, 1, "Derivate"))
            ),
            pageImages = listOf(PageImage(1, byteArrayOf(1, 2, 3))),
            strokeIds = setOf("old", "new"),
            pageDbIds = listOf(10, 11, 12)
        )
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
            update(settings.copy(apiKey = "test-key").withTask(LectureTask.LIVE_NOTES, TaskModel(gemini = "test-model")).withTask(LectureTask.FINAL_REVIEW, TaskModel(gemini = "test-model")))
        }
        val microphone = FakeMicrophone()
        val lecture = newLecture(settingsStore, engine)
        val recorder = LectureRecorder(lecture, settingsStore, microphone, FakeNotebook())
        recorder.start()

        val random = Random(5)
        // 61 s of speech, a pause (cut of the first chunk), 4 s more
        microphone.speak(61.0, loud = true, random = random)
        microphone.speak(1.0, loud = false, random = random)
        microphone.speak(4.0, loud = true, random = random)

        withTimeout(20_000) {
            while (lecture.transcript.isEmpty() || lecture.suggestions.isEmpty()) delay(20)
        }
        assertEquals(1, requests.size)
        assertTrue(requests[0].contains("### Page 2 (visible)\\nSlide 1 of PDF 1:\\nLimiti\\nHandwritten notes of the student: see the image."), requests[0])
        assertTrue(requests[0].contains("### Page 3\\nSlide 2 of PDF 1:\\nDerivate"))
        assertTrue(requests[0].contains("Update the outline in this answer."))
        assertTrue(requests[0].contains("\"mimeType\":\"audio/wav\""))
        val note = lecture.suggestions.single()
        assertEquals("Definizione di limite", note.title)
        assertEquals(1, note.pageIndex)
        assertEquals(11, note.pageDbId)
        assertEquals(10_000L, note.atMs)
        assertEquals(2, lecture.professorPageIndex)

        lecture.updateSuggestion(note.id, SuggestionStatus.INSERTED)
        recorder.stop()
        withTimeout(20_000) {
            while (lecture.status != LectureStatus.FINISHED) delay(20)
        }

        // The last seconds, then the final review
        assertEquals(3, requests.size)
        assertTrue(requests[1].contains("[p. 2, 0:10, inserted] Definizione di limite"), requests[1])
        // The outline was rewritten a few seconds before
        assertTrue(requests[1].contains("The outline is not updated in this answer."))
        assertTrue(requests[2].contains("https://x/files/rec"))
        // The review reads the pages seen
        assertTrue(requests[2].contains("### Page 3\\nSlide 2 of PDF 1:\\nDerivate"), requests[2])
        assertEquals(2, lecture.transcript.size)
        // The second chunk repeated the same note: not duplicated
        assertEquals(listOf("Definizione di limite", "Esercizio d'esame"), lecture.suggestions.map { it.title })
        assertTrue(lecture.suggestions.last().fromFinalReview)
        assertEquals(ReviewStatus.DONE, lecture.reviewStatus)
        assertEquals("# Limiti\n- Definizione", lecture.finalReview)
        assertEquals(3, lecture.usage.requests)
        assertEquals(mapOf("TEXT" to 1200L, "AUDIO" to 1800L), lecture.usage.promptByModality)
        assertEquals(ReviewProvider.GEMINI, lecture.reviewProvider)
        assertEquals("test-model", lecture.reviewModel)
        assertEquals(66_000L, lecture.elapsedMs)

        val wav = File(directory, "lecture/audio.wav")
        assertEquals(Wav.HEADER_SIZE + 66L * LECTURE_SAMPLE_RATE * 2, wav.length())
        assertFalse(File(directory, "lecture/audio.pcm").exists())
        val record = Json { ignoreUnknownKeys = true }.decodeFromString(
            LectureRecord.serializer(),
            File(directory, "lecture/lecture.json").readText()
        )
        assertEquals(7, record.documentId)
        assertEquals(LectureStatus.FINISHED, record.status)
        assertEquals(SuggestionStatus.INSERTED, record.suggestions.first().status)
        assertNotNull(record.finalReview)
        // The pages seen kept for a later review: their text, the image of the visible one
        assertEquals(
            listOf(
                SeenPage(PageContent(2, dbId = 12, slide = SlideText(1, 1, "Derivate"))),
                SeenPage(PageContent(1, dbId = 11, slide = SlideText(1, 0, "Limiti"), hasInk = true), "p11.jpg")
            ),
            record.pages
        )
        assertTrue(File(directory, "lecture/pages/p11.jpg").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun withoutAKeyTheRecordingGoesOn() = runBlocking<Unit> {
        val engine = MockEngine { respond("", HttpStatusCode.InternalServerError) }
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath)
        val microphone = FakeMicrophone()
        val lecture = newLecture(settingsStore, engine)
        val recorder = LectureRecorder(lecture, settingsStore, microphone, FakeNotebook())
        // The key of the environment, if any, would be used: not a test of this machine
        if (settingsStore.hasApiKey) return@runBlocking
        recorder.start()
        microphone.speak(62.0, loud = true, random = Random(1))
        microphone.speak(1.0, loud = false, random = Random(1))
        withTimeout(20_000) {
            while (lecture.problem == null) delay(20)
        }
        assertEquals(LectureProblem.MissingApiKey, lecture.problem)
        recorder.stop()
        withTimeout(20_000) {
            while (lecture.status != LectureStatus.FINISHED) delay(20)
        }
        assertTrue(File(directory, "lecture/audio.wav").length() > Wav.HEADER_SIZE)
    }

    @Test
    fun repeatedNotesAreSuggestedOnce() = runBlocking<Unit> {
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath)
        val lecture = newLecture(settingsStore, MockEngine { respond("", HttpStatusCode.NotFound) })
        val first = AnalysisNote(
            kind = "DEFINITION",
            title = "Funzione hash crittografica",
            content = "Deve generare un output pseudocasuale: non deve contenere informazioni sul messaggio originale."
        )
        withContext(stateContext) {
            lecture.addNotes(listOf(first), 0, 60_000, defaultPage = null, fromFinalReview = false)
            lecture.addNotes(
                listOf(
                    // The same content with another title, as the small models write it again
                    first.copy(title = "Proprietà delle funzioni hash", content = "- " + first.content + " (continua)"),
                    // Too short to tell
                    AnalysisNote(kind = "EXAM_HINT", title = "Esame", content = "Funzione hash all'esame"),
                    AnalysisNote(
                        kind = "DEFINITION",
                        title = "Collision resistance",
                        content = "Non deve essere possibile trovare due messaggi diversi con lo stesso valore hash."
                    )
                ),
                60_000, 120_000, defaultPage = null, fromFinalReview = false
            )
        }
        assertEquals(listOf("Funzione hash crittografica", "Esame", "Collision resistance"), lecture.suggestions.map { it.title })
    }

    private fun newLecture(
        settingsStore: AssistantSettingsStore,
        engine: MockEngine,
        record: LectureRecord? = null,
        name: String = "lecture",
        courseContext: String = ""
    ) = Lecture(
        documentId = record?.documentId ?: 7,
        directory = File(directory, name).absolutePath,
        record = record,
        settingsStore = settingsStore,
        backends = ModelBackends(
            GeminiBackend(GeminiClient(HttpClient(engine))) { settingsStore.apiKey },
            OllamaBackend(OllamaClient(HttpClient(engine))) { settingsStore.settings.localUrl }
        ),
        player = null,
        stateContext = stateContext,
        courseContext = { courseContext }
    ).apply { if (record == null) documentTitle = "Analisi 1" }

    private fun recorder(settingsStore: AssistantSettingsStore, engine: MockEngine, microphone: FakeMicrophone) =
        LectureRecorder(newLecture(settingsStore, engine), settingsStore, microphone, FakeNotebook())

    private suspend fun awaitFinished(lecture: Lecture) = withTimeout(20_000) {
        while (lecture.status != LectureStatus.FINISHED) delay(20)
    }

    @Test
    fun minutesWithoutSpeechAreNotAnalyzed() = runBlocking<Unit> {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val engine = MockEngine { request ->
            requests.add(request.url.encodedPath)
            respond("", HttpStatusCode.InternalServerError)
        }
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath).apply {
            update(settings.copy(apiKey = "test-key").withTask(LectureTask.FINAL_REVIEW, TaskModel(provider = ModelProvider.LOCAL)))
        }
        val microphone = FakeMicrophone()
        val recorder = recorder(settingsStore, engine, microphone)
        val lecture = recorder.lecture
        recorder.start()
        // A minute of room noise, no speech
        microphone.speak(61.0, loud = false, random = Random(2), quietLevel = 60)
        withTimeout(20_000) {
            while (lecture.usage.skippedUpdates == 0) delay(20)
        }
        recorder.stop()
        awaitFinished(lecture)

        assertTrue(requests.isEmpty(), requests.toString())
        assertEquals(1, lecture.usage.skippedUpdates)
        // Nothing to review locally without a transcript
        assertEquals(LectureProblem.NoTranscript, lecture.problem)
        assertEquals(ReviewStatus.FAILED, lecture.reviewStatus)
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
            update(settings.copy(apiKey = "test-key", localUrl = "http://ollama.test").withTask(LectureTask.FINAL_REVIEW, TaskModel(provider = ModelProvider.LOCAL)))
        }
        val microphone = FakeMicrophone()
        val recorder = recorder(settingsStore, engine, microphone)
        val lecture = recorder.lecture
        recorder.start()
        val random = Random(4)
        microphone.speak(61.0, loud = true, random = random)
        microphone.speak(1.0, loud = false, random = random)
        withTimeout(20_000) {
            while (lecture.transcript.isEmpty()) delay(20)
        }
        recorder.stop()
        awaitFinished(lecture)

        // Refused once with the media resolution, then sent without it
        assertEquals(2, geminiRequests.size)
        assertFalse(geminiRequests[1].contains("mediaResolution"))
        // The local review: transcript and notes, no upload of the recording
        assertTrue(ollamaRequest.contains("\"model\":\"gemma4:12b\""), ollamaRequest)
        assertTrue(ollamaRequest.contains("[0:00] Il teorema ponte"))
        assertTrue(ollamaRequest.contains("\"images\":[\"AQID\"]"))
        assertTrue(ollamaRequest.contains("\"keep_alive\":0"))
        assertTrue(ollamaRequest.contains("\"format\":{"))
        assertEquals("# Limiti", lecture.finalReview)
        assertEquals(ReviewProvider.LOCAL, lecture.reviewProvider)
        assertEquals("gemma4:12b", lecture.reviewModel)
        assertEquals(ReviewStatus.DONE, lecture.reviewStatus)
        // The local review is free: only the live update is billed
        assertEquals(1, lecture.usage.requests)
    }

    private val plainChunkAnswer = """{"transcript":"Il teorema ponte","currentTopic":"Limiti","outline":"- Limiti","notes":[]}"""

    @Test
    fun theRecordingGoesOnWithTheNotebookClosed() = runBlocking<Unit> {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val engine = MockEngine { request ->
            requests.add(request.body.toByteArray().decodeToString())
            respond(geminiAnswer(plainChunkAnswer), HttpStatusCode.OK, jsonHeaders)
        }
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath).apply {
            update(settings.copy(apiKey = "test-key"))
        }
        val microphone = FakeMicrophone()
        val notebook = FakeNotebook()
        val lecture = newLecture(settingsStore, engine)
        val recorder = LectureRecorder(lecture, settingsStore, microphone, notebook)
        recorder.start()
        assertTrue(lecture.isNotebookAttached)

        // The document is closed: the recording goes on
        recorder.detachNotebook(notebook)
        assertFalse(lecture.isNotebookAttached)
        val random = Random(3)
        microphone.speak(61.0, loud = true, random = random)
        microphone.speak(1.0, loud = false, random = random)
        withTimeout(20_000) { while (requests.isEmpty()) delay(20) }
        assertTrue(requests[0].contains("The notebook is closed"), requests[0])

        // Opened again: the pages are back
        recorder.attachNotebook(notebook)
        microphone.speak(60.0, loud = true, random = random)
        microphone.speak(1.0, loud = false, random = random)
        withTimeout(20_000) { while (requests.size < 2) delay(20) }
        assertTrue(requests[1].contains("## Notebook (3 pages)"), requests[1])
        assertFalse(requests[1].contains("The notebook is closed"))
        recorder.finishNow()
    }

    @Test
    fun exitingSavesTheLectureAndItsReviewIsWrittenLater() = runBlocking<Unit> {
        val reviewAnswer = """{"notesMarkdown":"# Limiti","missing":[]}"""
        val reviewRequests = Collections.synchronizedList(mutableListOf<String>())
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val body = request.body.toByteArray().decodeToString()
            if (body.contains("The lecture is over")) reviewRequests.add(body)
            when {
                path.endsWith(":generateContent") ->
                    respond(geminiAnswer(if (body.contains("The lecture is over")) reviewAnswer else plainChunkAnswer), HttpStatusCode.OK, jsonHeaders)
                path == "/upload/v1beta/files" -> respond("{}", HttpStatusCode.OK, headersOf("X-Goog-Upload-URL", "https://upload.test/1"))
                path == "/1" -> respond("""{"file":{"name":"files/rec","uri":"https://x/files/rec","mimeType":"audio/wav","state":"ACTIVE"}}""", HttpStatusCode.OK, jsonHeaders)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath).apply {
            update(settings.copy(apiKey = "test-key"))
        }
        val microphone = FakeMicrophone()
        val lecture = newLecture(settingsStore, engine)
        val recorder = LectureRecorder(lecture, settingsStore, microphone, FakeNotebook())
        recorder.start()
        microphone.speak(30.0, loud = true, random = Random(6))
        withTimeout(20_000) { while (lecture.elapsedMs < 30_000) delay(20) }

        // The application exits: no review, the audio and the lecture are saved
        recorder.finishNow()
        assertEquals(LectureStatus.FINISHED, lecture.status)
        assertEquals(ReviewStatus.NONE, lecture.reviewStatus)
        assertTrue(File(directory, "lecture/audio.wav").length() > Wav.HEADER_SIZE)
        assertFalse(File(directory, "lecture/audio.pcm").exists())

        // Opened again: the review can be written now
        val library = LectureLibrary(directory.absolutePath)
        val record = assertNotNull(library.load(File(directory, "lecture").absolutePath))
        assertEquals(LectureStatus.FINISHED, record.status)
        // The pages seen are in the record: the review needs no open notebook
        val seen = SeenPage(PageContent(4, dbId = 21, slide = SlideText(1, 3, "Teorema ponte")))
        val reloaded = newLecture(settingsStore, engine, record.copy(pages = listOf(seen)))
        assertEquals("Analisi 1", reloaded.documentTitle)
        assertTrue(reloaded.canGenerateReview)
        reloaded.generateFinalReview(ReviewProvider.GEMINI)
        withTimeout(20_000) { while (reloaded.reviewStatus != ReviewStatus.DONE) delay(20) }
        assertEquals("# Limiti", reloaded.finalReview)
        assertTrue(reviewRequests.single().contains("### Page 5\\nSlide 4 of PDF 1:\\nTeorema ponte"), reviewRequests.single())
    }

    /** A notebook with a block of handwriting written before the lecture on its page 2. */
    private class InkNotebook : NotebookSource {
        val block = InkBlock(1, 11, "k1", setOf("old"), Rect(10f, 10f, 50f, 30f))
        override val documentTitle = "Analisi 1"
        override suspend fun strokeIds() = setOf("old")
        override suspend fun capture(knownStrokeIds: Set<String>, textPages: Int, imagePages: Int) = NotebookSnapshot(
            documentTitle = documentTitle,
            pageCount = 3,
            visiblePageIndexes = listOf(1),
            pages = listOf(PageContent(1, dbId = 11, slide = SlideText(1, 0, "Limiti"), hasInk = true)),
            inkBlocks = listOf(block),
            strokeIds = setOf("old"),
            pageDbIds = listOf(10, 11, 12)
        )

        override suspend fun inkBlocks(pageIndexes: List<Int>) = if (1 in pageIndexes) listOf(block) else emptyList()
        override suspend fun renderInk(block: InkBlock) = byteArrayOf(7)
    }

    @Test
    fun theHandwritingIsReadOnceAndSentAsText() = runBlocking<Unit> {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val body = request.body.toByteArray().decodeToString()
            when {
                path.endsWith(":generateContent") -> {
                    requests.add(body)
                    val answer = when {
                        body.contains("You transcribe the handwritten notes") -> """{"blocks":[{"image":1,"text":"lim f(x) = L"}]}"""
                        body.contains("The lecture is over") -> """{"notesMarkdown":"# Limiti","missing":[]}"""
                        else -> plainChunkAnswer
                    }
                    respond(geminiAnswer(answer), HttpStatusCode.OK, jsonHeaders)
                }
                path == "/upload/v1beta/files" -> respond("{}", HttpStatusCode.OK, headersOf("X-Goog-Upload-URL", "https://upload.test/1"))
                path == "/1" -> respond("""{"file":{"name":"files/rec","uri":"https://x/files/rec","mimeType":"audio/wav","state":"ACTIVE"}}""", HttpStatusCode.OK, jsonHeaders)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath).apply {
            update(settings.copy(apiKey = "test-key"))
        }
        val microphone = FakeMicrophone()
        val lecture = newLecture(settingsStore, engine)
        val recorder = LectureRecorder(lecture, settingsStore, microphone, InkNotebook())
        recorder.start()
        microphone.speak(61.0, loud = true, random = Random(8))
        microphone.speak(1.0, loud = false, random = Random(8))
        withTimeout(20_000) { while (requests.none { it.contains("You transcribe the handwritten notes") }) delay(20) }
        microphone.speak(4.0, loud = true, random = Random(8))
        recorder.stop()
        awaitFinished(lecture)

        val live = requests.filter { it.contains("## New audio") }
        assertEquals(2, live.size)
        // Not read yet, then its text
        assertTrue(live[0].contains("Handwritten notes of the student, not transcribed yet."), live[0])
        assertTrue(live[1].contains("Written by hand by the student (transcribed):\\nlim f(x) = L"), live[1])
        assertEquals(1, requests.count { it.contains("You transcribe the handwritten notes") })
        assertTrue(requests.single { it.contains("The lecture is over") }.contains("lim f(x) = L"))
        // Kept for the next lectures of the notebook
        assertTrue(File(directory, "handwriting.json").readText().contains("lim f(x) = L"))
    }

    /** An answer of Ollama, streamed as its chat API does. */
    private fun ollamaAnswer(payload: String) = listOf(
        buildJsonObject {
            put("message", buildJsonObject {
                put("role", "assistant")
                put("content", payload)
            })
            put("done", false)
        },
        buildJsonObject {
            put("message", buildJsonObject {
                put("role", "assistant")
                put("content", "")
            })
            put("done", true)
            put("eval_count", 10)
        }
    ).joinToString("\n") + "\n"

    private class FakeTranscriber : Transcriber {
        var closed = false
        val prompts = Collections.synchronizedList(mutableListOf<String>())
        override suspend fun transcribe(wav: ByteArray, language: String, prompt: String): List<TranscriptSegment> {
            prompts.add(prompt)
            return listOf(TranscriptSegment(2_000, 5_000, "Il teorema ponte"), TranscriptSegment(5_000, 9_000, "vale per ogni successione"))
        }

        override suspend fun close() {
            closed = true
        }
    }

    @Test
    fun aLectureEntirelyOnThisComputer() = runBlocking<Unit> {
        val ollamaRequests = Collections.synchronizedList(mutableListOf<String>())
        val geminiRequests = Collections.synchronizedList(mutableListOf<String>())
        val notes = """{"currentTopic":"Limiti","outline":"- Limiti","notes":[{"kind":"THEOREM","title":"Teorema ponte","content":"Vale per ogni successione","importance":3,"atSeconds":2}]}"""
        val recap = """{"currentTopic":"Limiti","notes":[{"kind":"EXPLANATION","title":"Riepilogo","content":"Il teorema ponte collega limiti e successioni","importance":2,"atSeconds":0}]}"""
        val review = """{"notesMarkdown":"# Limiti","missing":[]}"""
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val body = request.body.toByteArray().decodeToString()
            when {
                path == "/api/chat" -> {
                    ollamaRequests.add(body)
                    val answer = when {
                        body.contains("\"messages\":[]") -> ""
                        body.contains("The lecture is over") -> review
                        body.contains("I missed something") -> recap
                        else -> notes
                    }
                    respond(ollamaAnswer(answer), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/x-ndjson"))
                }
                else -> {
                    geminiRequests.add(path)
                    respond("", HttpStatusCode.InternalServerError)
                }
            }
        }
        val local = TaskModel(provider = ModelProvider.LOCAL)
        // No Gemini key: nothing goes online
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath).apply {
            update(
                settings.withTask(LectureTask.TRANSCRIPTION, local)
                    .withTask(LectureTask.LIVE_NOTES, local.copy(local = "gemma4:e4b"))
                    .withTask(LectureTask.CATCH_UP, local.copy(local = "gemma4:e4b"))
                    .withTask(LectureTask.FINAL_REVIEW, local)
            )
        }
        val microphone = FakeMicrophone()
        val transcriber = FakeTranscriber()
        val lecture = newLecture(settingsStore, engine, courseContext = "Analisi 1\nLimiti\nteorema ponte")
        val recorder = LectureRecorder(lecture, settingsStore, microphone, FakeNotebook(), transcriber)
        recorder.start()
        val random = Random(8)
        microphone.speak(61.0, loud = true, random = random)
        microphone.speak(1.0, loud = false, random = random)
        withTimeout(20_000) { while (lecture.suggestions.isEmpty()) delay(20) }

        // The transcript of this computer, one segment per update
        assertEquals("Il teorema ponte vale per ogni successione", lecture.transcript.single().text)
        assertEquals(2_000L, lecture.transcript.single().startMs)
        val live = ollamaRequests.single()
        assertTrue(live.contains("\"model\":\"gemma4:e4b\""))
        assertTrue(live.contains("## New transcript"))
        assertTrue(live.contains("[0:02] Il teorema ponte"))
        assertTrue(live.contains("\"keep_alive\":600"))
        assertFalse(live.contains("audio/wav"))
        assertEquals(2_000L, lecture.suggestions.single().atMs)
        // The course context helps the transcription, and the models
        assertEquals("Analisi 1. Limiti. teorema ponte", transcriber.prompts.first())
        assertTrue(live.contains("Course, topics and terminology:\\nAnalisi 1\\nLimiti\\nteorema ponte"), live)
        assertEquals(1, lecture.usage.localRequests)
        assertEquals(0, lecture.usage.requests)

        // "I missed something": the transcript of the last minutes
        microphone.speak(2.0, loud = true, random = random)
        recorder.catchUp()
        microphone.speak(0.2, loud = true, random = random)
        withTimeout(20_000) { while (lecture.suggestions.size < 2) delay(20) }
        assertTrue(ollamaRequests.any { it.contains("I missed something") })
        assertEquals("Riepilogo", lecture.suggestions.last().title)

        recorder.stop()
        withTimeout(20_000) { while (lecture.status != LectureStatus.FINISHED) delay(20) }
        assertEquals("# Limiti", lecture.finalReview)
        assertEquals(ModelProvider.LOCAL, lecture.reviewProvider)
        // The live model unloaded and the transcription stopped at the end
        assertTrue(ollamaRequests.any { it.contains("\"messages\":[]") && it.contains("gemma4:e4b") })
        assertTrue(transcriber.closed)
        assertTrue(geminiRequests.isEmpty(), geminiRequests.toString())
    }
}
