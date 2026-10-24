package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.model.text.RichTextParser
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Answer of the model to a chunk of the lecture (structured output, [LectureAnalysis.chunkSchema]). */
@Serializable
internal data class ChunkAnalysis(
    val transcript: String = "",
    val currentTopic: String = "",
    /** Page of the document (1-based) the lecturer is talking about. */
    val professorPage: Int? = null,
    val outline: String = "",
    val notes: List<AnalysisNote> = emptyList()
)

@Serializable
internal data class AnalysisNote(
    val kind: String = "OTHER",
    val title: String = "",
    val content: String = "",
    /** Page of the document (1-based) where the note belongs. */
    val page: Int? = null,
    val importance: Int = 2,
    /** Seconds from the start of the audio of the request. */
    val atSeconds: Double = 0.0
)

/** Answer of the model to the review of the whole lecture ([LectureAnalysis.finalReviewSchema]). */
@Serializable
internal data class FinalReview(
    val notesMarkdown: String = "",
    val missing: List<AnalysisNote> = emptyList()
)

internal object LectureAnalysis {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun parseChunk(text: String): ChunkAnalysis = parse(text, ChunkAnalysis.serializer()).let { analysis ->
        analysis.copy(
            outline = normalizeMarkdown(analysis.outline),
            notes = analysis.notes.map { it.copy(content = normalizeMarkdown(it.content)) }
        )
    }

    fun parseFinalReview(text: String): FinalReview = parse(text, FinalReview.serializer()).let { review ->
        review.copy(
            notesMarkdown = normalizeMarkdown(review.notesMarkdown),
            missing = review.missing.map { it.copy(content = normalizeMarkdown(it.content)) }
        )
    }

    @Serializable
    private class ContextAnswer(val text: String = "")

    /** The course context written by the model. */
    fun parseCourseContext(text: String): String = parse(text, ContextAnswer.serializer()).text.trim()

    @Serializable
    private class HandwritingAnswer(val blocks: List<HandwritingText> = emptyList())

    @Serializable
    private class HandwritingText(val image: Int = 0, val text: String = "")

    /** The transcribed handwriting, by number of image (1-based). */
    fun parseHandwriting(text: String): Map<Int, String> =
        parse(text, HandwritingAnswer.serializer()).blocks.associate { it.image to normalizeMarkdown(it.text) }

    /**
     * Bullet points as "- ": the canvas reads a single `*` as italic, and the models (the small
     * local ones in particular) often write "*   item"; line breaks written in HTML (`<br>`) or
     * escaped twice in the JSON (a "\\n" left in the text, not followed by a letter as the LaTeX
     * commands are) as new lines. The blocks of code are left as they are: a ` * ` of a comment, a
     * `\\n` of a string and a `<br>` of HTML are code there.
     */
    internal fun normalizeMarkdown(markdown: String): String {
        val blocks = RichTextParser.parse(markdown).filter { it.kind == RichTextParser.Kind.CODE_BLOCK }
        if (blocks.isEmpty()) return normalizeProse(markdown)
        return buildString {
            var from = 0
            for (block in blocks) {
                append(normalizeProse(markdown.substring(from, block.start)))
                append(markdown, block.start, block.end)
                from = block.end
            }
            append(normalizeProse(markdown.substring(from)))
        }
    }

    private fun normalizeProse(text: String): String =
        text.replace(LINE_BREAK, "\n").replace(LITERAL_NEW_LINE, "\n").replace(BULLET, "$1- ")

    private val BULLET = Regex("""(?m)^([ \t]*)[*+][ \t]+""")
    private val LINE_BREAK = Regex("""[ \t]*<br\s*/?>[ \t]*""", RegexOption.IGNORE_CASE)
    private val LITERAL_NEW_LINE = Regex("""\\n(?![A-Za-z])""")

    /**
     * The LaTeX commands written with a single backslash in the JSON strings, as the models (the
     * small local ones in particular) often do: `\frac` and `\neq` are valid escapes (a form
     * feed, a new line) that would lose the command, `\alpha` is not valid at all.
     *
     * The backslash is doubled when it does not start a valid escape, when `\b`, `\f`, `\r` or
     * `\t` is followed by a letter (a backspace, form feed, carriage return or tab before a letter
     * is never meant), and when `\n` starts a LaTeX command (a new line before a word is common).
     * In a block of code of a string (between fences at the start of its lines) only the escapes
     * that are not valid are doubled: a tab before a word is a tab there, `\nu` a new line.
     */
    internal fun repairLatexEscapes(json: String): String {
        val out = StringBuilder(json.length + 16)
        var inString = false
        // A block of code of the string, and the start of one of its lines (after a new line)
        var inCode = false
        var lineStart = false
        var i = 0
        while (i < json.length) {
            val c = json[i]
            if (c == '"') {
                inString = !inString
                inCode = false
                lineStart = inString
                out.append(c)
                i++
                continue
            }
            if (inString && lineStart) {
                lineStart = false
                if (fenceAt(json, i)) inCode = !inCode
            }
            if (!inString || c != '\\') {
                out.append(c)
                i++
                continue
            }
            val next = json.getOrNull(i + 1)
            var end = i + 1
            while (end < json.length && json[end].isLetter()) end++
            val word = json.substring(i + 1, end)
            val validEscape = when (next) {
                null -> true
                '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> true
                'u' -> json.length >= i + 6 && json.substring(i + 2, i + 6).all { it in HEX_DIGITS }
                else -> false
            }
            val isLatex = when {
                !validEscape -> next != null
                inCode -> false
                next == 'b' || next == 'f' || next == 'r' || next == 't' -> word.length > 1
                next == 'n' -> word in LATEX_N_COMMANDS
                else -> false
            }
            if (isLatex) {
                out.append("\\\\")
                i++
            } else {
                // A valid escape, copied as it is (also the escaped quote, which does not end the string)
                out.append(c)
                if (next != null) out.append(next)
                i += 2
                if (next == 'n') lineStart = true
            }
        }
        return out.toString()
    }

