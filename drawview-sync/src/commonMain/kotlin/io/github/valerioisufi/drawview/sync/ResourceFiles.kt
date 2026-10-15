package io.github.valerioisufi.drawview.sync

import io.github.valerioisufi.drawview.platform.PlatformFiles
import io.github.valerioisufi.drawview.platform.newResourcePath

/** Where the files of the resources of the documents are read and written. */
public interface ResourceFiles {
    /** The content of the file [path], or `null` when it is not there. */
    public fun read(path: String): ByteArray?

    /** Writes [bytes] in a new file (named with [extension]); returns its path, or `null`. */
    public fun write(bytes: ByteArray, extension: String): String?

    /** Whether the file [path] is there. */
    public fun exists(path: String): Boolean = read(path) != null
}

/** The files of the resources of the device (see [PlatformFiles.resourcesDirectory]). */
internal object PlatformResourceFiles : ResourceFiles {
    override fun read(path: String): ByteArray? = if (path.isEmpty()) null else PlatformFiles.readBytes(path)

    override fun exists(path: String): Boolean = path.isNotEmpty() && PlatformFiles.exists(path)

    override fun write(bytes: ByteArray, extension: String): String? {
        val path = newResourcePath("shared", extension.ifEmpty { "bin" })
        return path.takeIf { PlatformFiles.writeBytes(it, bytes) }
    }
}
