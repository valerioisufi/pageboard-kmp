package io.github.valerioisufi.pageboard.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

public actual object SecureStore {
    private val directory: File get() = File(drawViewDesktopDataDirectory, "secure").apply { mkdirs() }

    public actual fun read(name: String): String? = try {
        File(directory, fileName(name)).takeIf { it.exists() }?.readText()
    } catch (_: Exception) {
        null
    }

    public actual fun write(name: String, value: String?): Boolean = try {
        val file = File(directory, fileName(name))
        if (value == null) {
            file.delete()
        } else {
            file.writeText(value)
            // Readable by its user only (where the file system has the permissions)
            runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun fileName(name: String) = name.map { if (it.isLetterOrDigit() || it in "-_.") it else '_' }.joinToString("")
}
