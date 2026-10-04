package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.gemini.GeminiContent
import io.github.valerioisufi.drawview.lecture.gemini.GeminiFile
import io.github.valerioisufi.drawview.lecture.gemini.GeminiPart
import io.github.valerioisufi.drawview.lecture.gemini.GenerateContentRequest
import io.github.valerioisufi.drawview.lecture.gemini.GenerationConfig
import io.github.valerioisufi.drawview.lecture.gemini.ThinkingConfig
import kotlin.io.encoding.Base64

/**
 * Audio of a request.
 *
 * @param contextMs leading milliseconds already analyzed with the previous request (context only).
 */
class RequestAudio(val wav: ByteArray, val startMs: Long, val contextMs: Long, val endMs: Long) {
    val newStartMs: Long get() = startMs + contextMs
}

/** What the assistant remembers of the lecture, sent with every request. */
class LectureMemory(
    val elapsedMs: Long,
    val outline: String,
    val currentTopic: String,
    val previousTranscript: String,
    val suggestions: List<NoteSuggestion>
)

/** A PDF document of the notebook uploaded with the Files API. */
class UploadedSlides(val document: SlideDocument, val file: GeminiFile)

/**
 * The requests of the assistant. The parts are ordered from the most stable (instructions,
 * course, slides) to the most variable (memory, notebook, audio): the stable prefix is reused by
 * the implicit caching of the Gemini API.
 */
object LecturePrompts {

    private fun languageRule(settings: AssistantSettings): String {
        val language = settings.notesLanguage.trim().ifEmpty { "the language of the lecture" }
        return "Language: write the transcript in the language spoken; write currentTopic, outline and notes in $language."
    }

    /** The Markdown rendered by the text blocks of the notebook. */
    private const val MARKDOWN_RULE =
        "Markdown of the notebook: \"-\" for bullet points, **bold**, *italic*, \"#\" headings, LaTeX formulas between \$...\$ (inline) or \$\$...\$\$ (display); no tables, images or links."

    fun chunkSystemInstruction(settings: AssistantSettings): String = """
        You are a note-taking assistant listening to a university lecture live, next to a student who takes handwritten notes in a notebook app (the lecture slides, when available, are imported as pages of the same notebook).
        Every minute or so you receive the newest part of the lecture audio together with what the student is looking at and writing. Your goal: make sure the student does not miss anything important, without distracting them.

        How to work:
        - Listen to the new audio and compare what the lecturer says with (1) the slides and (2) what the student has already written or typed. Look carefully at the handwriting in the page images and in the close-ups of the new writing.
        - Suggest a note ONLY for important content said in the new audio that is missing from both the slides and the notes of the student, and that is not among the notes already suggested. Typical cases: definitions, formulas, theorems and their conditions, steps of worked examples, explanations that clarify a slide, anything the lecturer stresses ("this is important", "this will be in the exam"), announcements and deadlines, references (books, papers, websites), answers to the questions of the students.
        - If the student already wrote it, even abbreviated or in their own words, it is not missing. If it is on a slide, it is not missing, unless the lecturer adds something that is not there (a condition, an intuition, an example).
        - No note is better than a redundant note: an empty list is the right answer when nothing is missing. Prefer few, high-value notes.
        - Never invent or complete content that was not said. When the audio is unclear, say so instead of guessing.
        - The notes are inserted as they are in the notebook: concise and ready to use (short sentences or bullet points, formulas in LaTeX between ${'$'}...${'$'}, no preamble such as "The lecturer said").
        - The page of a note is the notebook page where it belongs (usually the slide being discussed).
        - $MARKDOWN_RULE

        Fields:
        - transcript: faithful transcript of the new audio only (after the context part), not summarized; mark unintelligible parts as [inaudible]; empty when there is no speech.
        - currentTopic: the current topic in a few words.
        - professorPage: the notebook page the lecturer is talking about, when the audio and the slides make it clear (e.g. they moved to the next slide, they describe a figure). Omit it when unsure.
        - outline: the updated outline of the whole lecture so far, as a Markdown bullet list of at most about 300 words. Integrate the new content into the previous outline keeping its structure, and compress older details rather than dropping topics: it is your memory of the lecture.
        - notes: the missing content, as described above. atSeconds is measured from the start of the audio of the request.

        ${languageRule(settings)}
    """.trimIndent()

    fun finalReviewSystemInstruction(settings: AssistantSettings): String = """
        You are a note-taking assistant. The lecture is over: you receive its recording (or its transcript), the slides when available, the pages of the notebook of the student and the notes suggested during the lecture.

        Write:
        - notesMarkdown: complete, well organized notes of the whole lecture in Markdown, to study from: headings following the structure of the lecture, concise bullet points, definitions, formulas in LaTeX between ${'$'}...${'$'}, examples, and what the lecturer stressed or announced (in a "**Important**" line, translated). Integrate slides and notes of the student without copying the slides verbatim: refer to them (e.g. "p. 12") when useful. Never invent content that was not said or shown.
        - $MARKDOWN_RULE
        - missing: the important content of the lecture that is still missing from the notebook (slides, notes of the student and suggested notes marked as inserted or already noted), in the same format as the live notes, with the notebook page where it belongs and atSeconds measured from the start of the recording. Empty when nothing is missing.

        ${languageRule(settings).replace("currentTopic, outline and notes", "notesMarkdown and missing")}
    """.trimIndent()

