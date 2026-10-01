package io.github.valerioisufi.drawview.core.platform

import java.io.File

/**
 * Root directory of the DrawView desktop data (database, resources, LaTeX fonts).
 */
val drawViewDesktopDataDirectory: File
    get() = File(System.getProperty("user.home"), ".drawview").apply { mkdirs() }

actual object PlatformFiles {
    actual fun resourcesDirectory(): String {
        val dir = File(drawViewDesktopDataDirectory, "resources")
        dir.mkdirs()
        return dir.absolutePath
    }

    actual fun readBytes(path: String): ByteArray? = try {
        File(path).takeIf { it.exists() }?.readBytes()
    } catch (_: Exception) {
        null
    }

    actual fun writeBytes(path: String, bytes: ByteArray): Boolean = try {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        true
    } catch (_: Exception) {
        false
    }

    actual fun exists(path: String): Boolean = File(path).exists()

    actual fun delete(path: String): Boolean = File(path).delete()
}
