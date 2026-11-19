package io.github.valerioisufi.pageboard.lecture.bench

import io.github.valerioisufi.pageboard.lecture.AssistantSettings
import io.github.valerioisufi.pageboard.lecture.ChunkAnalysis
import io.github.valerioisufi.pageboard.lecture.LectureAnalysis
import io.github.valerioisufi.pageboard.lecture.LectureMemory
import io.github.valerioisufi.pageboard.lecture.LecturePrompts
import io.github.valerioisufi.pageboard.lecture.NoteKind
import io.github.valerioisufi.pageboard.lecture.NoteSuggestion
import io.github.valerioisufi.pageboard.lecture.NotebookSnapshot
import io.github.valerioisufi.pageboard.lecture.PageContent
import io.github.valerioisufi.pageboard.lecture.PageImage
import io.github.valerioisufi.pageboard.lecture.RequestTranscript
import io.github.valerioisufi.pageboard.lecture.SlideText
import io.github.valerioisufi.pageboard.lecture.TranscriptSegment
import io.github.valerioisufi.pageboard.lecture.model.ModelPrompt
import io.github.valerioisufi.pageboard.ml.GeneratorOptions
import io.github.valerioisufi.pageboard.ml.InstalledModel
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the bench reads of a lecture prepared by lecture-eval (`prepared.json`). */
@Serializable
internal class PreparedLecture(
    val lectureId: String,
    val documentId: String,
    val documentTitle: String = "",
    val durationMs: Long,
    val context: PreparedContext,
    val notes: List<PreparedNote> = emptyList()
)

@Serializable
internal class PreparedContext(
    val sentences: List<PreparedSentence> = emptyList(),
    val slides: List<PreparedSlide> = emptyList(),
    val pagesWorkedOn: List<PageWorkedOn> = emptyList()
)

@Serializable
internal class PreparedSentence(val startMs: Long, val endMs: Long, val text: String)

@Serializable
internal class PreparedSlide(val pageId: String, val number: Int, val text: String)

@Serializable
internal class PageWorkedOn(val pageId: String, val number: Int, val fromMs: Long, val toMs: Long)

/** A note of the student: typed or written by hand, from [atMs] (before the lecture: negative) to [removedAtMs]. */
@Serializable
internal class PreparedNote(
    val pageId: String,
    val pageNumber: Int = 0,
    val text: String = "",
    val handwritten: Boolean = false,
    val atMs: Long = 0,
    val removedAtMs: Long? = null
)

/** What the bench reads of a lecture of PencilNotes (`lecture.json`): the pages seen, with their images. */
@Serializable
internal class RecordedLecture(val pages: List<RecordedPage> = emptyList(), val suggestions: List<RecordedSuggestion> = emptyList())

/** A note that the assistant suggested during the lecture. */
@Serializable
internal class RecordedSuggestion(val title: String = "")

@Serializable
internal class RecordedPage(val content: RecordedContent, val image: String? = null)

@Serializable
internal class RecordedContent(val index: Int, val pageId: String = "", val slide: SlideText? = null, val imageCount: Int = 0)

@Serializable
internal class CourseContextFile(val text: String = "")

/**
 * A lecture of the workspace of lecture-eval, with the pages seen and the course context of its
 * copy in `<bench>/material/pencilnotes`, or else in the snapshot of the workspace: the requests of the assistant as it would have built them.
 */