    /** Request for a chunk of the lecture ([catchUp]: the student asked what they missed). */
    fun chunkRequest(
        settings: AssistantSettings,
        memory: LectureMemory,
        notebook: NotebookSnapshot?,
        slides: List<UploadedSlides>,
        audio: RequestAudio,
        catchUp: Boolean = false
    ): GenerateContentRequest {
        val parts = mutableListOf<GeminiPart>()
        courseParts(settings, notebook?.documentTitle)?.let { parts.add(it) }
        parts.addAll(slideParts(slides))
        parts.add(GeminiPart.text(memoryText(memory)))
        notebook?.let { parts.addAll(notebookParts(it)) }

        val contextSeconds = audio.contextMs / 1000
        val audioIntro = buildString {
            appendLine("## New audio")
            append("Lecture time ${formatLectureTime(audio.newStartMs)}–${formatLectureTime(audio.endMs)}.")
            if (contextSeconds > 0) {
                append(" The first $contextSeconds s of the audio (from ${formatLectureTime(audio.startMs)}) are context, already analyzed: transcribe and analyze only what follows.")
            }
            if (catchUp) {
                append(
                    "\nThe student pressed \"I missed something\": they lost track during the last minutes. For this request consider the whole audio, context included, " +
                        "and suggest the notes that let them catch up (still excluding what is already in their notes, on the slides or among the notes already suggested). " +
                        "The transcript still covers only the new audio."
                )
            }
        }
        parts.add(GeminiPart.text(audioIntro))
        parts.add(GeminiPart.inline("audio/wav", Base64.encode(audio.wav)))

        return GenerateContentRequest(
            contents = listOf(GeminiContent(role = "user", parts = parts)),
            systemInstruction = GeminiContent(parts = listOf(GeminiPart.text(chunkSystemInstruction(settings)))),
            generationConfig = GenerationConfig(
                responseMimeType = "application/json",
                responseJsonSchema = LectureAnalysis.chunkSchema,
                maxOutputTokens = 8192,
                thinkingConfig = ThinkingConfig(settings.thinkingLevel)
            )
        )
    }

    /**
     * Request for the review of the whole lecture: the complete [audioFile] when it could be
     * uploaded, the transcript in any case.
     */
    fun finalReviewRequest(
        settings: AssistantSettings,
        documentTitle: String,
        memory: LectureMemory,
        transcript: List<TranscriptSegment>,
        pageImages: List<PageImage>,
        slides: List<UploadedSlides>,
        audioFile: GeminiFile?
    ): GenerateContentRequest {
        val parts = mutableListOf<GeminiPart>()
        courseParts(settings, documentTitle)?.let { parts.add(it) }
        parts.addAll(slideParts(slides))
        if (pageImages.isNotEmpty()) {
            parts.add(GeminiPart.text("## Notebook pages seen during the lecture (as last seen)"))
            for (image in pageImages.sortedBy { it.pageIndex }) {
                parts.add(GeminiPart.text("Notebook page ${image.pageIndex + 1}:"))
                parts.add(GeminiPart.inline("image/jpeg", Base64.encode(image.jpeg)))
            }
        }
        parts.add(GeminiPart.text(memoryText(memory)))
        if (transcript.isNotEmpty()) {
            parts.add(
                GeminiPart.text(
                    "## Live transcript (may contain recognition errors" + (if (audioFile != null) "; the recording below is authoritative" else "") + ")\n" +
                        transcript.joinToString("\n") { "[${formatLectureTime(it.startMs)}] ${it.text}" }
                )
            )
        }
        if (audioFile != null) {
            parts.add(GeminiPart.text("## Complete recording of the lecture (${formatLectureTime(memory.elapsedMs)})"))
            parts.add(GeminiPart.file(audioFile.mimeType.ifEmpty { "audio/wav" }, audioFile.uri))
        }
        return GenerateContentRequest(
            contents = listOf(GeminiContent(role = "user", parts = parts)),
            systemInstruction = GeminiContent(parts = listOf(GeminiPart.text(finalReviewSystemInstruction(settings)))),
            generationConfig = GenerationConfig(
                responseMimeType = "application/json",
                responseJsonSchema = LectureAnalysis.finalReviewSchema,
                maxOutputTokens = 32768,
                thinkingConfig = ThinkingConfig(if (settings.thinkingLevel == "high") "high" else "medium")
            )
        )
    }

