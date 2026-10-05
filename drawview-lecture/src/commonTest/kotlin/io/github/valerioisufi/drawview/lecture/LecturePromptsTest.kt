package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.gemini.GeminiClient
import io.github.valerioisufi.drawview.lecture.gemini.GeminiFile
import io.github.valerioisufi.drawview.lecture.gemini.GenerateContentRequest
import io.github.valerioisufi.drawview.lecture.model.GeminiBackend
import io.github.valerioisufi.drawview.lecture.model.PromptPart
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
        pages = listOf(
            // Slide with notes: the written area
            NotebookPage(4, visible = true, slide = SlidePage("res1", 2), image = PageImage(4, byteArrayOf(1, 2, 3), isCrop = true), typedTexts = listOf("Teorema ponte")),
            // Slide without notes: in the PDF, no image
            NotebookPage(3, visible = false, slide = SlidePage("res1", 1), image = null, typedTexts = emptyList()),
            // Empty page
            NotebookPage(5, visible = false, slide = null, image = null, typedTexts = emptyList()),
            // Slide of a PDF not sent: the whole page
            NotebookPage(6, visible = false, slide = SlidePage("res2", 0), image = PageImage(6, byteArrayOf(4, 5)), typedTexts = emptyList())
        ),
        newWriting = listOf(PageImage(4, byteArrayOf(6), isCrop = true)),
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

    private fun request(
        catchUp: Boolean = false,
        includeOutline: Boolean = true,
        mediaResolution: String? = GeminiBackend.MEDIA_RESOLUTION
    ): GenerateContentRequest = GeminiBackend.toRequest(
        LecturePrompts.chunkPrompt(
            settings = settings,
            memory = memory,
            notebook = notebook,
            slides = slides,
            speech = RequestAudio(byteArrayOf(9, 9, 9), startMs = 55_000, contextMs = 5_000, endMs = 125_000),
            catchUp = catchUp,
            includeOutline = includeOutline
        ),
        mediaResolution
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
        assertTrue(texts.contains("- Page 5 (visible): slide 3 of PDF 1, with notes of the student (image: the written area)."))
        assertTrue(texts.contains("- Page 4 (nearby): slide 2 of PDF 1, nothing written by the student."))
        assertTrue(texts.contains("- Page 6 (nearby): blank paper, nothing written by the student."))
        assertTrue(texts.contains("- Page 7 (nearby): a slide (see the image) (image: the whole page)."))
        assertTrue(texts.contains("Notebook page 5, area written by the student:"))
        assertTrue(texts.contains("Notebook page 7:"))
        assertTrue(texts.contains("Close-up of what the student wrote on page 5"))
        assertTrue(texts.contains("Typed text on page 5:\n- Teorema ponte"))
        assertTrue(texts.contains("PDF pages 1–3 are notebook pages 3–5"))
        assertTrue(texts.contains("[p. 5, 1:00, already noted] Limite"))
        assertTrue(texts.contains("Lecture time 1:00–2:05. The first 5 s"))
        assertFalse(texts.contains("I missed something"))
        assertTrue(request(catchUp = true).contents.single().parts.mapNotNull { it.text }.any { it.contains("I missed something") })
    }

    @Test
    fun onlyTheWrittenAreasAndTheUnreadableSlidesAreImages() {
        val images = request().contents.single().parts.mapNotNull { it.inlineData }.filter { it.mimeType == "image/jpeg" }
        // Page 5 (written area), page 7 (slide not in the PDFs), close-up of the new writing
        assertEquals(3, images.size)
    }

    @Test
    fun theOutlineIsRequestedOnlyWhenDue() {
        fun schemaOf(request: GenerateContentRequest) =
            GeminiClient.json.encodeToJsonElement(GenerateContentRequest.serializer(), request).jsonObject
                .getValue("generationConfig").jsonObject.getValue("responseJsonSchema").jsonObject
        val withOutline = schemaOf(request(includeOutline = true))
        assertTrue("outline" in withOutline.getValue("properties").jsonObject)
        val withoutOutline = schemaOf(request(includeOutline = false))
        assertFalse("outline" in withoutOutline.getValue("properties").jsonObject)
        assertFalse(withoutOutline.getValue("required").jsonArray.any { it.jsonPrimitive.content == "outline" })
        assertTrue(request(includeOutline = false).contents.single().parts.mapNotNull { it.text }.any { it.contains("The outline is not updated in this answer.") })
    }

    @Test
    fun mediaResolutionIsSentUnlessRefused() {
        fun configOf(request: GenerateContentRequest) =
            GeminiClient.json.encodeToJsonElement(GenerateContentRequest.serializer(), request).jsonObject.getValue("generationConfig").jsonObject
        assertEquals("MEDIA_RESOLUTION_MEDIUM", configOf(request()).getValue("mediaResolution").jsonPrimitive.content)
        assertFalse("mediaResolution" in configOf(request(mediaResolution = null)))
    }

    @Test
    fun localReviewUsesTheTranscriptAndTheNotes() {
        val prompt = LecturePrompts.finalReviewPrompt(
            settings = settings,
            documentTitle = "Analisi 1",
            memory = memory,
            transcript = listOf(TranscriptSegment(0, 60_000, "Buongiorno"), TranscriptSegment(60_000, 120_000, "Oggi i limiti")),
            pageImages = listOf(PageImage(7, byteArrayOf(2)), PageImage(4, byteArrayOf(1), isCrop = true))
        )
        assertTrue(prompt.system.contains("notesMarkdown"))
        val texts = prompt.parts.filterIsInstance<PromptPart.Text>().joinToString("\n") { it.text }
        assertTrue(texts.contains("[0:00] Buongiorno\n[1:00] Oggi i limiti"))
        assertTrue(texts.contains("the recording is not available"))
        // Neither audio nor files (a local model receives text and images), images in page order
        assertTrue(prompt.parts.none { it is PromptPart.Audio || it is PromptPart.File })
        assertEquals(listOf(1.toByte(), 2.toByte()), prompt.parts.filterIsInstance<PromptPart.Image>().map { it.jpeg.single() })
        assertTrue(texts.indexOf("Notebook page 5, area written by the student") < texts.indexOf("Notebook page 8"))
    }

    @Test
    fun withTheLocalTranscriptTheModelReadsTextOnly() {
        val prompt = LecturePrompts.chunkPrompt(
            settings = settings,
            memory = memory,
            notebook = notebook,
            slides = emptyList(),
            speech = RequestTranscript(
                context = emptyList(),
                segments = listOf(TranscriptSegment(60_000, 64_000, "Il teorema ponte"), TranscriptSegment(64_000, 70_000, "vale per ogni successione")),
                startMs = 60_000,
                contextMs = 0,
                endMs = 120_000
            )
        )
        assertTrue(prompt.parts.none { it is PromptPart.Audio || it is PromptPart.File })
        val texts = prompt.parts.filterIsInstance<PromptPart.Text>().joinToString("\n") { it.text }
        assertTrue(texts.contains("## New transcript"))
        // Times from the start of the request, as atSeconds
        assertTrue(texts.contains("[0:00] Il teorema ponte\n[0:04] vale per ogni successione"), texts)
        assertFalse(prompt.system.contains("- transcript:"))
        assertFalse(prompt.schema.getValue("properties").jsonObject.containsKey("transcript"))
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
        val request = GeminiBackend.toRequest(
            LecturePrompts.finalReviewPrompt(
                settings = settings,
                documentTitle = "Analisi 1",
                memory = memory,
                transcript = listOf(TranscriptSegment(0, 60_000, "Buongiorno")),
                pageImages = listOf(PageImage(4, byteArrayOf(1))),
                slides = slides,
                audioFile = audio
            )
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
    fun bulletsAreWrittenWithDashes() {
        assertEquals(
            "# Hash\n- **Collisioni**: rare\n    - *nota* in corsivo\n- SHA-256",
            LectureAnalysis.normalizeMarkdown("# Hash\n*   **Collisioni**: rare\n    *   *nota* in corsivo\n+ SHA-256")
        )
        val review = LectureAnalysis.parseFinalReview("""{"notesMarkdown":"*   uno\n*   due","missing":[]}""")
        assertEquals("- uno\n- due", review.notesMarkdown)
        assertEquals("- **A**: uno\n- **B**: due\ntre", LectureAnalysis.normalizeMarkdown("- **A**: uno<br>- **B**: due <BR/> tre"))
    }

    @Test
    fun latexWrittenWithSingleBackslashesIsKept() {
        // As the local models write it: "\frac" and "\neq" are escapes of JSON, "\alpha" is not valid
        val analysis = LectureAnalysis.parseChunk(
            """{"notes":[{"kind":"FORMULA","title":"F","importance":2,
               "content":"${'$'}\frac{a}{b} \neq \alpha \times \rightarrow \beta \underline{x}${'$'}\nNuova riga\ne poi \"citato\" \u00e8\\\\"}]}"""
        )
        assertEquals(
            "\$\\frac{a}{b} \\neq \\alpha \\times \\rightarrow \\beta \\underline{x}\$\nNuova riga\ne poi \"citato\" è\\\\",
            analysis.notes.single().content
        )
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

class AssistantSettingsTest {
    @Test
    fun theFirstSettingsBecomeTasks() {
        val saved = """{"apiKey":"k","model":"gemini-old","reviewProvider":"LOCAL","localModel":"gemma4:27b"}"""
        val json = AssistantSettingsStore.json
        val settings = AssistantSettingsStore.migrate(json.decodeFromString(AssistantSettings.serializer(), saved), json.parseToJsonElement(saved).jsonObject)
        assertEquals(TaskModel(ModelProvider.LOCAL, "gemini-old", "gemma4:27b"), settings.task(LectureTask.FINAL_REVIEW))
        assertEquals("gemini-old", settings.task(LectureTask.LIVE_NOTES).gemini)
        assertEquals(ModelProvider.GEMINI, settings.task(LectureTask.LIVE_NOTES).provider)
        assertEquals(ModelProvider.LOCAL, settings.reviewProvider)
        assertTrue(settings.sendsAudio)
    }

    @Test
    fun theSettingsSavedWithThePricesAreRead() {
        val saved = """{"apiKey":"k","tasks":{},"priceInput":0.75,"priceCachedInput":0.075,"priceOutput":3.75,"whisperLanguage":"it"}"""
        val settings = AssistantSettingsStore.json.decodeFromString(AssistantSettings.serializer(), saved)
        assertEquals("it", settings.whisperLanguage)
    }

    @Test
    fun whatIsSentToGoogle() {
        val local = TaskModel(provider = ModelProvider.LOCAL)
        assertEquals(setOf(SharedData.AUDIO, SharedData.NOTEBOOK), AssistantSettings().sentToGoogle)
        val localAudio = AssistantSettings().withTask(LectureTask.TRANSCRIPTION, local)
        assertEquals(setOf(SharedData.TRANSCRIPT, SharedData.NOTEBOOK), localAudio.sentToGoogle)
        val onlyReviewOnline = LectureTask.entries.fold(AssistantSettings()) { s, task -> s.withTask(task, local) }
            .withTask(LectureTask.FINAL_REVIEW, TaskModel())
        assertEquals(setOf(SharedData.TRANSCRIPT, SharedData.NOTEBOOK), onlyReviewOnline.sentToGoogle)
        val allLocal = LectureTask.entries.fold(AssistantSettings()) { s, task -> s.withTask(task, local) }
        assertTrue(allLocal.sentToGoogle.isEmpty())
    }

    @Test
    fun localSuggestionsNeedTheLocalTranscription() {
        val settings = AssistantSettings()
            .withTask(LectureTask.LIVE_NOTES, TaskModel(provider = ModelProvider.LOCAL))
            .consistent()
        assertEquals(ModelProvider.LOCAL, settings.task(LectureTask.TRANSCRIPTION).provider)
        assertFalse(settings.sendsAudio)
        assertEquals(AssistantSettings.DEFAULT_LOCAL_LIVE_MODEL, AssistantSettings().task(LectureTask.LIVE_NOTES).local)
    }
}

