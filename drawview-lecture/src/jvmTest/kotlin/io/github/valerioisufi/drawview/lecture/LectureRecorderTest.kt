package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.audio.CaptureEvent
import io.github.valerioisufi.drawview.lecture.audio.LECTURE_SAMPLE_RATE
import io.github.valerioisufi.drawview.lecture.audio.RecordingFile
import io.github.valerioisufi.drawview.lecture.audio.Wav
import io.github.valerioisufi.drawview.lecture.audio.toLittleEndianBytes
import io.github.valerioisufi.drawview.lecture.gemini.GeminiClient
import io.github.valerioisufi.drawview.lecture.gemini.GeminiException
import io.github.valerioisufi.drawview.lecture.handwriting.HandwritingReader
import io.github.valerioisufi.drawview.lecture.handwriting.InkBlock
import io.github.valerioisufi.drawview.lecture.local.OllamaClient
import io.github.valerioisufi.drawview.lecture.model.GeminiBackend
import io.github.valerioisufi.drawview.lecture.model.ModelBackends
import io.github.valerioisufi.drawview.lecture.model.OllamaBackend
import io.github.valerioisufi.drawview.lecture.testing.FakeMicrophone
import io.github.valerioisufi.drawview.lecture.testing.FakeNotebook
import io.github.valerioisufi.drawview.lecture.testing.MemorySecrets
import io.github.valerioisufi.drawview.lecture.transcription.SpeechHints
import io.github.valerioisufi.drawview.lecture.transcription.StreamingTranscriber
import io.github.valerioisufi.drawview.lecture.transcription.Transcriber
import io.github.valerioisufi.drawview.lecture.transcription.Transcribers
import io.github.valerioisufi.drawview.lecture.transcription.Transcription
import io.github.valerioisufi.drawview.model.geometry.Rect
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
                    """{"file":{"name":"files/rec","uri":"https://x/files/rec","mimeType":"audio/ogg","state":"ACTIVE"}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json")
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath, MemorySecrets()).apply {
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
        assertEquals("11", note.pageId)
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
        // The review reads the pages seen
        assertTrue(requests[2].contains("### Page 3\\nSlide 2 of PDF 1:\\nDerivate"), requests[2])
        assertTrue(requests[2].contains("### Page 2 (visible)"))
        assertEquals(2, lecture.transcript.size)
        // The second chunk repeated the same note: not duplicated
        assertEquals(listOf("Definizione di limite", "Esercizio d'esame"), lecture.suggestions.map { it.title })
        assertTrue(lecture.suggestions.last().fromFinalReview)
        assertEquals(ReviewStatus.DONE, lecture.reviewStatus)
        assertEquals("# Limiti\n- Definizione", lecture.finalReview)
        assertEquals(3, lecture.usage.requests)
        assertEquals(mapOf("TEXT" to 1200L, "AUDIO" to 1800L), lecture.usage.promptByModality)
        assertEquals(ModelProvider.GEMINI, lecture.reviewProvider)
        assertEquals("test-model", lecture.reviewModel)
        assertEquals(66_000L, lecture.elapsedMs)

        assertEquals(66_000L, RecordingFile(File(directory, "lecture").path).durationMs())
        val record = Json { ignoreUnknownKeys = true }.decodeFromString(
            LectureRecord.serializer(),
            File(directory, "lecture/lecture.json").readText()
        )
        assertEquals("7", record.documentId)
        assertEquals(LectureStatus.FINISHED, record.status)
        assertEquals(SuggestionStatus.INSERTED, record.suggestions.first().status)
        assertNotNull(record.finalReview)
        // The pages seen kept for a later review: their text, the image of the visible one
        assertEquals(
            listOf(
                SeenPage(PageContent(2, pageId = "12", slide = SlideText(1, 1, "Derivate"))),
                SeenPage(PageContent(1, pageId = "11", slide = SlideText(1, 0, "Limiti"), hasInk = true), "p11.jpg", visible = true, imageHash = contentHash(byteArrayOf(1, 2, 3)))
            ),
            record.pages
        )
        assertTrue(File(directory, "lecture/pages/p11.jpg").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun withoutAKeyTheRecordingGoesOn() = runBlocking<Unit> {
        val engine = MockEngine { respond("", HttpStatusCode.InternalServerError) }
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath, MemorySecrets())
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
        assertTrue(RecordingFile(File(directory, "lecture").path).samples() > 0)
    }

    @Test
    fun aCallInterruptsTheMicrophoneAndTheRecordingGoesOn() = runBlocking<Unit> {
        val engine = MockEngine { respond("", HttpStatusCode.InternalServerError) }
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath, MemorySecrets())
        val microphone = FakeMicrophone()
        val lecture = newLecture(settingsStore, engine)
        val recorder = LectureRecorder(lecture, settingsStore, microphone, FakeNotebook(), analyzes = false)
        recorder.start()
        microphone.speak(2.0, loud = true, random = Random(1))

        // A call: told, saved for the other devices; then the microphone is given back
        microphone.event(CaptureEvent.INTERRUPTED)
        withTimeout(5_000) { while (!lecture.microphoneInterrupted) delay(10) }
        microphone.event(CaptureEvent.DEVICE_CHANGED)
        microphone.event(CaptureEvent.RESUMED)
        withTimeout(5_000) { while (lecture.microphoneInterrupted) delay(10) }
        microphone.speak(2.0, loud = true, random = Random(2))
        recorder.stop()
        awaitFinished(lecture)

        assertFalse(lecture.microphoneInterrupted)
        assertEquals(4_000L, RecordingFile(File(directory, "lecture").path).durationMs())
    }

    @Test
    fun withoutTheModelsTheLectureIsRecordedWithThePagesSeen() = runBlocking<Unit> {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond("", HttpStatusCode.InternalServerError)
        }
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath, MemorySecrets())
        val microphone = FakeMicrophone()
        val lecture = newLecture(settingsStore, engine)
        val recorder = LectureRecorder(lecture, settingsStore, microphone, FakeNotebook(), analyzes = false)
        recorder.start()
        microphone.speak(62.0, loud = true, random = Random(1))
        recorder.catchUp()
        recorder.stop()
        awaitFinished(lecture)

        assertEquals(0, requests, "no model is asked")
        assertTrue(RecordingFile(File(directory, "lecture").path).samples() > 0)
        assertNull(lecture.finalReview)
        assertTrue(lecture.suggestions.isEmpty())
        // The pages seen, with the image of the visible one (for the review written elsewhere)
        val record = assertNotNull(LectureLibrary(directory.absolutePath).load(File(directory, "lecture").absolutePath))
        assertEquals(listOf("11", "12"), record.pages.map { it.content.pageId }.sorted())
        assertTrue(record.pages.any { it.image != null })
    }

    @Test
    fun repeatedNotesAreSuggestedOnce() = runBlocking<Unit> {
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath, MemorySecrets())
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
        documentId = record?.documentId ?: "7",
        directory = File(directory, name).absolutePath,
        record = record,
        settingsStore = settingsStore,
        backends = ModelBackends(
            GeminiBackend(GeminiClient(HttpClient(engine))) { settingsStore.apiKey },
            OllamaBackend(OllamaClient(HttpClient(engine))) { settingsStore.settings.localUrl }
        ),
        audioOutput = null,
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
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath, MemorySecrets()).apply {
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
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath, MemorySecrets()).apply {
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
        assertEquals(ModelProvider.LOCAL, lecture.reviewProvider)
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
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath, MemorySecrets()).apply {
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
                path == "/1" -> respond("""{"file":{"name":"files/rec","uri":"https://x/files/rec","mimeType":"audio/ogg","state":"ACTIVE"}}""", HttpStatusCode.OK, jsonHeaders)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath, MemorySecrets()).apply {
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
        assertTrue(RecordingFile(File(directory, "lecture").path).samples() > 0)

        // Opened again: the review can be written now
        val library = LectureLibrary(directory.absolutePath)
        val record = assertNotNull(library.load(File(directory, "lecture").absolutePath))
        assertEquals(LectureStatus.FINISHED, record.status)
        // The pages seen are in the record: the review needs no open notebook
        val seen = SeenPage(PageContent(4, pageId = "21", slide = SlideText(1, 3, "Teorema ponte")))
        val reloaded = newLecture(settingsStore, engine, record.copy(pages = listOf(seen)))
        assertEquals("Analisi 1", reloaded.documentTitle)
        assertTrue(reloaded.canGenerateReview)
        assertTrue(withContext(stateContext) { FinalReviewer(reloaded, settingsStore, handwriting = null).start(ModelProvider.GEMINI, notebook = null) })
        withTimeout(20_000) { while (reloaded.reviewStatus != ReviewStatus.DONE) delay(20) }
        assertEquals("# Limiti", reloaded.finalReview)
        assertTrue(reviewRequests.single().contains("### Page 5\\nSlide 4 of PDF 1:\\nTeorema ponte"), reviewRequests.single())
    }

    @Test
    fun aRecordingMadeElsewhereIsTranscribedAndReviewed() = runBlocking<Unit> {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val engine = MockEngine { request ->
            val body = request.body.toByteArray().decodeToString()
            if (request.url.encodedPath != "/api/chat") return@MockEngine respond("", HttpStatusCode.InternalServerError)
            requests.add(body)
            val answer = if (body.contains("The lecture is over")) """{"notesMarkdown":"# Limiti","missing":[]}""" else ""
            respond(ollamaAnswer(answer), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/x-ndjson"))
        }
        val local = TaskModel(provider = ModelProvider.LOCAL)
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath, MemorySecrets()).apply {
            update(LectureTask.entries.fold(settings) { s, task -> s.withTask(task, local) })
        }
        // 2 minutes of speech with a pause, recorded by a phone at 44.1 kHz stereo
        val random = Random(9)
        val phone = PcmResamplerInput.stereo44k(
            ShortArray(65 * LECTURE_SAMPLE_RATE) { random.nextInt(-6000, 6000).toShort() } +
                ShortArray(LECTURE_SAMPLE_RATE) +
                ShortArray(60 * LECTURE_SAMPLE_RATE) { random.nextInt(-6000, 6000).toShort() }
        )
        val input = File(directory, "lezione.wav").apply { writeBytes(phone) }
        val transcriber = FakeTranscriber()
        val lecture = newLecture(settingsStore, engine, courseContext = "Analisi 1")
        val pages = listOf(PageContent(0, pageId = "10", slide = SlideText(1, 0, "Limiti")), PageContent(1, pageId = "11", slide = SlideText(1, 1, "Derivate")))
        LectureImporter(lecture, settingsStore, transcriber).run(input.absolutePath, pages, listOf("10", "11"))

        assertEquals(LectureStatus.FINISHED, lecture.status)
        assertTrue(lecture.elapsedMs in 125_000L..127_000L, "${lecture.elapsedMs}")
        // Every chunk transcribed, the repeated part dropped
        assertTrue(lecture.transcript.size >= 2, lecture.transcript.toString())
        assertEquals(2_000L, lecture.transcript.first().startMs)
        assertTrue(transcriber.prompts.all { it.startsWith("Analisi 1") })
        assertTrue(transcriber.closed)
        assertEquals("# Limiti", lecture.finalReview)
        // The review knows the notebook, with no page on screen
        val review = requests.single { it.contains("The lecture is over") }
        assertTrue(review.contains("### Page 2\\nSlide 2 of PDF 1:\\nDerivate"), review)
        assertFalse(review.contains("(visible)"))
        assertTrue(File(directory, "lecture/audio.opus").exists())
    }

    @Test
    fun aRecordingImportedWithoutTheModelsKeepsItsAudioOnly() = runBlocking<Unit> {
        val engine = MockEngine { respond("", HttpStatusCode.InternalServerError) }
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath, MemorySecrets())
        val input = File(directory, "lezione.wav").apply { writeBytes(Wav.encode(ShortArray(5 * LECTURE_SAMPLE_RATE) { (it % 200 * 50).toShort() })) }
        val lecture = newLecture(settingsStore, engine)
        LectureImporter(lecture, settingsStore, FakeTranscriber()).run(input.absolutePath, emptyList(), emptyList(), reviews = false)

        // Finished, to be reviewed by a device with the models
        assertEquals(LectureStatus.FINISHED, lecture.status)
        assertEquals(5_000L, lecture.elapsedMs)
        assertTrue(lecture.transcript.isEmpty())
        assertNull(lecture.finalReview)
        assertEquals(5_000L, RecordingFile(File(directory, "lecture").path).durationMs())
    }

    /** A notebook with a block of handwriting on its page 2: written before the lecture, or of [strokes]. */
    private class InkNotebook(strokes: Set<String> = setOf("old")) : NotebookSource {
        val block = InkBlock(1, "11", "k1", strokes, Rect(10f, 10f, 50f, 30f))
        override val documentTitle = "Analisi 1"
        override suspend fun strokeIds() = setOf("old")
        override suspend fun capture(knownStrokeIds: Set<String>, textPages: Int, imagePages: Int) = NotebookSnapshot(
            documentTitle = documentTitle,
            pageCount = 3,
            visiblePageIndexes = listOf(1),
            pages = listOf(PageContent(1, pageId = "11", slide = SlideText(1, 0, "Limiti"), hasInk = true)),
            inkBlocks = listOf(block),
            strokeIds = setOf("old"),
            pageIds = listOf("10", "11", "12")
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
                path == "/1" -> respond("""{"file":{"name":"files/rec","uri":"https://x/files/rec","mimeType":"audio/ogg","state":"ACTIVE"}}""", HttpStatusCode.OK, jsonHeaders)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath, MemorySecrets()).apply {
            update(settings.copy(apiKey = "test-key"))
        }
        val microphone = FakeMicrophone()
        val lecture = newLecture(settingsStore, engine)
        val recorder = LectureRecorder(lecture, settingsStore, microphone, InkNotebook())
        recorder.start()
        microphone.speak(61.0, loud = true, random = Random(8))
        microphone.speak(1.0, loud = false, random = Random(8))
        withTimeout(20_000) { while (synchronized(requests) { requests.none { it.contains("You transcribe the handwritten notes") } }) delay(20) }
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

    @Test
    fun theReviewIsWrittenOnRequest() = runBlocking<Unit> {
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
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath, MemorySecrets()).apply {
            update(settings.copy(apiKey = "test-key", reviewAtTheEnd = false).withTask(LectureTask.TRANSCRIPTION, TaskModel(gemini = AssistantSettings.DEFAULT_MODEL)))
        }
        val microphone = FakeMicrophone()
        val lecture = newLecture(settingsStore, engine)
        val notebook = InkNotebook(strokes = setOf("new"))
        val recorder = LectureRecorder(lecture, settingsStore, microphone, notebook)
        recorder.start()
        // Written during the lecture: still being written at the only update
        microphone.speak(61.0, loud = true, random = Random(9))
        microphone.speak(1.0, loud = false, random = Random(9))
        recorder.stop()
        awaitFinished(lecture)

        // Over without a review, nor the handwriting read for it
        assertEquals(ReviewStatus.NONE, lecture.reviewStatus)
        assertTrue(requests.none { it.contains("The lecture is over") || it.contains("You transcribe the handwritten notes") })
        assertTrue(lecture.canGenerateReview)

        // Asked: the handwriting first, then the review
        val reviewer = FinalReviewer(lecture, settingsStore, HandwritingReader(File(directory, "handwriting.json").absolutePath, lecture.backends))
        assertTrue(withContext(stateContext) { reviewer.start(ModelProvider.GEMINI, notebook) })
        // A second request while it runs is refused
        assertFalse(withContext(stateContext) { reviewer.start(ModelProvider.GEMINI, notebook) })
        withTimeout(20_000) { while (lecture.reviewStatus != ReviewStatus.DONE) delay(20) }
        assertEquals("# Limiti", lecture.finalReview)
        val handwriting = requests.indexOfFirst { it.contains("You transcribe the handwritten notes") }
        val review = requests.indexOfFirst { it.contains("The lecture is over") }
        assertTrue(handwriting in 0 until review)
        assertTrue(requests[review].contains("lim f(x) = L"))
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

    @Test
    fun localSuggestionsReadTheTranscriptOfGemini() = runBlocking<Unit> {
        val speech = Collections.synchronizedList(mutableListOf<String>())
        val ollamaRequests = Collections.synchronizedList(mutableListOf<String>())
        val notes = """{"currentTopic":"Limiti","outline":"- Limiti","notes":[{"kind":"THEOREM","title":"Teorema ponte","content":"Vale per ogni successione","importance":3,"atSeconds":2}]}"""
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val body = request.body.toByteArray().decodeToString()
            when {
                path.endsWith("gemini-3.5-transcribe:generateContent") -> {
                    speech.add(body)
                    respond(geminiAnswer("Il teorema ponte vale per ogni successione."), HttpStatusCode.OK, jsonHeaders)
                }
                path == "/api/chat" -> {
                    ollamaRequests.add(body)
                    respond(ollamaAnswer(if (body.contains("\"messages\":[]")) "" else notes), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/x-ndjson"))
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val local = TaskModel(provider = ModelProvider.LOCAL, local = "gemma4:e4b")
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath, MemorySecrets()).apply {
            update(
                settings.copy(apiKey = "test-key", spokenLanguage = "it", reviewAtTheEnd = false)
                    .withTask(LectureTask.TRANSCRIPTION, TaskModel(gemini = "gemini-3.5-transcribe"))
                    .withTask(LectureTask.LIVE_NOTES, local)
                    .withTask(LectureTask.CATCH_UP, local)
            )
        }
        assertEquals(TranscriptionMode.SPEECH_CHUNKS, settingsStore.settings.transcription)
        val microphone = FakeMicrophone()
        val lecture = newLecture(settingsStore, engine, courseContext = "Analisi 1\nLimiti; successioni\nteorema ponte, epsilon-delta")
        val transcriber = Transcribers(lecture.backends.gemini).forUpdates(settingsStore.settings)
        val recorder = LectureRecorder(lecture, settingsStore, microphone, FakeNotebook(), transcriber)
        recorder.start()
        val random = Random(10)
        microphone.speak(61.0, loud = true, random = random)
        microphone.speak(1.0, loud = false, random = random)
        withTimeout(20_000) { while (lecture.suggestions.isEmpty()) delay(20) }

        // Transcribed by Gemini, then read by the local model
        val segment = lecture.transcript.single()
        assertEquals("Il teorema ponte vale per ogni successione.", segment.text)
        assertEquals(0L, segment.startMs)
        val live = ollamaRequests.single()
        assertTrue(live.contains("## New transcript"), live)
        assertTrue(live.contains("[0:00] Il teorema ponte vale per ogni successione."), live)
        assertTrue(speech.single().contains("\"languageCodes\":[\"it-IT\"]"))
        assertTrue(speech.single().contains("\"customVocabulary\":[\"Limiti\",\"successioni\",\"teorema ponte\",\"epsilon-delta\"]"), speech.single())

        // The last seconds: the new audio only, without the overlap with the previous chunk
        microphone.speak(4.0, loud = true, random = random)
        recorder.stop()
        awaitFinished(lecture)
        assertEquals(2, speech.size)
        val data = Regex("\"data\":\"([^\"]+)\"").find(speech[1])!!.groupValues[1]
        val seconds = (java.util.Base64.getDecoder().decode(data).size - Wav.HEADER_SIZE) / 2.0 / LECTURE_SAMPLE_RATE
        assertTrue(seconds in 4.0..5.0, "$seconds s")
        assertEquals(2, lecture.transcript.size)
        assertEquals(ReviewStatus.NONE, lecture.reviewStatus)
    }

    /** A live transcription: an utterance every 10 s of audio, the last one at the end of the audio. */
    private class FakeStream : StreamingTranscriber {
        lateinit var listener: StreamingTranscriber.Listener
        lateinit var scope: CoroutineScope
        val final = MutableStateFlow(0L)
        var receivedMs = 0L
        var lastEnd = 0L
        var count = 0
        var closed = false

        override fun start(scope: CoroutineScope, hints: () -> SpeechHints, listener: StreamingTranscriber.Listener) {
            this.scope = scope
            this.listener = listener
        }

        override fun push(samples: ShortArray) {
            receivedMs += samples.size * 1000L / LECTURE_SAMPLE_RATE
            if (receivedMs - lastEnd >= 10_000) utterance()
        }

        override fun endOfAudio() = utterance()

        private fun utterance() {
            val segment = TranscriptSegment(lastEnd, receivedMs, "frase ${++count}")
            lastEnd = receivedMs
            runBlocking { listener.onFinal(segment) }
            final.value = receivedMs
        }

        override suspend fun awaitFinal(untilMs: Long, timeoutMs: Long) {
            withTimeoutOrNull(timeoutMs) { final.first { it >= untilMs - 1_000 } }
        }

        override suspend fun close() {
            closed = true
        }
    }

    @Test
    fun theTranscriptMadeLiveIsSharedAmongTheUpdates() = runBlocking<Unit> {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val engine = MockEngine { request ->
            requests.add(request.body.toByteArray().decodeToString())
            respond(geminiAnswer(plainChunkAnswer), HttpStatusCode.OK, jsonHeaders)
        }
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath, MemorySecrets()).apply {
            update(settings.copy(apiKey = "test-key", reviewAtTheEnd = false))
        }
        assertEquals(TranscriptionMode.STREAMING, settingsStore.settings.transcription)
        val microphone = FakeMicrophone()
        val lecture = newLecture(settingsStore, engine)
        val stream = FakeStream()
        val recorder = LectureRecorder(lecture, settingsStore, microphone, FakeNotebook(), stream = stream)
        recorder.start()
        val random = Random(12)
        microphone.speak(35.0, loud = true, random = random)
        // While it is said
        withTimeout(5_000) { while (lecture.transcript.size < 3) delay(10) }
        stream.listener.onInterim("e quindi il limite")
        withTimeout(5_000) { while (lecture.interimTranscript.isEmpty()) delay(10) }
        microphone.speak(26.0, loud = true, random = random)
        microphone.speak(1.0, loud = false, random = random)
        withTimeout(20_000) { while (requests.isEmpty()) delay(20) }

        // The utterances of the first minute, as text
        val first = requests.single()
        assertTrue(first.contains("## New transcript"), first)
        assertTrue(first.contains("[0:00] frase 1") && first.contains("[0:50] frase 6"), first)
        assertFalse(first.contains("audio/wav"))
        assertEquals("", lecture.interimTranscript)

        microphone.speak(4.0, loud = true, random = random)
        recorder.stop()
        awaitFinished(lecture)
        // The last words become final at the end of the audio, and go to the last update only
        val last = requests[1].substringAfter("## New transcript")
        assertTrue(last.contains("frase 7"), last)
        // (The end of the transcript before is in the memory of the lecture)
        assertFalse(last.contains("frase 6"))
        assertEquals((1..7).map { "frase $it" }, lecture.transcript.map { it.text })
        assertTrue(stream.closed)
    }

    @Test
    fun aLectureIsTranscribedAgainByAnotherModel() = runBlocking<Unit> {
        val engine = MockEngine { respond("", HttpStatusCode.NotFound) }
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath, MemorySecrets())
        val old = listOf(TranscriptSegment(0, 60_000, "whisper 1"), TranscriptSegment(60_000, 120_000, "whisper 2"), TranscriptSegment(120_000, 130_000, "whisper 3"))
        val record = LectureRecord(id = "lecture", documentId = "7", startedAt = 1L, durationMs = 130_000, transcript = old)
        val lecture = newLecture(settingsStore, engine, record)
        lecture.recording.open()
        val random = Random(13)
        repeat(130) { second -> lecture.recording.append(ShortArray(LECTURE_SAMPLE_RATE) { if (second % 60 == 59) 0 else random.nextInt(-6000, 6000).toShort() }) }
        lecture.recording.finish()

        var allowed = 1
        val transcriber = object : Transcriber {
            override suspend fun transcribe(wav: ByteArray, hints: SpeechHints): Transcription {
                if (allowed-- <= 0) {
                    throw GeminiException(
                        429,
                        "You exceeded your current quota. * Quota exceeded for metric: generativelanguage.googleapis.com/generate_requests_per_model_per_day, " +
                            "limit: 100, model: gemini-3.5-transcribe Please retry in 7h39m28.572455s."
                    )
                }
                val seconds = (wav.size - Wav.HEADER_SIZE) / 2 / LECTURE_SAMPLE_RATE
                return Transcription(listOf(TranscriptSegment(0, seconds * 1000L, "Gemini $seconds s")), TokenUsage(requests = 1, promptTokens = 100))
            }
        }
        suspend fun runAgain() {
            withContext(stateContext) {
                lecture.status = LectureStatus.IMPORTING
                lecture.importStep = ImportStep.TRANSCRIBING
            }
            LectureRetranscriber(lecture, settingsStore, transcriber).run()
        }

        // The quota of the day over after the first piece: the piece transcribed is kept, the old transcript after it
        runAgain()
        assertEquals(listOf("Gemini", "whisper 2", "whisper 3"), lecture.transcript.map { if (it.text.startsWith("Gemini")) "Gemini" else it.text })
        assertEquals(1, lecture.usage.requests)
        val problem = lecture.problem as LectureProblem.QuotaExhausted
        assertEquals("gemini-3.5-transcribe", problem.model)
        assertTrue(problem.daily)
        assertEquals(27_568_572L, problem.retryInMs)
        assertEquals(LectureStatus.FINISHED, lecture.status)
        assertNull(lecture.importStep)
        assertTrue(File(directory, "lecture/lecture.json").readText().contains("Gemini"))

        // The quota back: all of it
        allowed = 100
        runAgain()
        assertTrue(lecture.transcript.size >= 2)
        assertTrue(lecture.transcript.all { it.text.startsWith("Gemini") })
        assertNull(lecture.problem)
    }

    @Test
    fun aRecordingIsTranscribedInParallelAndInOrder() = runBlocking<Unit> {
        val recording = RecordingFile(File(directory, "parallel").absolutePath)
        recording.open()
        // Four minutes of speech, with a pause every 50 s
        val random = Random(11)
        repeat(240) { second ->
            val pause = second % 50 == 49
            recording.append(ShortArray(LECTURE_SAMPLE_RATE) { if (pause) 0 else random.nextInt(-6000, 6000).toShort() })
        }
        recording.finish()

        val running = java.util.concurrent.atomic.AtomicInteger()
        var mostAtOnce = 0
        val transcriber = object : Transcriber {
            override val parallelism = 3
            override suspend fun transcribe(wav: ByteArray, hints: SpeechHints): Transcription {
                val now = running.incrementAndGet()
                synchronized(this) { mostAtOnce = maxOf(mostAtOnce, now) }
                val seconds = (wav.size - Wav.HEADER_SIZE) / 2 / LECTURE_SAMPLE_RATE
                // The first chunks are the slowest
                delay(300L - seconds)
                running.decrementAndGet()
                return Transcription(listOf(TranscriptSegment(0, seconds * 1000L, "${hints.courseContext} $seconds s")), TokenUsage(requests = 1))
            }
        }
        val chunks = mutableListOf<RecordingTranscriber.Chunk>()
        val progress = mutableListOf<Float>()
        RecordingTranscriber(recording, transcriber) { SpeechHints(courseContext = "Analisi") }.transcribe(240_000L) { chunk, done ->
            chunks.add(chunk)
            progress.add(done)
        }
        val segments = chunks.flatMap { it.segments }
        assertTrue(segments.size >= 3, segments.toString())
        assertEquals(segments.sortedBy { it.startMs }, segments)
        // No overlap: every chunk starts where the previous one ends
        segments.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs) }
        assertTrue(segments.all { it.text.startsWith("Analisi ") })
        assertEquals(progress.sorted(), progress)
        assertEquals(1f, progress.last())
        assertEquals(3, mostAtOnce)
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
        val settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath, MemorySecrets()).apply {
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

/** WAV files as other applications write them. */
private object PcmResamplerInput {
    /** [mono] at 16 kHz as a 44.1 kHz stereo WAV file (nearest sample, the same on both channels). */
    fun stereo44k(mono: ShortArray): ByteArray {
        val frames = (mono.size.toLong() * 44_100 / LECTURE_SAMPLE_RATE).toInt()
        val data = ShortArray(frames * 2) { i -> mono[((i / 2).toLong() * LECTURE_SAMPLE_RATE / 44_100).toInt()] }
        val header = Wav.header(data.size * 2L, sampleRate = 44_100).also {
            // Two channels: channels, byte rate and block align
            it[22] = 2
            val byteRate = 44_100 * 4
            for (b in 0 until 4) it[28 + b] = (byteRate shr (8 * b)).toByte()
            it[32] = 4
        }
        return header + data.toLittleEndianBytes()
    }
}
