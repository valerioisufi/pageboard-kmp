package io.github.valerioisufi.pageboard.export

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

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

    fun writeLongLe(v: Long) {
        writeIntLe(v and 0xFFFFFFFFL)
        writeIntLe((v ushr 32) and 0xFFFFFFFFL)
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
 * The dates of an entry of a ZIP archive: its last change [modifiedAt] and its creation
 * [createdAt], in milliseconds since the epoch (UTC).
 */
internal class ZipEntryTimes(val modifiedAt: Long, val createdAt: Long = modifiedAt)

/**
 * Minimal zero-dependency ZIP archive writer using the standard uncompressed (STORE) method.
 *
 * Each entry is written with UTF-8 filename flags and valid PKWARE local headers, central directory
 * headers, and end-of-central-directory record. An entry given its [ZipEntryTimes] is dated three
 * ways, so that every tool keeps the dates (see `docs/plans/export-metadata.md`): the DOS date and time
 * in [timeZone], the extended timestamp (`0x5455`, UTC seconds) and the NTFS times (`0x000A`, with
 * the creation); an entry without them is dated 1980-01-01 00:00 with no extra field.
 */
internal class ZipWriter(private val timeZone: TimeZone = TimeZone.currentSystemDefault()) {
    private val output = ByteOutput()
    private val entries = mutableListOf<EntryInfo>()

    private class EntryInfo(
        val name: String,
        val crc32: Long,
        val size: Long,
        val offset: Long,
        val dosTime: Int,
        val dosDate: Int,
        val extra: ByteArray,
        val externalAttributes: Long
    )

    /**
     * Adds an entry with [name] (normalized to forward slashes) and raw [data], dated by [times].
     */
    fun addEntry(name: String, data: ByteArray, times: ZipEntryTimes? = null) {
        add(name.replace('\\', '/').trimStart('/'), data, times, externalAttributes = 0L)
    }

    /**
     * Adds the directory [name] (normalized to forward slashes, ending with one), dated by [times].
     */
    fun addDirectory(name: String, times: ZipEntryTimes? = null) {
        val normalizedName = name.replace('\\', '/').trim('/') + "/"
        add(normalizedName, ByteArray(0), times, externalAttributes = MS_DOS_DIRECTORY)
    }

    private fun add(normalizedName: String, data: ByteArray, times: ZipEntryTimes?, externalAttributes: Long) {
        val nameBytes = normalizedName.encodeToByteArray()
        val crc32 = Crc32.compute(data)
        val offset = output.size.toLong()
        val (dosTime, dosDate) = times?.let { dosDateTime(it.modifiedAt) } ?: (0 to DOS_EPOCH_DATE)
        val extra = times?.let(::extraFields) ?: ByteArray(0)

        // Local file header (30 bytes + name + extra + data)
        output.writeIntLe(0x04034b50L) // Local file header signature
        output.writeShortLe(20) // Version needed to extract: 2.0
        output.writeShortLe(0x0800) // General purpose bit flag: UTF-8 filenames
        output.writeShortLe(0) // Compression method: 0 = STORE
        output.writeShortLe(dosTime) // Last mod file time
        output.writeShortLe(dosDate) // Last mod file date
        output.writeIntLe(crc32) // CRC-32
        output.writeIntLe(data.size.toLong()) // Compressed size
        output.writeIntLe(data.size.toLong()) // Uncompressed size
        output.writeShortLe(nameBytes.size) // File name length
        output.writeShortLe(extra.size) // Extra field length
        output.writeBytes(nameBytes)
        output.writeBytes(extra)
        output.writeBytes(data)

        entries.add(EntryInfo(normalizedName, crc32, data.size.toLong(), offset, dosTime, dosDate, extra, externalAttributes))
    }

    /**
     * Finalizes the central directory and returns the full ZIP archive bytes.
     */
    fun toByteArray(): ByteArray {
        val cdStartOffset = output.size.toLong()

        for (entry in entries) {
            val nameBytes = entry.name.encodeToByteArray()
            // Central directory header (46 bytes + name + extra)
            output.writeIntLe(0x02014b50L) // Central directory header signature
            output.writeShortLe(20) // Version made by: 2.0
            output.writeShortLe(20) // Version needed to extract: 2.0
            output.writeShortLe(0x0800) // General purpose bit flag: UTF-8
            output.writeShortLe(0) // Compression method: 0 = STORE
            output.writeShortLe(entry.dosTime) // Last mod file time
            output.writeShortLe(entry.dosDate) // Last mod file date
            output.writeIntLe(entry.crc32) // CRC-32
            output.writeIntLe(entry.size) // Compressed size
            output.writeIntLe(entry.size) // Uncompressed size
            output.writeShortLe(nameBytes.size) // File name length
            output.writeShortLe(entry.extra.size) // Extra field length
            output.writeShortLe(0) // File comment length
            output.writeShortLe(0) // Disk number start
            output.writeShortLe(0) // Internal file attributes
            output.writeIntLe(entry.externalAttributes) // External file attributes
            output.writeIntLe(entry.offset) // Relative offset of local header
            output.writeBytes(nameBytes)
            output.writeBytes(entry.extra)
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

    /** The DOS time and date of [epochMs] in [timeZone], held within the years DOS can tell. */
    @OptIn(ExperimentalTime::class)
    private fun dosDateTime(epochMs: Long): Pair<Int, Int> {
        val dateTime = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone)
        return when {
            dateTime.year < 1980 -> 0 to DOS_EPOCH_DATE
            dateTime.year > 2107 -> DOS_LAST_TIME to DOS_LAST_DATE
            else -> {
                val time = (dateTime.hour shl 11) or (dateTime.minute shl 5) or (dateTime.second / 2)
                val date = ((dateTime.year - 1980) shl 9) or ((dateTime.month.ordinal + 1) shl 5) or dateTime.day
                time to date
            }
        }
    }

    /** The extended timestamp (the change) and the NTFS times (change, access, creation) of [times]. */
    private fun extraFields(times: ZipEntryTimes): ByteArray {
        val extra = ByteOutput(EXTRA_SIZE)
        // Extended timestamp: flags (bit 0, the modification) and the UTC seconds
        extra.writeShortLe(0x5455)
        extra.writeShortLe(5)
        extra.writeByte(0x01)
        extra.writeIntLe(unixSeconds(times.modifiedAt))
        // NTFS: reserved, then attribute 1 with the three FILETIMEs
        extra.writeShortLe(0x000A)
        extra.writeShortLe(32)
        extra.writeIntLe(0L)
        extra.writeShortLe(0x0001)
        extra.writeShortLe(24)
        extra.writeLongLe(fileTime(times.modifiedAt))
        extra.writeLongLe(fileTime(times.modifiedAt))
        extra.writeLongLe(fileTime(times.createdAt))
        return extra.toByteArray()
    }

    private companion object {
        /** 1980-01-01, the first DOS date. */
        const val DOS_EPOCH_DATE = 0x21

        /** 2107-12-31 23:59:58, the last DOS date and time. */
        const val DOS_LAST_DATE = (127 shl 9) or (12 shl 5) or 31
        const val DOS_LAST_TIME = (23 shl 11) or (59 shl 5) or 29

        /** The MS-DOS attribute of a directory. */
        const val MS_DOS_DIRECTORY = 0x10L

        /** The bytes of the two extra fields: 4 + 5 and 4 + 32. */
        const val EXTRA_SIZE = 45

        /** Milliseconds from 1601-01-01 (the FILETIME epoch) to 1970-01-01. */
        const val FILETIME_EPOCH_OFFSET_MS = 11_644_473_600_000L

        /** The seconds of [epochMs] as the signed 32 bits of the extended timestamp hold them. */
        fun unixSeconds(epochMs: Long): Long =
            (epochMs.floorDiv(1000L)).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()) and 0xFFFFFFFFL

        /** [epochMs] in hundreds of nanoseconds from 1601-01-01 (never before it). */
        fun fileTime(epochMs: Long): Long = (epochMs + FILETIME_EPOCH_OFFSET_MS).coerceAtLeast(0L) * 10_000L
    }
}
