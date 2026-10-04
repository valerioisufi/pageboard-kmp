package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.gemini.GeminiClient
import io.github.valerioisufi.drawview.lecture.gemini.GeminiFile
import io.github.valerioisufi.drawview.lecture.gemini.GenerateContentRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LecturePromptsTest {
    private val settings = AssistantSettings(courseContext = "Analisi 1, prof. Rossi: limiti, derivate")

    private val notebook = NotebookSnapshot(
        documentTitle = "Analisi 1",
        pageCount = 10,
        visiblePageIndexes = listOf(4),
        pageImages = listOf(PageImage(4, byteArrayOf(1, 2, 3)), PageImage(5, byteArrayOf(4, 5))),
        newWriting = listOf(PageImage(4, byteArrayOf(6))),
        typedTexts = mapOf(4 to listOf("Teorema ponte")),
        slides = emptyList(),
        strokeIds = setOf("a")
    )

    private val slides = listOf(
        UploadedSlides(
            SlideDocument("res1", "/tmp/slides.pdf", listOf(0 to 2, 1 to 3, 2 to 4)),
            GeminiFile(name = "files/abc", uri = "https://example.test/files/abc", mimeType = "application/pdf", state = "ACTIVE")
        )
    )

    private val memory = LectureMemory(
        elapsedMs = 125_000,
        outline = "- Limiti",
        currentTopic = "Limiti",
        previousTranscript = "abbiamo visto",
        suggestions = listOf(
            NoteSuggestion("n1", NoteKind.DEFINITION, "Limite", "Definizione epsilon-delta", pageIndex = 4, atMs = 60_000, status = SuggestionStatus.ALREADY_NOTED)
        )
    )

    private fun request(catchUp: Boolean = false): GenerateContentRequest = LecturePrompts.chunkRequest(
        settings = settings,
        memory = memory,
        notebook = notebook,
        slides = slides,
        audio = RequestAudio(byteArrayOf(9, 9, 9), startMs = 55_000, contextMs = 5_000, endMs = 125_000),
        catchUp = catchUp
    )

    @Test
    fun partsGoFromTheStableToTheVariableContent() {
        val parts = request().contents.single().parts
        val texts = parts.mapNotNull { it.text }
        assertTrue(texts.first().startsWith("## Course"))
        assertTrue(texts.first().contains("prof. Rossi"))
        // Slides right after the course, audio last
        assertEquals("https://example.test/files/abc", parts.first { it.fileData != null }.fileData?.fileUri)
        assertTrue(parts.indexOfFirst { it.fileData != null } < parts.indexOfFirst { it.text?.startsWith("## Lecture so far") == true })
        assertEquals("audio/wav", parts.last().inlineData?.mimeType)
        assertEquals("CQkJ", parts.last().inlineData?.data)
    }

    @Test
    fun notebookAndMemoryAreDescribedWithOneBasedPages() {
        val texts = request().contents.single().parts.mapNotNull { it.text }.joinToString("\n")
        assertTrue(texts.contains("The student is looking at page 5"))
        assertTrue(texts.contains("Notebook page 5 (visible):"))
        assertTrue(texts.contains("Notebook page 6 (nearby):"))
        assertTrue(texts.contains("Close-up of what the student wrote on page 5"))
        assertTrue(texts.contains("Typed text on page 5:\n- Teorema ponte"))
        assertTrue(texts.contains("PDF pages 1–3 are notebook pages 3–5"))
        assertTrue(texts.contains("[p. 5, 1:00, already noted] Limite"))
        assertTrue(texts.contains("Lecture time 1:00–2:05. The first 5 s"))
        assertFalse(texts.contains("I missed something"))
        assertTrue(request(catchUp = true).contents.single().parts.mapNotNull { it.text }.any { it.contains("I missed something") })
    }

    @Test
    fun requestUsesTheStructuredOutputOfTheGeminiApi() {
        val encoded = GeminiClient.json.encodeToJsonElement(GenerateContentRequest.serializer(), request()).jsonObject
        val config = encoded.getValue("generationConfig").jsonObject
        assertEquals("application/json", config.getValue("responseMimeType").jsonPrimitive.content)
        assertEquals("low", config.getValue("thinkingConfig").jsonObject.getValue("thinkingLevel").jsonPrimitive.content)
        val schema = config.getValue("responseJsonSchema").jsonObject
        assertEquals(
            setOf("transcript", "currentTopic", "outline", "notes"),
            schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet()
        )
        val part = encoded.getValue("contents").jsonArray.first().jsonObject.getValue("parts").jsonArray.last().jsonObject
        assertNotNull(part["inlineData"]?.jsonObject?.get("mimeType"))
        // No null fields
        assertFalse(part.containsKey("text"))
        assertNotNull(encoded["systemInstruction"])
    }

    @Test
    fun finalReviewSendsTheRecordingAndTheTranscript() {
        val audio = GeminiFile(name = "files/rec", uri = "https://example.test/files/rec", mimeType = "audio/wav", state = "ACTIVE")
        val request = LecturePrompts.finalReviewRequest(
            settings = settings,
            documentTitle = "Analisi 1",
            memory = memory,
            transcript = listOf(TranscriptSegment(0, 60_000, "Buongiorno")),
            pageImages = listOf(PageImage(4, byteArrayOf(1))),
            slides = slides,
            audioFile = audio
        )
        val parts = request.contents.single().parts
        assertEquals("https://example.test/files/rec", parts.last().fileData?.fileUri)
        assertTrue(parts.mapNotNull { it.text }.any { it.contains("[0:00] Buongiorno") })
        val schema = request.generationConfig?.responseJsonSchema as JsonObject
        assertEquals(listOf("notesMarkdown", "missing"), (schema["required"] as JsonArray).map { it.jsonPrimitive.content })
    }

    @Test
    fun mappingCompressesConsecutivePages() {
        assertEquals("not shown in the notebook", LecturePrompts.describeMapping(emptyList()))
        assertEquals(
            "PDF pages 1–2 are notebook pages 1–2; PDF page 3 is notebook page 7",
            LecturePrompts.describeMapping(listOf(0 to 0, 1 to 1, 2 to 6))
        )
    }
}

