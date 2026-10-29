package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.model.ModelPrompt
import io.github.valerioisufi.drawview.lecture.model.PromptPart
import io.github.valerioisufi.drawview.lecture.transcription.SpeechHints

/**
 * What was said in a part of the lecture: its audio, which whisper.cpp transcribes on this
 * computer, or its transcript, which the requests carry (never the audio).
 *
 * @param contextMs leading milliseconds already analyzed with the previous request (context only).
 */
internal sealed interface RequestSpeech {
    val startMs: Long
    val contextMs: Long
    val endMs: Long
    val newStartMs: Long get() = startMs + contextMs
}

/** The audio of a part of the lecture, to transcribe. */
public class RequestAudio(
    internal val wav: ByteArray,
    override val startMs: Long,
    override val contextMs: Long,
    override val endMs: Long
) : RequestSpeech

/** The transcript of a request: [context] already analyzed, then the new [segments]. */
internal class RequestTranscript(
    val context: List<TranscriptSegment>,
    val segments: List<TranscriptSegment>,
    override val startMs: Long,
    override val contextMs: Long,
    override val endMs: Long
) : RequestSpeech

/**
 * What the assistant remembers of the lecture, sent with every request.
 *
 * @param courseContext the course of the notebook ([CourseContext]).
 */
internal class LectureMemory(
    val elapsedMs: Long,
    val outline: String,
    val currentTopic: String,
    val previousTranscript: String,
    val suggestions: List<NoteSuggestion>,
    val courseContext: String = ""
)

/**
 * What opens every request of a lecture and does not change during it: the title of the notebook
 * and its pages when the lecture began, whose slides are given whole (see
 * [LecturePrompts.chunkPrompt]).
 */
internal class LectureStart(val documentTitle: String, val pages: List<PageContent>)

/**
 * The requests of the assistant, as [ModelPrompt]s answered by Gemini or by a local model. The
 * parts are ordered from the most stable (instructions, course) to the most variable (memory,
 * notebook, audio): the stable prefix is reused by the implicit caching of Gemini.
 *
 * The notebook is described in the same way for every provider ([PageContent]: text of the
 * slides, typed text, handwriting), with the images of the visible pages; only the number of
 * images changes. The local models receive no audio: their prompts carry the transcript.
 */
internal object LecturePrompts {

    /**
     * The language spoken, by its name ("Italian"), when the settings say it: the slides are often
     * in another language (English), which the models would follow otherwise.
     */
    internal fun spokenLanguage(settings: AssistantSettings): String? = LANGUAGE_NAMES[settings.spokenLanguage.trim().lowercase()]

    private fun languageRule(settings: AssistantSettings, fields: String): String {
        val language = settings.notesLanguage.trim().ifEmpty {
            spokenLanguage(settings)?.let { "$it, the language of the lecture" }
                ?: "the language spoken in the lecture (not necessarily the language of the slides)"
        }
        return "Language: write $fields in $language."
    }

    /** The Markdown rendered by the text blocks of the notebook. */
    private const val MARKDOWN_RULE =
        "Markdown of the notebook: \"-\" for bullet points, **bold**, *italic*, \"#\" headings, `code` in the line, blocks of code between fences with their language (a line \"```python\", the code with its indentation, a line \"```\"), LaTeX formulas between \$...\$ (inline) or \$\$...\$\$ (display); no tables, HTML, images or links."

    /** The images are numbered by the local models too ("attached image 1"): not the pages. */
    private const val PAGE_NUMBER_RULE =
        "Pages are always the notebook page numbers written in the labels (\"Notebook page 12\"), never the position of an image in the request."

