package io.github.valerioisufi.pageboard.export

/**
 * One finished lecture recording of a note to export (see [DocumentRecordings]): its suggested
 * [fileName] (e.g. `2026-11-21 10.15.m4a`), when it started ([recordedAt]), whether its audio is
 * on this device ([hasAudio]), an optional plain-text [transcript] paired with
 * [transcriptFileName], and [writeAudio] writing the encoded audio to a file path while
 * reporting progress from 0 to 1.
 */
public class ExportedRecording(
    public val fileName: String,
    public val recordedAt: Long,
    public val hasAudio: Boolean = true,
    public val transcriptFileName: String? = null,
    public val transcript: String? = null,
    public val writeAudio: suspend (path: String, progress: (Float) -> Unit) -> Unit
)

/**
 * Provides the lecture recordings of a note for batch export (GRASP Protected Variations),
 * supplied by the application so `pageboard-export` stays independent of `pageboard-lecture`.
 */
public fun interface DocumentRecordings {
    /** The exportable recordings of the note [documentId], or an empty list when it has none. */
    public suspend fun of(documentId: String): List<ExportedRecording>
}
