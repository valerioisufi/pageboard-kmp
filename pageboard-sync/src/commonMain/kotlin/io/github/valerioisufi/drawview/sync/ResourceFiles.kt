package io.github.valerioisufi.drawview.sync

import io.github.valerioisufi.drawview.platform.PlatformFiles
import io.github.valerioisufi.drawview.platform.newResourcePath
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Where the files of the resources of the documents are read and written. */
public interface ResourceFiles {
    /** The content of the file [path], or `null` when it is not there. */
    public fun read(path: String): ByteArray?

    /** Writes [bytes] in a new file (named with [extension]); returns its path, or `null`. */
    public fun write(bytes: ByteArray, extension: String): String?

    /** Whether the file [path] is there. */
    public fun exists(path: String): Boolean = read(path) != null

    /**
     * A new file named with [extension] written as its bytes come (see [ResourceDownload]), or
     * `null` when it cannot be made. By default its bytes are kept in memory, then [write]n.
     */
    public fun receive(extension: String): ResourceDownload? = MemoryDownload(this, extension)
}

/** The file of a resource being received, written as its bytes come (see [ResourceFiles.receive]). */
public interface ResourceDownload {
    /** Writes [bytes] after the ones received; returns whether it could. */
    public fun append(bytes: ByteArray): Boolean

    /** Keeps the file received whole; returns its path, or `null` when it could not. */
    public fun finish(): String?

    /** Deletes what was received. */
    public fun discard()
}

/** A download kept in memory until it is whole, then written by [files]. */
private class MemoryDownload(private val files: ResourceFiles, private val extension: String) : ResourceDownload {
    private var bytes = ByteArray(0)
    private var size = 0

    override fun append(bytes: ByteArray): Boolean {
        if (size + bytes.size > this.bytes.size) this.bytes = this.bytes.copyOf(maxOf(size + bytes.size, this.bytes.size * 2))
        bytes.copyInto(this.bytes, size)
        size += bytes.size
        return true
    }

    override fun finish(): String? = files.write(bytes.copyOf(size), extension)

    override fun discard() {
        bytes = ByteArray(0)
        size = 0
    }
}

/** The files of the resources of the device (see [PlatformFiles.resourcesDirectory]). */
internal object PlatformResourceFiles : ResourceFiles {
    override fun read(path: String): ByteArray? = if (path.isEmpty()) null else PlatformFiles.readBytes(path)

    override fun exists(path: String): Boolean = path.isNotEmpty() && PlatformFiles.exists(path)

    override fun write(bytes: ByteArray, extension: String): String? {
        val path = newResourcePath("shared", extension.ifEmpty { "bin" })
        return path.takeIf { PlatformFiles.writeBytes(it, bytes) }
    }

    override fun receive(extension: String): ResourceDownload = PartFileDownload(newResourcePath("shared", extension.ifEmpty { "bin" }))
}

/**
 * A download written to the file [path] with `.part` after its name, renamed [path] once whole: the
 * memory does not hold the file, however large.
 */
private class PartFileDownload(private val path: String) : ResourceDownload {
    private val part = Path("$path.part")

    override fun append(bytes: ByteArray): Boolean = try {
        SystemFileSystem.sink(part, append = true).buffered().use { it.write(bytes) }
        true
    } catch (_: IOException) {
        false
    }

    override fun finish(): String? = try {
        SystemFileSystem.atomicMove(part, Path(path))
        path
    } catch (_: IOException) {
        discard()
        null
    }

    override fun discard() {
        try {
            SystemFileSystem.delete(part, mustExist = false)
        } catch (_: IOException) {
        }
    }
}