    /**
     * @param withSlides the request opens with the text of every slide of the notebook.
     */
    fun chunkSystemInstruction(settings: AssistantSettings, withSlides: Boolean = false): String {
        val slides = if (withSlides) "\n            - $SLIDES_RULE" else ""
        return """
            You are a note-taking assistant listening to a university lecture live, next to a student who takes handwritten notes in a notebook app (the lecture slides, when available, are imported as pages of the same notebook).
            At every update you receive the transcript of the newest part of the lecture together with what the student is looking at and writing. Your goal: make sure the student does not miss anything important, without distracting them.
            The notebook is described page by page in text (the text of the slides, what the student typed, their handwriting when transcribed), with images of the visible pages and close-ups of what the student just wrote.

            How to work:
            - Compare what the lecturer says in the new transcript with (1) the slides and (2) what the student has already written or typed. Look carefully at the handwriting in the images too.
            - Suggest a note ONLY for important content said in the new transcript that is missing from both the slides and the notes of the student, and that is not among the notes already suggested. Typical cases: definitions, formulas, theorems and their conditions, steps of worked examples, explanations that clarify a slide, anything the lecturer stresses ("this is important", "this will be in the exam"), announcements and deadlines, references (books, papers, websites), answers to the questions of the students.
            - If the student already wrote it, even abbreviated or in their own words, it is not missing. If it is on a slide, it is not missing, unless the lecturer adds something that is not there (a condition, an intuition, an example).$slides
            - No note is better than a redundant note: an empty list is the right answer when nothing is missing. Prefer few, high-value notes.
            - Never invent or complete content that was not said. The transcript may contain recognition errors: do not build on words that make no sense.
            - The notes are inserted as they are in the notebook: concise and ready to use (short sentences or bullet points, formulas in LaTeX between ${'$'}...${'$'}, code in blocks of code, no preamble such as "The lecturer said").
            - The page of a note is the notebook page where it belongs (usually the slide being discussed). $PAGE_NUMBER_RULE
            - $MARKDOWN_RULE

            Fields:
            - currentTopic: the current topic in a few words.
            - professorPage: the notebook page the lecturer is talking about, when the transcript and the slides make it clear (e.g. they moved to the next slide, they describe a figure). Omit it when unsure.
            - outline (only when the request asks for it, every few minutes): the updated outline of the whole lecture so far, as a Markdown bullet list of at most about 300 words. Integrate the new content into the previous outline keeping its structure, and compress older details rather than dropping topics: it is your memory of the lecture.
            - notes: the missing content, as described above. atSeconds is measured from the start of the transcript of the request.

            ${languageRule(settings, "currentTopic, outline and notes")}
        """.trimIndent()
    }

    fun finalReviewSystemInstruction(settings: AssistantSettings): String = """
        You are a note-taking assistant. The lecture is over: you receive its transcript, the pages of the notebook seen during the lecture (text of the slides, notes of the student, some images) and the notes suggested during the lecture.

        Write:
        - notesMarkdown: complete, well organized notes of the whole lecture in Markdown, to study from: headings following the structure of the lecture, concise bullet points, definitions, formulas in LaTeX between ${'$'}...${'$'}, code in blocks of code, examples, and what the lecturer stressed or announced (in a "**Important**" line, translated). Integrate slides and notes of the student without copying the slides verbatim: refer to them (e.g. "p. 12") when useful. Never invent content that was not said or shown.
        - Write about what the lecturer discussed: the slides and the notes of the student help you complete and organize it, but the nearby pages that were never on screen were probably not discussed, and are not part of the notes unless the lecturer talked about them.
        - $MARKDOWN_RULE
        - $PAGE_NUMBER_RULE
        - missing: the important content of the lecture that is still missing from the notebook (slides, notes of the student and suggested notes marked as inserted or already noted), in the same format as the live notes, with the notebook page where it belongs and atSeconds measured from the start of the lecture. Empty when nothing is missing.

        ${languageRule(settings, "notesMarkdown and missing")}
    """.trimIndent()