    private fun courseParts(settings: AssistantSettings, documentTitle: String?): GeminiPart? {
        val context = settings.courseContext.trim()
        if (context.isEmpty() && documentTitle.isNullOrBlank()) return null
        return GeminiPart.text(
            buildString {
                appendLine("## Course")
                if (!documentTitle.isNullOrBlank()) appendLine("Notebook: $documentTitle")
                if (context.isNotEmpty()) appendLine("Notes of the student about the course and its terminology:\n$context")
            }.trim()
        )
    }

    private fun slideParts(slides: List<UploadedSlides>): List<GeminiPart> {
        if (slides.isEmpty()) return emptyList()
        val parts = mutableListOf(GeminiPart.text("## Slides (PDF documents imported in the notebook)"))
        slides.forEachIndexed { i, uploaded ->
            parts.add(GeminiPart.text("PDF ${i + 1}: ${describeMapping(uploaded.document.pages)}."))
            parts.add(GeminiPart.file(uploaded.file.mimeType.ifEmpty { "application/pdf" }, uploaded.file.uri))
        }
        return parts
    }

    /** "PDF pages 1–30 are notebook pages 3–32", compressing the consecutive ranges. */
    internal fun describeMapping(pages: List<Pair<Int, Int>>): String {
        if (pages.isEmpty()) return "not shown in the notebook"
        val ranges = mutableListOf<Pair<Pair<Int, Int>, Pair<Int, Int>>>()
        for (pair in pages.sortedBy { it.first }) {
            val last = ranges.lastOrNull()
            if (last != null && pair.first == last.second.first + 1 && pair.second == last.second.second + 1) {
                ranges[ranges.lastIndex] = last.first to pair
            } else {
                ranges.add(pair to pair)
            }
        }
        return ranges.joinToString("; ") { (from, to) ->
            if (from == to) "PDF page ${from.first + 1} is notebook page ${from.second + 1}"
            else "PDF pages ${from.first + 1}–${to.first + 1} are notebook pages ${from.second + 1}–${to.second + 1}"
        }
    }

    private fun memoryText(memory: LectureMemory): String = buildString {
        appendLine("## Lecture so far")
        append("Elapsed time: ${formatLectureTime(memory.elapsedMs)}.")
        if (memory.currentTopic.isNotBlank()) append(" Current topic: ${memory.currentTopic}.")
        appendLine()
        appendLine("### Outline")
        appendLine(memory.outline.ifBlank { "(the lecture has just started)" })
        val suggested = memory.suggestions.takeLast(MAX_SUGGESTIONS_IN_MEMORY)
        if (suggested.isNotEmpty()) {
            appendLine("### Notes already suggested (do not repeat them)")
            appendLine("Status: inserted = added to the notebook; already noted = the student already had it; dismissed = not useful to the student; new = not handled yet.")
            for (s in suggested) {
                val page = s.pageIndex?.let { "p. ${it + 1}, " }.orEmpty()
                append("- [$page${formatLectureTime(s.atMs)}, ${statusLabel(s.status)}] ${s.title}: ")
                appendLine(s.content.replace('\n', ' ').take(140))
            }
        }
        if (memory.previousTranscript.isNotBlank()) {
            appendLine("### End of the previous transcript")
            appendLine(memory.previousTranscript)
        }
    }.trim()

    private fun statusLabel(status: SuggestionStatus) = when (status) {
        SuggestionStatus.NEW -> "new"
        SuggestionStatus.INSERTED -> "inserted"
        SuggestionStatus.ALREADY_NOTED -> "already noted"
        SuggestionStatus.DISMISSED -> "dismissed"
    }

    private fun notebookParts(notebook: NotebookSnapshot): List<GeminiPart> {
        val parts = mutableListOf<GeminiPart>()
        val visible = notebook.visiblePageIndexes.map { it + 1 }
        parts.add(
            GeminiPart.text(
                "## Notebook (${notebook.pageCount} pages)\n" +
                    "The student is looking at page ${visible.joinToString(", ")}. The images show the visible pages, then the pages around them."
            )
        )
        val visibleSet = notebook.visiblePageIndexes.toSet()
        for (image in notebook.pageImages) {
            val role = if (image.pageIndex in visibleSet) "visible" else "nearby"
            parts.add(GeminiPart.text("Notebook page ${image.pageIndex + 1} ($role):"))
            parts.add(GeminiPart.inline("image/jpeg", Base64.encode(image.jpeg)))
        }
        for (image in notebook.newWriting) {
            parts.add(GeminiPart.text("Close-up of what the student wrote on page ${image.pageIndex + 1} since the previous update:"))
            parts.add(GeminiPart.inline("image/jpeg", Base64.encode(image.jpeg)))
        }
        if (notebook.typedTexts.isNotEmpty()) {
            parts.add(
                GeminiPart.text(
                    notebook.typedTexts.entries.sortedBy { it.key }.joinToString("\n") { (index, texts) ->
                        "Typed text on page ${index + 1}:\n" + texts.joinToString("\n") { "- " + it.replace('\n', ' ') }
                    }
                )
            )
        }
        return parts
    }

    private const val MAX_SUGGESTIONS_IN_MEMORY = 60
}
