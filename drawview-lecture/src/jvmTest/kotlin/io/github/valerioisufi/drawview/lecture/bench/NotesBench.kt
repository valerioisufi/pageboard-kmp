package io.github.valerioisufi.drawview.lecture.bench

import io.github.valerioisufi.drawview.lecture.AssistantSettings
import io.github.valerioisufi.drawview.lecture.ChunkAnalysis
import io.github.valerioisufi.drawview.lecture.LectureAnalysis
import io.github.valerioisufi.drawview.lecture.LectureMemory
import io.github.valerioisufi.drawview.lecture.LecturePrompts
import io.github.valerioisufi.drawview.lecture.LectureStart
import io.github.valerioisufi.drawview.lecture.NoteKind
import io.github.valerioisufi.drawview.lecture.NoteSuggestion
import io.github.valerioisufi.drawview.lecture.PageContent
import io.github.valerioisufi.drawview.lecture.RequestTranscript
import io.github.valerioisufi.drawview.lecture.formatLectureTime
import io.github.valerioisufi.drawview.lecture.gemini.GeminiClient
import io.github.valerioisufi.drawview.lecture.gemini.GeminiException
import io.github.valerioisufi.drawview.lecture.model.GeminiBackend
import io.github.valerioisufi.drawview.lecture.model.GeminiPromptCache
import io.github.valerioisufi.drawview.lecture.model.ModelPrompt
import io.github.valerioisufi.drawview.lecture.model.PromptPart
import io.github.valerioisufi.drawview.ml.EmbedderOptions
import io.github.valerioisufi.drawview.ml.GeneratorOptions
import java.io.File
import kotlin.math.sqrt
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A note suggested by a configuration of [NotesBench], as it is kept for grading. */
@Serializable
internal class BenchNote(val atMs: Long, val page: Int?, val kind: String, val title: String, val content: String)

/** What a configuration of [NotesBench] wrote and cost over the whole lecture. */
@Serializable
internal class NotesRun(
    val config: String,
    val lectureId: String,
    val intervalMs: Long,
    val requests: Int,
    val failed: Int,
    val millis: Long,
    val longestMillis: Long = 0,
    val promptTokens: Long,
    val outputTokens: Long,
    val promptByModality: Map<String, Long>,
    val cachedTokens: Long = 0,
    val outline: String,
    val notes: List<BenchNote>,
    /** The run stopped at the cap of the dollars: it is not whole, and is not kept. */
    val stopped: Boolean = false
)

/**
 * The live notes of whole lectures of the workspace of lecture-eval, [lectureIds], a request every
 * [intervalMs], built as the assistant builds them ([LecturePrompts.chunkPrompt]), by each
 * configuration of [configs]:
 *
 * * `e4b-text`: Gemma 4 E4B on this computer, the schema asked, with the transcript of the
 *   lecture; the notebook is the visible page and those next to it, plus the pages found by
 *   searching the notebook with the new transcript (BM25 and EmbeddingGemma 2 merged by Reciprocal
 *   Rank Fusion, as `drawview-search` ranks them);
 * * `<gemini model>-text`: a model of the Gemini API (`GEMINI_API_KEY`), with the transcript of
 *   the interval, the notebook as the assistant sends it. With `-cached`, the requests open with
 *   the slides of the notebook ([LectureStart]) kept in an explicit cache ([GeminiPromptCache]),
 *   as the recorder sends them to Gemini; with `-medium`, the model thinks at the medium level.
 *
 * The audio never goes to a model: the lecture is transcribed on a computer (the transcript of
 * the workspace stands for it).
 *
 * A configuration may end with `@<seconds>`, its own interval instead of [intervalMs], and then
 * with `#<n>`, a label for running it again.
 *
 * `<config>+repeat` is the run of `<config>`, already written, through [NoteChecks]: no model is
 * asked, the notes dropped are in its `<config>+repeat.md` with their reason, and its time is the
 * time of the run it comes from.
 *
 * The notebook is the one of the student without the assistant ([BenchLecture.untouched]).
 *
 * Every run is written to `<bench>/answers/notes/<lecture>/<config>.json` (the notes, the tokens,
 * the time), the answers to `<config>.md`, and a run already written is read back instead of being
 * paid again. The Gemini runs stop at [maxDollars], estimated from the tokens and counted in
 * `<bench>/dollars.txt`, which the grader of `tools/notes-bench` adds to as well; a run stopped
 * there is not kept.
 */