    /**
     * A chunk of the lecture ([catchUp]: the student asked what they missed).
     *
     * With [start], the request opens with the course and the text of every slide of the notebook
     * as it was when the lecture began: the same in every request ([ModelPrompt.cachedParts]), and
     * the pages around the visible one do not repeat it.
     */
    fun chunkPrompt(
        settings: AssistantSettings,
        memory: LectureMemory,
        notebook: NotebookSnapshot?,
        speech: RequestTranscript,
        catchUp: Boolean = false,
        includeOutline: Boolean = true,
        start: LectureStart? = null
    ): ModelPrompt {
        val parts = mutableListOf<PromptPart>()
        courseText(memory.courseContext, start?.documentTitle ?: notebook?.documentTitle)?.let { parts.add(PromptPart.Text(it)) }
        val slides = start?.let { slidesText(it.pages) }
        if (slides != null) parts.add(PromptPart.Text(slides.text))
        val cachedParts = if (slides != null) parts.size else 0
        parts.add(PromptPart.Text(memoryText(memory)))
        if (notebook != null) {
            parts.addAll(notebookParts(notebook, slides?.pageIds.orEmpty()))
        } else {
            parts.add(PromptPart.Text("## Notebook\nThe notebook is closed: the student is not looking at its pages now (they may be taking notes elsewhere)."))
        }

        val contextSeconds = speech.contextMs / 1000
        val intro = buildString {
            appendLine("## New transcript")
            append("Lecture time ${formatLectureTime(speech.newStartMs)}–${formatLectureTime(speech.endMs)}.")
            append(" The times in brackets are measured from ${formatLectureTime(speech.startMs)}, as atSeconds.")
            if (contextSeconds > 0) append(" The context part is already analyzed: analyze what follows it.")
            if (catchUp) {
                append(
                    "\nThe student pressed \"I missed something\": they lost track during the last minutes. For this request consider the whole transcript, context included, " +
                        "and suggest the notes that let them catch up (still excluding what is already in their notes, on the slides or among the notes already suggested)."
                )
            }
            append(if (includeOutline) "\nUpdate the outline in this answer." else "\nThe outline is not updated in this answer.")
        }
        parts.add(PromptPart.Text(intro))
        parts.add(PromptPart.Text(transcriptText(speech)))

        return ModelPrompt(
            system = chunkSystemInstruction(settings, withSlides = slides != null),
            parts = parts,
            schema = LectureAnalysis.chunkSchema(includeOutline),
            maxOutputTokens = 8192,
            thinkingLevel = settings.thinkingLevel,
            cachedParts = cachedParts
        )
    }

    /** The text of the slides opening the requests, and the pages whose slide it holds. */
    internal class StartSlides(val text: String, val pageIds: Set<String>)