    /** Whether a fence of code (up to 3 spaces, then 3 backticks or tildes) is at [index] of [json]. */
    private fun fenceAt(json: String, index: Int): Boolean {
        var i = index
        while (i < json.length && i - index < 3 && json[i] == ' ') i++
        val c = json.getOrNull(i) ?: return false
        return (c == '`' || c == '~') && json.startsWith(c.toString().repeat(3), i)
    }

    private const val HEX_DIGITS = "0123456789abcdefABCDEF"

    /** The LaTeX commands starting with "n" (not `\ne` and `\ni`: a new line before "e" or "i" is common in Italian). */
    private val LATEX_N_COMMANDS = setOf(
        "neq", "nabla", "neg", "nu", "not", "notin", "nmid", "nleq", "ngeq", "nless", "ngtr", "nexists",
        "nsubseteq", "nsupseteq", "nparallel", "newline", "nolimits", "nonumber", "natural", "nearrow", "nwarrow"
    )

    /**
     * Decodes a JSON answer, tolerating a Markdown code fence around it: closed at the end of the
     * answer, or not closed (the fences of the code in the strings of the JSON are not it).
     */
    private fun <T> parse(text: String, serializer: KSerializer<T>): T {
        val trimmed = text.trim()
        val body = if (trimmed.startsWith("```")) {
            val inside = trimmed.substringAfter('\n', "")
            (if (inside.endsWith("```")) inside.removeSuffix("```") else inside).trim()
        } else {
            trimmed
        }
        return json.decodeFromString(serializer, repairLatexEscapes(body))
    }

    private val noteSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("kind") {
                put("type", "string")
                putJsonArray("enum") { NoteKind.entries.forEach { add(it.name) } }
            }
            putJsonObject("title") {
                put("type", "string")
            }
            putJsonObject("content") {
                put("type", "string")
            }
            putJsonObject("page") {
                put("type", "integer")
            }
            putJsonObject("importance") {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", 3)
            }
            putJsonObject("atSeconds") {
                put("type", "number")
            }
        }
        putJsonArray("required") {
            add("kind")
            add("title")
            add("content")
            add("importance")
        }
    }

    @Serializable
    private class TranscriptAnswer(val transcript: String = "")

    /** The transcript of [LecturePrompts.transcriptionOnlyPrompt]. */
    fun parseTranscript(text: String): String = parse(text, TranscriptAnswer.serializer()).transcript.trim()

    /** JSON schema of [LecturePrompts.transcriptionOnlyPrompt]. */
    val transcriptSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("transcript") { put("type", "string") }
        }
        putJsonArray("required") { add("transcript") }
    }

    /** JSON schema of the course context ([LecturePrompts.courseContextPrompt]). */
    val courseContextSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("text") { put("type", "string") }
        }
        putJsonArray("required") { add("text") }
    }

    /**
     * JSON schema of [ChunkAnalysis], with or without the outline (updated every few minutes) and
     * the transcript (not asked when the audio is transcribed on this computer).
     */
    fun chunkSchema(includeOutline: Boolean, includeTranscript: Boolean = true): JsonObject =
        chunkSchemas.getValue(includeOutline to includeTranscript)

    private val chunkSchemas: Map<Pair<Boolean, Boolean>, JsonObject> = listOf(true, false).flatMap { outline ->
        listOf(true, false).map { transcript -> (outline to transcript) to buildChunkSchema(outline, transcript) }
    }.toMap()

    private fun buildChunkSchema(includeOutline: Boolean, includeTranscript: Boolean): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            if (includeTranscript) {
                putJsonObject("transcript") {
                    put("type", "string")
                    put("description", "Faithful transcript of the new audio only.")
                }
            }
            putJsonObject("currentTopic") {
                put("type", "string")
                put("description", "Current topic in a few words.")
            }
            putJsonObject("professorPage") {
                put("type", "integer")
                put("description", "Notebook page number (1-based) the lecturer is talking about; omit if unsure.")
            }
            if (includeOutline) {
                putJsonObject("outline") {
                    put("type", "string")
                    put("description", "Updated outline of the whole lecture so far (Markdown bullet list).")
                }
            }
            putJsonObject("notes") {
                put("type", "array")
                put("items", noteSchema)
            }
        }
        putJsonArray("required") {
            if (includeTranscript) add("transcript")
            add("currentTopic")
            if (includeOutline) add("outline")
            add("notes")
        }
    }

    /** JSON schema of [FinalReview]. */
    val finalReviewSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("notesMarkdown") {
                put("type", "string")
                put("description", "Complete, well organized notes of the lecture in Markdown.")
            }
            putJsonObject("missing") {
                put("type", "array")
                put("items", noteSchema)
            }
        }
        putJsonArray("required") {
            add("notesMarkdown")
            add("missing")
        }
    }
}
