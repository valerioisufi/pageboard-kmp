package io.github.valerioisufi.pageboard.platform

import java.io.File

/** System property naming the data directory of the application (`~/.drawview` when not set). */
public const val PAGEBOARD_DATA_DIRECTORY_PROPERTY: String = "pageboard.dataDir"

/**
 * Root directory of the DrawView desktop data (database, resources, LaTeX fonts): the
 * [PAGEBOARD_DATA_DIRECTORY_PROPERTY] system property, so that each application keeps its own
 * data, `~/.drawview` by default. Its layout is brought up to date the first time it is read
 * ([DesktopDataLayout]).
 */
/**
 * Stops the run when the data directory is given under the property of before the rename to
 * Pageboard, `drawview.dataDir`, and not under [PAGEBOARD_DATA_DIRECTORY_PROPERTY]: a command
 * written for the old name would otherwise open the default directory, where real notes are.
 */
public fun checkDataDirectoryProperty() {
    check(System.getProperty(OLD_DATA_DIRECTORY_PROPERTY) == null || System.getProperty(PAGEBOARD_DATA_DIRECTORY_PROPERTY) != null) {
        "$OLD_DATA_DIRECTORY_PROPERTY is not read any more: give the data directory with -D$PAGEBOARD_DATA_DIRECTORY_PROPERTY"
    }
}

private const val OLD_DATA_DIRECTORY_PROPERTY = "drawview.dataDir"

public val drawViewDesktopDataDirectory: File
    get() {
        checkDataDirectoryProperty()
        val configured = System.getProperty(PAGEBOARD_DATA_DIRECTORY_PROPERTY)?.takeIf { it.isNotBlank() }
        return (configured?.let(::File) ?: File(System.getProperty("user.home"), ".drawview")).apply {
            mkdirs()
            DesktopDataLayout.ensureUpToDate(this)
        }
    }

public actual object PlatformFiles {
    public actual fun resourcesDirectory(): String {
        val dir = File(drawViewDesktopDataDirectory, DesktopDataLayout.RESOURCES)
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

    public actual fun name(path: String): String = File(path).name

    public actual fun parent(path: String): String? = File(path).parent
}