internal class NotesBench(
    private val bench: Bench,
    private val workspace: File,
    private val configs: List<String>,
    private val intervalMs: Long = 300_000L,
    private val lectureIds: List<String> = listOf("2026-10-15_09-48-14-2ce0b6"),
    private val maxDollars: Double = 1.2
) {
    private val settings = AssistantSettings(spokenLanguage = "it")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    private val ledger = File(bench.directory, "dollars.txt")
    private var spent = ledger.takeIf { it.isFile }?.readText()?.trim()?.toDoubleOrNull() ?: 0.0

    suspend fun run(report: StringBuilder) {
        for (lectureId in lectureIds) run(lectureId, report)
    }

    private suspend fun run(lectureId: String, report: StringBuilder) {
        report.appendLine("## The notes of a whole lecture").appendLine()
        val recorded = BenchLecture.read(workspace, File(bench.material, "pencilnotes"), lectureId)
        val lecture = recorded?.untouched()
        if (recorded == null || lecture == null) {
            report.appendLine("No lecture $lectureId in $workspace and ${bench.material}/pencilnotes.").appendLine()
            return
        }
        report.appendLine(
            "`$lectureId` (${formatLectureTime(lecture.prepared.durationMs)}), without the assistant: ${lecture.prepared.notes.size} notes of the student " +
                "(${recorded.prepared.notes.size - lecture.prepared.notes.size} inserted from the suggestions removed), no images of the pages."
        ).appendLine()
        report.appendLine("| Configuration | Interval | Requests | Failed | Notes | Prompt tokens | Cached tokens | Output tokens | Time | Longest | Dollars |")
            .appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        for (config in configs) {
            val file = bench.output("notes/$lectureId/$config.json")
            val run = when {
                file.isFile -> json.decodeFromString(NotesRun.serializer(), file.readText())
                config.endsWith(CHECKED) -> checked(config, lecture)
                else -> run(config, lecture)
            }
            if (run == null) {
                report.appendLine("| $config | no run of ${config.removeSuffix(CHECKED)} to check | | | | | | | | | |")
                println("notes $lectureId $config: no run of ${config.removeSuffix(CHECKED)} to check")
                continue
            }
            if (run.stopped) {
                report.appendLine("| $config | stopped at ${"%.2f".format(spent)} dollars, not kept | | | | | | | | | |")
                println("notes $config: stopped at ${"%.2f".format(spent)} dollars")
                continue
            }
            file.writeText(json.encodeToString(NotesRun.serializer(), run))
            report.appendLine(
                "| $config | ${run.intervalMs / 1000} s | ${run.requests} | ${run.failed} | ${run.notes.size} | ${run.promptTokens} | ${run.cachedTokens} | ${run.outputTokens} | " +
                    "${seconds(run.millis)} | ${seconds(run.longestMillis)} | ${"%.3f".format(dollars(config, run.promptTokens, run.cachedTokens, run.outputTokens))} |"
            )
            println("notes $lectureId $config: ${run.requests} requests, ${run.notes.size} notes, ${seconds(run.millis)}, the longest ${seconds(run.longestMillis)}")
        }
        report.appendLine()
    }

    /** The run [config] names through its checks; `null` when that run was not written. */
    private fun checked(config: String, lecture: BenchLecture): NotesRun? {
        val file = bench.output("notes/${lecture.id}/${config.removeSuffix(CHECKED)}.json")
        if (!file.isFile) return null
        val run = json.decodeFromString(NotesRun.serializer(), file.readText())
        val slides = lecture.pagesAt(lecture.prepared.durationMs).mapNotNull { it.slide?.text }
        val decisions = NoteChecks(listOf(RepeatCheck(slides))).decide(run.notes)
        bench.output("notes/${lecture.id}/$config.md").writeText(
            decisions.filter { it.droppedBecause != null }.joinToString("\n\n") { "### ${it.droppedBecause}\n${it.note.title}: ${it.note.content}" }
        )
        return NotesRun(
            config, run.lectureId, run.intervalMs, run.requests, run.failed, run.millis, run.longestMillis, run.promptTokens, run.outputTokens,
            run.promptByModality, run.cachedTokens, run.outline, decisions.filter { it.droppedBecause == null }.map { it.note }, run.stopped
        )
    }

    private suspend fun run(config: String, lecture: BenchLecture): NotesRun {
        val configured = config.substringBefore('#')
        val name = configured.substringBefore('@')
        val intervalMs = configured.substringAfter('@', "").toLongOrNull()?.times(1000) ?: intervalMs
        val cached = name.contains("-cached")
        val base = name.replace("-cached", "").replace("-medium", "")
        val settings = if (name.contains("-medium")) settings.copy(thinkingLevel = "medium") else settings
        val device = base.startsWith("e4b")
        val model = if (device) null else base.removeSuffix("-text")
        val start = if (cached) LectureStart(lecture.prepared.documentTitle, lecture.pagesAt(0)) else null
        val gemini = if (device) null else GeminiBackend(GeminiClient()) { System.getenv("GEMINI_API_KEY").orEmpty() }
        val cache = if (cached && gemini != null) GeminiPromptCache(gemini.client) else null
        val search = if (device) NotebookSearch.build(bench, lecture.pagesAt(lecture.prepared.durationMs)) else null
        val answers = StringBuilder()
        var outline = ""
        var topic = ""
        val suggestions = mutableListOf<NoteSuggestion>()
        val notes = mutableListOf<BenchNote>()
        var requests = 0
        var failed = 0
        var millis = 0L
        var longestMillis = 0L
        var stopped = false
        var promptTokens = 0L
        var outputTokens = 0L
        var cachedTokens = 0L
        val byModality = HashMap<String, Long>()
        var startMs = 0L
        while (startMs < lecture.prepared.durationMs) {
            val endMs = minOf(startMs + intervalMs, lecture.prepared.durationMs)
            val segments = lecture.transcript.filter { it.startMs >= startMs && it.startMs < endMs }
            if (segments.isEmpty()) {
                startMs = endMs
                continue
            }
            val speech = RequestTranscript(emptyList(), segments, startMs, 0L, endMs)
            val memory = LectureMemory(
                elapsedMs = endMs,
                outline = outline,
                currentTopic = topic,
                previousTranscript = lecture.transcript.filter { it.endMs <= startMs }.takeLast(3).joinToString(" ") { it.text }.takeLast(600),
                suggestions = suggestions.toList(),
                courseContext = lecture.courseContext
            )
            val notebook = if (search != null) {
                val visible = lecture.visibleIndex(endMs)
                val query = segments.joinToString(" ") { it.text }
                val found = search.find(query.take(MAX_QUERY_CHARS), count = FOUND_PAGES, excluding = visible?.let { (it - 1)..(it + 1) }?.toSet().orEmpty())
                lecture.notebook(endMs, before = 1, after = 1, found = found.toSet())
            } else {
                lecture.notebook(endMs)
            }
            val prompt = LecturePrompts.chunkPrompt(settings, memory, notebook, speech, includeOutline = true, start = start)
            val start = TimeSource.Monotonic.markNow()
            val text: String? = if (device) {
                val answer = bench.ask(bench.generators.first { it.spec.id == "gemma-4-e4b" }, GeneratorOptions(maxTokens = 16384, images = true), prompt, SchemaMode.ASKED)
                answer.error?.let { answers.appendLine("### ${formatLectureTime(startMs)}: error\n$it\n") }
                answer.text.takeIf { answer.error == null }?.let { jsonOf(it, prompt.schema) }
            } else {
                if (spent >= maxDollars) {
                    answers.appendLine("### ${formatLectureTime(startMs)}: stopped at ${"%.2f".format(spent)} dollars\n")
                    stopped = true
                    break
                }
                askGemini(gemini!!, prompt, model!!, answers, startMs, cache)?.let { answer ->
                    val usage = answer.usage
                    if (usage != null) {
                        promptTokens += usage.promptTokens
                        outputTokens += usage.outputTokens
                        cachedTokens += usage.cachedTokens
                        usage.promptByModality.forEach { (modality, count) -> byModality[modality] = (byModality[modality] ?: 0L) + count }
                        spent += dollars(config, usage.promptTokens, usage.cachedTokens, usage.outputTokens)
                        ledger.writeText(spent.toString())
                    }
                    answer.text
                }
            }
            millis += start.elapsedNow().inWholeMilliseconds
            longestMillis = maxOf(longestMillis, start.elapsedNow().inWholeMilliseconds)
            requests++
            val analysis: ChunkAnalysis? = text?.let {
                try {
                    LectureAnalysis.parseChunk(it)
                } catch (_: Exception) {
                    null
                }
            }
            answers.appendLine("### ${formatLectureTime(startMs)}–${formatLectureTime(endMs)}").appendLine("```").appendLine(text ?: "(no answer)").appendLine("```")
            if (analysis == null) {
                failed++
            } else {
                if (analysis.outline.isNotBlank()) outline = analysis.outline.trim()
                if (analysis.currentTopic.isNotBlank()) topic = analysis.currentTopic.trim()
                analysis.notes.forEachIndexed { i, note ->
                    val atMs = startMs + (note.atSeconds * 1000).toLong()
                    suggestions.add(
                        NoteSuggestion(
                            id = "$startMs-$i",
                            kind = NoteKind.entries.firstOrNull { it.name == note.kind } ?: NoteKind.OTHER,
                            title = note.title,
                            content = note.content,
                            pageIndex = note.page?.minus(1),
                            atMs = atMs
                        )
                    )
                    notes.add(BenchNote(atMs, note.page, note.kind, note.title, note.content))
                }
            }
            println("notes $config ${formatLectureTime(startMs)}: ${analysis?.notes?.size ?: "failed"} (${seconds(start.elapsedNow().inWholeMilliseconds)})")
            startMs = endMs
        }
        if (device) bench.loaded.unloadAll()
        cache?.clear(System.getenv("GEMINI_API_KEY").orEmpty())
        bench.output("notes/${lecture.id}/$config.md").writeText(answers.toString())
        return NotesRun(config, lecture.id, intervalMs, requests, failed, millis, longestMillis, promptTokens, outputTokens, byModality, cachedTokens, outline, notes, stopped)
    }

    /** [prompt] answered by [model] of Gemini, repeated on a temporary error; `null` when it could not be. */
    private suspend fun askGemini(backend: GeminiBackend, prompt: ModelPrompt, model: String, answers: StringBuilder, startMs: Long, cache: GeminiPromptCache?) =
        run {
            repeat(ATTEMPTS) { attempt ->
                try {
                    return@run backend.generate(prompt, model, cache)
                } catch (e: GeminiException) {
                    answers.appendLine("### ${formatLectureTime(startMs)}: ${e.statusCode} ${e.message?.take(300)}\n")
                    if (!e.isRetryable || attempt == ATTEMPTS - 1) return@run null
                    delay(30_000L * (attempt + 1))
                }
            }
            null
        }

    /**
     * The price of a run of [config] from its tokens, [cachedTokens] being part of [promptTokens]
     * (the prices of text).
     */
    private fun dollars(config: String, promptTokens: Long, cachedTokens: Long, outputTokens: Long): Double {
        val (input, cached, output) = when {
            config.startsWith("gemini-3.8-flash") -> Triple(0.75, 0.075, 3.75)
            config.startsWith("gemini-3.5-flash-lite") -> Triple(0.30, 0.03, 2.50)
            else -> Triple(0.0, 0.0, 0.0)
        }
        return ((promptTokens - cachedTokens) * input + cachedTokens * cached + outputTokens * output) / 1_000_000.0
    }

    companion object {
        private const val CHECKED = "+repeat"
        private const val ATTEMPTS = 4
        private const val FOUND_PAGES = 3
        private const val MAX_QUERY_CHARS = 2000
    }
}