internal class BenchLecture(
    val prepared: PreparedLecture,
    private val pages: Map<String, RecordedPage>,
    private val pagesDirectory: File,
    val courseContext: String,
    private val suggestionTitles: List<String> = emptyList()
) {
    val id: String get() = prepared.lectureId

    /**
     * This lecture as the student had it without the assistant: the notes inserted from its
     * suggestions removed (a note inserted begins with the title of its suggestion in bold), and no
     * images of the pages, which were taken with those notes on them.
     */
    fun untouched(): BenchLecture {
        val fromAssistant = suggestionTitles.map { "**${it.trim()}**" }
        val notes = prepared.notes.filter { note -> fromAssistant.none { note.text.trim().startsWith(it) } }
        return BenchLecture(
            PreparedLecture(prepared.lectureId, prepared.documentId, prepared.documentTitle, prepared.durationMs, prepared.context, notes),
            pages.mapValues { RecordedPage(it.value.content, image = null) },
            pagesDirectory,
            courseContext
        )
    }

    /** The transcript, a segment a sentence. */
    val transcript: List<TranscriptSegment> = prepared.context.sentences.map { TranscriptSegment(it.startMs, it.endMs, it.text) }

    /** The page the student is looking at [atMs]: the page worked on then, or the last one before. */
    private fun visiblePageId(atMs: Long): String? {
        val worked = prepared.context.pagesWorkedOn
        return (worked.firstOrNull { atMs in it.fromMs..it.toMs } ?: worked.lastOrNull { it.fromMs <= atMs } ?: worked.firstOrNull())?.pageId
            ?: pages.values.minByOrNull { it.content.index }?.content?.pageId
    }

    /** The page [pageId] as the student had it [atMs]: its slide, the notes typed by then. */
    private fun page(pageId: String, atMs: Long): PageContent? {
        val recorded = pages[pageId] ?: return null
        val notes = prepared.notes.filter { it.pageId == pageId && it.atMs <= atMs && (it.removedAtMs == null || it.removedAtMs > atMs) }
        return PageContent(
            index = recorded.content.index,
            pageId = pageId,
            slide = recorded.content.slide,
            typedTexts = notes.filter { !it.handwritten }.map { it.text.trim() }.filter { it.isNotEmpty() },
            imageCount = recorded.content.imageCount
        )
    }

    /** The notebook [atMs]: the pages around the visible one in text, the visible one as an image (as the local models get it). */
    fun notebook(atMs: Long, before: Int = 4, after: Int = 5, found: Set<Int> = emptySet()): NotebookSnapshot? {
        val visibleId = visiblePageId(atMs) ?: return null
        val visible = pages[visibleId]?.content?.index ?: return null
        val around = pages.values.map { it.content }.filter { it.index in (visible - before)..(visible + after) || it.index in found }.sortedBy { it.index }
        val contents = around.mapNotNull { page(it.pageId, atMs) }
        val image = pages[visibleId]?.image?.let { File(pagesDirectory, it) }?.takeIf { it.isFile }?.let { PageImage(visible, it.readBytes()) }
        return NotebookSnapshot(
            documentTitle = prepared.documentTitle,
            pageCount = (pages.values.maxOfOrNull { it.content.index } ?: 0) + 1,
            visiblePageIndexes = listOf(visible),
            pages = contents,
            pageImages = listOfNotNull(image)
        )
    }

    /** Every page of the notebook as the student had it [atMs], in its order. */
    fun pagesAt(atMs: Long): List<PageContent> = pages.values.mapNotNull { page(it.content.pageId, atMs) }.sortedBy { it.index }

    /** The index of the page the student is looking at [atMs]. */
    fun visibleIndex(atMs: Long): Int? = visiblePageId(atMs)?.let { pages[it]?.content?.index }

    /** Every page seen, as at the end of the lecture, with the images of the pages that were on screen. */
    fun pagesSeen(): Pair<List<PageContent>, List<PageImage>> {
        val contents = pages.values.mapNotNull { page(it.content.pageId, prepared.durationMs) }.sortedBy { it.index }
        val images = pages.values.filter { it.image != null }.sortedBy { it.content.index }.take(MAX_REVIEW_IMAGES)
            .mapNotNull { page -> File(pagesDirectory, page.image!!).takeIf { it.isFile }?.let { PageImage(page.content.index, it.readBytes()) } }
        return contents to images
    }

    companion object {
        private const val MAX_REVIEW_IMAGES = 8
        private val json = Json { ignoreUnknownKeys = true }

        /** The lecture [id] of [workspace], with its copy in [pencilnotes] or in the snapshot of [workspace]; `null` when either is missing. */
        fun read(workspace: File, pencilnotes: File, id: String): BenchLecture? {
            val preparedFile = File(workspace, "lectures/$id/prepared.json").takeIf { it.isFile } ?: return null
            val prepared = json.decodeFromString(PreparedLecture.serializer(), preparedFile.readText())
            val documentDirectory = File(pencilnotes.takeIf { it.isDirectory } ?: File(workspace, "snapshot"), "lectures/doc-${prepared.documentId}")
            val lectureFile = File(documentDirectory, "$id/lecture.json").takeIf { it.isFile } ?: return null
            val recorded = json.decodeFromString(RecordedLecture.serializer(), lectureFile.readText())
            val context = File(documentDirectory, "context.json").takeIf { it.isFile }
                ?.let { json.decodeFromString(CourseContextFile.serializer(), it.readText()).text }.orEmpty()
            return BenchLecture(prepared, recorded.pages.associateBy { it.content.pageId }, File(documentDirectory, "$id/pages"), context, recorded.suggestions.map { it.title })
        }
    }
}

