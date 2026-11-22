package io.github.valerioisufi.pageboard.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Loads the JNI libraries bundled as resources of the desktop (JVM) artifacts.
 *
 * The libraries are stored under `/native/<platform>/` (built by `./gradlew buildDesktopNativeLibs`,
 * see `native/scripts/build_desktop.sh`) and extracted into a private temporary directory before being loaded, since the JVM cannot
 * load a shared library from inside a jar.
 */
@InternalPageboardApi
public object JvmNativeLibraries {

    /** Resource folder name of the running platform, e.g. `darwin-aarch64`. */
    internal val platformFolder: String by lazy {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val isArm = arch.contains("aarch64") || arch.contains("arm64")
        when {
            os.contains("mac") || os.contains("darwin") -> if (isArm) "darwin-aarch64" else "darwin-x86_64"
            os.contains("win") -> if (isArm) "windows-aarch64" else "windows-x86_64"
            else -> if (isArm) "linux-aarch64" else "linux-x86_64"
        }
    }

    /** Maps a library name (e.g. `ink_bridge`) to its platform file name (e.g. `libink_bridge.dylib`). */
    internal fun mappedName(name: String): String = System.mapLibraryName(name).let {
        // System.mapLibraryName returns .jnilib on some older macOS JDKs
        if (it.endsWith(".jnilib")) it.removeSuffix(".jnilib") + ".dylib" else it
    }

    private val extractionDir: File by lazy {
        Files.createTempDirectory("pageboard_native").toFile().apply { deleteOnExit() }
    }

    private val loaded = mutableSetOf<String>()

    /**
     * Loads [names] in order (dependencies first) from the resources of [anchor]'s class loader,
     * falling back to `java.library.path` and to the local CMake build directory.
     *
     * @return `true` when every library has been loaded.
     */
    @Synchronized
    public fun load(anchor: Class<*>, vararg names: String): Boolean {
        for (name in names) {
            if (name in loaded) continue
            if (!loadOne(anchor, name)) return false
            loaded.add(name)
        }
        return true
    }

    private fun loadOne(anchor: Class<*>, name: String): Boolean {
        val fileName = mappedName(name)

        // 1. Bundled resource
        val resourcePath = "/native/$platformFolder/$fileName"
        anchor.getResourceAsStream(resourcePath)?.use { stream ->
            try {
                val target = File(extractionDir, fileName)
                Files.copy(stream, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                target.deleteOnExit()
                System.load(target.absolutePath)
                return true
            } catch (e: Throwable) {
                System.err.println("Pageboard: failed to load bundled $fileName: ${e.message}")
            }
        }

        // 2. java.library.path
        try {
            System.loadLibrary(name)
            return true
        } catch (_: Throwable) {
        }

        // 3. Local development build (native/scripts/build_desktop.sh)
        val buildDir = "native/build/desktop-$platformFolder"
        val candidates = listOf(buildDir, "../$buildDir", "../../$buildDir")
        for (dir in candidates) {
            val file = File(dir, fileName)
            if (file.exists()) {
                try {
                    System.load(file.absolutePath)
                    return true
                } catch (e: Throwable) {
                    System.err.println("Pageboard: failed to load ${file.absolutePath}: ${e.message}")
                }
            }
        }

        System.err.println("Pageboard: native library '$name' not available for $platformFolder")
        return false
    }
}
