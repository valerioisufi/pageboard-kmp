package io.github.valerioisufi.pageboard.export

/**
 * Fields that can be composed and reordered to name exported recording files inside the ZIP.
 */
public enum class RecordingNamePart {
    FOLDER,
    NOTE,
    DATE,
    TOPIC,
    NUMBER
}

/**
 * Formats a recording entry file stem (without extension) from [parts], keeping only non-blank
 * sanitized components separated by `" - "`, falling back to note and date if all chosen parts
 * resolve to empty strings.
 */
public fun formatRecordingFileName(
    parts: List<RecordingNamePart>,
    folder: String?,
    note: String,
    date: String,
    topic: String?,
    number: Int,
    totalInNote: Int = number
): String {
    val width = maxOf(2, totalInNote.toString().length)
    val segments = parts.mapNotNull { part ->
        when (part) {
            RecordingNamePart.FOLDER -> folder?.trim()?.takeIf { it.isNotEmpty() }?.let(DocumentExport::sanitizeFileName)
            RecordingNamePart.NOTE -> note.trim().takeIf { it.isNotEmpty() }?.let(DocumentExport::sanitizeFileName)
            RecordingNamePart.DATE -> date.trim().takeIf { it.isNotEmpty() }?.let(DocumentExport::sanitizeFileName)
            RecordingNamePart.TOPIC -> topic?.trim()?.takeIf { it.isNotEmpty() }?.let(DocumentExport::sanitizeFileName)
            RecordingNamePart.NUMBER -> number.coerceAtLeast(1).toString().padStart(width, '0')
        }
    }
    if (segments.isNotEmpty()) return segments.joinToString(" - ")
    val fallback = listOfNotNull(
        note.trim().takeIf { it.isNotEmpty() }?.let(DocumentExport::sanitizeFileName),
        date.trim().takeIf { it.isNotEmpty() }?.let(DocumentExport::sanitizeFileName)
    )
    return fallback.joinToString(" - ").ifEmpty { "Recording" }
}

/**
 * One finished lecture recording of a note to export (see [DocumentRecordings]): its suggested
 * [fileName] (e.g. `2026-11-21 10.15.m4a`), when it started ([recordedAt]), whether its audio is
 * on this device ([hasAudio]), an optional plain-text [transcript] paired with
 * [transcriptFileName], a persisted [topic] (or lazy [resolveTopic] generator when missing), and
 * [writeAudio] writing the encoded audio to a file path while reporting progress from 0 to 1.
 */
public class ExportedRecording(
    public val fileName: String,
    public val recordedAt: Long,
    public val hasAudio: Boolean = true,
    public val transcriptFileName: String? = null,
    public val transcript: String? = null,
    public val topic: String = "",
    public val resolveTopic: (suspend () -> String)? = null,
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
