package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.gemini.GeminiClient
import io.github.valerioisufi.drawview.lecture.gemini.GeminiFile
import io.github.valerioisufi.drawview.lecture.gemini.GenerateContentRequest
import io.github.valerioisufi.drawview.lecture.model.GeminiBackend
import io.github.valerioisufi.drawview.lecture.model.ModelPrompt
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
    private val settings = AssistantSettings()

    private val notebook = NotebookSnapshot(
        documentTitle = "Analisi 1",
        pageCount = 10,
        visiblePageIndexes = listOf(4),
        pages = listOf(
            // The visible page: a slide with typed and handwritten notes
            PageContent(4, dbId = 15, slide = SlideText(1, 2, "Teorema ponte\nSia f: A -> R"), typedTexts = listOf("Vale anche per x -> inf"), hasInk = true),
            // A slide without notes
            PageContent(3, dbId = 14, slide = SlideText(1, 1, "Limiti di funzioni")),
            // Empty page
            PageContent(5, dbId = 16),
            // A scanned slide (no text) with a picture of the student
            PageContent(6, dbId = 17, slide = SlideText(2, 0, ""), imageCount = 1)
        ),
        pageImages = listOf(PageImage(4, byteArrayOf(1, 2, 3))),
        newWriting = listOf(PageImage(4, byteArrayOf(6), isCrop = true)),
        strokeIds = setOf("a")
    )

    private val memory = LectureMemory(
        elapsedMs = 125_000,
        outline = "- Limiti",
        currentTopic = "Limiti",
        previousTranscript = "abbiamo visto",
        suggestions = listOf(
            NoteSuggestion("n1", NoteKind.DEFINITION, "Limite", "Definizione epsilon-delta", pageIndex = 4, atMs = 60_000, status = SuggestionStatus.ALREADY_NOTED)
        ),
        courseContext = "Analisi 1, prof. Rossi\nLimiti; derivate\nteorema ponte, epsilon-delta"
    )

    private fun prompt(catchUp: Boolean = false, includeOutline: Boolean = true): ModelPrompt = LecturePrompts.chunkPrompt(
        settings = settings,
        memory = memory,
        notebook = notebook,
        speech = RequestAudio(byteArrayOf(9, 9, 9), startMs = 55_000, contextMs = 5_000, endMs = 125_000),
        catchUp = catchUp,
        includeOutline = includeOutline
    )

    private fun request(
        catchUp: Boolean = false,
        includeOutline: Boolean = true,
        mediaResolution: String? = GeminiBackend.MEDIA_RESOLUTION
    ): GenerateContentRequest = GeminiBackend.toRequest(prompt(catchUp, includeOutline), mediaResolution)

    @Test
    fun partsGoFromTheStableToTheVariableContent() {
        val parts = request().contents.single().parts
        val texts = parts.mapNotNull { it.text }
        assertTrue(texts.first().startsWith("## Course"))
        assertTrue(texts.first().contains("prof. Rossi"))
        assertTrue(texts.indexOfFirst { it.startsWith("## Lecture so far") } < texts.indexOfFirst { it.startsWith("## Notebook") })
        // No file: the slides are text
        assertTrue(parts.none { it.fileData != null })
        assertEquals("audio/wav", parts.last().inlineData?.mimeType)
        assertEquals("CQkJ", parts.last().inlineData?.data)
    }

    @Test
    fun notebookAndMemoryAreDescribedWithOneBasedPages() {
        val texts = request().contents.single().parts.mapNotNull { it.text }.joinToString("\n")
        assertTrue(texts.contains("The student is looking at page 5"))
        assertTrue(texts.contains("### Page 5 (visible)\nSlide 3 of PDF 1:\nTeorema ponte\nSia f: A -> R\nTyped by the student:\n- Vale anche per x -> inf\nHandwritten notes of the student: see the image."), texts)
        assertTrue(texts.contains("### Page 4\nSlide 2 of PDF 1:\nLimiti di funzioni"))
        assertTrue(texts.contains("### Page 6\nBlank paper."))
        assertTrue(texts.contains("### Page 7\nSlide 1 of PDF 2, without text.\nPictures inserted by the student: 1."))
        // In the order of the notebook
        assertTrue(texts.indexOf("### Page 4") < texts.indexOf("### Page 5") && texts.indexOf("### Page 5") < texts.indexOf("### Page 6"))
        assertTrue(texts.contains("Notebook page 5, as the student sees it:"))
        assertTrue(texts.contains("Close-up of what the student wrote on notebook page 5"))
        assertTrue(texts.contains("[p. 5, 1:00, already noted] Limite"))
        assertTrue(texts.contains("Lecture time 1:00–2:05. The first 5 s"))
        assertFalse(texts.contains("I missed something"))
        assertTrue(request(catchUp = true).contents.single().parts.mapNotNull { it.text }.any { it.contains("I missed something") })
    }

    @Test
    fun theSameNotebookForEveryProvider() {
        val gemini = request().contents.single().parts
        val local = prompt().parts
        // The visible page and the close-up of the new writing
        assertEquals(2, gemini.mapNotNull { it.inlineData }.count { it.mimeType == "image/jpeg" })
        assertEquals(2, local.count { it is PromptPart.Image })
        assertEquals(
            local.filterIsInstance<PromptPart.Text>().map { it.text },
            gemini.mapNotNull { it.text }
        )
    }

    @Test
    fun theCourseContextIsWrittenFromTheNotebookAndHelpsTheTranscription() {
        val prompt = LecturePrompts.courseContextPrompt(AssistantSettings(whisperLanguage = "it"), "Analisi 1", notebook.pages)
        assertTrue(prompt.system.contains("short context in Italian"))
        val text = prompt.parts.filterIsInstance<PromptPart.Text>().single().text
        assertTrue(text.startsWith("## Notebook: Analisi 1"))
        assertTrue(text.contains("Teorema ponte") && text.contains("Limiti di funzioni"))
        assertTrue(prompt.parts.none { it is PromptPart.Image })
        assertEquals("Analisi 1\nLimiti", LectureAnalysis.parseCourseContext("""{"text":" Analisi 1\nLimiti "}"""))

        assertEquals(
            "Teorema ponte Sia f: A -> R. Analisi 1, prof. Rossi. Limiti; derivate. teorema ponte, epsilon-delta",
            LecturePrompts.transcriptionPrompt(memory.courseContext, listOf("Teorema ponte\nSia f: A -> R\nPer ogni x"))
        )
        assertEquals(20, LecturePrompts.transcriptionPrompt(memory.courseContext, emptyList(), maxChars = 20).length)
        assertEquals("", LecturePrompts.transcriptionPrompt("", emptyList()))
    }

    @Test
    fun theNotesAreInTheLanguageSpokenNotInTheOneOfTheSlides() {
        assertTrue(LecturePrompts.chunkSystemInstruction(AssistantSettings(whisperLanguage = "it")).contains("write currentTopic, outline and notes in Italian, the language of the lecture"))
        assertTrue(LecturePrompts.chunkSystemInstruction(AssistantSettings()).contains("not necessarily the language of the slides"))
        assertTrue(LecturePrompts.chunkSystemInstruction(AssistantSettings(notesLanguage = "English", whisperLanguage = "it")).contains("in English."))
    }

    @Test
    fun theTextOfTheFarthestPagesIsCutFirst() {
        val long = "x".repeat(900)
        val pages = listOf(
            PageContent(4, slide = SlideText(1, 4, long)),
            PageContent(5, slide = SlideText(1, 5, long)),
            PageContent(3, slide = SlideText(1, 3, long))
        )
        val text = LecturePrompts.pagesText(pages, visible = setOf(4), withImage = emptySet(), maxChars = 1500)
        assertTrue(text.contains("### Page 5 (visible)\nSlide 5 of PDF 1:\n$long"))
        assertTrue(text.contains("### Page 6\nSlide 6 of PDF 1:\nxxx"))
        assertTrue(text.contains("### Page 4\nSlide 4 of PDF 1 (text left out)."))
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
            pages = notebook.pages,
            pageImages = listOf(PageImage(7, byteArrayOf(2)), PageImage(4, byteArrayOf(1)))
        )
        assertTrue(prompt.system.contains("notesMarkdown"))
        val texts = prompt.parts.filterIsInstance<PromptPart.Text>().joinToString("\n") { it.text }
        assertTrue(texts.contains("[0:00] Buongiorno\n[1:00] Oggi i limiti"))
        assertTrue(texts.contains("the recording is not available"))
        assertTrue(texts.contains("## Notebook during the lecture"))
        assertTrue(texts.contains("Slide 3 of PDF 1:\nTeorema ponte"))
        // Neither audio nor files (a local model receives text and images), images in page order
        assertTrue(prompt.parts.none { it is PromptPart.Audio || it is PromptPart.File })
        assertEquals(listOf(1.toByte(), 2.toByte()), prompt.parts.filterIsInstance<PromptPart.Image>().map { it.jpeg.single() })
        assertTrue(texts.indexOf("Notebook page 5:") < texts.indexOf("Notebook page 8:"))
    }

    @Test
    fun withTheLocalTranscriptTheModelReadsTextOnly() {
        val prompt = LecturePrompts.chunkPrompt(
            settings = settings,
            memory = memory,
            notebook = notebook,
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
                pages = notebook.pages,
                pageImages = listOf(PageImage(4, byteArrayOf(1))),
                audioFile = audio
            )
        )
        val parts = request.contents.single().parts
        assertEquals("https://example.test/files/rec", parts.last().fileData?.fileUri)
        assertEquals(1, parts.count { it.fileData != null })
        assertTrue(parts.mapNotNull { it.text }.any { it.contains("[0:00] Buongiorno") })
        val schema = request.generationConfig?.responseJsonSchema as JsonObject
        assertEquals(listOf("notesMarkdown", "missing"), (schema["required"] as JsonArray).map { it.jsonPrimitive.content })
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
        // A new line escaped twice, not a LaTeX command
        assertEquals("messaggio.\n- **Corollario** ${'$'}a \\neq b${'$'}", LectureAnalysis.normalizeMarkdown("messaggio.\\n- **Corollario** ${'$'}a \\neq b${'$'}"))
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
    fun theLanguageSpokenIsACode() {
        val store = AssistantSettingsStore("/nonexistent-dir/settings.json")
        store.update(AssistantSettings(whisperLanguage = " IT "))
        assertEquals("it", store.settings.whisperLanguage)
        store.update(AssistantSettings(whisperLanguage = "italiano"))
        assertEquals("auto", store.settings.whisperLanguage)
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

