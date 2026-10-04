package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.gemini.GeminiFile
import io.github.valerioisufi.drawview.lecture.model.ModelPrompt
import io.github.valerioisufi.drawview.lecture.model.PromptPart

/**
 * What was said in the part of the lecture of a request: its audio (Gemini transcribes it) or its
 * transcript (transcribed on this computer).
 *
 * @param contextMs leading milliseconds already analyzed with the previous request (context only).
 */
sealed interface RequestSpeech {
    val startMs: Long
    val contextMs: Long
    val endMs: Long
    val newStartMs: Long get() = startMs + contextMs
}

/** The audio of a request. */
class RequestAudio(
    val wav: ByteArray,
    override val startMs: Long,
    override val contextMs: Long,
    override val endMs: Long
) : RequestSpeech

/** The transcript of a request: [context] already analyzed, then the new [segments]. */
class RequestTranscript(
    val context: List<TranscriptSegment>,
    val segments: List<TranscriptSegment>,
    override val startMs: Long,
    override val contextMs: Long,
    override val endMs: Long
) : RequestSpeech

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
 * The requests of the assistant, as [ModelPrompt]s answered by Gemini or by a local model. The
 * parts are ordered from the most stable (instructions, course, slides) to the most variable
 * (memory, notebook, audio): the stable prefix is reused by the implicit caching of Gemini.
 *
 * The local models receive neither audio nor files: their prompts carry the transcript instead
 * of the audio, and the slides as images of the pages (see [NotebookSnapshot]).
 */
object LecturePrompts {

    private fun languageRule(settings: AssistantSettings, fields: String, withTranscript: Boolean): String {
        val language = settings.notesLanguage.trim().ifEmpty { "the language of the lecture" }
        return if (withTranscript) "Language: write the transcript in the language spoken; write $fields in $language."
        else "Language: write $fields in $language."
    }

    /** The Markdown rendered by the text blocks of the notebook. */
    private const val MARKDOWN_RULE =
        "Markdown of the notebook: \"-\" for bullet points, **bold**, *italic*, \"#\" headings, LaTeX formulas between \$...\$ (inline) or \$\$...\$\$ (display); no tables, images or links."

    /**
     * @param withAudio the request carries the audio, which the model transcribes; otherwise its
     *   transcript.
     */
    fun chunkSystemInstruction(settings: AssistantSettings, withAudio: Boolean = true): String {
        val source = if (withAudio) "audio" else "transcript"
        val transcriptField = if (withAudio) {
            "- transcript: faithful transcript of the new audio only (after the context part), not summarized; mark unintelligible parts as [inaudible]; empty when there is no speech.\n"
        } else {
            ""
        }
        val unclear = if (withAudio) "When the audio is unclear, say so instead of guessing."
        else "The transcript may contain recognition errors: do not build on words that make no sense."
        return """
            You are a note-taking assistant listening to a university lecture live, next to a student who takes handwritten notes in a notebook app (the lecture slides, when available, are imported as pages of the same notebook).
            Every minute or so you receive the newest part of the lecture ($source) together with what the student is looking at and writing. Your goal: make sure the student does not miss anything important, without distracting them.

            How to work:
            - Compare what the lecturer says in the new $source with (1) the slides and (2) what the student has already written or typed. Look carefully at the handwriting in the page images and in the close-ups of the new writing.
            - Suggest a note ONLY for important content said in the new $source that is missing from both the slides and the notes of the student, and that is not among the notes already suggested. Typical cases: definitions, formulas, theorems and their conditions, steps of worked examples, explanations that clarify a slide, anything the lecturer stresses ("this is important", "this will be in the exam"), announcements and deadlines, references (books, papers, websites), answers to the questions of the students.
            - If the student already wrote it, even abbreviated or in their own words, it is not missing. If it is on a slide, it is not missing, unless the lecturer adds something that is not there (a condition, an intuition, an example).
            - No note is better than a redundant note: an empty list is the right answer when nothing is missing. Prefer few, high-value notes.
            - Never invent or complete content that was not said. $unclear
            - The notes are inserted as they are in the notebook: concise and ready to use (short sentences or bullet points, formulas in LaTeX between ${'$'}...${'$'}, no preamble such as "The lecturer said").
            - The page of a note is the notebook page where it belongs (usually the slide being discussed).
            - $MARKDOWN_RULE

            Fields:
            ${transcriptField}- currentTopic: the current topic in a few words.
            - professorPage: the notebook page the lecturer is talking about, when the $source and the slides make it clear (e.g. they moved to the next slide, they describe a figure). Omit it when unsure.
            - outline (only when the request asks for it, every few minutes): the updated outline of the whole lecture so far, as a Markdown bullet list of at most about 300 words. Integrate the new content into the previous outline keeping its structure, and compress older details rather than dropping topics: it is your memory of the lecture.
            - notes: the missing content, as described above. atSeconds is measured from the start of the $source of the request.

            ${languageRule(settings, "currentTopic, outline and notes", withAudio)}
        """.trimIndent()
    }

