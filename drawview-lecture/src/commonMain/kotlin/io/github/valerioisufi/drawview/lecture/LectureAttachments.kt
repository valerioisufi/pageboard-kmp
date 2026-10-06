package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.core.attachment.AttachmentChange
import io.github.valerioisufi.drawview.core.attachment.AttachmentReceiver
import io.github.valerioisufi.drawview.core.attachment.AttachmentSource
import io.github.valerioisufi.drawview.core.attachment.DocumentAttachments
import io.github.valerioisufi.drawview.core.platform.PlatformFiles
import io.github.valerioisufi.drawview.lecture.audio.RecordingFile
import io.github.valerioisufi.drawview.lecture.handwriting.HandwritingReader
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
 * * `lecture/<id>/audio`: its audio, once recorded (the device without it wants it);
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
            // Recorded once: the device without it wants it
            is Item.Audio -> !library.isActiveHere(directory) && RecordingFile(directory).pcmBytes() == 0L
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
                if (library.isActiveHere(directory!!)) return@withContext null
                val path = RecordingFile(directory).wavPath
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

    override suspend fun receive(documentId: String, key: String, version: String, size: Long): AttachmentReceiver? {
        val item = Item.parse(key) ?: return null
        if (!wants(documentId, key, version)) return null
        return when (item) {
            is Item.Audio -> {
                if (size > MAX_AUDIO_BYTES) return null
                val directory = directoryOf(documentId, item.lectureId)
                FileReceiver(Path("$directory/audio.wav.part")) { part -> keepAudio(documentId, item.lectureId, part) }
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
        val kept = files.records.withLock {
            if (lectureId in files.deletedLectures(documentId)) return
            val live = library.loadedLecture(directory)
            live?.merge(theirs) ?: withContext(Dispatchers.IO) {
                val mine = files.readRecord(directory)
                val merged = if (mine == null) theirs.copy(id = lectureId) else LectureRecords.merge(mine, theirs)
                if (merged != mine && !writeLectureRecord(directory, merged)) return@withContext null
                merged
            } ?: return
        }
        withContext(Dispatchers.IO) { if (kept.recordedBy != files.deviceId) dropUnnamedPages(directory, kept) }
        updateIndex(documentId) { index -> indexLecture(documentId, lectureId, index) }
        library.onReceived(documentId)
    }

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

    private suspend fun keepAudio(documentId: String, lectureId: String, part: Path) {
        val directory = directoryOf(documentId, lectureId)
        val kept = withContext(Dispatchers.IO) {
            try {
                SystemFileSystem.atomicMove(part, RecordingFile(directory).wavPath)
                true
            } catch (e: Exception) {
                println("DrawView: cannot keep the audio received for $directory: ${e.message}")
                false
            }
        }
        if (!kept) return
        updateIndex(documentId) { index -> indexLecture(documentId, lectureId, index) }
        library.loadedLecture(directory)?.audioReceived()
        library.onReceived(documentId)
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
            if (!library.isActiveHere(directory) && SystemFileSystem.exists(Path(directory))) {
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
        // The audio once recorded (the raw one is being recorded)
        val audio = RecordingFile(directory)
        if (record.status == LectureStatus.FINISHED && !SystemFileSystem.exists(audio.pcmPath)) {
            SystemFileSystem.metadataOrNull(audio.wavPath)?.size?.let { index[Item.Audio(lectureId).key] = audioVersion(it) }
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

    /** Written to [part], kept by [onComplete] once whole. */
    private class FileReceiver(private val part: Path, private val onComplete: suspend (Path) -> Unit) : AttachmentReceiver {
        private var sink: RawSink? = null

        override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
            val current = sink ?: run {
                part.parent?.let { SystemFileSystem.createDirectories(it) }
                SystemFileSystem.sink(part).also { sink = it }
            }
            current.write(Buffer().apply { write(bytes) }, bytes.size.toLong())
        }

        override suspend fun complete() {
            withContext(Dispatchers.IO) {
                sink?.flush()
                sink?.close()
                sink = null
            }
            onComplete(part)
        }

        override suspend fun cancel() = withContext(Dispatchers.IO) {
            sink?.close()
            sink = null
            SystemFileSystem.delete(part, mustExist = false)
        }
    }

    private companion object {
        const val MAX_ITEM_BYTES = 64L * 1024 * 1024
        const val MAX_AUDIO_BYTES = 4L * 1024 * 1024 * 1024
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
}
