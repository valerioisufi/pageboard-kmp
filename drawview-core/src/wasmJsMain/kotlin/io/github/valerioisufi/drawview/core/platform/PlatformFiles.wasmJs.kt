package io.github.valerioisufi.drawview.core.platform

/**
 * The browser has no file system: resources are kept in memory for the lifetime of the page.
 */
actual object PlatformFiles {
    private val files = mutableMapOf<String, ByteArray>()

    actual fun resourcesDirectory(): String = "/drawview_resources"

    actual fun readBytes(path: String): ByteArray? = files[path]

    actual fun writeBytes(path: String, bytes: ByteArray): Boolean {
        files[path] = bytes
        return true
    }

    actual fun exists(path: String): Boolean = files.containsKey(path)

    actual fun delete(path: String): Boolean = files.remove(path) != null
}
