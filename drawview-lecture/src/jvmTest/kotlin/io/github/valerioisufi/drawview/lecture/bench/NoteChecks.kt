package io.github.valerioisufi.drawview.lecture.bench

import io.github.valerioisufi.drawview.lecture.LectureAnalysis
import io.github.valerioisufi.drawview.lecture.TranscriptSegment
import io.github.valerioisufi.drawview.lecture.model.ModelPrompt
import io.github.valerioisufi.drawview.lecture.model.PromptPart
import java.io.File
import kotlin.time.TimeSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add

/** One test a note of a run must pass before it reaches the student. */
internal fun interface NoteCheck {
    /** Why [note] is dropped, given the notes [kept] before it; `null` when it passes. */
    suspend fun droppedBecause(note: BenchNote, kept: List<BenchNote>): String?
}

/** What [NoteChecks] decided about a note: [droppedBecause] is `null` when the note was kept. */
internal class NoteDecision(val note: BenchNote, val droppedBecause: String?)

/** The checks a note must pass, in order: the first that drops it says why, and the others are not asked. */
internal class NoteChecks(private val checks: List<NoteCheck>) {
    /** A decision for each of [notes], in their order, each note checked against those kept before it. */
    suspend fun decide(notes: List<BenchNote>): List<NoteDecision> {
        val kept = mutableListOf<BenchNote>()
        return notes.map { note ->
            var reason: String? = null
            for (check in checks) {
                reason = check.droppedBecause(note, kept)
                if (reason != null) break
            }
            if (reason == null) kept.add(note)
            NoteDecision(note, reason)
        }
    }
}

/**
 * Drops a note whose words of the lecturer ([BenchNote.said]) are not in the transcript of its request:
 * less than [share] of them are there, or the model gave none. It is aimed at the notes of what the
 * lecturer did not say, and asks no request of its own.
 */
internal class SaidCheck(
    private val transcript: List<TranscriptSegment>,
    private val intervalMs: Long,
    private val share: Double = SAID_SHARE
) : NoteCheck {
    override suspend fun droppedBecause(note: BenchNote, kept: List<BenchNote>): String? {
        val said = RepeatCheck.wordsOf(note.said)
        if (said.isEmpty()) return "gives no words of the lecturer"
        val heard = transcript.filter { it.startMs >= note.fromMs && it.startMs < note.fromMs + intervalMs }
            .flatMapTo(HashSet()) { RepeatCheck.wordsOf(it.text) }
        val found = said.count { it in heard }.toDouble() / said.size
        return if (found < share) "the lecturer did not say it (${(found * 100).toInt()}% of the words)" else null
    }

    companion object {
        const val SAID_SHARE = 0.8
    }
}

/** What [ModelCheck] answered about a note, and how long it took. */
@Serializable
internal class NoteJudgement(val verdict: String, val millis: Long)

/**
 * Drops a note the model cannot back with the lecturer, or finds on a slide, with a short request per
 * note: the pages [pagesOf] gives for it and the transcript of its request. The model does not give a
 * verdict, which it answers `ADDS` to almost always (docs/local-notes.md, phase 4): it copies the
 * sentence of the lecturer and the sentence of a slide that say the same as the note, and a copy is
 * believed only if its words are really there. [ask] answers a prompt with its text, or `null` when it
 * failed, and a note whose judgement cannot be read is kept: a check drops a note for a reason only.
 * The judgements are kept in [store] by the text of the note, and a judgement already
 * there is not asked again. [millisByInterval] is the time of the judgements of each interval.
 */
