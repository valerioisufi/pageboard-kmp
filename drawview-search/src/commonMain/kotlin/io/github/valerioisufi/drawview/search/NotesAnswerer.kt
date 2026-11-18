package io.github.valerioisufi.drawview.search

import io.github.valerioisufi.drawview.ml.GenerationPart
import io.github.valerioisufi.drawview.ml.GenerationRequest
import io.github.valerioisufi.drawview.ml.Generator
import io.github.valerioisufi.drawview.ml.GeneratorOptions
import io.github.valerioisufi.drawview.ml.InstalledModels
import io.github.valerioisufi.drawview.ml.LoadedModels
import io.github.valerioisufi.drawview.ml.ModelCatalog
import io.github.valerioisufi.drawview.ml.ModelException
import io.github.valerioisufi.drawview.ml.ModelSpec
import io.github.valerioisufi.drawview.model.text.RichTextParser
import io.github.valerioisufi.drawview.model.text.RichTextParser.Kind

/**
 * A passage from the student's notes cited by a [NotesAnswer].
 *
 * @param number 1-based citation index (`[1]`, `[2]`, …).
 * @param hit the retrieved passage and its place.
 */
public data class CitedPassage(
    public val number: Int,
    public val hit: SearchHit
)

/**
 * Answer synthesized by [NotesAnswerer] from the passages retrieved for [question].
 *
 * @param question the question asked by the student.
 * @param text Markdown answer citing passages as `[1]`, `[2]`, … (empty when no passages matched).
 * @param citations passages cited in [text], ordered by first appearance.
 * @param passages all passages retrieved and given to the model.
 * @param modelName identifier or display name of the model that wrote [text].
 */
public data class NotesAnswer(
    public val question: String,
    public val text: String,
    public val citations: List<CitedPassage>,
    public val passages: List<SearchHit>,
    public val modelName: String
)

/**
 * Optional online backend (such as Google Gemini, provided by `drawview-lecture` when an API key
 * is configured) that answers a question from the retrieved passages when explicitly chosen by
 * the student (Protected Variations, Indirection).
 */
public interface OnlineAnswerBackend {
    /** Whether the online backend is currently configured and ready (e.g. an API key is set). */
    public val isAvailable: Boolean get() = true

    /**
     * Generates an answer for [prompt] with [systemInstruction], returning the answer text paired
     * with the model name.
     */
    public suspend fun generate(systemInstruction: String, prompt: String): Pair<String, String>

    public companion object {
        /** Creates an [OnlineAnswerBackend] from [isAvailable] and [generate] lambdas. */
        public operator fun invoke(
            isAvailable: () -> Boolean = { true },
            generate: suspend (systemInstruction: String, prompt: String) -> Pair<String, String>
        ): OnlineAnswerBackend = object : OnlineAnswerBackend {
            override val isAvailable: Boolean get() = isAvailable()
            override suspend fun generate(
                systemInstruction: String,
                prompt: String
            ): Pair<String, String> = generate(systemInstruction, prompt)
        }
    }
}

/**
 * Retrieval-augmented question answering ("Ask your notes") over the passages found by [engine]
 * (Pure Fabrication).
 *
 * Answers on the device with Gemma 4 ([answerOnDevice]) or online ([answerOnline]) only when the
 * student explicitly asks for it.
 */
