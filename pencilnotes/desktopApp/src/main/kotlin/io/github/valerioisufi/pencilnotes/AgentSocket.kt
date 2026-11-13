package io.github.valerioisufi.pencilnotes

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/**
 * Where the application of a data directory listens to its clients: a Unix domain socket in a directory
 * only its user can enter, so that the system keeps everybody else out. Beside the data, or, where its
 * path would be too long for a socket, in a directory of the temporary directory named after the data
 * directory.
 */
internal object AgentSocket {
    private const val SOCKET_NAME = "agent.sock"

    /** Fewer bytes than the system allows in the path of a socket (104 on macOS, 108 on Linux). */
    internal const val MAX_PATH_BYTES = 100

    /** The socket of the data directory [dataDirectory]. */
    fun fileOf(dataDirectory: File): File {
        val beside = File(File(dataDirectory.absoluteFile, "agent"), SOCKET_NAME)
        if (beside.path.toByteArray(StandardCharsets.UTF_8).size <= MAX_PATH_BYTES) return beside
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(dataDirectory.absolutePath.toByteArray(StandardCharsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return File(File(System.getProperty("java.io.tmpdir"), "pencilnotes-$hash"), SOCKET_NAME)
    }

    /** The address of [socket]. */
    fun addressOf(socket: File): UnixDomainSocketAddress = UnixDomainSocketAddress.of(socket.toPath())

    /**
     * Makes the directory of [socket] ready, entered by this user only.
     *
     * @throws IOException when it cannot be trusted: e.g. someone else made it in the temporary directory.
     */
    fun prepareDirectory(socket: File) {
        val directory = socket.parentFile.toPath()
        val posix = directory.fileSystem.supportedFileAttributeViews().contains("posix")
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (posix) {
                Files.createDirectories(directory.parent)
                Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            } else {
                Files.createDirectories(directory)
            }
            return
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw IOException("$directory is not a directory")
        if (posix) {
            val user = directory.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
            if (Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS) != user) throw IOException("$directory belongs to someone else")
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        }
    }
}

/** A line longer than the bound given to [BoundedLineReader]: the connection cannot go on. */
internal class LineTooLongException : IOException("The line is too long")

/**
 * The lines of [input] in UTF-8, each of at most [maxBytes]: a line is never held beyond its bound,
 * whatever the other side sends.
 */
internal class BoundedLineReader(private val input: InputStream, private val maxBytes: Int) {
    private val buffer = ByteArray(16 * 1024)
    private var position = 0
    private var limit = 0

    /** The next line, or `null` at the end of the input. */
    fun readLine(): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            if (position == limit) {
                limit = input.read(buffer)
                position = 0
                if (limit <= 0) {
                    limit = 0
                    if (line.size() == 0) return null
                    throw EOFException("The input ended within a line")
                }
            }
            val start = position
            while (position < limit && buffer[position] != NEWLINE) position++
            if (line.size() + (position - start) > maxBytes) throw LineTooLongException()
            line.write(buffer, start, position - start)
            if (position < limit) {
                position++
                return line.toString(StandardCharsets.UTF_8)
            }
        }
    }

    private companion object {
        const val NEWLINE = '\n'.code.toByte()
    }
}

/** Writes [line] and a line feed to this socket, in UTF-8, all of it. */
internal fun SocketChannel.writeLine(line: String) {
    val bytes = ByteBuffer.wrap((line + "\n").toByteArray(StandardCharsets.UTF_8))
    while (bytes.hasRemaining()) write(bytes)
}
