package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.audio.RecordingFile
import io.github.valerioisufi.drawview.lecture.handwriting.HandwritingReader
import io.github.valerioisufi.drawview.model.AttachmentChange
import io.github.valerioisufi.drawview.model.AttachmentReceiver
import io.github.valerioisufi.drawview.model.AttachmentSource
import io.github.valerioisufi.drawview.model.DocumentAttachments
import io.github.valerioisufi.drawview.platform.PlatformFiles
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * The lectures of the notebooks shared with the devices working on them (GRASP Pure
 * Fabrication: the synchronization knows only items with a key and a version):
 *
 * * `lecture/<id>`: the lecture, merged with the one of the other device ([LectureRecords.merge]);
 * * `lecture/<id>/audio`: its audio, as it is recorded (it only grows, so that what a device has
 *   is the start of what the others have: it is asked for from there, and followed live);
 * * `lecture/<id>/page/<name>`: the images of the pages seen, the ones its lecture names;
 * * `context`: the course context, the most recent one; `handwriting`: the handwriting read, all
 *   of it; `deleted`: the lectures deleted, deleted on every device.
 *
 * The lectures shown or being recorded take what is received at once ([Lecture.merge]); the
 * assistant is told of the others ([LectureAssistant.onReceived]).
 */
internal class LectureAttachments(
    private val library: LectureAssistantLibrary,
    scope: CoroutineScope
) : DocumentAttachments {

    private val changesFlow = MutableSharedFlow<AttachmentChange>(extraBufferCapacity = Int.MAX_VALUE)
    override val changes: SharedFlow<AttachmentChange> = changesFlow

    /** The versions of the items of the documents known so far (read from the folders once). */
    private val indexes = mutableMapOf<String, MutableMap<String, String>>()
    private val indexLock = Mutex()

    /** What changed here and is not in the versions yet (see [applyChangesHere]). */
    private val changedHere = atomic(emptySet<LibraryChange>())
    private val changedSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        library.files.onChanged = { change ->
            changedHere.update { it + change }
            changedSignal.trySend(Unit)
        }
        scope.launch { for (signal in changedSignal) applyChangesHere() }
    }

    override suspend fun versions(documentId: String): Map<String, String> {
        applyChangesHere()
        return indexLock.withLock { indexOf(documentId).toMap() }
    }

    override suspend fun version(documentId: String, key: String): String? {
        applyChangesHere()
        return indexLock.withLock { indexOf(documentId)[key] }
    }

    override suspend fun wants(documentId: String, key: String, version: String): Boolean = withContext(Dispatchers.IO) {
        val item = Item.parse(key) ?: return@withContext false
        val lectureId = item.lectureId ?: return@withContext true
        if (lectureId in files.deletedLectures(documentId)) return@withContext false
        val directory = directoryOf(documentId, lectureId)
        when (item) {
            is Item.Record -> true
            // Shared by the device that recorded it, and wanted here now (see LectureSharingSettings):
            // more of it than there is here
            is Item.Audio -> {
                val record = files.readRecord(directory) ?: return@withContext false
                val sharing = files.sharingOf(documentId)
                record.audioShared && !library.isActiveHere(directory) && (audioSize(version) ?: 0) > RecordingFile(directory).size() &&
                    (sharing.downloadAudio == AudioDownload.AUTOMATIC || lectureId in sharing.requestedAudio)
            }
            // The one its lecture names (any, before the lecture arrives)
            is Item.Page -> files.readRecord(directory)?.pages?.any { it.image == item.name && "h${it.imageHash}" == version } ?: true
            else -> true
        }
    }

    override suspend fun open(documentId: String, key: String): AttachmentSource? = withContext(Dispatchers.IO) {
        val item = Item.parse(key) ?: return@withContext null
        val lectureId = item.lectureId
        if (lectureId != null && lectureId in files.deletedLectures(documentId)) return@withContext null
        val directory = lectureId?.let { directoryOf(documentId, it) }
        when (item) {
            is Item.Record -> {
                val record = files.readRecord(directory!!) ?: return@withContext null
                MemorySource(LectureRecords.version(record), LectureRecords.encode(record))
            }
            is Item.Audio -> {
                // Being recorded too: what is written already (it never changes)
                val path = RecordingFile(directory!!).path
                val size = SystemFileSystem.metadataOrNull(path)?.size ?: return@withContext null
                FileSource(audioVersion(size), size, path)
            }
            is Item.Page -> {
                val bytes = PlatformFiles.readBytes("$directory/pages/${item.name}") ?: return@withContext null
                MemorySource("h" + contentHash(bytes), bytes)
            }
            Item.Context -> {
                val context = files.loadContext(documentId) ?: return@withContext null
                val bytes = encodeContext(context)
                MemorySource("c" + contentHash(bytes), bytes)
            }
            Item.Handwriting -> {
                val texts = HandwritingReader.readFile(handwritingPath(documentId)).takeIf { it.isNotEmpty() } ?: return@withContext null
                val bytes = HandwritingReader.canonical(texts).encodeToByteArray()
                MemorySource("w" + contentHash(bytes), bytes)
            }
            Item.Deleted -> {
                val deleted = files.deletedLectures(documentId).takeIf { it.isNotEmpty() } ?: return@withContext null
                val bytes = encodeDeleted(deleted)
                MemorySource("d" + contentHash(bytes), bytes)
            }
        }
    }

    /** The audio received so far of the lectures, by folder (0..1), while it arrives. */
    private val audioProgressState = MutableStateFlow<Map<String, Float>>(emptyMap())
    val audioProgress: StateFlow<Map<String, Float>> = audioProgressState

    /** The audio here: the start of the audio of any version longer (it only grows). */
    override suspend fun received(documentId: String, key: String, version: String): Long {
        val item = Item.parse(key) as? Item.Audio ?: return 0
        val size = withContext(Dispatchers.IO) { RecordingFile(directoryOf(documentId, item.lectureId)).size() }
        return size.takeIf { it <= (audioSize(version) ?: 0) } ?: 0
    }

    /** The folders whose audio is being received (from one device at a time: it is appended). */
    private val receivingAudio = mutableSetOf<String>()
    private val receivingLock = Mutex()

    override suspend fun receive(documentId: String, key: String, version: String, size: Long, from: Long): AttachmentReceiver? {
        val item = Item.parse(key) ?: return null
        if (!wants(documentId, key, version)) return null
        return when (item) {
            is Item.Audio -> {
                if (size > MAX_AUDIO_BYTES) return null
                val directory = directoryOf(documentId, item.lectureId)
                // Appended to the audio here, which must be what comes before
                val path = RecordingFile(directory).path
                val appends = receivingLock.withLock {
                    directory !in receivingAudio && withContext(Dispatchers.IO) { RecordingFile(directory).size() } == from && receivingAudio.add(directory)
                }
                if (!appends) return null
                FileReceiver(path, append = true, size = size, start = from, onProgress = { received ->
                    audioProgressState.update { it + (directory to (received.toFloat() / size).coerceIn(0f, 1f)) }
                }, onEnd = {
                    receivingLock.withLock { receivingAudio.remove(directory) }
                    audioProgressState.update { it - directory }
                }) { keepAudio(documentId, item.lectureId) }
            }
            else -> {
                if (size > MAX_ITEM_BYTES) return null
                MemoryReceiver { bytes -> keep(documentId, item, bytes) }
            }
        }
    }

    // ==========================================================
    // What is received
    // ==========================================================

    private suspend fun keep(documentId: String, item: Item, bytes: ByteArray) {
        when (item) {
            is Item.Record -> keepRecord(documentId, item.lectureId, bytes)
            is Item.Page -> keepPage(documentId, item, bytes)
            Item.Context -> keepContext(documentId, bytes)
            Item.Handwriting -> keepHandwriting(documentId, bytes)
            Item.Deleted -> keepDeleted(documentId, bytes)
            is Item.Audio -> Unit
        }
    }

    /** The lecture as another device has it: merged with the one here (if any). */
    private suspend fun keepRecord(documentId: String, lectureId: String, bytes: ByteArray) {
        val theirs = LectureRecords.decode(bytes)?.takeIf { it.documentId == documentId } ?: return
        val directory = directoryOf(documentId, lectureId)
        var before: LectureRecord? = null
        val kept = files.records.withLock {
            if (lectureId in files.deletedLectures(documentId)) return
            before = withContext(Dispatchers.IO) { files.readRecord(directory) }
            val live = library.loadedLecture(directory)
            live?.merge(theirs) ?: withContext(Dispatchers.IO) {
                val mine = before
                val merged = if (mine == null) theirs.copy(id = lectureId) else LectureRecords.merge(mine, theirs)
                if (merged != mine && !writeLectureRecord(directory, merged)) return@withContext null
                merged
            } ?: return
        }
        withContext(Dispatchers.IO) { if (kept.recordedBy != files.deviceId) dropUnnamedPages(directory, kept) }
        updateIndex(documentId) { index -> indexLecture(documentId, lectureId, index) }
        library.onReceived(documentId)
        // What arrived from another device: a lecture, its review
        val previous = before
        val arrival = when {
            previous == null && kept.recordedBy != files.deviceId -> LectureArrival.Kind.LECTURE
            previous != null && previous.finalReview == null && kept.finalReview != null -> LectureArrival.Kind.REVIEW
            else -> null
        }
        arrival?.let { library.onArrival(LectureArrival(documentId, directory, it, from = arrivedFrom(kept, it), startedAt = kept.startedAt)) }
        // Over now, its audio arrived while it was recorded
        if (previous?.status != LectureStatus.FINISHED) audioArrivedIfWhole(documentId, directory, kept)
    }

    /** The name of the device a lecture, or its review, comes from. */
    private fun arrivedFrom(record: LectureRecord, kind: LectureArrival.Kind): String = when (kind) {
        LectureArrival.Kind.REVIEW -> record.stampOf(LecturePart.REVIEW).by.let { by -> record.usageByDevice[by]?.name ?: record.generation?.name }
        else -> null
    } ?: record.recordedOn

    /** The images of the pages of a lecture recorded elsewhere that it no longer names. */
    private fun dropUnnamedPages(directory: String, record: LectureRecord) {
        val pages = Path("$directory/pages")
        if (!SystemFileSystem.exists(pages)) return
        val named = record.pages.mapNotNullTo(mutableSetOf()) { it.image }
        for (file in SystemFileSystem.list(pages)) if (file.name !in named) SystemFileSystem.delete(file, mustExist = false)
    }

    private suspend fun keepPage(documentId: String, item: Item.Page, bytes: ByteArray) {
        val directory = directoryOf(documentId, item.lectureId)
        if (!withContext(Dispatchers.IO) { writeAtomically("$directory/pages/${item.name}", bytes) }) return
        updateIndex(documentId) { index -> indexLecture(documentId, item.lectureId, index) }
    }

    /** More of the audio of a lecture arrived (its whole audio, once recorded). */
    private suspend fun keepAudio(documentId: String, lectureId: String) {
        val directory = directoryOf(documentId, lectureId)
        updateIndex(documentId) { index -> indexLecture(documentId, lectureId, index) }
        library.loadedLecture(directory)?.audioReceived()
        library.onReceived(documentId)
        withContext(Dispatchers.IO) { files.readRecord(directory) }?.let { audioArrivedIfWhole(documentId, directory, it) }
    }

    /** The folders whose whole audio was told of (once: it may arrive before or after the lecture is over). */
    private val audioTold = mutableSetOf<String>()

    /** Tells of the audio of a lecture recorded elsewhere once it is whole here: recorded whole, and arrived whole. */
    private suspend fun audioArrivedIfWhole(documentId: String, directory: String, record: LectureRecord) {
        if (record.status != LectureStatus.FINISHED || record.recordedBy == files.deviceId) return
        val here = withContext(Dispatchers.IO) { RecordingFile(directory).durationMs() }
        if (here == 0L || here < record.durationMs - WHOLE_AUDIO_TOLERANCE_MS) return
        if (!receivingLock.withLock { audioTold.add(directory) }) return
        library.onArrival(LectureArrival(documentId, directory, LectureArrival.Kind.AUDIO, record.recordedOn, record.startedAt))
    }

    private val wantedAgainFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val wantedAgain: SharedFlow<String> = wantedAgainFlow

    /** The audio of a lecture of [documentId] was asked for: the devices having it are asked for it. */
    fun audioRequested(documentId: String) {
        wantedAgainFlow.tryEmit(documentId)
    }

    /** The most recent course context is kept. */
    private suspend fun keepContext(documentId: String, bytes: ByteArray) {
        val theirs = decodeContext(bytes) ?: return
        val kept = files.records.withLock {
            withContext(Dispatchers.IO) {
                val mine = files.loadContext(documentId)
                if (mine == null || theirs.stamp > mine.stamp || (theirs.stamp == mine.stamp && theirs.text > mine.text)) {
                    files.writeContext(documentId, theirs)
                    theirs
                } else {
                    null
                }
            }
        } ?: return
        updateIndex(documentId) { index -> indexShared(documentId, index) }
        library.onContextReceived(documentId, kept)
    }

    private suspend fun keepHandwriting(documentId: String, bytes: ByteArray) {
        val theirs = HandwritingReader.decode(bytes) ?: return
        HandwritingReader.fileLock.withLock {
            withContext(Dispatchers.IO) {
                val path = handwritingPath(documentId)
                val mine = HandwritingReader.readFile(path)
                val merged = HandwritingReader.merge(mine, theirs)
                if (merged != mine) writeAtomically(path, HandwritingReader.encode(merged).encodeToByteArray())
            }
        }
        updateIndex(documentId) { index -> indexShared(documentId, index) }
    }

    /** The lectures deleted on another device: deleted here too (the one being recorded here once over). */
    private suspend fun keepDeleted(documentId: String, bytes: ByteArray) {
        val theirs = decodeDeleted(bytes) ?: return
        val deleted = files.records.withLock { withContext(Dispatchers.IO) { files.markDeleted(documentId, theirs) } }
        for (lectureId in deleted.keys) {
            val directory = directoryOf(documentId, lectureId)
            if (library.isActiveHere(directory)) {
                // Being recorded here: deleted once over
                library.loadedLecture(directory)?.let { it.setState { deletedElsewhere = true } }
            } else if (SystemFileSystem.exists(Path(directory))) {
                library.onDeleted(documentId, directory)
                withContext(Dispatchers.IO) { files.deleteFiles(directory) }
            }
        }
        updateIndex(documentId) { index ->
            index.keys.removeAll { key -> Item.parse(key)?.lectureId in deleted }
            indexShared(documentId, index)
        }
        library.onReceived(documentId)
    }

    // ==========================================================
    // Versions
    // ==========================================================

    /** What changed here: the versions of its items again, the other devices told of the new ones. */
    private suspend fun applyChangesHere() {
        for (change in changedHere.getAndSet(emptySet())) onChangedHere(change)
    }

    private suspend fun onChangedHere(change: LibraryChange) {
        updateIndex(change.documentId) { index ->
            val lectureId = change.lectureId
            if (lectureId != null) {
                indexLecture(change.documentId, lectureId, index)
            } else {
                val deleted = files.deletedLectures(change.documentId)
                index.keys.removeAll { key -> Item.parse(key)?.lectureId in deleted }
                indexShared(change.documentId, index)
            }
        }
    }

    /** Changes the versions of [documentId] by [block]: the items whose version changed are told. */
    private suspend fun updateIndex(documentId: String, block: (MutableMap<String, String>) -> Unit) {
        val changed = indexLock.withLock {
            val index = indexOf(documentId)
            val before = index.toMap()
            withContext(Dispatchers.IO) { block(index) }
            index.filter { (key, version) -> before[key] != version }
        }
        for ((key, version) in changed) changesFlow.tryEmit(AttachmentChange(documentId, key, version))
    }

    /** The versions of the items of [documentId], read from its folder the first time. Holding [indexLock]. */
    private suspend fun indexOf(documentId: String): MutableMap<String, String> = indexes.getOrPut(documentId) {
        withContext(Dispatchers.IO) {
            val index = mutableMapOf<String, String>()
            val folder = Path(files.documentDirectory(documentId))
            if (SystemFileSystem.exists(folder)) {
                val deleted = files.deletedLectures(documentId)
                for (path in SystemFileSystem.list(folder)) {
                    if (SystemFileSystem.metadataOrNull(path)?.isDirectory != true || path.name in deleted) continue
                    recoverInterrupted(path.toString())
                    indexLecture(documentId, path.name, index)
                }
                indexShared(documentId, index)
            }
            index
        }
    }

    /**
     * A recording interrupted here (the application closed meanwhile) is recovered before it is
     * offered: the other devices would show it as being recorded.
     */
    private suspend fun recoverInterrupted(directory: String) {
        val record = files.readRecord(directory) ?: return
        if (record.status == LectureStatus.FINISHED || !files.statusSetHere(record) || library.isActiveHere(directory)) return
        files.records.withLock { files.load(directory) }
    }

    /** The versions of the items of the lecture [lectureId] in [index]. */
    private fun indexLecture(documentId: String, lectureId: String, index: MutableMap<String, String>) {
        if (!isName(lectureId)) return
        val directory = directoryOf(documentId, lectureId)
        val prefix = Item.Record(lectureId).key
        index.keys.removeAll { it == prefix || it.startsWith("$prefix/") }
        if (lectureId in files.deletedLectures(documentId)) return
        val record = files.readRecord(directory) ?: return
        index[prefix] = LectureRecords.version(record)
        // The audio, also while it is recorded
        if (record.audioShared) {
            RecordingFile(directory).size().takeIf { it > 0 }?.let { index[Item.Audio(lectureId).key] = audioVersion(it) }
        }
        for (page in record.pages) {
            val name = page.image?.takeIf(::isName) ?: continue
            val bytes = PlatformFiles.readBytes("$directory/pages/$name") ?: continue
            index[Item.Page(lectureId, name).key] = "h" + contentHash(bytes)
        }
    }

    /** The versions of what the lectures of [documentId] share, in [index]. */
    private fun indexShared(documentId: String, index: MutableMap<String, String>) {
        index.remove(Item.Context.key)
        index.remove(Item.Handwriting.key)
        index.remove(Item.Deleted.key)
        files.loadContext(documentId)?.let { index[Item.Context.key] = "c" + contentHash(encodeContext(it)) }
        HandwritingReader.readFile(handwritingPath(documentId)).takeIf { it.isNotEmpty() }?.let {
            index[Item.Handwriting.key] = "w" + contentHash(HandwritingReader.canonical(it).encodeToByteArray())
        }
        files.deletedLectures(documentId).takeIf { it.isNotEmpty() }?.let { index[Item.Deleted.key] = "d" + contentHash(encodeDeleted(it)) }
    }

    private val files get() = library.files

    private fun directoryOf(documentId: String, lectureId: String) = files.lectureDirectory(documentId, lectureId)

    private fun handwritingPath(documentId: String) = files.documentDirectory(documentId) + "/handwriting.json"

    private fun audioVersion(size: Long) = "a$size"

    /** The size of the audio at [version]. */
    private fun audioSize(version: String): Long? = version.removePrefix("a").toLongOrNull()

    private fun encodeContext(context: CourseContext) = Lecture.recordJson.encodeToString(CourseContext.serializer(), context).encodeToByteArray()

    private fun decodeContext(bytes: ByteArray): CourseContext? = try {
        Lecture.recordJson.decodeFromString(CourseContext.serializer(), bytes.decodeToString())
    } catch (_: Exception) {
        null
    }

    private fun encodeDeleted(deleted: Map<String, Long>) =
        Lecture.recordJson.encodeToString(deletedSerializer, deleted.entries.sortedBy { it.key }.associate { it.toPair() }).encodeToByteArray()

    private fun decodeDeleted(bytes: ByteArray): Map<String, Long>? = try {
        Lecture.recordJson.decodeFromString(deletedSerializer, bytes.decodeToString()).filterKeys(::isName)
    } catch (_: Exception) {
        null
    }

    /** The items, by key (the names in the keys are names of files: nothing else is accepted). */
    private sealed class Item(val key: String) {
        open val lectureId: String? get() = null

        class Record(override val lectureId: String) : Item("lecture/$lectureId")
        class Audio(override val lectureId: String) : Item("lecture/$lectureId/audio")
        class Page(override val lectureId: String, val name: String) : Item("lecture/$lectureId/page/$name")
        data object Context : Item("context")
        data object Handwriting : Item("handwriting")
        data object Deleted : Item("deleted")

        companion object {
            fun parse(key: String): Item? {
                val parts = key.split('/')
                return when {
                    key == Context.key -> Context
                    key == Handwriting.key -> Handwriting
                    key == Deleted.key -> Deleted
                    parts.firstOrNull() != "lecture" || parts.size < 2 || !isName(parts[1]) -> null
                    parts.size == 2 -> Record(parts[1])
                    parts.size == 3 && parts[2] == "audio" -> Audio(parts[1])
                    parts.size == 4 && parts[2] == "page" && isName(parts[3]) -> Page(parts[1], parts[3])
                    else -> null
                }
            }
        }
    }

    private class MemorySource(override val version: String, private val bytes: ByteArray) : AttachmentSource {
        override val size = bytes.size.toLong()

        override suspend fun read(offset: Long, length: Int): ByteArray =
            bytes.copyOfRange(offset.toInt().coerceIn(0, bytes.size), (offset + length).coerceIn(0, size).toInt())
    }

    /** A file read in order (the audio: it never changes once recorded). */
    private class FileSource(override val version: String, override val size: Long, private val path: Path) : AttachmentSource {
        private var source: RawSource? = null
        private var position = 0L

        override suspend fun read(offset: Long, length: Int): ByteArray? = withContext(Dispatchers.IO) {
            try {
                if (source == null || position != offset) {
                    source?.close()
                    source = SystemFileSystem.source(path).also { it.skipFully(offset) }
                    position = offset
                }
                val buffer = Buffer()
                while (buffer.size < length) {
                    if (source!!.readAtMostTo(buffer, length - buffer.size) < 0) break
                }
                position += buffer.size
                buffer.readByteArray()
            } catch (_: Exception) {
                null
            }
        }

        override suspend fun close() {
            source?.close()
            source = null
        }

        private fun RawSource.skipFully(count: Long) {
            val buffer = Buffer()
            var left = count
            while (left > 0) {
                val read = readAtMostTo(buffer, minOf(left, SKIP_BYTES))
                if (read < 0) break
                buffer.clear()
                left -= read
            }
        }
    }

    private class MemoryReceiver(private val onComplete: suspend (ByteArray) -> Unit) : AttachmentReceiver {
        private val buffer = Buffer()

        override suspend fun write(bytes: ByteArray) {
            buffer.write(bytes)
        }

        override suspend fun complete() = onComplete(buffer.readByteArray())

        override suspend fun cancel() = buffer.clear()
    }

    /**
     * Written to [part] (after the [start] bytes there when [append]), kept by [onComplete] once
     * whole; [onEnd] once received, or not (what was written stays: it goes on from there).
     */
    private class FileReceiver(
        private val part: Path,
        private val append: Boolean,
        private val size: Long,
        start: Long,
        private val onProgress: (Long) -> Unit,
        private val onEnd: suspend () -> Unit = {},
        private val onComplete: suspend () -> Unit
    ) : AttachmentReceiver {
        private var sink: RawSink? = null
        private var received = start

        override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
            val current = sink ?: run {
                part.parent?.let { SystemFileSystem.createDirectories(it) }
                SystemFileSystem.sink(part, append = append).also { sink = it }
            }
            current.write(Buffer().apply { write(bytes) }, bytes.size.toLong())
            received += bytes.size
            onProgress(received)
        }

        override suspend fun complete() {
            close()
            onComplete()
        }

        // Kept: the transfer goes on from there (see received)
        override suspend fun cancel() = close()

        private suspend fun close() {
            withContext(Dispatchers.IO) {
                sink?.flush()
                sink?.close()
                sink = null
            }
            onEnd()
        }
    }

    private companion object {
        const val MAX_ITEM_BYTES = 64L * 1024 * 1024

        const val MAX_AUDIO_BYTES = 4L * 1024 * 1024 * 1024

        /** The audio here is whole when it is this close to the length the recording device says. */
        const val WHOLE_AUDIO_TOLERANCE_MS = 2_000L
        const val SKIP_BYTES = 64L * 1024
        val deletedSerializer = MapSerializer(String.serializer(), Long.serializer())

        /** A name of a file made here (no folder, no hidden file). */
        fun isName(name: String) = name.length in 1..MAX_NAME && name.first().isLetterOrDigit() && name.all { it.isLetterOrDigit() || it in "._-" }

        const val MAX_NAME = 160
    }
}

/** What the lectures shared need of the assistant (the lectures loaded, the ones being recorded). */
internal interface LectureAssistantLibrary {
    val files: LectureLibrary

    /** The lecture in [directory], loaded to be shown or recorded. */
    suspend fun loadedLecture(directory: String): Lecture?

    /** Whether the lecture in [directory] is being recorded or imported here. */
    suspend fun isActiveHere(directory: String): Boolean

    /** Lectures of [documentId] were received (or changed by another device). */
    suspend fun onReceived(documentId: String)

    suspend fun onContextReceived(documentId: String, context: CourseContext)

    /** The lecture in [directory] was deleted on another device. */
    suspend fun onDeleted(documentId: String, directory: String)

    /** Something arrived from another device (to tell the user). */
    suspend fun onArrival(arrival: LectureArrival)
}

/** A lecture, its audio or its review arrived from another device ([from]: its name). */
internal data class LectureArrival(val documentId: String, val directory: String, val kind: Kind, val from: String, val startedAt: Long) {
    enum class Kind { LECTURE, AUDIO, REVIEW }
}
