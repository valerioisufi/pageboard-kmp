package io.github.valerioisufi.drawview.lecture

import kotlinx.serialization.Serializable

/** Kind of content suggested by the assistant. */
@Serializable
enum class NoteKind {
    DEFINITION, FORMULA, THEOREM, EXAMPLE, EXPLANATION, EXAM_HINT, ANNOUNCEMENT, REFERENCE, OTHER;

    companion object {
        fun parse(value: String): NoteKind = entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: OTHER
    }
}

/** What the student did with a suggestion (sent back to the model, which learns what is known). */
@Serializable
enum class SuggestionStatus { NEW, INSERTED, ALREADY_NOTED, DISMISSED }

/**
 * Content said in the lecture and missing from the slides and from the notes of the student.
 *
 * @param content notebook-ready Markdown (formulas between `$...$`).
 * @param pageIndex page of the document where the content belongs (0-based), if known.
 * @param importance 1 (detail) to 3 (stressed by the lecturer, exam).
 * @param atMs time of the lecture when it was said.
 * @param fromFinalReview found by the review of the whole lecture.
 */
@Serializable
data class NoteSuggestion(
    val id: String,
    val kind: NoteKind,
    val title: String,
    val content: String,
    val pageIndex: Int? = null,
    val importance: Int = 2,
    val atMs: Long = 0,
    val fromFinalReview: Boolean = false,
    val status: SuggestionStatus = SuggestionStatus.NEW
)

@Serializable
data class TranscriptSegment(val startMs: Long, val endMs: Long, val text: String)

@Serializable
data class TokenUsage(
    val requests: Int = 0,
    val promptTokens: Long = 0,
    val cachedTokens: Long = 0,
    val outputTokens: Long = 0
) {
    operator fun plus(other: TokenUsage) = TokenUsage(
        requests = requests + other.requests,
        promptTokens = promptTokens + other.promptTokens,
        cachedTokens = cachedTokens + other.cachedTokens,
        outputTokens = outputTokens + other.outputTokens
    )
}

/**
 * A recorded lecture, saved as `lecture.json` next to its audio.
 */
@Serializable
data class LectureRecord(
    val id: String,
    val documentId: Int,
    val documentTitle: String = "",
    val startedAt: Long,
    val durationMs: Long = 0,
    val outline: String = "",
    val currentTopic: String = "",
    val transcript: List<TranscriptSegment> = emptyList(),
    val suggestions: List<NoteSuggestion> = emptyList(),
    val finalReview: String? = null,
    val usage: TokenUsage = TokenUsage()
)

/** Formats [ms] as `m:ss` (or `h:mm:ss`). */
fun formatLectureTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$ss" else "$minutes:$ss"
}
