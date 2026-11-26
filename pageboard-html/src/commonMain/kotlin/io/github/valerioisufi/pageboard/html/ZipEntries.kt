package io.github.valerioisufi.pageboard.html

/**
 * [input], raw deflated data (RFC 1951, no header), inflated to its [size] bytes; `null` when it
 * does not inflate to exactly that many.
 */
internal expect fun inflateRaw(input: ByteArray, size: Int): ByteArray?

/**
 * The entries of a ZIP archive (a Word document), read by its central directory: stored or
 * deflated. An archive is a file of the user, maybe from anywhere: every offset and length is
 * checked against the bytes there, an entry is inflated only when asked, to the size the
 * directory gives (at most [MAX_ENTRY] bytes, at most [MAX_RATIO] times its compressed size), and
 * what cannot be read fails the reading ([UnreadableArchive]), never the application.
 */
internal class ZipEntries private constructor(private val bytes: ByteArray, private val entries: Map<String, Entry>) {

    private class Entry(val method: Int, val compressed: Int, val size: Int, val headerAt: Int)

    /** The names of the entries, the directories left out. */
    val names: Set<String> get() = entries.keys

    /** The bytes of the entry [name], `null` when there is none. */
    fun read(name: String): ByteArray? {
        val entry = entries[name] ?: return null
        val at = entry.headerAt
        if (at < 0 || at + LOCAL_SIZE > bytes.size || intLe(at) != LOCAL_HEADER) throw UnreadableArchive("no local header for $name")
        val dataStart = at.toLong() + LOCAL_SIZE + shortLe(at + 26) + shortLe(at + 28)
        val dataEnd = dataStart + entry.compressed
        if (dataEnd > bytes.size) throw UnreadableArchive("$name cut")
        val data = bytes.copyOfRange(dataStart.toInt(), dataEnd.toInt())
        return when (entry.method) {
            STORED -> data.takeIf { it.size == entry.size } ?: throw UnreadableArchive("$name of another size")
            DEFLATED -> inflateRaw(data, entry.size) ?: throw UnreadableArchive("$name does not inflate")
            else -> throw UnreadableArchive("$name compressed by method ${entry.method}")
        }
    }

    private fun intLe(at: Int): Long = intLe(bytes, at)

    private fun shortLe(at: Int): Int = shortLe(bytes, at)

    /** An archive that cannot be read. */
    class UnreadableArchive(message: String) : Exception(message)

    companion object {
        /** The most entries an archive may hold. */
        const val MAX_ENTRIES = 10_000

        /** The largest entry inflated, in bytes. */
        const val MAX_ENTRY = 64 * 1024 * 1024

        /** How many times its compressed size an entry may inflate to. */
        const val MAX_RATIO = 1_000L

        private const val LOCAL_HEADER = 0x04034b50L
        private const val CENTRAL_HEADER = 0x02014b50L
        private const val END_OF_DIRECTORY = 0x06054b50L
        private const val LOCAL_SIZE = 30
        private const val CENTRAL_SIZE = 46
        private const val END_SIZE = 22
        private const val MAX_COMMENT = 0xFFFF
        private const val STORED = 0
        private const val DEFLATED = 8
        private const val ENCRYPTED = 1

        /** The entries of [bytes], read by its central directory. */
        fun of(bytes: ByteArray): ZipEntries {
            val end = endOfDirectory(bytes) ?: throw UnreadableArchive("not an archive")
            val count = shortLe(bytes, end + 10)
            val directorySize = intLe(bytes, end + 12)
            val directoryAt = intLe(bytes, end + 16)
            if (count > MAX_ENTRIES) throw UnreadableArchive("$count entries")
            if (directoryAt + directorySize > end) throw UnreadableArchive("a directory out of the archive")
            val entries = LinkedHashMap<String, Entry>()
            var at = directoryAt.toInt()
            repeat(count) {
                if (at + CENTRAL_SIZE > end || intLe(bytes, at) != CENTRAL_HEADER) throw UnreadableArchive("no entry at $at")
                val flags = shortLe(bytes, at + 8)
                val method = shortLe(bytes, at + 10)
                val compressed = intLe(bytes, at + 20)
                val size = intLe(bytes, at + 24)
                val nameLength = shortLe(bytes, at + 28)
                val extraLength = shortLe(bytes, at + 30)
                val commentLength = shortLe(bytes, at + 32)
                val headerAt = intLe(bytes, at + 42)
                val next = at.toLong() + CENTRAL_SIZE + nameLength + extraLength + commentLength
                if (next > end) throw UnreadableArchive("an entry cut at $at")
                if (flags and ENCRYPTED != 0) throw UnreadableArchive("an entry encrypted")
                // Of the sizes of ZIP64 (all ones), and those too large to inflate
                if (size > MAX_ENTRY || compressed > bytes.size || size > compressed.coerceAtLeast(1) * MAX_RATIO) throw UnreadableArchive("an entry of $size bytes from $compressed")
                val name = bytes.decodeToString(at + CENTRAL_SIZE, at + CENTRAL_SIZE + nameLength)
                if (!name.endsWith('/')) entries[name] = Entry(method, compressed.toInt(), size.toInt(), headerAt.toInt())
                at = next.toInt()
            }
            return ZipEntries(bytes, entries)
        }

        /** Where the record ending the central directory starts, looked for from the end (after its comment). */
        private fun endOfDirectory(bytes: ByteArray): Int? {
            val last = bytes.size - END_SIZE
            val first = (last - MAX_COMMENT).coerceAtLeast(0)
            for (at in last downTo first) {
                if (intLe(bytes, at) == END_OF_DIRECTORY) return at
            }
            return null
        }

        private fun intLe(bytes: ByteArray, at: Int): Long {
            if (at < 0 || at + 4 > bytes.size) return -1
            return (bytes[at].toLong() and 0xFF) or ((bytes[at + 1].toLong() and 0xFF) shl 8) or
                ((bytes[at + 2].toLong() and 0xFF) shl 16) or ((bytes[at + 3].toLong() and 0xFF) shl 24)
        }

        private fun shortLe(bytes: ByteArray, at: Int): Int {
            if (at < 0 || at + 2 > bytes.size) return 0
            return (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        }
    }
}
