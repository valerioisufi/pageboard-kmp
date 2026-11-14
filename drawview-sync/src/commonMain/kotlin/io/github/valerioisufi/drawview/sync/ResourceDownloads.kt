package io.github.valerioisufi.drawview.sync

import io.github.valerioisufi.drawview.platform.Sha256
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The files of the resources being received from the other devices (Information Expert), by
 * document and resource: written by [files] as their parts come, in order, and checked against the
 * hash their operation names once whole. A download interrupted goes on from where it stopped,
 * over another connection too.
 *
 * @param maxSize the largest file received.
 */
internal class ResourceDownloads(private val files: ResourceFiles, private val maxSize: Long) {
    /** A file of [total] bytes being received: [received] of them, hashed as they come. */
    private class Download(val total: Long, val extension: String, val file: ResourceDownload) {
        var received = 0L
        val hash = Sha256()
    }

    private val downloads = mutableMapOf<Pair<String, String>, Download>()
    private val mutex = Mutex()

    /** How many bytes of the resource [id] of [documentId] were received (where to ask from). */
    suspend fun receivedOf(documentId: String, id: String): Long = mutex.withLock { downloads[documentId to id]?.received ?: 0L }

    /** A file received whole: [path] where it is kept, `null` when it was refused or could not be kept. */
    class Whole(val path: String?)

    /**
     * Writes [chunk], a part of the resource of [documentId]: the file when it completes it, kept if
     * its hash is [hash] (none: not checked); `null` while more is wanted (or the part is not the
     * next one, or cannot be written).
     */
    suspend fun add(documentId: String, chunk: ResourceChunk, hash: suspend () -> String?): Whole? {
        if (chunk.total !in 0..maxSize) return null
        val key = documentId to chunk.id
        val complete = mutex.withLock {
            var download = downloads[key]
            if (download == null || download.total != chunk.total || chunk.offset == 0L && download.received != 0L) {
                // The bytes as they come, not as many as announced
                download?.file?.discard()
                val file = files.receive(chunk.extension) ?: return null
                download = Download(chunk.total, chunk.extension, file).also { downloads[key] = it }
            }
            // The next bytes only
            if (chunk.offset != download.received || chunk.offset + chunk.bytes.size > download.total) return null
            if (!download.file.append(chunk.bytes)) {
                downloads.remove(key)
                download.file.discard()
                return null
            }
            download.hash.update(chunk.bytes)
            download.received += chunk.bytes.size
            if (download.received < download.total) return null
            downloads.remove(key)
            download
        }
        // The file the operation adding the resource names (signed by its author): another one is
        // not kept, and asked for again at the next connection
        val expected = hash()
        if (!expected.isNullOrEmpty() && complete.hash.digestHex() != expected) {
            complete.file.discard()
            return Whole(null)
        }
        return Whole(complete.file.finish())
    }
}
