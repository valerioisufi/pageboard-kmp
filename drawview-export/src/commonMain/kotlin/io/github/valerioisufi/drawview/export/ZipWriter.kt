package io.github.valerioisufi.drawview.export

/**
 * Standard IEEE 802.3 CRC-32 calculation for ZIP archives.
 */
internal object Crc32 {
    private val table = IntArray(256) { i ->
        var c = i
        for (k in 0..7) {
            c = if ((c and 1) != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1
        }
        c
    }

    fun compute(bytes: ByteArray): Long {
        var crc = -1
        for (b in bytes) {
            val index = (crc xor b.toInt()) and 0xFF
            crc = (crc ushr 8) xor table[index]
        }
        return (crc.toLong() xor -1L) and 0xFFFFFFFFL
    }
}

/**
 * A growable byte buffer for constructing binary archives.
 */
internal class ByteOutput(initialCapacity: Int = 4096) {
    private var buffer = ByteArray(initialCapacity)
    var size: Int = 0
        private set

    fun writeByte(b: Int) {
        ensureCapacity(size + 1)
        buffer[size++] = b.toByte()
    }

    fun writeShortLe(v: Int) {
        ensureCapacity(size + 2)
        buffer[size++] = (v and 0xFF).toByte()
        buffer[size++] = ((v ushr 8) and 0xFF).toByte()
    }

    fun writeIntLe(v: Long) {
        ensureCapacity(size + 4)
        buffer[size++] = (v and 0xFF).toByte()
        buffer[size++] = ((v ushr 8) and 0xFF).toByte()
        buffer[size++] = ((v ushr 16) and 0xFF).toByte()
        buffer[size++] = ((v ushr 24) and 0xFF).toByte()
    }

    fun writeBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        ensureCapacity(size + length)
        bytes.copyInto(buffer, destinationOffset = size, startIndex = offset, endIndex = offset + length)
        size += length
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun ensureCapacity(minCapacity: Int) {
        if (minCapacity > buffer.size) {
            var newCap = buffer.size * 2
            if (newCap < minCapacity) newCap = minCapacity
            buffer = buffer.copyOf(newCap)
        }
    }
}

/**
 * Minimal zero-dependency ZIP archive writer using the standard uncompressed (STORE) method.
 *
 * Each entry is written with UTF-8 filename flags and valid PKWARE local headers, central directory
 * headers, and end-of-central-directory record.
 */
internal class ZipWriter {
    private val output = ByteOutput()
    private val entries = mutableListOf<EntryInfo>()

    private class EntryInfo(
        val name: String,
        val crc32: Long,
        val size: Long,
        val offset: Long
    )

    /**
     * Adds an entry with [name] (normalized to forward slashes) and raw [data].
     */
    fun addEntry(name: String, data: ByteArray) {
        val normalizedName = name.replace('\\', '/').trimStart('/')
        val nameBytes = normalizedName.encodeToByteArray()
        val crc32 = Crc32.compute(data)
        val offset = output.size.toLong()

        // Local file header (30 bytes + name + data)
        output.writeIntLe(0x04034b50L) // Local file header signature
        output.writeShortLe(20) // Version needed to extract: 2.0
        output.writeShortLe(0x0800) // General purpose bit flag: UTF-8 filenames
        output.writeShortLe(0) // Compression method: 0 = STORE
        output.writeShortLe(0) // Last mod file time
        output.writeShortLe(0x21) // Last mod file date (1980-01-01)
        output.writeIntLe(crc32) // CRC-32
        output.writeIntLe(data.size.toLong()) // Compressed size
        output.writeIntLe(data.size.toLong()) // Uncompressed size
        output.writeShortLe(nameBytes.size) // File name length
        output.writeShortLe(0) // Extra field length
        output.writeBytes(nameBytes)
        output.writeBytes(data)

        entries.add(EntryInfo(normalizedName, crc32, data.size.toLong(), offset))
    }

    /**
     * Finalizes the central directory and returns the full ZIP archive bytes.
     */
    fun toByteArray(): ByteArray {
        val cdStartOffset = output.size.toLong()

        for (entry in entries) {
            val nameBytes = entry.name.encodeToByteArray()
            // Central directory header (46 bytes + name)
            output.writeIntLe(0x02014b50L) // Central directory header signature
            output.writeShortLe(20) // Version made by: 2.0
            output.writeShortLe(20) // Version needed to extract: 2.0
            output.writeShortLe(0x0800) // General purpose bit flag: UTF-8
            output.writeShortLe(0) // Compression method: 0 = STORE
            output.writeShortLe(0) // Last mod file time
            output.writeShortLe(0x21) // Last mod file date
            output.writeIntLe(entry.crc32) // CRC-32
            output.writeIntLe(entry.size) // Compressed size
            output.writeIntLe(entry.size) // Uncompressed size
            output.writeShortLe(nameBytes.size) // File name length
            output.writeShortLe(0) // Extra field length
            output.writeShortLe(0) // File comment length
            output.writeShortLe(0) // Disk number start
            output.writeShortLe(0) // Internal file attributes
            output.writeIntLe(0L) // External file attributes
            output.writeIntLe(entry.offset) // Relative offset of local header
            output.writeBytes(nameBytes)
        }

        val cdSize = output.size.toLong() - cdStartOffset

        // End of central directory record (22 bytes)
        output.writeIntLe(0x06054b50L) // EOCD signature
        output.writeShortLe(0) // Number of this disk
        output.writeShortLe(0) // Disk where central directory starts
        output.writeShortLe(entries.size) // Number of CD records on this disk
        output.writeShortLe(entries.size) // Total number of CD records
        output.writeIntLe(cdSize) // Size of central directory
        output.writeIntLe(cdStartOffset) // Offset of start of central directory
        output.writeShortLe(0) // ZIP file comment length

        return output.toByteArray()
    }
}
