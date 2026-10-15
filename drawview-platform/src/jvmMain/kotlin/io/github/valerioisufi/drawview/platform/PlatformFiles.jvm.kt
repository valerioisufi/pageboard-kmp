package io.github.valerioisufi.drawview.platform

import java.io.File

/** System property naming the data directory of the application (`~/.drawview` when not set). */
public const val DRAWVIEW_DATA_DIRECTORY_PROPERTY: String = "drawview.dataDir"

/**
 * Root directory of the DrawView desktop data (database, resources, LaTeX fonts): the
 * [DRAWVIEW_DATA_DIRECTORY_PROPERTY] system property, so that each application keeps its own
 * data, `~/.drawview` by default.
 */
public val drawViewDesktopDataDirectory: File
    get() {
        val configured = System.getProperty(DRAWVIEW_DATA_DIRECTORY_PROPERTY)?.takeIf { it.isNotBlank() }
        return (configured?.let(::File) ?: File(System.getProperty("user.home"), ".drawview")).apply { mkdirs() }
    }

public actual object PlatformFiles {
    public actual fun resourcesDirectory(): String {
        val dir = File(drawViewDesktopDataDirectory, "resources")
        dir.mkdirs()
        return dir.absolutePath
    }

    public actual fun readBytes(path: String): ByteArray? = try {
        File(path).takeIf { it.exists() }?.readBytes()
    } catch (_: Exception) {
        null
    }

    public actual fun writeBytes(path: String, bytes: ByteArray): Boolean = try {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        true
    } catch (_: Exception) {
        false
    }

    public actual fun exists(path: String): Boolean = File(path).exists()

    public actual fun delete(path: String): Boolean = File(path).delete()
}
