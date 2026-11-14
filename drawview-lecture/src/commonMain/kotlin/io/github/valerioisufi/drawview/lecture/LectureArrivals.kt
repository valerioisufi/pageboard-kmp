package io.github.valerioisufi.drawview.lecture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

// What the lecture assistant tells of the lectures arriving, and the files it exports

/** An audio or transcript exported: the name of the file, and why it failed, if it did. */
public class ExportResult(public val fileName: String, public val failure: String?)

/**
 * Saves [segments] in [target] as a plain-text file with timestamps (`[m:ss] text`, the extension
 * of [target] becomes `.txt`).
 *
 * @return the path written.
 */
internal fun exportTranscript(segments: List<TranscriptSegment>, target: String): String {
    require(segments.isNotEmpty()) { "the transcript is empty" }
    val base = target.substringBeforeLast('.').takeIf { target.substringAfterLast('/').contains('.') } ?: target
    val txt = Path("$base.txt")
    val content = segments.joinToString("\n") { "[${formatLectureTime(it.startMs)}] ${it.text}" } + "\n"
    SystemFileSystem.sink(txt).buffered().use { it.write(content.encodeToByteArray()) }
    return txt.toString()
}

/**
 * A lecture received from another device ([arrival]), [durationMs] long, without a transcript:
 * offered to be transcribed and reviewed here by [provider] (see [LectureAssistant.generationOffers]).
 */
public class GenerationOffer(public val arrival: LectureArrival, public val durationMs: Long, public val provider: ModelProvider, public val live: Boolean = false) {
    /**
     * The tokens of the requests to Gemini, about: the review reads the transcript (made on this
     * computer) and the notebook; a lecture followed live, an hour of it, adds its notes. `0` for
     * the local models.
     */
    public val estimatedTokens: Long
        get() = when {
            provider == ModelProvider.LOCAL -> 0
            live -> LIVE_NOTES_TOKENS_PER_HOUR + REVIEW_TOKENS + HOUR_SECONDS * TRANSCRIPT_TOKENS_PER_SECOND
            else -> REVIEW_TOKENS + durationMs / 1000 * TRANSCRIPT_TOKENS_PER_SECOND
        }

    private companion object {
        /** About 150 words a minute. */
        const val TRANSCRIPT_TOKENS_PER_SECOND = 4L

        /** The notebook, the images and the answer of a review. */
        const val REVIEW_TOKENS = 20_000L

        /** The notes of an hour, a request every 5 minutes, measured on a recorded lecture (2026-10-29). */
        const val LIVE_NOTES_TOKENS_PER_HOUR = 130_000L
        const val HOUR_SECONDS = 3_600L
    }
}

/** The audio of a lecture followed is whole when it is this close to the length the recording device says. */
internal const val FOLLOW_END_TOLERANCE_MS = 2_000L

/** A notification of the system: its [channel] (the kind of notifications, where the system names them), [title] and [text]. */
public class ArrivalNotification(public val channel: String, public val title: String, public val text: String)
