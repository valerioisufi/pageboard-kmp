package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.core.platform.PlatformFiles
import io.github.valerioisufi.drawview.core.platform.localDeviceName
import io.github.valerioisufi.drawview.core.util.currentTimeMillis
import io.github.valerioisufi.drawview.lecture.audio.LECTURE_BYTES_PER_SECOND
import io.github.valerioisufi.drawview.lecture.audio.RecordingFile
import io.github.valerioisufi.drawview.lecture.platform.lectureDataDirectory
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** A lecture of a document, as listed by the [LectureLibrary]. */
class LectureSummary(
    val directory: String,
    val documentId: String,
    val documentTitle: String,
    val startedAt: Long,
    val durationMs: Long,
    val hasReview: Boolean,
    val suggestionCount: Int,
    /** Saved while recording: still being recorded (maybe on another device), or interrupted. */
    val wasRecording: Boolean,
    /** The name of the device that recorded it, when it is another one. */
    val recordedElsewhere: String? = null,
    /** Being recorded (or imported) on another device. */
    val activeElsewhere: Boolean = false
)

/**
 * The lectures saved on this device: `doc-<document id>/<date>-<random>/` in the [root]
 * directory, each with its `lecture.json`, its audio and the images of its review (see
 * [Lecture]); beside them the course context and the handwriting read, shared by the lectures of
 * the document, and the lectures deleted (`deleted.json`, so that the other devices delete them
 * too).
 *
 * The document ids are the same on the devices working on a document, so the lectures stay
 * attached to it there too ([LectureAttachments]); the names of the lecture folders are unique
 * among the devices.
 */
class LectureLibrary(private val root: String = lectureDataDirectory()) {

    /** This device among the ones sharing the lectures: random, kept in [root]. */
    val deviceId: String by lazy {
        val path = "${root.trimEnd('/')}/device-id"
        PlatformFiles.readBytes(path)?.decodeToString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: randomName(DEVICE_ID_BYTES).also { PlatformFiles.writeBytes(path, it.encodeToByteArray()) }
    }

    /** The name of this device, shown with the lectures it records on the other ones. */
    val deviceName: String by lazy { localDeviceName() }

    /**
     * Held while a `lecture.json` is read, merged and written (the lecture being recorded, and
     * the one received from another device).
     */
    internal val records = Mutex()

    /** Told of what changes here (not received from another device), to send it to the others ([LectureAttachments]). */
    internal var onChanged: ((LibraryChange) -> Unit)? = null

    /** Something of the lectures of [documentId] changed: the lecture [lectureId], or what they share (`null`). */
    internal fun changed(documentId: String, lectureId: String?) {
        onChanged?.invoke(LibraryChange(documentId, lectureId))
    }

    fun documentDirectory(documentId: String): String = "${root.trimEnd('/')}/doc-$documentId"

    /** The folder of the lecture [lectureId] of [documentId]. */
    internal fun lectureDirectory(documentId: String, lectureId: String) = "${documentDirectory(documentId)}/$lectureId"

    /** Folder of a new lecture of [documentId] started at [startedAt]: the date, and a few random characters. */
    fun newLectureDirectory(documentId: String, startedAt: Long): String {
        val base = "${documentDirectory(documentId)}/${timestampName(startedAt)}-${randomName(NAME_RANDOM_BYTES)}"
        var directory = base
        var suffix = 2
        while (SystemFileSystem.exists(Path(directory))) directory = "$base-${suffix++}"
        return directory
    }

    /** The course context of [documentId], if any. */
    fun loadContext(documentId: String): CourseContext? {
        val bytes = PlatformFiles.readBytes("${documentDirectory(documentId)}/context.json") ?: return null
        return try {
            Lecture.recordJson.decodeFromString(CourseContext.serializer(), bytes.decodeToString())
        } catch (_: Exception) {
            null
        }
    }

    fun saveContext(documentId: String, context: CourseContext): Boolean = writeContext(documentId, context).also { if (it) changed(documentId, null) }

    internal fun writeContext(documentId: String, context: CourseContext): Boolean = writeAtomically(
        "${documentDirectory(documentId)}/context.json",
        Lecture.recordJson.encodeToString(CourseContext.serializer(), context).encodeToByteArray()
    )

