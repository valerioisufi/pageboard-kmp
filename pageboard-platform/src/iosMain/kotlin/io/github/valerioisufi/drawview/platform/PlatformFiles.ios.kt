package io.github.valerioisufi.drawview.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
public actual object PlatformFiles {
    public actual fun resourcesDirectory(): String {
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: ""
        val dir = "$documents/pageboard_resources"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        return dir
    }

    public actual fun readBytes(path: String): ByteArray? {
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        val size = data.length.toInt()
        if (size == 0) return ByteArray(0)
        val bytes = ByteArray(size)
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), data.bytes, data.length)
        }
        return bytes
    }

    public actual fun writeBytes(path: String, bytes: ByteArray): Boolean {
        val directory = path.substringBeforeLast('/', "")
        if (directory.isNotEmpty()) {
            NSFileManager.defaultManager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
        }
        val data = if (bytes.isEmpty()) NSData() else bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        return data.writeToFile(path, atomically = true)
    }

    public actual fun exists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

    public actual fun delete(path: String): Boolean = NSFileManager.defaultManager.removeItemAtPath(path, error = null)

    public actual fun name(path: String): String = path.trimEnd('/').substringAfterLast('/')

    public actual fun parent(path: String): String? {
        val trimmed = path.trimEnd('/')
        return when (val slash = trimmed.lastIndexOf('/')) {
            -1 -> null
            0 -> "/"
            else -> trimmed.substring(0, slash)
        }
    }
}
