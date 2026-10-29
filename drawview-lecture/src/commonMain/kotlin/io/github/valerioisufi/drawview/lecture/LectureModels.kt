package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.model.DrawDocument
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** Kind of content suggested by the assistant. */
@Serializable
public enum class NoteKind {
    DEFINITION, FORMULA, THEOREM, EXAMPLE, EXPLANATION, EXAM_HINT, ANNOUNCEMENT, REFERENCE, OTHER;

    public companion object {
        public fun parse(value: String): NoteKind = entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: OTHER
    }
}

/** What the student did with a suggestion (sent back to the model, which learns what is known). */
@Serializable
public enum class SuggestionStatus { NEW, INSERTED, ALREADY_NOTED, DISMISSED }

/**
 * Content said in the lecture and missing from the slides and from the notes of the student.
 *
 * @param content notebook-ready Markdown (formulas between `$...$`).
 * @param pageIndex page of the document where the content belongs (0-based), if known.
 * @param pageId identifier of that page ([io.github.valerioisufi.drawview.model.DrawPage.id]):
 *   the page is found again when the pages have been reordered (see [resolvedPageIndex]).
 * @param importance 1 (detail) to 3 (stressed by the lecturer, exam).
 * @param atMs time of the lecture when it was said.
 * @param fromFinalReview found by the review of the whole lecture.
 * @param statusStamp when [status] was last changed, and where (the most recent wins, see [Stamp]).
 * @param anchor a few exact words of its page the note is about, quoted by the model (empty: none),
 *   to place it next to them; never written when empty, so that the records written before stay
 *   as they were.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class NoteSuggestion(
    val id: String,
    val kind: NoteKind,
    val title: String,
    val content: String,
    val pageIndex: Int? = null,
    val pageId: String = "",
    val importance: Int = 2,
    val atMs: Long = 0,
    val fromFinalReview: Boolean = false,
    val status: SuggestionStatus = SuggestionStatus.NEW,
    val statusStamp: Stamp? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val anchor: String = ""
)

/** The page of [document] where the suggestion belongs: by identifier, or by index. */
public fun NoteSuggestion.resolvedPageIndex(document: DrawDocument): Int? {
    if (pageId.isNotEmpty()) {
        val index = document.pages.indexOfFirst { it.id == pageId }
        if (index >= 0) return index
    }
    return pageIndex?.takeIf { it in 0 until document.pages.size }
}

@Serializable
/** A part of the transcription: its text, spoken from [startMs] to [endMs] of the recording. */
public data class TranscriptSegment(val startMs: Long, val endMs: Long, val text: String)

/**
 * Tokens billed by the Gemini API (the local model is free).
 *
 * @param promptByModality prompt tokens by modality (TEXT, IMAGE, AUDIO, DOCUMENT, ...).
 * @param skippedUpdates updates not sent because the chunk held no speech.
 * @param localRequests requests answered by a local model (free, not counted in the tokens).
 */
