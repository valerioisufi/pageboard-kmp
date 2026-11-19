package io.github.valerioisufi.drawview.lecture.transcription

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

/** [EncoderArchive] with the zip and the digests of the JDK. */
internal object JvmEncoderArchive : EncoderArchive {
    override fun sha256(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun extract(zip: String, directory: String, name: String, maxBytes: Long) {
        val parent = File(directory).canonicalFile
        val target = File(parent, name)
        val partial = File(parent, "$name.part")
        partial.deleteRecursively()
        try {
            partial.mkdirs()
            val root = partial.canonicalFile
            var extracted = 0L
            ZipFile(zip).use { file ->
                for (entry in file.entries()) {
                    val path = entry.name.replace('\\', '/')
                    // The resource forks that the zip of a Mac adds
                    if (path.startsWith("__MACOSX/")) continue
                    if (path != "$name/" && !path.startsWith("$name/")) throw IOException("unexpected entry $path in the encoder")
                    val inside = path.removePrefix("$name/")
                    if (inside.isEmpty()) continue
                    val out = File(root, inside).canonicalFile
                    if (!out.toPath().startsWith(root.toPath())) throw IOException("entry $path goes out of the encoder")
                    if (entry.isDirectory) {
                        out.mkdirs()
                        continue
                    }
                    out.parentFile.mkdirs()
                    file.getInputStream(entry).use { input ->
                        out.outputStream().buffered(BUFFER_SIZE).use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                extracted += read
                                if (extracted > maxBytes) throw IOException("the encoder is larger than $maxBytes bytes")
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
            }
            if (!File(root, "coremldata.bin").isFile) throw IOException("no Core ML model in the encoder")
            target.deleteRecursively()
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Throwable) {
            partial.deleteRecursively()
            throw e
        }
    }

    private const val BUFFER_SIZE = 256 * 1024
}

internal actual fun encoderArchive(): EncoderArchive? {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val appleSilicon = (os.contains("mac") || os.contains("darwin")) && (arch.contains("aarch64") || arch.contains("arm64"))
    return JvmEncoderArchive.takeIf { appleSilicon && WhisperNative.isAvailable }
}
