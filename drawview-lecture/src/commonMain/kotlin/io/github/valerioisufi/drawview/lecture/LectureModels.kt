package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.readSnapshot
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
 * @param pageDbId database id of that page: the page is found again when the pages have been
 *   reordered (see [resolvedPageIndex]).
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
    val pageDbId: Int = 0,
    val importance: Int = 2,
    val atMs: Long = 0,
    val fromFinalReview: Boolean = false,
    val status: SuggestionStatus = SuggestionStatus.NEW
)

/** The page of [document] where the suggestion belongs: by database id, or by index. */
fun NoteSuggestion.resolvedPageIndex(document: DrawDocument): Int? {
    if (pageDbId > 0) {
        val index = readSnapshot { document.pages.indexOfFirst { it.dbId == pageDbId } }
        if (index >= 0) return index
    }
    return pageIndex?.takeIf { it in 0 until document.pages.size }
}

@Serializable
data class TranscriptSegment(val startMs: Long, val endMs: Long, val text: String)

/**
 * Tokens billed by the Gemini API (the local model is free).
 *
 * @param promptByModality prompt tokens by modality (TEXT, IMAGE, AUDIO, DOCUMENT, ...).
 * @param skippedUpdates updates not sent because the chunk held no speech.
 * @param localRequests requests answered by a local model (free, not counted in the tokens).
 */
@Serializable
data class TokenUsage(
    val requests: Int = 0,
    val promptTokens: Long = 0,
    val cachedTokens: Long = 0,
    val outputTokens: Long = 0,
    val promptByModality: Map<String, Long> = emptyMap(),
    val skippedUpdates: Int = 0,
    val localRequests: Int = 0
) {
    operator fun plus(other: TokenUsage) = TokenUsage(
        requests = requests + other.requests,
        promptTokens = promptTokens + other.promptTokens,
        cachedTokens = cachedTokens + other.cachedTokens,
        outputTokens = outputTokens + other.outputTokens,
        promptByModality = (promptByModality.keys + other.promptByModality.keys).associateWith {
            (promptByModality[it] ?: 0L) + (other.promptByModality[it] ?: 0L)
        },
        skippedUpdates = skippedUpdates + other.skippedUpdates,
        localRequests = localRequests + other.localRequests
    )
}

/** State of a lecture. A lecture saved while recording was interrupted (the app was closed). */
@Serializable
/** [IMPORTING]: an audio file recorded elsewhere, being converted, transcribed and reviewed. */
enum class LectureStatus { RECORDING, FINALIZING, FINISHED, IMPORTING }

/** The steps of an import ([LectureStatus.IMPORTING]). */
enum class ImportStep { CONVERTING, TRANSCRIBING, REVIEWING }

/**
 * An image of the notes of the student kept for the final review by the first versions (read only
 * to load their lectures: see [SeenPage]).
 */
@Serializable
data class ReviewPage(val pageIndex: Int, val pageDbId: Int = 0, val file: String, val isCrop: Boolean = true)

/**
 * A page of the notebook seen during the lecture, as last seen, kept for the final review (also
 * when the notebook is closed, or after a restart).
 *
 * @param image file in `pages/` of the lecture with its last image (the visible pages only).
 * @param visible the page was on screen (the others were around it).
 */
@Serializable
data class SeenPage(val content: PageContent, val image: String? = null, val visible: Boolean = false)

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
    val status: LectureStatus = LectureStatus.FINISHED,
    val outline: String = "",
    val currentTopic: String = "",
    val transcript: List<TranscriptSegment> = emptyList(),
    val suggestions: List<NoteSuggestion> = emptyList(),
    val finalReview: String? = null,
    /** Model that wrote the final review. */
    val finalReviewBy: String? = null,
    val finalReviewProvider: ReviewProvider? = null,
    val usage: TokenUsage = TokenUsage(),
    /** The pages of the notebook seen during the lecture, the most recent last. */
    val pages: List<SeenPage> = emptyList(),
    /** The images of the first versions, read as [pages]. */
    val reviewPages: List<ReviewPage> = emptyList()
)

/**
 * The course of a notebook in a few lines (subject, topics, terms that the lecturer will say),
 * written by the assistant from the notebook and editable by the student: it helps the
 * transcription and the models. Saved as `context.json` of the lectures of the notebook.
 *
 * @param edited written or changed by the student: not replaced by a new one automatically.
 */
@Serializable
data class CourseContext(val text: String, val edited: Boolean = false, val generatedAt: Long = 0)

/** Formats [ms] as `m:ss` (or `h:mm:ss`). */
fun formatLectureTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$ss" else "$minutes:$ss"
}