@Serializable
public data class TokenUsage(
    val requests: Int = 0,
    val promptTokens: Long = 0,
    val cachedTokens: Long = 0,
    val outputTokens: Long = 0,
    val promptByModality: Map<String, Long> = emptyMap(),
    val skippedUpdates: Int = 0,
    val localRequests: Int = 0
) {
    public operator fun plus(other: TokenUsage): TokenUsage = TokenUsage(
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
/**
 * [IMPORTING]: an audio file recorded elsewhere being converted, transcribed and reviewed, or a
 * lecture being transcribed again.
 */
public enum class LectureStatus { RECORDING, FINALIZING, FINISHED, IMPORTING }

/** The steps of an import or of a new transcription ([LectureStatus.IMPORTING]). */
public enum class ImportStep { CONVERTING, TRANSCRIBING, REVIEWING }

/**
 * A page of the notebook seen during the lecture, as last seen, kept for the final review (also
 * when the notebook is closed, or after a restart).
 *
 * @param image file in `pages/` of the lecture with its last image (the visible pages only).
 * @param imageHash the hash of that image ([contentHash]): the other devices want that one.
 * @param visible the page was on screen (the others were around it).
 */
@Serializable
public data class SeenPage(val content: PageContent, val image: String? = null, val visible: Boolean = false, val imageHash: String? = null)

/**
 * When something of a lecture was changed, and on which device (its identifier,
 * [LectureLibrary.deviceId]): of two changes made apart, the most recent one wins (by the clocks of
 * the devices; [by] tells apart the changes of the same millisecond).
 */
@Serializable
public data class Stamp(val at: Long, val by: String) : Comparable<Stamp> {
    override fun compareTo(other: Stamp): Int = compareValuesBy(this, other, Stamp::at, Stamp::by)

    /** A change made after this one on [device] at [now] (just after this one, when the clock of [device] is behind). */
    public fun next(device: String, now: Long): Stamp = Stamp(maxOf(now, at + 1), device)

    public companion object {
        public val NONE: Stamp = Stamp(0, "")
    }
}

/**
 * The parts of a lecture changed as a whole, each by one device at a time: of the same part
 * changed on two devices, the most recent change is kept ([LectureRecord.stamps]).
 */
@Serializable
public enum class LecturePart {
    /** The title, the duration, the status and the pages seen: changed by the device recording it (or importing it). */
    RECORDING,

    /** The outline and the current topic. */
    OUTLINE,
    TRANSCRIPT,

    /** The final review and the model that wrote it. */
    REVIEW,

    /** Whether its audio goes to the other devices ([LectureRecord.audioShared]). */
    SHARING,

    /** The device writing its transcript or its review now ([LectureRecord.generation]). */
    GENERATION
}

/**
 * The tokens a device used for a lecture ([name]: the device), changed by it only: [stamp] tells
 * the most recent.
 */
@Serializable
public data class DeviceUsage(val name: String, val usage: TokenUsage, val stamp: Stamp = Stamp.NONE)

/**
 * The device writing the transcript or the review of a lecture ([name]: its name), since [since],
 * still there at [refreshedAt]: the others do not write them meanwhile (see
 * [LectureAssistant.generate]).
 */
@Serializable
public data class GenerationLock(val device: String, val name: String, val since: Long, val refreshedAt: Long) {
    /** Whether it still holds, at [now] (a device gone stops refreshing it). */
    public fun holds(now: Long): Boolean = now - refreshedAt < STALE_MS

    public companion object {
        internal const val REFRESH_MS = 30_000L
        internal const val STALE_MS = 3 * 60_000L
    }
}

/**
 * A recorded lecture, saved as `lecture.json` next to its audio.
 */
@Serializable
public data class LectureRecord(
    val id: String,
    val documentId: String,
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
    val finalReviewProvider: ModelProvider? = null,
    /** The tokens used for it, by device (each one counts its own). */
    val usageByDevice: Map<String, DeviceUsage> = emptyMap(),
    /** The pages of the notebook seen during the lecture, the most recent last. */
    val pages: List<SeenPage> = emptyList(),
    /** The device that recorded it ([LectureLibrary.deviceId]), and its name. */
    val recordedBy: String = "",
    val recordedOn: String = "",
    /** Whether its audio goes to the other devices (otherwise it stays where it is). */
    val audioShared: Boolean = true,
    /** The device writing its transcript or review now, if any. */
    val generation: GenerationLock? = null,
    /** When its parts were last changed, and where (the suggestions have their own, see [LectureRecords.merge]). */
    val stamps: Map<LecturePart, Stamp> = emptyMap()
) {
    public fun stampOf(part: LecturePart): Stamp = stamps[part] ?: Stamp.NONE
}

/**
 * The course of a notebook in a few lines (subject, topics, terms that the lecturer will say),
 * written by the assistant from the notebook and editable by the student: it helps the
 * transcription and the models. Saved as `context.json` of the lectures of the notebook.
 *
 * @param edited written or changed by the student: not replaced by a new one automatically.
 * @param stamp when it was written, and where: of two contexts written apart, the most recent is kept.
 */
@Serializable
public data class CourseContext(val text: String, val edited: Boolean = false, val generatedAt: Long = 0, val stamp: Stamp = Stamp.NONE)

/** Formats [ms] as `m:ss` (or `h:mm:ss`). */
public fun formatLectureTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$ss" else "$minutes:$ss"
}