/**
 * The requests of the assistant built as it builds them ([LecturePrompts]), from lectures of the
 * workspace of lecture-eval (dev only), answered by every generation model of the bench with their
 * schema enforced and asked:
 *
 * * the live notes of the first [liveMinutes] minutes, a request a minute, the outline and the
 *   notes of the answers given back to the next request (as `LectureRecorder` does), by every
 *   model of the bench (`pageboard.bench.models` chooses them);
 * * the catch-up of the last three minutes, after them;
 * * the course context of the notebook;
 * * the review of [reviewLecture], by E4B and 12B.
 *
 * With the schema enforced, the live notes and the course context of the first lecture only: a
 * spoiled answer runs to the longest answer allowed. Each answer is read by the parser of the
 * assistant as it comes and from its block of JSON, and checked against its schema exactly.
 */
internal class PromptBench(
    private val bench: Bench,
    private val workspace: File,
    private val lectureIds: List<String> = listOf("2026-10-15_09-48-14-2ce0b6", "2026-10-13_11-36-52-928359", "2026-10-09_09-39-11-5849ef"),
    private val reviewLecture: String = "2026-10-13_14-11-40-b6878b",
    private val liveMinutes: Int = 10,
    /** The requests measured: `live` (with the catch-up), `context`, `review`; all when empty. */
    private val kinds: List<String> = emptyList(),
    /** How many of [lectureIds] (all when `null`). */
    private val lectureCount: Int? = null
) {
    private fun measures(kind: String) = kinds.isEmpty() || kind in kinds

    private enum class Kind(val label: String) {
        LIVE("live notes"), CATCH_UP("catch-up"), CONTEXT("course context"), REVIEW("review")
    }

    private class Result(val kind: Kind, val model: String, val mode: SchemaMode, val answer: Answer, val asIs: Boolean, val fromBlock: Boolean, val schemaError: String?, val notes: Int)

    private val results = mutableListOf<Result>()
    private val answers = StringBuilder()
    private val settings = AssistantSettings(spokenLanguage = "it")

    suspend fun run(report: StringBuilder) {
        report.appendLine("## The requests of the assistant").appendLine()
        val split = File(workspace, "split.json").takeIf { it.isFile }?.readText().orEmpty()
        val lectures = lectureIds.filter { "\"$it\": \"TEST\"" !in split }.take(lectureCount ?: lectureIds.size)
            .mapNotNull { BenchLecture.read(workspace, File(bench.material, "pencilnotes"), it) }
        val review = reviewLecture.takeIf { "\"$it\": \"TEST\"" !in split }?.let { BenchLecture.read(workspace, File(bench.material, "pencilnotes"), it) }
        if (lectures.isEmpty()) {
            report.appendLine("No lecture found in $workspace and ${bench.material}/pencilnotes.").appendLine()
            return
        }
        report.appendLine("Lectures (dev of lecture-eval): ${lectures.joinToString { "`${it.id}` (${it.prepared.documentTitle.lines().first()})" }}; the review of `${review?.id}`.")
        for (model in bench.generators) {
            for (mode in bench.modes) {
                // A spoiled answer enforced runs to the longest answer (minutes): one lecture is enough for it
                for (lecture in if (mode == SchemaMode.ENFORCED) lectures.take(1) else lectures) {
                    if (measures("live")) live(model, mode, lecture)
                    if (measures("context")) context(model, mode, lecture)
                }
                if (review != null && model.spec.id != "gemma-4-e2b" && measures("review")) review(model, mode, review)
            }
            bench.loaded.unload(model.spec.id)
            bench.output("prompts/answers.md").writeText(answers.toString())
        }
        report.appendLine().appendLine("| Request | Model | Schema | Requests | Usable as they come | Usable from their block | Follow the schema | Notes an answer | Time a request |")
        report.appendLine("|---|---|---|---|---|---|---|---|---|")
        for ((key, group) in results.groupBy { Triple(it.kind, it.model, it.mode) }) {
            val (kind, model, mode) = key
            val failures = group.count { it.answer.error != null }
            report.appendLine(
                "| ${kind.label} | $model | ${mode.name.lowercase()} | ${group.size}${if (failures > 0) " ($failures failed)" else ""} | " +
                    "${percent(group.count { it.asIs }, group.size)} | ${percent(group.count { it.fromBlock }, group.size)} | " +
                    "${percent(group.count { it.schemaError == null }, group.size)} | ${"%.1f".format(group.meanOf { it.notes.toDouble() })} | " +
                    "${seconds(group.map { it.answer.millis }.average().toLong())} |"
            )
        }
        val errors = results.mapNotNull { r -> (r.answer.error ?: r.schemaError)?.let { "${r.kind.label}, ${r.model}, ${r.mode.name.lowercase()}: $it" } }.distinct()
        if (errors.isNotEmpty()) {
            report.appendLine().appendLine("What was wrong (the first of each answer):")
            errors.take(20).forEach { report.appendLine("* ${it.take(200)}") }
        }
        report.appendLine()
    }

    /** The live notes of the first minutes, a request a minute, each with the memory left by the answers before. */
    private suspend fun live(model: InstalledModel, mode: SchemaMode, lecture: BenchLecture) {
        var outline = ""
        var topic = ""
        var lastOutlineMs = 0L
        val suggestions = mutableListOf<NoteSuggestion>()
        val options = GeneratorOptions(maxTokens = 16384, images = true)
        for (minute in 0 until liveMinutes) {
            val startMs = minute * 60_000L
            val endMs = startMs + 60_000L
            val segments = lecture.transcript.filter { it.startMs >= startMs && it.startMs < endMs }
            if (segments.isEmpty()) continue
            val memory = LectureMemory(
                elapsedMs = endMs,
                outline = outline,
                currentTopic = topic,
                previousTranscript = lecture.transcript.filter { it.endMs <= startMs }.takeLast(3).joinToString(" ") { it.text }.takeLast(600),
                suggestions = suggestions.toList(),
                courseContext = lecture.courseContext
            )
            val includeOutline = outline.isBlank() || endMs - lastOutlineMs >= 210_000L
            val speech = RequestTranscript(emptyList(), segments, segments.first().startMs, 0L, segments.last().endMs)
            val prompt = LecturePrompts.chunkPrompt(settings, memory, lecture.notebook(endMs), speech, includeOutline = includeOutline)
            val result = ask(Kind.LIVE, model, options, prompt, mode, lecture.id)
            result.parsed?.let { analysis ->
                if (analysis.outline.isNotBlank()) {
                    outline = analysis.outline.trim()
                    lastOutlineMs = endMs
                }
                if (analysis.currentTopic.isNotBlank()) topic = analysis.currentTopic.trim()
                analysis.notes.forEachIndexed { i, note ->
                    suggestions.add(
                        NoteSuggestion(
                            id = "$minute-$i",
                            kind = NoteKind.entries.firstOrNull { it.name == note.kind } ?: NoteKind.OTHER,
                            title = note.title,
                            content = note.content,
                            pageIndex = note.page?.minus(1),
                            atMs = startMs + (note.atSeconds * 1000).toLong()
                        )
                    )
                }
            }
        }
        // "I missed something" after them: the last three minutes
        val endMs = liveMinutes * 60_000L
        val segments = lecture.transcript.filter { it.endMs > endMs - 3 * 60_000L && it.startMs < endMs }
        if (segments.isNotEmpty()) {
            val memory = LectureMemory(endMs, outline, topic, "", suggestions.toList(), lecture.courseContext)
            val speech = RequestTranscript(emptyList(), segments, segments.first().startMs, 0L, segments.last().endMs)
            val prompt = LecturePrompts.chunkPrompt(settings, memory, lecture.notebook(endMs), speech, catchUp = true, includeOutline = false)
            ask(Kind.CATCH_UP, model, options, prompt, mode, lecture.id)
        }
    }

    private suspend fun context(model: InstalledModel, mode: SchemaMode, lecture: BenchLecture) {
        val (pages, _) = lecture.pagesSeen()
        val prompt = LecturePrompts.courseContextPrompt(settings, lecture.prepared.documentTitle, pages)
        ask(Kind.CONTEXT, model, GeneratorOptions(maxTokens = 16384), prompt, mode, lecture.id)
    }

    private suspend fun review(model: InstalledModel, mode: SchemaMode, lecture: BenchLecture) {
        val (pages, images) = lecture.pagesSeen()
        val memory = LectureMemory(lecture.prepared.durationMs, "", "", "", emptyList(), lecture.courseContext)
        val prompt = LecturePrompts.finalReviewPrompt(settings, lecture.prepared.documentTitle, memory, lecture.transcript, pages, images)
        // The answer of the review is long: a context of the request and of its answer
        ask(Kind.REVIEW, model, GeneratorOptions(maxTokens = 65536, images = true), prompt, mode, lecture.id)
    }

    private class Asked(val parsed: ChunkAnalysis?)

    private suspend fun ask(kind: Kind, model: InstalledModel, options: GeneratorOptions, prompt: ModelPrompt, mode: SchemaMode, lectureId: String): Asked {
        val answer = bench.ask(model, options, prompt, mode)
        // Read as the parser of the assistant reads it (a leading block of code dropped), which takes
        // a missing key for an empty one: a usable answer holds the keys too
        fun usable(text: String): Boolean = try {
            when (kind) {
                Kind.LIVE, Kind.CATCH_UP -> LectureAnalysis.parseChunk(text)
                Kind.CONTEXT -> LectureAnalysis.parseCourseContext(text)
                Kind.REVIEW -> LectureAnalysis.parseFinalReview(text)
            }
            val trimmed = text.trim()
            val body = if (trimmed.startsWith("```")) trimmed.substringAfter('\n').substringBeforeLast("```") else trimmed
            holdsKeys(body, requiredKeys(prompt.schema!!))
        } catch (_: Exception) {
            false
        }
        val block = jsonOf(answer.text, prompt.schema)
        val json = parseJsonOrNull(LectureAnalysis.repairLatexEscapes(block))
        val schemaError = if (answer.error != null) answer.error else if (json == null) "not JSON" else schemaError(json, prompt.schema!!)
        val parsed = if (kind == Kind.LIVE || kind == Kind.CATCH_UP) {
            try {
                LectureAnalysis.parseChunk(block)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val notes = parsed?.notes?.size ?: if (kind == Kind.REVIEW) runCatching { LectureAnalysis.parseFinalReview(block).missing.size }.getOrDefault(0) else 0
        results.add(Result(kind, model.spec.name, mode, answer, answer.error == null && usable(answer.text), answer.error == null && usable(block), schemaError, notes))
        answers.appendLine("### ${kind.label}, ${model.spec.id}, ${mode.name.lowercase()}, $lectureId (${seconds(answer.millis)}, ${answer.pieces} pieces, ${answer.accelerator})")
        answers.appendLine("```").appendLine(answer.error ?: answer.text).appendLine("```")
        println("prompts ${kind.label} ${model.spec.id} $mode $lectureId: ${seconds(answer.millis)}, schema ${schemaError ?: "ok"}")
        return Asked(parsed)
    }
}