public class NotesAnswerer(
    private val engine: SearchEngine,
    private val loadedModels: LoadedModels? = null,
    private val installedModels: InstalledModels? = null,
    private val generatorOptions: GeneratorOptions = GeneratorOptions(
        maxTokens = 4096,
        images = false,
        audio = false
    ),
    private val generator: Generator? = null
) {
    /**
     * Answers [question] on the device using [modelSpec] (or [generator] when supplied directly),
     * retrieving up to [passageLimit] passages from [engine] (of [documentIds] only, when given).
     */
    public suspend fun answerOnDevice(
        question: String,
        modelSpec: ModelSpec = ModelCatalog.GEMMA_4_E2B,
        passageLimit: Int = SearchEngine.DEFAULT_PASSAGE_LIMIT,
        documentIds: Set<String>? = null,
        onProgress: (Int) -> Unit = {}
    ): NotesAnswer {
        val trimmed = question.trim()
        val retrieved = engine.passages(trimmed, limit = passageLimit, documentIds = documentIds)
        if (retrieved.isEmpty()) {
            return NotesAnswer(
                question = trimmed,
                text = "",
                citations = emptyList(),
                passages = emptyList(),
                modelName = modelSpec.name
            )
        }

        val prompt = buildPrompt(trimmed, retrieved)
        val request = GenerationRequest(
            system = SYSTEM_PROMPT,
            parts = listOf(GenerationPart.Text(prompt))
        )
        val rawAnswer = if (generator != null) {
            generator.generate(request, onProgress)
        } else {
            val installed = installedModels?.get(modelSpec)
                ?: throw ModelException("Model ${modelSpec.name} is not installed")
            val loader = loadedModels
                ?: throw ModelException("Local model runtime is not available")
            loader.withGenerator(
                model = installed,
                options = generatorOptions,
                keepLoadedMillis = KEEP_GENERATOR_LOADED_MS
            ) { active ->
                active.generate(request, onProgress)
            }
        }
        val cleaned = rawAnswer.trim()
        return NotesAnswer(
            question = trimmed,
            text = cleaned,
            citations = extractCitations(cleaned, retrieved),
            passages = retrieved,
            modelName = modelSpec.name
        )
    }

    /**
     * Answers [question] using the online [backend] (e.g. Google Gemini, chosen explicitly by the
     * student), retrieving up to [passageLimit] passages from [engine] (of [documentIds] only, when
     * given).
     */
    public suspend fun answerOnline(
        question: String,
        backend: OnlineAnswerBackend,
        passageLimit: Int = SearchEngine.DEFAULT_PASSAGE_LIMIT,
        documentIds: Set<String>? = null
    ): NotesAnswer {
        val trimmed = question.trim()
        val retrieved = engine.passages(trimmed, limit = passageLimit, documentIds = documentIds)
        if (retrieved.isEmpty()) {
            return NotesAnswer(
                question = trimmed,
                text = "",
                citations = emptyList(),
                passages = emptyList(),
                modelName = "Gemini"
            )
        }

        val prompt = buildPrompt(trimmed, retrieved)
        val (rawAnswer, modelName) = backend.generate(SYSTEM_PROMPT, prompt)
        val cleaned = rawAnswer.trim()
        return NotesAnswer(
            question = trimmed,
            text = cleaned,
            citations = extractCitations(cleaned, retrieved),
            passages = retrieved,
            modelName = modelName
        )
    }

    public companion object {
        private const val KEEP_GENERATOR_LOADED_MS = 60_000L

        /** A bracket holding a list of numbers or ranges (`1`, `2-3`) separated by `,` or `;`. */
        private val CITATION_REGEX =
            Regex("""\[\s*(\d{1,9}(?:\s*[-–]\s*\d{1,9})?(?:\s*[,;]\s*\d{1,9}(?:\s*[-–]\s*\d{1,9})?)*)\s*]""")

        /** The words after which a bracket is an interval of numbers, not a list of citations. */
        private val INTERVAL_WORD_REGEX = Regex("""(?i)\b(interval|intervals|intervallo|intervalli)\s*$""")

        /** The spans whose brackets are not citations: formulas and code, as the page finds them. */
        private val NON_CITATION_KINDS = setOf(Kind.FORMULA, Kind.CODE, Kind.CODE_BLOCK)

        internal const val SYSTEM_PROMPT: String =
            "You answer questions for a student using ONLY the numbered passages from their notes provided below.\n" +
                "Rules:\n" +
                "1. Write your answer in the same language as the student's question.\n" +
                "2. Base every statement strictly on the provided passages. Do not invent facts or use outside knowledge.\n" +
                "3. Cite the passages you use inline with their bracketed numbers, such as [1] or [2].\n" +
                "4. Preserve mathematical formulas in LaTeX between $ or $$.\n" +
                "5. If the provided passages do not contain the answer to the question, state clearly in the language of the question that the answer is not in the notes."

        internal fun buildPrompt(question: String, passages: List<SearchHit>): String = buildString {
            appendLine("Passages from the notes:")
            appendLine()
            passages.forEachIndexed { index, hit ->
                val number = index + 1
                val location = formatPassageLocation(hit.anchor)
                if (location.isNotEmpty()) {
                    appendLine("[$number] ${hit.title} ($location)")
                } else {
                    appendLine("[$number] ${hit.title}")
                }
                appendLine(hit.text.trim())
                appendLine()
            }
            append("Question: ")
            append(question)
        }

        /**
         * Reads the passages cited in [answerText]: brackets holding numbers or ranges separated
         * by `,` or `;` (`[1]`, `[1, 2]`, `[1][2]`, `[1-3]`), outside formulas and code (found by
         * [RichTextParser], as on the page). A bracket is a citation only when every number in it
         * is a passage given (so neither `[0, 1]` nor `[2, 9]` with three passages is one) and it
         * does not follow the word "interval"; each passage is listed once, in the order of its
         * first citation.
         */
        internal fun extractCitations(answerText: String, passages: List<SearchHit>): List<CitedPassage> {
            if (answerText.isBlank() || passages.isEmpty()) return emptyList()
            val text = withoutFormulasAndCode(answerText)
            val count = passages.size
            val seen = LinkedHashSet<Int>()
            for (match in CITATION_REGEX.findAll(text)) {
                val before = text.substring(maxOf(0, match.range.first - 24), match.range.first)
                if (INTERVAL_WORD_REGEX.containsMatchIn(before)) continue
                seen += citedNumbers(match.groupValues[1], count) ?: continue
            }
            return seen.map { number -> CitedPassage(number = number, hit = passages[number - 1]) }
        }

        /**
         * The numbers of the list [content] (`1, 2-3`) in their order, or `null` when one of them
         * is not in `1..count` or a range is reversed.
         */
        private fun citedNumbers(content: String, count: Int): List<Int>? {
            val numbers = ArrayList<Int>()
            for (item in content.split(',', ';')) {
                val bounds = item.split('-', '–').map { it.trim().toIntOrNull() ?: return null }
                val first = bounds.first()
                val last = bounds.last()
                if (first < 1 || last < first || last > count) return null
                for (number in first..last) numbers += number
            }
            return numbers
        }

        /** [text] with its formulas and code blanked out, every other character in its place. */
        private fun withoutFormulasAndCode(text: String): String {
            val spans = RichTextParser.parse(text).filter { it.kind in NON_CITATION_KINDS }
            if (spans.isEmpty()) return text
            val chars = text.toCharArray()
            for (span in spans) {
                for (i in span.start until span.end) {
                    if (chars[i] != '\n') chars[i] = ' '
                }
            }
            return chars.concatToString()
        }

        private fun formatPassageLocation(anchor: SearchAnchor): String = when (anchor) {
            is SearchAnchor.Document -> ""
            is SearchAnchor.Page -> "p. ${anchor.pageNumber}"
            is SearchAnchor.Lecture -> {
                val totalSeconds = (anchor.startMs / 1000L).coerceAtLeast(0L)
                val minutes = totalSeconds / 60L
                val seconds = totalSeconds % 60L
                "lecture $minutes:${seconds.toString().padStart(2, '0')}"
            }
        }
    }
}
