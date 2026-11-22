package io.github.valerioisufi.pageboard.platform

import java.io.File

/**
 * System property naming the data directory on the desktop. When it is given it wins over the
 * directory of the application ([PageboardDesktop.init]): a check or a test run on a directory of
 * its own never opens real notes.
 */
public const val PAGEBOARD_DATA_DIRECTORY_PROPERTY: String = "pageboard.dataDir"

/**
 * The data directory of the application on the desktop (its database, resources, LaTeX fonts and
 * secrets), the counterpart of [PageboardAndroid]: the application names it once at its start,
 * before a library reads it, and no other application shares it.
 */
public object PageboardDesktop {
    private val directory = DesktopDataDirectory()

    /**
     * Names [dataDirectory] as the data directory of the application. It is given once: the same
     * directory again does nothing, another one fails.
     */
    public fun init(dataDirectory: File) {
        directory.init(dataDirectory)
    }

    /** Whether the data directory is known: given by [init] or by [PAGEBOARD_DATA_DIRECTORY_PROPERTY]. */
    public val isInitialized: Boolean get() = directory.isKnown

    /**
     * The data directory: the [PAGEBOARD_DATA_DIRECTORY_PROPERTY] property when it is given, else
     * the directory given to [init]. Its layout is brought up to date the first time it is read
     * ([DesktopDataLayout]).
     *
     * @throws IllegalStateException when neither gives it, or when the data directory is given
     * under the property of before the rename to Pageboard only.
     */
    public val dataDirectory: File
        get() = directory.read().apply {
            mkdirs()
            DesktopDataLayout.ensureUpToDate(this)
        }

    /**
     * Where the system keeps the data of an application named [name], for a new application:
     * `~/Library/Application Support/<name>` on macOS, `%LOCALAPPDATA%\<name>` on Windows,
     * `$XDG_DATA_HOME/<name>` (`~/.local/share/<name>` when it is not set) on Linux. Nothing is
     * created.
     */
    public fun applicationDataDirectory(name: String): File =
        applicationDataDirectory(name, System.getProperty("os.name"), File(System.getProperty("user.home")), System::getenv)
}

/**
 * The data directory given by the application and the property that wins over it (GRASP Pure
 * Fabrication): [PageboardDesktop] keeps the one of the process, a test makes its own.
 */
internal class DesktopDataDirectory(
    private val property: () -> String? = { System.getProperty(PAGEBOARD_DATA_DIRECTORY_PROPERTY) }
) {
    private var given: File? = null

    /** Names [dataDirectory], once: the same directory again does nothing, another one fails. */
    fun init(dataDirectory: File) {
        val absolute = dataDirectory.absoluteFile
        synchronized(this) {
            val current = given
            check(current == null || current == absolute) {
                "The data directory of the application is $current already: it is given once"
            }
            given = absolute
        }
    }

    /** Whether the property or [init] gives the data directory. */
    val isKnown: Boolean get() = fromProperty() != null || synchronized(this) { given != null }

    /** The data directory: the property when it is given, else the directory of [init]. */
    fun read(): File {
        checkDataDirectoryProperty()
        return fromProperty() ?: synchronized(this) { given } ?: error(
            "The data directory of the application is not given: call PageboardDesktop.init(directory) " +
                "at its start, or run it with -D$PAGEBOARD_DATA_DIRECTORY_PROPERTY=<directory>"
        )
    }

    private fun fromProperty(): File? = property()?.takeIf { it.isNotBlank() }?.let(::File)
}

/** The directory of the data of an application named [name] on the system [osName] (see [PageboardDesktop.applicationDataDirectory]). */
internal fun applicationDataDirectory(name: String, osName: String, home: File, environment: (String) -> String?): File {
    require(name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' }) {
        "The name of an application is one segment of a path: '$name'"
    }
    val os = osName.lowercase()
    val root = when {
        os.contains("mac") -> File(home, "Library/Application Support")
        os.startsWith("windows") -> environment("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(home, "AppData/Local")
        else -> environment("XDG_DATA_HOME")?.let(::File)?.takeIf { it.isAbsolute } ?: File(home, ".local/share")
    }
    return File(root, name)
}

/**
 * Stops the run when the data directory is given under the property of before the rename to
 * Pageboard, `drawview.dataDir`, and not under [PAGEBOARD_DATA_DIRECTORY_PROPERTY]: a command
 * written for the old name would otherwise open the directory of the application, where real notes
 * are.
 */
internal fun checkDataDirectoryProperty() {
    check(System.getProperty(OLD_DATA_DIRECTORY_PROPERTY) == null || System.getProperty(PAGEBOARD_DATA_DIRECTORY_PROPERTY) != null) {
        "$OLD_DATA_DIRECTORY_PROPERTY is not read any more: give the data directory with -D$PAGEBOARD_DATA_DIRECTORY_PROPERTY"
    }
}

private const val OLD_DATA_DIRECTORY_PROPERTY = "drawview.dataDir"

public actual object PlatformFiles {
    public actual fun resourcesDirectory(): String {
        val dir = File(PageboardDesktop.dataDirectory, DesktopDataLayout.RESOURCES)
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