internal class ModelCheck(
    private val transcript: List<TranscriptSegment>,
    private val intervalMs: Long,
    private val store: File,
    private val pagesOf: suspend (BenchNote) -> List<String>,
    private val ask: suspend (ModelPrompt) -> String?
) : NoteCheck {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), NoteJudgement.serializer())
    private val judgements = (if (store.isFile) json.decodeFromString(serializer, store.readText()) else emptyMap()).toMutableMap()

    val millisByInterval = HashMap<Long, Long>()

    override suspend fun droppedBecause(note: BenchNote, kept: List<BenchNote>): String? {
        val fromMs = if (note.fromMs > 0) note.fromMs else note.atMs / intervalMs * intervalMs
        val key = "${note.title}: ${note.content}"
        val judgement = judgements[key] ?: judge(note, fromMs).also {
            judgements[key] = it
            store.writeText(json.encodeToString(serializer, judgements))
        }
        millisByInterval[fromMs] = (millisByInterval[fromMs] ?: 0L) + judgement.millis
        return when (judgement.verdict) {
            "REPEATS" -> "the model says it repeats the slides"
            "NOT_SAID" -> "the model says the lecturer did not say it"
            else -> null
        }
    }

    private suspend fun judge(note: BenchNote, fromMs: Long): NoteJudgement {
        val heard = transcript.filter { it.startMs >= fromMs && it.startMs < fromMs + intervalMs }.joinToString(" ") { it.text }
        val pages = pagesOf(note).joinToString("\n\n") { it.take(MAX_PAGE_CHARS) }
        val prompt = ModelPrompt(
            system = JUDGE_INSTRUCTION,
            parts = listOf(PromptPart.Text("## Slides\n$pages\n\n## What the lecturer said\n$heard\n\n## The note\n${note.title}: ${note.content}")),
            schema = JUDGE_SCHEMA,
            maxOutputTokens = 256,
            thinkingLevel = "low"
        )
        val start = TimeSource.Monotonic.markNow()
        val answer = ask(prompt)
        val verdict = answer?.let { verdictOf(it, heard, pages) } ?: "UNREADABLE"
        return NoteJudgement(verdict, start.elapsedNow().inWholeMilliseconds)
    }

    private fun verdictOf(answer: String, heard: String, pages: String): String? = try {
        val copied = json.decodeFromString(JudgeAnswer.serializer(), answer)
        when {
            !isIn(copied.lecturer, heard) -> "NOT_SAID"
            isIn(copied.slide, pages) -> "REPEATS"
            else -> "ADDS"
        }
    } catch (_: Exception) {
        null
    }

    /** Whether [copy] says something and its words are in [text]. */
    private fun isIn(copy: String, text: String): Boolean {
        val words = RepeatCheck.wordsOf(copy)
        val inText = RepeatCheck.wordsOf(text)
        return words.isNotEmpty() && words.count { it in inText }.toDouble() / words.size >= COPY_SHARE
    }

    @Serializable
    private class JudgeAnswer(val lecturer: String = "", val slide: String = "")

    companion object {
        private const val MAX_PAGE_CHARS = 1500
        private const val COPY_SHARE = 0.8
        private const val JUDGE_INSTRUCTION =
            "You check a note suggested to a student during a university lecture, against the slides and what the lecturer said. Answer with JSON:\n" +
                "- lecturer: copy word for word the sentence of the lecturer that says the same as the note; empty if there is none;\n" +
                "- slide: copy word for word the sentence of the slides that says the same as the note; empty if there is none.\n" +
                "Do not write anything of your own."
        private val JUDGE_SCHEMA = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("lecturer") { put("type", "string") }
                putJsonObject("slide") { put("type", "string") }
            }
            putJsonArray("required") {
                add("lecturer")
                add("slide")
            }
        }
    }
}

/** [prompt] asking each note for the words of the lecturer it comes from, in the field `said`. */
internal fun withSaid(prompt: ModelPrompt): ModelPrompt = ModelPrompt(
    system = prompt.system + "\n\n" + SAID_INSTRUCTION,
    parts = prompt.parts,
    schema = schemaWithSaid(prompt.schema),
    maxOutputTokens = prompt.maxOutputTokens,
    thinkingLevel = prompt.thinkingLevel,
    cachedParts = prompt.cachedParts
)

private const val SAID_INSTRUCTION =
    "For every note, the field `said` copies, word for word, the sentence of the new transcript the note comes from. " +
        "A note that no sentence of the new transcript supports is not written."