    /** The lectures of [documentId], the most recent first. */
    fun list(documentId: String): List<LectureSummary> {
        val documentPath = Path(documentDirectory(documentId))
        if (!SystemFileSystem.exists(documentPath)) return emptyList()
        val deleted = deletedLectures(documentId)
        return SystemFileSystem.list(documentPath).mapNotNull { path ->
            if (path.name in deleted) return@mapNotNull null
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
                wasRecording = record.status != LectureStatus.FINISHED,
                recordedElsewhere = record.recordedOn.takeIf { record.recordedBy.isNotEmpty() && record.recordedBy != deviceId },
                activeElsewhere = record.status != LectureStatus.FINISHED && !statusSetHere(record)
            )
        }.sortedByDescending { it.startedAt }
    }

    /**
     * Reads the lecture saved in [directory]. A lecture saved while recording here is recovered:
     * the application stopped (closed or crashed) during the recording, whose raw audio is
     * converted into its WAV file (and the lecture saved again, finished). Must not be called for
     * the lecture being recorded; a lecture being recorded on another device stays as it is.
     */
    fun load(directory: String): LectureRecord? {
        val record = readRecord(directory) ?: return null
        if (record.status == LectureStatus.FINISHED || !statusSetHere(record)) return record
        val audio = RecordingFile(directory)
        val recordedMs = audio.pcmBytes() * 1000 / LECTURE_BYTES_PER_SECOND
        try {
            audio.finish()
        } catch (e: Throwable) {
            println("DrawView: cannot recover the recording $directory: ${e.message}")
        }
        val recovered = record.copy(status = LectureStatus.FINISHED, durationMs = maxOf(record.durationMs, recordedMs))
        val stamped = LectureRecords.stamped(recovered, record, deviceId, currentTimeMillis())
        if (writeLectureRecord(directory, stamped)) changed(record.documentId, Path(directory).name)
        return stamped
    }

    /** Whether the status of [record] was set here (recording or importing it), not by another device. */
    internal fun statusSetHere(record: LectureRecord): Boolean = record.stampOf(LecturePart.RECORDING).by.let { it.isEmpty() || it == deviceId }

    /** Deletes the lecture in [directory], audio included, here and on the other devices. */
    fun delete(directory: String): Boolean {
        val path = Path(directory)
        val documentId = path.parent?.name?.removePrefix("doc-") ?: return false
        markDeleted(documentId, mapOf(path.name to currentTimeMillis()))
        changed(documentId, null)
        return deleteFiles(directory)
    }

    /** Deletes the files of the lecture in [directory]. */
    internal fun deleteFiles(directory: String): Boolean = try {
        deleteRecursively(Path(directory))
        true
    } catch (e: Throwable) {
        println("DrawView: cannot delete the lecture $directory: ${e.message}")
        false
    }

    /** The lectures of [documentId] deleted (here or on another device), with when. */
    internal fun deletedLectures(documentId: String): Map<String, Long> {
        val bytes = PlatformFiles.readBytes("${documentDirectory(documentId)}/deleted.json") ?: return emptyMap()
        return try {
            Lecture.recordJson.decodeFromString(deletedSerializer, bytes.decodeToString())
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Adds [lectures] to the ones deleted of [documentId]: the deleted ones, all of them. */
    internal fun markDeleted(documentId: String, lectures: Map<String, Long>): Map<String, Long> {
        val known = deletedLectures(documentId)
        val all = (known.keys + lectures.keys).associateWith { maxOf(known[it] ?: 0L, lectures[it] ?: 0L) }
        if (all != known) writeAtomically("${documentDirectory(documentId)}/deleted.json", Lecture.recordJson.encodeToString(deletedSerializer, all).encodeToByteArray())
        return all
    }

    private fun deleteRecursively(path: Path) {
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
            SystemFileSystem.list(path).forEach { deleteRecursively(it) }
        }
        SystemFileSystem.delete(path, mustExist = false)
    }

    internal fun readRecord(directory: String): LectureRecord? {
        val bytes = PlatformFiles.readBytes("$directory/lecture.json") ?: return null
        return try {
            Lecture.recordJson.decodeFromString(LectureRecord.serializer(), bytes.decodeToString())
        } catch (_: Exception) {
            null
        }
    }

    private fun randomName(bytes: Int) = Random.nextBytes(bytes).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @OptIn(ExperimentalTime::class)
    private fun timestampName(epochMs: Long): String {
        val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
        fun two(value: Int) = value.toString().padStart(2, '0')
        return "${t.year}-${two(t.month.ordinal + 1)}-${two(t.day)}_${two(t.hour)}-${two(t.minute)}-${two(t.second)}"
    }

    private companion object {
        const val DEVICE_ID_BYTES = 8
        const val NAME_RANDOM_BYTES = 3
        val deletedSerializer = MapSerializer(String.serializer(), Long.serializer())
    }
}

/** Something of the lectures of [documentId] changed here: the lecture [lectureId], or what they share (`null`). */
internal data class LibraryChange(val documentId: String, val lectureId: String?)

/** Writes `lecture.json` of [directory] (atomically). */
internal fun writeLectureRecord(directory: String, record: LectureRecord): Boolean =
    writeAtomically("$directory/lecture.json", Lecture.recordJson.encodeToString(LectureRecord.serializer(), record).encodeToByteArray())

/** Writes [path] whole or not at all (the other devices may be reading it). */
internal fun writeAtomically(path: String, bytes: ByteArray): Boolean = try {
    val temporary = "$path.tmp"
    PlatformFiles.writeBytes(temporary, bytes) && run {
        SystemFileSystem.atomicMove(Path(temporary), Path(path))
        true
    }
} catch (e: Throwable) {
    println("DrawView: cannot write $path: ${e.message}")
    false
}