class LectureAnalysisTest {
    @Test
    fun parsesAnswersWithCodeFencesAndMissingFields() {
        val analysis = LectureAnalysis.parseChunk(
            """
            ```json
            {"transcript": "Oggi parliamo di limiti", "currentTopic": "Limiti", "outline": "- Limiti",
             "notes": [{"kind": "definition", "title": "Limite", "content": "${'$'}\\lim_{x \\to a} f(x) = L${'$'}", "page": 3, "importance": 3, "atSeconds": 12.5, "extra": true}],
             "unknown": 1}
            ```
            """.trimIndent()
        )
        assertEquals("Oggi parliamo di limiti", analysis.transcript)
        assertEquals(null, analysis.professorPage)
        val note = analysis.notes.single()
        assertEquals(NoteKind.DEFINITION, NoteKind.parse(note.kind))
        assertEquals("\$\\lim_{x \\to a} f(x) = L\$", note.content)
        assertEquals(3, note.page)
        assertEquals(12.5, note.atSeconds)
    }

    @Test
    fun unknownKindsAreOther() {
        assertEquals(NoteKind.OTHER, NoteKind.parse("anecdote"))
        assertEquals(NoteKind.EXAM_HINT, NoteKind.parse(" EXAM_HINT "))
    }

    @Test
    fun formatsLectureTimes() {
        assertEquals("0:00", formatLectureTime(0))
        assertEquals("1:05", formatLectureTime(65_000))
        assertEquals("1:02:03", formatLectureTime(3_723_000))
    }
}