private fun schemaWithSaid(schema: JsonObject): JsonObject = schema.edited("properties") { properties ->
    properties.edited("notes") { notes ->
        notes.edited("items") { items ->
            val described = items.edited("properties") { note ->
                JsonObject(
                    note + (
                        "said" to buildJsonObject {
                            put("type", "string")
                            put("description", "The sentence of the new transcript this note comes from, copied word for word.")
                        }
                        )
                )
            }
            val required = described["required"]?.jsonArray.orEmpty() + JsonPrimitive("said")
            JsonObject(described + ("required" to JsonArray(required)))
        }
    }
}

private fun JsonObject.edited(key: String, edit: (JsonObject) -> JsonObject) = JsonObject(this + (key to edit(getValue(key).jsonObject)))

@Serializable
private class SaidNote(val said: String = "")

@Serializable
private class SaidAnswer(val notes: List<SaidNote> = emptyList())

private val saidJson = Json { ignoreUnknownKeys = true }

/** The `said` of every note of the answer [text], in order; nothing when it cannot be read. */
internal fun saidOf(text: String): List<String> = try {
    saidJson.decodeFromString(SaidAnswer.serializer(), LectureAnalysis.repairLatexEscapes(text)).notes.map { it.said }
} catch (_: Exception) {
    emptyList()
}

/**
 * Drops a note that repeats a slide or a note kept before it, by the words they share: the share of
 * the words of the note found in the other text. Every slide of [slides] counts, also those the
 * request did not hold. It costs no model, and it reads only the content of a note, or its title
 * when the content has no words.
 *
 * [slideShare] and [noteShare] come from the verdicts of the grader that did not change on the dev
 * lectures (docs/local-notes.md, phase 3).
 */
internal class RepeatCheck(
    slides: List<String>,
    private val slideShare: Double = SLIDE_SHARE,
    private val noteShare: Double = NOTE_SHARE
) : NoteCheck {
    private val slides = slides.map { wordsOf(it) }

    override suspend fun droppedBecause(note: BenchNote, kept: List<BenchNote>): String? {
        val words = wordsOfNote(note)
        if (words.isEmpty()) return null
        val slide = slides.maxOfOrNull { share(words, it) } ?: 0.0
        if (slide >= slideShare) return "repeats a slide (${percent(slide)} of its words)"
        val before = kept.maxOfOrNull { share(words, wordsOfNote(it)) } ?: 0.0
        if (before >= noteShare) return "repeats a note (${percent(before)} of its words)"
        return null
    }

    private fun wordsOfNote(note: BenchNote): Set<String> = wordsOf(note.content).ifEmpty { wordsOf(note.title) }

    private fun share(words: Set<String>, other: Set<String>) = words.count { it in other }.toDouble() / words.size

    private fun percent(share: Double) = "${(share * 100).toInt()}%"

    companion object {
        const val SLIDE_SHARE = 0.35
        const val NOTE_SHARE = 0.60
        private const val STEM = 6
        private val WORD = Regex("""[\p{L}\p{N}]+""")
        private val STOP_WORDS = (
            "il lo la i gli le un uno una di a da in con su per tra fra e o ma se che chi cui non più come dove quando " +
                "del dello della dei degli delle al allo alla ai agli alle dal dallo dalla dai dagli dalle " +
                "nel nello nella nei negli nelle sul sullo sulla sui sugli sulle è sono essere ha hanno si ci ne " +
                "questo questa questi queste quello quella quelli quelle anche ad ed od ogni tutti tutte tutto tutta " +
                "può possono viene vengono"
            ).split(' ').toSet()

        /** The words of [text] that tell something: in lower case, cut to their first letters, without the short and the common ones. */
        fun wordsOf(text: String): Set<String> = WORD.findAll(text.lowercase()).map { it.value }
            .filter { it !in STOP_WORDS && (it.length >= 3 || it.all(Char::isDigit)) }
            .map { it.take(STEM) }
            .toSet()
    }
}