    /**
     * The slides of [pages] in text, in the order of the notebook, as long as they fit in
     * [MAX_START_SLIDES_CHARS]; `null` when no page has a slide with text.
     */
    internal fun slidesText(pages: List<PageContent>): StartSlides? {
        val pageIds = mutableSetOf<String>()
        val text = buildString {
            appendLine("## Slides of the notebook")
            appendLine("The text of the slides of the notebook when the lecture began, whether they are on screen or not.")
            for (page in pages.sortedBy { it.index }) {
                val slide = page.slide?.takeIf { it.text.isNotBlank() } ?: continue
                val entry = "### Page ${page.index + 1}, slide ${slide.pdfPageIndex + 1} of PDF ${slide.pdfNumber}\n${slide.text.trim()}\n"
                if (length + entry.length > MAX_START_SLIDES_CHARS) break
                append(entry)
                if (page.pageId.isNotEmpty()) pageIds.add(page.pageId)
            }
        }.trim()
        return text.takeIf { pageIds.isNotEmpty() }?.let { StartSlides(it, pageIds) }
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
     * The review of the whole lecture, from its transcript.
     *
     * @param pages the pages of the notebook seen during the lecture, and those around them.
     * @param visiblePages the pages that were on screen (by index).
     * @param pageImages images of some of them, as last seen.
     */
    fun finalReviewPrompt(
        settings: AssistantSettings,
        documentTitle: String,
        memory: LectureMemory,
        transcript: List<TranscriptSegment>,
        pages: List<PageContent>,
        pageImages: List<PageImage>,
        visiblePages: Set<Int> = emptySet()
    ): ModelPrompt {
        val parts = mutableListOf<PromptPart>()
        courseText(memory.courseContext, documentTitle)?.let { parts.add(PromptPart.Text(it)) }
        if (pages.isNotEmpty()) {
            val withImage = pageImages.mapTo(mutableSetOf()) { it.pageIndex }
            parts.add(
                PromptPart.Text(
                    "## Notebook during the lecture (as last seen; \"visible\": the page was on screen, the others were nearby)\n" +
                        pagesText(pages.sortedBy { it.index }, visible = visiblePages, withImage = withImage, maxChars = MAX_REVIEW_NOTEBOOK_CHARS)
                )
            )
            for (image in pageImages.sortedBy { it.pageIndex }) {
                parts.add(PromptPart.Text(imageLabel(image) + ":"))
                parts.add(PromptPart.Image(image.jpeg))
            }
        }
        parts.add(PromptPart.Text(memoryText(memory)))
        if (transcript.isNotEmpty()) {
            parts.add(
                PromptPart.Text(
                    "## Transcript of the lecture (may contain recognition errors)\n" +
                        transcript.joinToString("\n") { "[${formatLectureTime(it.startMs)}] ${it.text}" }
                )
            )
        }
        return ModelPrompt(
            system = finalReviewSystemInstruction(settings),
            parts = parts,
            schema = LectureAnalysis.finalReviewSchema,
            maxOutputTokens = 32768,
            thinkingLevel = if (settings.thinkingLevel == "high") "high" else "medium"
        )
    }

    private fun courseText(courseContext: String, documentTitle: String?): String? {
        val context = courseContext.trim()
        if (context.isEmpty() && documentTitle.isNullOrBlank()) return null
        return buildString {
            appendLine("## Course")
            if (!documentTitle.isNullOrBlank()) appendLine("Notebook: $documentTitle")
            if (context.isNotEmpty()) appendLine("Course, topics and terminology:\n$context")
        }.trim()
    }

    /** The course context of a notebook, written from its pages (slides, typed and handwritten notes). */
    fun courseContextPrompt(settings: AssistantSettings, documentTitle: String, pages: List<PageContent>): ModelPrompt = ModelPrompt(
        system = """
            You prepare a lecture assistant for a university course. From the notebook of the course (its title, the text of its slides, the notes of the student), write a short context in ${spokenLanguage(settings) ?: "the language of the notes of the student, or of the slides"}, keeping the technical terms as they are written on the slides:
            - first line: the course and the subject of the notebook;
            - second line: the main topics, separated by semicolons;
            - third line: the technical terms, acronyms and names that the lecturer will likely say, separated by commas, the most specific first.
            At most 120 words of plain text, no Markdown. It helps the speech recognition and the assistant: write only what appears in the notebook.
        """.trimIndent(),
        parts = listOf(
            PromptPart.Text(
                "## Notebook: $documentTitle\n" +
                    pagesText(pages.sortedBy { it.index }, visible = emptySet(), withImage = emptySet(), maxChars = MAX_CONTEXT_NOTEBOOK_CHARS)
            )
        ),
        schema = LectureAnalysis.courseContextSchema,
        maxOutputTokens = 1024,
        thinkingLevel = "low"
    )

    /** What helps the speech recognition: the language of [settings], the course, the slides on screen. */
    fun speechHints(settings: AssistantSettings, courseContext: String, visibleSlides: List<String>) = SpeechHints(
        language = settings.spokenLanguage,
        prompt = transcriptionPrompt(courseContext, visibleSlides)
    )

    /**
     * The prompt of the speech recognition: the slides the student is looking at (their first
     * lines) and the course context, within [maxChars].
     */
    fun transcriptionPrompt(courseContext: String, visibleSlides: List<String>, maxChars: Int = 600): String {
        val slides = visibleSlides.joinToString(". ") { it.lines().take(2).joinToString(" ") }.take(maxChars / 3)
        val context = courseContext.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(". ")
        return listOf(slides, context).filter { it.isNotBlank() }.joinToString(". ").take(maxChars)
    }

    private fun memoryText(memory: LectureMemory): String = buildString {
        appendLine("## Lecture so far")
        appendLine("### Outline")
        appendLine(memory.outline.ifBlank { "(the lecture has just started)" })
        val suggested = memory.suggestions.takeLast(MAX_SUGGESTIONS_IN_MEMORY)
        if (suggested.isNotEmpty()) {
            appendLine("### Notes already suggested (do not repeat them)")
            appendLine("Status: inserted = added to the notebook; already noted = the student already had it; dismissed = not useful to the student; new = not handled yet.")
            val detailedFrom = (suggested.size - MAX_DETAILED_SUGGESTIONS).coerceAtLeast(0)
            for ((index, s) in suggested.withIndex()) {
                val page = s.pageIndex?.let { "p. ${it + 1}, " }.orEmpty()
                if (index < detailedFrom) {
                    appendLine("- [$page${formatLectureTime(s.atMs)}, ${statusLabel(s.status)}] ${s.title}")
                } else {
                    append("- [$page${formatLectureTime(s.atMs)}, ${statusLabel(s.status)}] ${s.title}: ")
                    appendLine(s.content.replace('\n', ' ').take(100))
                }
            }
        }
        append("Elapsed time: ${formatLectureTime(memory.elapsedMs)}.")
        if (memory.currentTopic.isNotBlank()) append(" Current topic: ${memory.currentTopic}.")
        appendLine()
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
     * The pages around the visible ones in text, the same for every provider, then the images: the
     * visible pages as they look and the close-ups of what the student just wrote.
     */
    /** @param slidesAbove pages whose slide is in the text of the slides opening the request. */
    private fun notebookParts(notebook: NotebookSnapshot, slidesAbove: Set<String> = emptySet()): List<PromptPart> {
        val parts = mutableListOf<PromptPart>()
        val withImage = notebook.pageImages.mapTo(mutableSetOf()) { it.pageIndex }
        parts.add(
            PromptPart.Text(
                buildString {
                    appendLine("## Notebook (${notebook.pageCount} pages)")
                    appendLine("The student is looking at page ${notebook.visiblePageIndexes.joinToString(", ") { "${it + 1}" }}. Pages around:")
                    append(pagesText(notebook.pages, notebook.visiblePageIndexes.toSet(), withImage, MAX_NOTEBOOK_TEXT_CHARS, slidesAbove))
                }.trim()
            )
        )
        for (image in notebook.pageImages) {
            parts.add(PromptPart.Text("${imageLabel(image)}, as the student sees it:"))
            parts.add(PromptPart.Image(image.jpeg))
        }
        for (image in notebook.newWriting) {
            parts.add(PromptPart.Text("Close-up of what the student wrote on notebook page ${image.pageIndex + 1} since the previous update:"))
            parts.add(PromptPart.Image(image.jpeg))
        }
        return parts
    }

    /**
     * [pages] in text, in the order of the notebook, within [maxChars]: the pages are given by
     * priority (the visible ones first, then by distance) and the texts of the last ones are cut
     * first.
     *
     * @param withImage pages whose image is in the request.
     * @param slidesAbove pages whose slide is already in the request ([slidesText]): its text is
     *   not repeated.
     */
    internal fun pagesText(pages: List<PageContent>, visible: Set<Int>, withImage: Set<Int>, maxChars: Int, slidesAbove: Set<String> = emptySet()): String {
        var budget = maxChars
        return pages.map { page ->
            val text = pageText(page, page.index in visible, page.index in withImage, budget, page.pageId.isNotEmpty() && page.pageId in slidesAbove)
            budget -= text.length
            page.index to text
        }.sortedBy { it.first }.joinToString("\n") { it.second }
    }

    private fun pageText(page: PageContent, isVisible: Boolean, hasImage: Boolean, budget: Int, slideAbove: Boolean = false): String = buildString {
        append("### Page ${page.index + 1}")
        if (isVisible) append(" (visible)")
        appendLine()
        if (page.isBlank) {
            append("Blank paper.")
            return@buildString
        }
        page.slide?.let { slide ->
            val name = "Slide ${slide.pdfPageIndex + 1} of PDF ${slide.pdfNumber}"
            val room = (budget - MIN_PAGE_CHARS).coerceAtLeast(0)
            when {
                slide.text.isBlank() -> appendLine("$name, without text" + if (hasImage) " (see the image)." else ".")
                slideAbove -> appendLine("$name: its text is among the slides above.")
                room == 0 -> appendLine("$name (text left out).")
                else -> {
                    appendLine("$name:")
                    appendLine(if (slide.text.length > room) slide.text.take(room) + "…" else slide.text)
                }
            }
        }
        if (page.typedTexts.isNotEmpty()) {
            appendLine("Typed by the student:")
            page.typedTexts.forEach { appendLine("- " + it.replace('\n', ' ')) }
        }
        when {
            page.handwriting.isNotEmpty() -> {
                appendLine("Written by hand by the student (transcribed):")
                page.handwriting.forEach { appendLine(it) }
                if (!page.inkTranscribed) {
                    appendLine(if (hasImage) "Other handwritten notes: see the image." else "Other handwritten notes, not transcribed yet.")
                }
            }
            page.hasInk -> appendLine(
                if (hasImage) "Handwritten notes of the student: see the image." else "Handwritten notes of the student, not transcribed yet."
            )
        }
        if (page.imageCount > 0) appendLine("Pictures inserted by the student: ${page.imageCount}.")
    }.trim()

    private fun imageLabel(image: PageImage): String =
        if (image.isCrop) "Notebook page ${image.pageIndex + 1}, area written by the student"
        else "Notebook page ${image.pageIndex + 1}"

    private const val MAX_SUGGESTIONS_IN_MEMORY = 50
    private const val MAX_DETAILED_SUGGESTIONS = 10

    /** Text of the pages around the visible ones in a live request. */
    private const val MAX_NOTEBOOK_TEXT_CHARS = 3_500

    /** Text of the slides opening every live request (about 20,000 tokens). */
    private const val MAX_START_SLIDES_CHARS = 80_000

    /** Why the slides open the requests: restating them is no note. */
    private const val SLIDES_RULE =
        "The text of every slide comes first, whether the slide is on screen or not. Restating a slide is redundant, explaining it is not: " +
            "when the lecturer adds something to a slide (an explanation, an example, a condition, an intuition, a step of a proof, a remark), suggest it, " +
            "even if its topic is on the slide."

    /** English names of the languages of the speech recognition, by ISO code. */

    private val LANGUAGE_NAMES = mapOf(
        "it" to "Italian", "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German", "pt" to "Portuguese",
        "nl" to "Dutch", "pl" to "Polish", "ro" to "Romanian", "el" to "Greek", "ru" to "Russian", "uk" to "Ukrainian",
        "zh" to "Chinese", "ja" to "Japanese", "ar" to "Arabic"
    )

    /** Text of the notebook from which the course context is written. */
    private const val MAX_CONTEXT_NOTEBOOK_CHARS = 12_000

    /** Text of the pages seen during the lecture in the final review. */
    private const val MAX_REVIEW_NOTEBOOK_CHARS = 18_000

    /** Room kept for the typed and handwritten notes of a page when the text of its slide is cut. */
    private const val MIN_PAGE_CHARS = 200
}
