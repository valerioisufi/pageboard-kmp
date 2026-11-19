package io.github.valerioisufi.pageboard.export

/**
 * Reads the entries of a ZIP archive written by [ZipWriter]: stored (not compressed), read from
 * their local headers one after the other. An archive is a file of the user, maybe from somewhere
 * else: nothing is allocated before its bytes are there, every length is checked against what is
 * left, and an entry that is not stored, a name too long or more entries than [MAX_ENTRIES] fail
 * the reading ([ArchiveException]), never the application.
 */
internal object ZipReader {
    /** The entries an archive may hold. */
    const val MAX_ENTRIES: Int = 100_000

    /** The longest name of an entry, in bytes. */
    private const val MAX_NAME: Int = 1024

    private const val LOCAL_HEADER = 0x04034b50L
    private const val CENTRAL_HEADER = 0x02014b50L

    /** The entries of [bytes] by their names, in their order; the directories (names ending with `/`) left out. */
    fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        var at = 0
        while (at + 4 <= bytes.size) {
            val signature = intLe(bytes, at)
            if (signature == CENTRAL_HEADER) break
            if (signature != LOCAL_HEADER) throw ArchiveException(ArchiveException.Reason.NOT_AN_ARCHIVE, "No entry at $at")
            if (at + 30 > bytes.size) throw ArchiveException(ArchiveException.Reason.NOT_AN_ARCHIVE, "A header cut at $at")
            val flags = shortLe(bytes, at + 6)
            val method = shortLe(bytes, at + 8)
            val crc = intLe(bytes, at + 14)
            val compressed = intLe(bytes, at + 18)
            val size = intLe(bytes, at + 22)
            val nameLength = shortLe(bytes, at + 26)
            val extraLength = shortLe(bytes, at + 28)
            // Stored, the sizes in the header (no data descriptor after the data)
            if (method != 0 || flags and 0x08 != 0 || compressed != size) throw ArchiveException(ArchiveException.Reason.NOT_AN_ARCHIVE, "An entry not stored at $at")
            if (nameLength > MAX_NAME) throw ArchiveException(ArchiveException.Reason.NOT_AN_ARCHIVE, "A name of $nameLength bytes")
            val dataStart = at + 30L + nameLength + extraLength
            val dataEnd = dataStart + size
            if (dataEnd > bytes.size) throw ArchiveException(ArchiveException.Reason.NOT_AN_ARCHIVE, "An entry cut at $at")
            val name = bytes.decodeToString(at + 30, at + 30 + nameLength)
            if (!name.endsWith("/")) {
                val data = bytes.copyOfRange(dataStart.toInt(), dataEnd.toInt())
                if (Crc32.compute(data) != crc) throw ArchiveException(ArchiveException.Reason.NOT_AN_ARCHIVE, "The entry $name is damaged")
                entries[name] = data
                if (entries.size > MAX_ENTRIES) throw ArchiveException(ArchiveException.Reason.NOT_AN_ARCHIVE, "More than $MAX_ENTRIES entries")
            }
            at = dataEnd.toInt()
        }
        return entries
    }

    private fun shortLe(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun intLe(bytes: ByteArray, at: Int): Long =
        (shortLe(bytes, at).toLong()) or (shortLe(bytes, at + 2).toLong() shl 16)
}
