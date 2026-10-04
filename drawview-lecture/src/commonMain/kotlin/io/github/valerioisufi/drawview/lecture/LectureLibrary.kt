package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.core.platform.PlatformFiles
import io.github.valerioisufi.drawview.lecture.audio.LECTURE_BYTES_PER_SECOND
import io.github.valerioisufi.drawview.lecture.audio.RecordingFile
import io.github.valerioisufi.drawview.lecture.platform.lectureDataDirectory
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** A lecture of a document, as listed by the [LectureLibrary]. */
class LectureSummary(
    val directory: String,
    val documentId: Int,
    val documentTitle: String,
    val startedAt: Long,
    val durationMs: Long,
    val hasReview: Boolean,
    val suggestionCount: Int,
    /** Saved while recording: still being recorded, or interrupted. */
    val wasRecording: Boolean
)

/**
 * The lectures saved on this device: `doc-<document id>/<date>/` in the [root] directory, each
 * with its `lecture.json`, its audio and the images of its review (see [Lecture]).
 *
 * The document ids are the database ids, never reused, so the lectures stay attached to their
 * document.
 */
class LectureLibrary(private val root: String = lectureDataDirectory()) {

    fun documentDirectory(documentId: Int): String = "${root.trimEnd('/')}/doc-$documentId"

    /** Folder of a new lecture of [documentId] started at [startedAt]. */
    fun newLectureDirectory(documentId: Int, startedAt: Long): String {
        val base = "${documentDirectory(documentId)}/${timestampName(startedAt)}"
        var directory = base
        var suffix = 2
        while (SystemFileSystem.exists(Path(directory))) directory = "$base-${suffix++}"
        return directory
    }

    /** The lectures of [documentId], the most recent first. */
    fun list(documentId: Int): List<LectureSummary> {
        val documentPath = Path(documentDirectory(documentId))
        if (!SystemFileSystem.exists(documentPath)) return emptyList()
        return SystemFileSystem.list(documentPath).mapNotNull { path ->
            val directory = path.toString()
            val record = readRecord(directory) ?: return@mapNotNull null
            LectureSummary(
                directory = directory,
                documentId = record.documentId,
                documentTitle = record.documentTitle,
                startedAt = record.startedAt,
                durationMs = record.durationMs,
                hasReview = record.finalReview != null,
                suggestionCount = record.suggestions.size,
                wasRecording = record.status != LectureStatus.FINISHED
            )
        }.sortedByDescending { it.startedAt }
    }

    /**
     * Reads the lecture saved in [directory]. A lecture saved while recording is recovered: the
     * application stopped (closed or crashed) during the recording, whose raw audio is converted
     * into its WAV file. Must not be called for the lecture being recorded.
     */
    fun load(directory: String): LectureRecord? {
        val record = readRecord(directory) ?: return null
        if (record.status == LectureStatus.FINISHED) return record
        val audio = RecordingFile(directory)
        val recordedMs = audio.pcmBytes() * 1000 / LECTURE_BYTES_PER_SECOND
        try {
            audio.finish()
        } catch (e: Throwable) {
            println("DrawView: cannot recover the recording $directory: ${e.message}")
        }
        return record.copy(status = LectureStatus.FINISHED, durationMs = maxOf(record.durationMs, recordedMs))
    }

    /** Deletes the lecture in [directory], audio included. */
    fun delete(directory: String): Boolean = try {
        deleteRecursively(Path(directory))
        true
    } catch (e: Throwable) {
        println("DrawView: cannot delete the lecture $directory: ${e.message}")
        false
    }

    private fun deleteRecursively(path: Path) {
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
            SystemFileSystem.list(path).forEach { deleteRecursively(it) }
        }
        SystemFileSystem.delete(path, mustExist = false)
    }

    private fun readRecord(directory: String): LectureRecord? {
        val bytes = PlatformFiles.readBytes("$directory/lecture.json") ?: return null
        return try {
            Lecture.recordJson.decodeFromString(LectureRecord.serializer(), bytes.decodeToString())
        } catch (_: Exception) {
            null
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun timestampName(epochMs: Long): String {
        val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
        fun two(value: Int) = value.toString().padStart(2, '0')
        return "${t.year}-${two(t.month.ordinal + 1)}-${two(t.day)}_${two(t.hour)}-${two(t.minute)}-${two(t.second)}"
    }
}
