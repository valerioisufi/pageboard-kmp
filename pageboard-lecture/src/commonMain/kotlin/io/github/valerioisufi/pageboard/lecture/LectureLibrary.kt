package io.github.valerioisufi.pageboard.lecture

import io.github.valerioisufi.pageboard.lecture.audio.RecordingFile
import io.github.valerioisufi.pageboard.lecture.platform.lectureDataDirectory
import io.github.valerioisufi.pageboard.platform.DeviceProfile
import io.github.valerioisufi.pageboard.platform.PlatformFiles
import io.github.valerioisufi.pageboard.platform.currentTimeMillis
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** A lecture of a document, as listed by the [LectureLibrary]. */
public class LectureSummary(
    public val directory: String,
    public val documentId: String,
    public val documentTitle: String,
    public val startedAt: Long,
    public val durationMs: Long,
    public val hasReview: Boolean,
    internal val suggestionCount: Int,
    /** Saved while recording: still being recorded (maybe on another device), or interrupted. */
    internal val wasRecording: Boolean,
    /** The name of the device that recorded it, when it is another one. */
    public val recordedElsewhere: String? = null,
    /** Being recorded (or imported) on another device. */
    public val activeElsewhere: Boolean = false
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
public class LectureLibrary(private val root: String = lectureDataDirectory()) {

    /** This device among the ones sharing the lectures: random, kept in [root]. */
    public val deviceId: String by lazy {
        val path = "${root.trimEnd('/')}/device-id"
        PlatformFiles.readBytes(path)?.decodeToString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: randomName(DEVICE_ID_BYTES).also { PlatformFiles.writeBytes(path, it.encodeToByteArray()) }
    }

    /** The name of this device (the one the user chose), shown with the lectures it records on the other ones. */
    public val deviceName: String get() = DeviceProfile.name.value

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

    public fun documentDirectory(documentId: String): String = "${root.trimEnd('/')}/doc-$documentId"

    /** The folder of the lecture [lectureId] of [documentId]. */
    internal fun lectureDirectory(documentId: String, lectureId: String) = "${documentDirectory(documentId)}/$lectureId"

    /** Folder of a new lecture of [documentId] started at [startedAt]: the date, and a few random characters. */
    public fun newLectureDirectory(documentId: String, startedAt: Long): String {
        val base = "${documentDirectory(documentId)}/${timestampName(startedAt)}-${randomName(NAME_RANDOM_BYTES)}"
        var directory = base
        var suffix = 2
        while (SystemFileSystem.exists(Path(directory))) directory = "$base-${suffix++}"
        return directory
    }

    /** The course context of [documentId], if any. */
    internal fun loadContext(documentId: String): CourseContext? {
        val bytes = PlatformFiles.readBytes("${documentDirectory(documentId)}/context.json") ?: return null
        return try {
            Lecture.recordJson.decodeFromString(CourseContext.serializer(), bytes.decodeToString())
        } catch (_: Exception) {
            null
        }
    }

    public fun saveContext(documentId: String, context: CourseContext): Boolean = writeContext(documentId, context).also { if (it) changed(documentId, null) }

    public fun writeContext(documentId: String, context: CourseContext): Boolean = writeAtomically(
        "${documentDirectory(documentId)}/context.json",
        Lecture.recordJson.encodeToString(CourseContext.serializer(), context).encodeToByteArray()
    )

    /** The documents with a lecture (not deleted): the folders of their lectures, without reading them. */
    public fun documentsWithLectures(): Set<String> {
        val rootPath = Path(root)
        if (!SystemFileSystem.exists(rootPath)) return emptySet()
        return SystemFileSystem.list(rootPath).mapNotNullTo(mutableSetOf()) { path ->
            val documentId = path.name.takeIf { it.startsWith("doc-") }?.removePrefix("doc-") ?: return@mapNotNullTo null
            val deleted = deletedLectures(documentId)
            documentId.takeIf {
                SystemFileSystem.list(path).any { lecture -> lecture.name !in deleted && SystemFileSystem.exists(Path(lecture, "lecture.json")) }
            }
        }
    }

    /** The lectures of [documentId], the most recent first. */
    public fun list(documentId: String): List<LectureSummary> {
        val documentPath = Path(documentDirectory(documentId))
        if (!SystemFileSystem.exists(documentPath)) return emptyList()
        val deleted = deletedLectures(documentId)
        return SystemFileSystem.list(documentPath).mapNotNull { path ->
            if (path.name in deleted) return@mapNotNull null
            // As built here, not as the file system lists it (with '\' on Windows): one string per
            // lecture, compared by the assistant and the attachments
            val directory = lectureDirectory(documentId, path.name)
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
     * The lectures of [documentId] as they are saved, the most recent first, read only: none is
     * recovered (see [load]), so that it can be read while a lecture is recorded, e.g. to export them.
     */
    public fun records(documentId: String): List<LectureRecord> = list(documentId).mapNotNull { readRecord(it.directory) }

    /**
     * Reads the lecture saved in [directory]. A lecture saved while recording here is recovered:
     * the application stopped (closed or crashed) during the recording, whose audio is kept as far
     * as written (the lecture saved again, finished). Must not be called for
     * the lecture being recorded; a lecture being recorded on another device stays as it is.
     */
    public fun load(directory: String): LectureRecord? {
        val record = readRecord(directory) ?: return null
        if (record.status == LectureStatus.FINISHED || !statusSetHere(record)) return record
        val recordedMs = try {
            RecordingFile(directory).durationMs()
        } catch (e: Throwable) {
            println("DrawView: cannot read the recording $directory: ${e.message}")
            0L
        }
        val recovered = record.copy(status = LectureStatus.FINISHED, durationMs = maxOf(record.durationMs, recordedMs))
        val stamped = LectureRecords.stamped(recovered, record, deviceId, currentTimeMillis())
        if (writeLectureRecord(directory, stamped)) changed(record.documentId, Path(directory).name)
        return stamped
    }

    /** Whether the status of [record] was set here (recording or importing it), not by another device. */
    internal fun statusSetHere(record: LectureRecord): Boolean = record.stampOf(LecturePart.RECORDING).by.let { it.isEmpty() || it == deviceId }

    /** Deletes the lecture in [directory], audio included, here and on the other devices. */
    public fun delete(directory: String): Boolean {
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

    /** How this device shares the lectures of [documentId] (see [LectureSharingSettings]). */
    public fun sharingOf(documentId: String): LectureSharingSettings =
        PlatformFiles.readBytes("${documentDirectory(documentId)}/sharing.json")?.let {
            try {
                val saved = Lecture.recordJson.decodeFromString(LectureSharingSettings.serializer(), it.decodeToString())
                // The id of a lecture is the name of its folder, without separators: Windows saved
                // the whole path of the folder until it split it on '\'. Written as read at the next change
                saved.copy(requestedAudio = saved.requestedAudio.mapTo(mutableSetOf()) { id -> id.substringAfterLast('/').substringAfterLast('\\') })
            } catch (_: Exception) {
                null
            }
        } ?: LectureSharingSettings()

    internal fun saveSharing(documentId: String, settings: LectureSharingSettings): Boolean =
        writeAtomically("${documentDirectory(documentId)}/sharing.json", Lecture.recordJson.encodeToString(LectureSharingSettings.serializer(), settings).encodeToByteArray())

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

    /**
     * The lecture saved in [directory], with the name of the folder as id: Windows saved the
     * whole path of the folder until it split it on '\', and the record is written with the name
     * the next time the lecture is saved.
     */
    internal fun readRecord(directory: String): LectureRecord? {
        val bytes = PlatformFiles.readBytes("$directory/lecture.json") ?: return null
        return try {
            val record = Lecture.recordJson.decodeFromString(LectureRecord.serializer(), bytes.decodeToString())
            val id = Path(directory).name
            if (record.id == id) record else record.copy(id = id)
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

/**
 * How this device shares the lectures of a notebook with the devices working on it (kept here, not
 * shared): whether the audio of its new lectures goes to them ([shareAudio]), whether it receives
 * the audio of theirs at once or only when asked for ([downloadAudio]), and the lectures whose
 * audio was asked for ([requestedAudio]).
 */
@Serializable
public data class LectureSharingSettings(
    val shareAudio: Boolean = true,
    val downloadAudio: AudioDownload = AudioDownload.AUTOMATIC,
    val requestedAudio: Set<String> = emptySet()
)

/** When the audio of the lectures recorded on other devices is received. */
@Serializable
public enum class AudioDownload { AUTOMATIC, ON_REQUEST }

/** Something of the lectures of [documentId] changed here: the lecture [lectureId], or what they share (`null`). */
internal data class LibraryChange(val documentId: String, val lectureId: String?)

/** Writes `lecture.json` of [directory] (atomically). */
public fun writeLectureRecord(directory: String, record: LectureRecord): Boolean =
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