/**
 * The pages of a notebook searched as `drawview-search` searches them: by their words (BM25) and by
 * EmbeddingGemma 2 (512 dimensions, the prompts of its model card), merged by Reciprocal Rank
 * Fusion; by the words only when the embedding model is not in the bench.
 */
internal class NotebookSearch private constructor(
    private val bench: Bench,
    private val pages: List<PageContent>,
    private val vectors: List<FloatArray>?
) {
    private val words = SearchBench.Bm25(pages.map { indexWords(textOf(it)) })

    /** The indexes of the [count] pages that best match [query], [excluding] some. */
    suspend fun find(query: String, count: Int, excluding: Set<Int>): List<Int> {
        if (query.isBlank()) return emptyList()
        val byWords = words.rank(indexWords(query))
        val ranking = if (vectors != null) {
            val model = bench.embedder!!
            val q = bench.withEmbedder(model, EmbedderOptions()) { it.embed(listOf("task: search result | query: $query")) }.first().let(::normalized)
            val byVectors = vectors.indices.sortedByDescending { dot(q, vectors[it]) }
            fuse(byWords, byVectors)
        } else {
            byWords
        }
        return ranking.map { pages[it].index }.filter { it !in excluding }.take(count)
    }

    companion object {
        /** What a page says: its slide and the notes typed on it. */
        fun textOf(page: PageContent): String = (listOfNotNull(page.slide?.text) + page.typedTexts).joinToString("\n")

        suspend fun build(bench: Bench, pages: List<PageContent>): NotebookSearch {
            val model = bench.embedder
            val vectors = model?.let {
                bench.withEmbedder(it, EmbedderOptions()) { embedder ->
                    pages.map { page -> "title: none | text: ${textOf(page).take(2000)}" }.chunked(16).flatMap { batch -> embedder.embed(batch) }
                }.map(::normalized)
            }
            return NotebookSearch(bench, pages, vectors)
        }

        private fun normalized(vector: FloatArray): FloatArray {
            val cut = vector.copyOf(minOf(512, vector.size))
            val norm = sqrt(cut.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0f } ?: 1f
            for (i in cut.indices) cut[i] /= norm
            return cut
        }

        private fun dot(a: FloatArray, b: FloatArray): Float {
            var sum = 0f
            for (i in a.indices) sum += a[i] * b[i]
            return sum
        }

        private fun fuse(words: List<Int>, vectors: List<Int>): List<Int> {
            val scores = HashMap<Int, Double>()
            for (ranking in listOf(words, vectors)) ranking.take(50).forEachIndexed { rank, page -> scores[page] = (scores[page] ?: 0.0) + 1.0 / (60 + rank + 1) }
            return scores.entries.sortedByDescending { it.value }.map { it.key }
        }
    }
}