    fun finalReviewSystemInstruction(settings: AssistantSettings): String = """
        You are a note-taking assistant. The lecture is over: you receive its recording or its transcript, the slides when available, the notes of the student and the notes suggested during the lecture.

        Write:
        - notesMarkdown: complete, well organized notes of the whole lecture in Markdown, to study from: headings following the structure of the lecture, concise bullet points, definitions, formulas in LaTeX between ${'$'}...${'$'}, examples, and what the lecturer stressed or announced (in a "**Important**" line, translated). Integrate slides and notes of the student without copying the slides verbatim: refer to them (e.g. "p. 12") when useful. Never invent content that was not said or shown.
        - $MARKDOWN_RULE
        - missing: the important content of the lecture that is still missing from the notebook (slides, notes of the student and suggested notes marked as inserted or already noted), in the same format as the live notes, with the notebook page where it belongs and atSeconds measured from the start of the lecture. Empty when nothing is missing.

        ${languageRule(settings, "notesMarkdown and missing", withTranscript = false)}
    """.trimIndent()

    /**
     * A chunk of the lecture ([catchUp]: the student asked what they missed).
     *
     * @param slides the PDF documents uploaded to Gemini (none for a local model).
     */
    fun chunkPrompt(
        settings: AssistantSettings,
        memory: LectureMemory,
        notebook: NotebookSnapshot?,
        slides: List<UploadedSlides>,
        speech: RequestSpeech,
        catchUp: Boolean = false,
        includeOutline: Boolean = true,
        includeTranscript: Boolean = speech is RequestAudio
    ): ModelPrompt {
        val withAudio = speech is RequestAudio
        val source = if (withAudio) "audio" else "transcript"
        val parts = mutableListOf<PromptPart>()
        courseText(settings, notebook?.documentTitle)?.let { parts.add(PromptPart.Text(it)) }
        parts.addAll(slideParts(slides))
        parts.add(PromptPart.Text(memoryText(memory)))
        if (notebook != null) {
            parts.addAll(notebookParts(notebook, slides))
        } else {
            parts.add(PromptPart.Text("## Notebook\nThe notebook is closed: the student is not looking at its pages now (they may be taking notes elsewhere)."))
        }

        val contextSeconds = speech.contextMs / 1000
        val intro = buildString {
            appendLine("## New $source")
            append("Lecture time ${formatLectureTime(speech.newStartMs)}–${formatLectureTime(speech.endMs)}.")
            if (withAudio) {
                if (contextSeconds > 0) {
                    append(" The first $contextSeconds s of the audio (from ${formatLectureTime(speech.startMs)}) are context, already analyzed: transcribe and analyze only what follows.")
                }
            } else {
                append(" The times in brackets are measured from ${formatLectureTime(speech.startMs)}, as atSeconds.")
                if (contextSeconds > 0) append(" The context part is already analyzed: analyze what follows it.")
            }
            if (catchUp) {
                append(
                    "\nThe student pressed \"I missed something\": they lost track during the last minutes. For this request consider the whole $source, context included, " +
                        "and suggest the notes that let them catch up (still excluding what is already in their notes, on the slides or among the notes already suggested)."
                )
            }
            append(if (includeOutline) "\nUpdate the outline in this answer." else "\nThe outline is not updated in this answer.")
        }
        parts.add(PromptPart.Text(intro))
        when (speech) {
            is RequestAudio -> parts.add(PromptPart.Audio(speech.wav))
            is RequestTranscript -> parts.add(PromptPart.Text(transcriptText(speech)))
        }

        return ModelPrompt(
            system = chunkSystemInstruction(settings, withAudio),
            parts = parts,
            schema = LectureAnalysis.chunkSchema(includeOutline, includeTranscript),
            maxOutputTokens = 8192,
            thinkingLevel = settings.thinkingLevel
        )
    }

    /** The transcript of a request, with the times from its start. */
    private fun transcriptText(speech: RequestTranscript): String = buildString {
        fun line(segment: TranscriptSegment) = "[${formatLectureTime(segment.startMs - speech.startMs)}] ${segment.text}"
        if (speech.context.isNotEmpty()) {
            appendLine("### Context (already analyzed)")
            speech.context.forEach { appendLine(line(it)) }
            appendLine("### New")
        }
        if (speech.segments.isEmpty()) appendLine("(no speech)")
        speech.segments.forEach { appendLine(line(it)) }
    }.trim()

