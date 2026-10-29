package io.github.valerioisufi.drawview.lecture.bench

import io.github.valerioisufi.drawview.lecture.AssistantSettings
import io.github.valerioisufi.drawview.lecture.ChunkAnalysis
import io.github.valerioisufi.drawview.lecture.LectureAnalysis
import io.github.valerioisufi.drawview.lecture.LectureMemory
import io.github.valerioisufi.drawview.lecture.LecturePrompts
import io.github.valerioisufi.drawview.lecture.NoteKind
import io.github.valerioisufi.drawview.lecture.NoteSuggestion
import io.github.valerioisufi.drawview.lecture.PageContent
import io.github.valerioisufi.drawview.lecture.RequestAudio
import io.github.valerioisufi.drawview.lecture.RequestSpeech
import io.github.valerioisufi.drawview.lecture.RequestTranscript
import io.github.valerioisufi.drawview.lecture.audio.RecordingFile
import io.github.valerioisufi.drawview.lecture.audio.SpeechChunker
import io.github.valerioisufi.drawview.lecture.audio.Wav
import io.github.valerioisufi.drawview.lecture.formatLectureTime
import io.github.valerioisufi.drawview.lecture.gemini.GeminiClient
import io.github.valerioisufi.drawview.lecture.gemini.GeminiException
import io.github.valerioisufi.drawview.lecture.model.GeminiBackend
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
    val promptTokens: Long,
    val outputTokens: Long,
    val promptByModality: Map<String, Long>,
    val cachedTokens: Long = 0,
    val outline: String,
    val notes: List<BenchNote>
)

/**
 * The live notes of a whole lecture of the workspace of lecture-eval, a request every
 * [intervalMs], built as the assistant builds them ([LecturePrompts.chunkPrompt]), by each
 * configuration of [configs]:
 *
 * * `e4b-text`, `e4b-audio`: Gemma 4 E4B on this computer, the schema asked, with the transcript of
 *   the lecture or its audio (clips of at most 30 seconds cut at a pause); the notebook is the
 *   visible page and those next to it, plus the pages found by searching the notebook (BM25 and
 *   EmbeddingGemma 2 merged by Reciprocal Rank Fusion, as `drawview-search` ranks them) with the
 *   new transcript, or with the topic and the visible slide when the model hears the audio;
 * * `<gemini model>-text`, `<gemini model>-audio`: a model of the Gemini API (`GEMINI_API_KEY`),
 *   with the transcript or the audio of the interval in one piece, the notebook as the assistant
 *   sends it; no transcript is asked back. With `-cached`, the text of every slide comes first,
 *   after the instructions, so that the prefix reused by the implicit caching of Gemini is long
 *   enough; with `-medium`, the model thinks at the medium level.
 *
 * A configuration may end with `@<seconds>`, its own interval instead of [intervalMs].
 *
 * The notebook is the one of the student without the assistant ([BenchLecture.untouched]).
 *
 * Every run is written to `<bench>/answers/notes/<config>.json` (the notes, the tokens, the time),
 * the answers to `<config>.md`. The Gemini runs stop at [maxDollars], estimated from the tokens.
 */
