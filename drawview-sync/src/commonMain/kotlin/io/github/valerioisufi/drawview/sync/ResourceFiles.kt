package io.github.valerioisufi.drawview.sync

import io.github.valerioisufi.drawview.core.platform.PlatformFiles
import io.github.valerioisufi.drawview.core.platform.newResourcePath

/** Where the files of the resources of the documents are read and written. */
interface ResourceFiles {
    /** The content of the file [path], or `null` when it is not there. */
    fun read(path: String): ByteArray?

    /** Writes [bytes] in a new file (named with [extension]); returns its path, or `null`. */
    fun write(bytes: ByteArray, extension: String): String?
}

/** The files of the resources of the device (see [PlatformFiles.resourcesDirectory]). */
object PlatformResourceFiles : ResourceFiles {
    override fun read(path: String): ByteArray? = if (path.isEmpty()) null else PlatformFiles.readBytes(path)

    override fun write(bytes: ByteArray, extension: String): String? {
        val path = newResourcePath("shared", extension.ifEmpty { "bin" })
        return path.takeIf { PlatformFiles.writeBytes(it, bytes) }
    }
}