    /**
     * The review of the whole lecture: the complete recording ([audioFile], Gemini) when it could be
     * uploaded, the transcript in any case.
     *
     * @param slides the PDF documents uploaded to Gemini (none for a local model).
     */
    fun finalReviewPrompt(
        settings: AssistantSettings,
        documentTitle: String,
        memory: LectureMemory,
        transcript: List<TranscriptSegment>,
        pageImages: List<PageImage>,
        slides: List<UploadedSlides> = emptyList(),
        audioFile: GeminiFile? = null
    ): ModelPrompt {
        val parts = mutableListOf<PromptPart>()
        courseText(settings, documentTitle)?.let { parts.add(PromptPart.Text(it)) }
        parts.addAll(slideParts(slides))
        if (pageImages.isNotEmpty()) {
            parts.add(PromptPart.Text("## Notes of the student seen during the lecture (as last seen)"))
            for (image in pageImages.sortedBy { it.pageIndex }) {
                parts.add(PromptPart.Text(imageLabel(image) + ":"))
                parts.add(PromptPart.Image(image.jpeg))
            }
        }
        parts.add(PromptPart.Text(memoryText(memory)))
        if (transcript.isNotEmpty()) {
            parts.add(
                PromptPart.Text(
                    "## Transcript of the lecture (may contain recognition errors" +
                        (if (audioFile != null) "; the recording below is authoritative" else "; the recording is not available") + ")\n" +
                        transcript.joinToString("\n") { "[${formatLectureTime(it.startMs)}] ${it.text}" }
                )
            )
        }
        if (audioFile != null) {
            parts.add(PromptPart.Text("## Complete recording of the lecture (${formatLectureTime(memory.elapsedMs)})"))
            parts.add(PromptPart.File(audioFile.uri, audioFile.mimeType.ifEmpty { "audio/wav" }))
        }
        return ModelPrompt(
            system = finalReviewSystemInstruction(settings),
            parts = parts,
            schema = LectureAnalysis.finalReviewSchema,
            maxOutputTokens = 32768,
            thinkingLevel = if (settings.thinkingLevel == "high") "high" else "medium"
        )
    }

    private fun courseText(settings: AssistantSettings, documentTitle: String?): String? {
        val context = settings.courseContext.trim()
        if (context.isEmpty() && documentTitle.isNullOrBlank()) return null
        return buildString {
            appendLine("## Course")
            if (!documentTitle.isNullOrBlank()) appendLine("Notebook: $documentTitle")
            if (context.isNotEmpty()) appendLine("Notes of the student about the course and its terminology:\n$context")
        }.trim()
    }

    private fun slideParts(slides: List<UploadedSlides>): List<PromptPart> {
        if (slides.isEmpty()) return emptyList()
        val parts = mutableListOf<PromptPart>(PromptPart.Text("## Slides (PDF documents imported in the notebook)"))
        slides.forEachIndexed { i, uploaded ->
            parts.add(PromptPart.Text("PDF ${i + 1}: ${describeMapping(uploaded.document.pages)}."))
            parts.add(PromptPart.File(uploaded.file.uri, uploaded.file.mimeType.ifEmpty { "application/pdf" }))
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

    /**
     * The pages around the visible ones described in words (slide, notes of the student, empty),
     * with an image only where the student wrote or where the slide cannot be read in the PDFs.
     */
    private fun notebookParts(notebook: NotebookSnapshot, slides: List<UploadedSlides>): List<PromptPart> {
        val pdfNumbers = slides.withIndex().associate { (i, uploaded) -> uploaded.document.resourceId to i + 1 }
        val parts = mutableListOf<PromptPart>()
        val visible = notebook.visiblePageIndexes.map { it + 1 }
        parts.add(
            PromptPart.Text(
                buildString {
                    appendLine("## Notebook (${notebook.pageCount} pages)")
                    appendLine("The student is looking at page ${visible.joinToString(", ")}. Pages around:")
                    for (page in notebook.pages) {
                        append("- Page ${page.index + 1} (${if (page.visible) "visible" else "nearby"}): ")
                        val slide = page.slide
                        val pdf = slide?.let { pdfNumbers[it.resourceId] }
                        val image = page.image
                        append(
                            when {
                                slide != null && pdf != null -> "slide ${slide.pdfPageIndex + 1} of PDF $pdf"
                                slide != null && image != null && !image.isCrop -> "a slide (see the image)"
                                slide != null -> "a slide (not shown)"
                                else -> "blank paper"
                            }
                        )
                        append(
                            when {
                                image == null -> ", nothing written by the student"
                                image.isCrop -> ", with notes of the student (image: the written area)"
                                else -> " (image: the whole page)"
                            }
                        )
                        appendLine(".")
                    }
                }.trim()
            )
        )
        for (page in notebook.pages) {
            val image = page.image ?: continue
            parts.add(PromptPart.Text(imageLabel(image) + ":"))
            parts.add(PromptPart.Image(image.jpeg))
        }
        for (image in notebook.newWriting) {
            parts.add(PromptPart.Text("Close-up of what the student wrote on page ${image.pageIndex + 1} since the previous update:"))
            parts.add(PromptPart.Image(image.jpeg))
        }
        val typed = notebook.pages.filter { it.typedTexts.isNotEmpty() }
        if (typed.isNotEmpty()) {
            parts.add(
                PromptPart.Text(
                    typed.joinToString("\n") { page ->
                        "Typed text on page ${page.index + 1}:\n" + page.typedTexts.joinToString("\n") { "- " + it.replace('\n', ' ') }
                    }
                )
            )
        }
        return parts
    }

    private fun imageLabel(image: PageImage): String =
        if (image.isCrop) "Notebook page ${image.pageIndex + 1}, area written by the student"
        else "Notebook page ${image.pageIndex + 1}"

    private const val MAX_SUGGESTIONS_IN_MEMORY = 60
}