internal class NotesBench(
    private val bench: Bench,
    private val workspace: File,
    private val configs: List<String>,
    private val intervalMs: Long = 300_000L,
    private val lectureId: String = "2026-10-15_09-48-14-2ce0b6",
    private val maxDollars: Double = 1.2
) {
    private val settings = AssistantSettings(spokenLanguage = "it")
    private val json = Json { prettyPrint = true }
    private var spent = 0.0

    suspend fun run(report: StringBuilder) {
        report.appendLine("## The notes of a whole lecture").appendLine()
        val recorded = BenchLecture.read(workspace, File(bench.material, "pencilnotes"), lectureId)
        val lecture = recorded?.untouched()
        if (recorded == null || lecture == null) {
            report.appendLine("No lecture $lectureId in $workspace and ${bench.material}/pencilnotes.").appendLine()
            return
        }
        val recording = RecordingFile(File(bench.material, "pencilnotes/lectures/doc-${lecture.prepared.documentId}/$lectureId").path)
        report.appendLine(
            "`$lectureId` (${formatLectureTime(lecture.prepared.durationMs)}), without the assistant: ${lecture.prepared.notes.size} notes of the student " +
                "(${recorded.prepared.notes.size - lecture.prepared.notes.size} inserted from the suggestions removed), no images of the pages."
        ).appendLine()
        report.appendLine("| Configuration | Interval | Requests | Failed | Notes | Prompt tokens | Cached tokens | Output tokens | Time | Dollars |")
            .appendLine("|---|---|---|---|---|---|---|---|---|---|")
        for (config in configs) {
            val run = run(config, lecture, recording)
            bench.output("notes/$config.json").writeText(json.encodeToString(NotesRun.serializer(), run))
            report.appendLine(
                "| $config | ${run.intervalMs / 1000} s | ${run.requests} | ${run.failed} | ${run.notes.size} | ${run.promptTokens} | ${run.cachedTokens} | ${run.outputTokens} | " +
                    "${seconds(run.millis)} | ${"%.3f".format(dollars(config, run.promptTokens, run.cachedTokens, run.outputTokens))} |"
            )
            println("notes $config: ${run.requests} requests, ${run.notes.size} notes, ${seconds(run.millis)}")
        }
        report.appendLine()
    }

    private suspend fun run(config: String, lecture: BenchLecture, recording: RecordingFile): NotesRun {
        val name = config.substringBefore('@')
        val intervalMs = config.substringAfter('@', "").toLongOrNull()?.times(1000) ?: intervalMs
        val cached = name.contains("-cached")
        val base = name.replace("-cached", "").replace("-medium", "")
        val settings = if (name.contains("-medium")) settings.copy(thinkingLevel = "medium") else settings
        val audio = base.endsWith("-audio")
        val device = base.startsWith("e4b")
        val model = if (device) null else base.removeSuffix("-audio").removeSuffix("-text")
        val slides = if (cached) slidesText(lecture) else null
        val gemini = if (device) null else GeminiBackend(GeminiClient()) { System.getenv("GEMINI_API_KEY").orEmpty() }
        val search = if (device) NotebookSearch.build(bench, lecture.pagesAt(lecture.prepared.durationMs)) else null
        val answers = StringBuilder()
        var outline = ""
        var topic = ""
        val suggestions = mutableListOf<NoteSuggestion>()
        val notes = mutableListOf<BenchNote>()
        var requests = 0
        var failed = 0
        var millis = 0L
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
            val samples = if (audio) recording.readSegment(startMs, endMs - startMs) ?: ShortArray(0) else ShortArray(0)
            val speech: RequestSpeech = if (audio) {
                RequestAudio(Wav.encode(samples), startMs, 0L, endMs)
            } else {
                RequestTranscript(emptyList(), segments, startMs, 0L, endMs)
            }
            val memory = LectureMemory(
                elapsedMs = endMs,
                outline = outline,
                currentTopic = topic,
                // The assistant keeps the end of the transcript only when there is one
                previousTranscript = if (audio) "" else lecture.transcript.filter { it.endMs <= startMs }.takeLast(3).joinToString(" ") { it.text }.takeLast(600),
                suggestions = suggestions.toList(),
                courseContext = lecture.courseContext
            )
            val notebook = if (search != null) {
                val visible = lecture.visibleIndex(endMs)
                val query = if (audio) {
                    listOf(topic, outline.lines().takeLast(4).joinToString(" "), lecture.pagesAt(endMs).firstOrNull { it.index == visible }?.let(NotebookSearch::textOf).orEmpty()).joinToString(" ")
                } else {
                    segments.joinToString(" ") { it.text }
                }
                val found = search.find(query.take(MAX_QUERY_CHARS), count = FOUND_PAGES, excluding = visible?.let { (it - 1)..(it + 1) }?.toSet().orEmpty())
                lecture.notebook(endMs, before = 1, after = 1, found = found.toSet())
            } else {
                lecture.notebook(endMs)
            }
            var prompt = LecturePrompts.chunkPrompt(settings, memory, notebook, speech, includeOutline = true, includeTranscript = false)
            if (audio && device) prompt = prompt.withAudioClips(samples)
            if (slides != null) prompt = prompt.withSlidesFirst(slides)
            val start = TimeSource.Monotonic.markNow()
            val text: String? = if (device) {
                val answer = bench.ask(bench.generators.first { it.spec.id == "gemma-4-e4b" }, GeneratorOptions(maxTokens = 16384, images = true, audio = audio), prompt, SchemaMode.ASKED)
                answer.error?.let { answers.appendLine("### ${formatLectureTime(startMs)}: error\n$it\n") }
                answer.text.takeIf { answer.error == null }?.let { jsonOf(it, prompt.schema) }
            } else {
                if (spent >= maxDollars) {
                    answers.appendLine("### ${formatLectureTime(startMs)}: stopped at ${"%.2f".format(spent)} dollars\n")
                    break
                }
                askGemini(gemini!!, prompt, model!!, answers, startMs)?.let { answer ->
                    val usage = answer.usage
                    if (usage != null) {
                        promptTokens += usage.promptTokens
                        outputTokens += usage.outputTokens
                        cachedTokens += usage.cachedTokens
                        usage.promptByModality.forEach { (modality, count) -> byModality[modality] = (byModality[modality] ?: 0L) + count }
                        spent += dollars(config, usage.promptTokens, usage.cachedTokens, usage.outputTokens)
                    }
                    answer.text
                }
            }
            millis += start.elapsedNow().inWholeMilliseconds
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
        bench.output("notes/$config.md").writeText(answers.toString())
        return NotesRun(config, lectureId, intervalMs, requests, failed, millis, promptTokens, outputTokens, byModality, cachedTokens, outline, notes)
    }

    /** [prompt] answered by [model] of Gemini, repeated on a temporary error; `null` when it could not be. */
    private suspend fun askGemini(backend: GeminiBackend, prompt: ModelPrompt, model: String, answers: StringBuilder, startMs: Long) =
        run {
            repeat(ATTEMPTS) { attempt ->
                try {
                    return@run backend.generate(prompt, model) {}
                } catch (e: GeminiException) {
                    answers.appendLine("### ${formatLectureTime(startMs)}: ${e.statusCode} ${e.message?.take(300)}\n")
                    if (!e.isRetryable || attempt == ATTEMPTS - 1) return@run null
                    delay(30_000L * (attempt + 1))
                }
            }
            null
        }

    /**
     * [this] prompt with its audio ([samples]) in clips of at most 30 seconds cut at a pause, as Gemma 4 takes
     * them.
     */
    private fun ModelPrompt.withAudioClips(samples: ShortArray): ModelPrompt = ModelPrompt(
        system = system,
        parts = parts.flatMap { part ->
            if (part !is PromptPart.Audio) return@flatMap listOf(part)
            val chunker = SpeechChunker(25, 30, overlapSeconds = 0)
            (chunker.push(samples) + listOfNotNull(chunker.cutNow())).map { PromptPart.Audio(Wav.encode(it.samples)) }
        },
        schema = schema,
        maxOutputTokens = maxOutputTokens,
        thinkingLevel = thinkingLevel
    )

    /** The text of every slide of the lecture, the same in every request: the cached prefix. */
    private fun slidesText(lecture: BenchLecture): String = buildString {
        appendLine("## Slides of the notebook")
        for (page in lecture.pagesAt(lecture.prepared.durationMs)) {
            val text = page.slide?.text?.trim().orEmpty()
            if (text.isNotEmpty()) appendLine("[Notebook page ${page.index + 1}] ${text.replace('\n', ' ')}")
        }
    }.trim()

    /** [this] prompt with [slides] at the start of its parts, and the rule that tells why. */
    private fun ModelPrompt.withSlidesFirst(slides: String): ModelPrompt = ModelPrompt(
        system = "$system\n\n$SLIDES_RULE",
        parts = listOf(PromptPart.Text(slides)) + parts,
        schema = schema,
        maxOutputTokens = maxOutputTokens,
        thinkingLevel = thinkingLevel
    )

    /**
     * The price of a run of [config] from its tokens, [cachedTokens] being part of [promptTokens]
     * (the audio as the text: the API says no other price for these models).
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
        private const val ATTEMPTS = 4
        private const val FOUND_PAGES = 3
        private const val MAX_QUERY_CHARS = 2000
        private const val SLIDES_RULE =
            "The text of every slide of the notebook comes first, whether the slide is on screen or not. Restating a slide is redundant, explaining it is not: " +
                "when the lecturer adds something to a slide (an explanation, an example, a condition, an intuition, a step of a proof, a remark), suggest it, " +
                "even if its topic is on the slide."
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
