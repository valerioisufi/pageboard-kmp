package io.github.valerioisufi.drawview.lecture

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
data class ChunkAnalysis(
    val transcript: String = "",
    val currentTopic: String = "",
    /** Page of the document (1-based) the lecturer is talking about. */
    val professorPage: Int? = null,
    val outline: String = "",
    val notes: List<AnalysisNote> = emptyList()
)

@Serializable
data class AnalysisNote(
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
data class FinalReview(
    val notesMarkdown: String = "",
    val missing: List<AnalysisNote> = emptyList()
)

object LectureAnalysis {
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

    /**
     * Bullet points as "- ": the canvas reads a single `*` as italic, and the models (the small
     * local ones in particular) often write "*   item".
     */
    internal fun normalizeMarkdown(markdown: String): String = markdown.replace(BULLET, "$1- ")

    private val BULLET = Regex("""(?m)^([ \t]*)[*+][ \t]+""")

    /** Decodes a JSON answer, tolerating a Markdown code fence around it. */
    private fun <T> parse(text: String, serializer: KSerializer<T>): T {
        val trimmed = text.trim()
        val body = if (trimmed.startsWith("```")) {
            trimmed.substringAfter('\n').substringBeforeLast("```").trim()
        } else {
            trimmed
        }
        return json.decodeFromString(serializer, body)
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
                put("description", "A few words.")
            }
            putJsonObject("content") {
                put("type", "string")
                put("description", "Concise notebook-ready Markdown; formulas in LaTeX between \$...\$.")
            }
            putJsonObject("page") {
                put("type", "integer")
                put("description", "Notebook page number (1-based) where the note belongs.")
            }
            putJsonObject("importance") {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", 3)
                put("description", "3 = stressed by the lecturer or relevant for the exam, 1 = useful detail.")
            }
            putJsonObject("atSeconds") {
                put("type", "number")
                put("description", "Seconds from the start of the audio of this request where it was said.")
            }
        }
        putJsonArray("required") {
            add("kind")
            add("title")
            add("content")
            add("importance")
        }
    }

    /** JSON schema of [ChunkAnalysis], with or without the outline (updated every few minutes). */
    fun chunkSchema(includeOutline: Boolean): JsonObject = if (includeOutline) chunkSchemaWithOutline else chunkSchemaWithoutOutline

    private val chunkSchemaWithOutline: JsonObject = buildChunkSchema(includeOutline = true)
    private val chunkSchemaWithoutOutline: JsonObject = buildChunkSchema(includeOutline = false)

    private fun buildChunkSchema(includeOutline: Boolean): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("transcript") {
                put("type", "string")
                put("description", "Faithful transcript of the new audio only.")
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
            add("transcript")
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
