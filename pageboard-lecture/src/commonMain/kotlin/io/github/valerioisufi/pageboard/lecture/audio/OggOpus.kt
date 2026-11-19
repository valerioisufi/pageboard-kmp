package io.github.valerioisufi.pageboard.lecture.audio

import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.readIntLe
import kotlinx.io.readLongLe
import kotlinx.io.readUByte
import kotlinx.io.writeIntLe
import kotlinx.io.writeLongLe
import kotlinx.io.writeShortLe

/**
 * The Ogg container of the Opus recordings (RFC 7845), a standard `.opus` file: written page by
 * page as the recording goes ([OggOpusWriter], a page about every second, so that a recording
 * interrupted keeps all but its last second), read from any position ([OggOpusReader]).
 *
 * The positions of the Ogg pages ("granules") count samples at 48 kHz, whatever the rate of the
 * recording ([RATE_FACTOR]).
 */
internal object OggOpus {
    /** 48 kHz over the rate of the recordings. */
    const val RATE_FACTOR = 48_000 / LECTURE_SAMPLE_RATE

    /** Packets of a page (one second of 20 ms frames). */
    const val PACKETS_PER_PAGE = 50

    const val SERIAL = 0x6c656374 // "lect"

    /** The identification header of a mono stream recorded at [inputRate], skipping [preSkip] samples at 48 kHz. */
    fun head(preSkip: Int, inputRate: Int): ByteArray {
        val bytes = Buffer()
        bytes.write("OpusHead".encodeToByteArray())
        bytes.writeByte(1) // version
        bytes.writeByte(1) // channels
        bytes.writeShortLe(preSkip.toShort())
        bytes.writeIntLe(inputRate)
        bytes.writeShortLe(0) // gain
        bytes.writeByte(0) // mapping family
        return bytes.readByteArray()
    }

    fun tags(): ByteArray {
        val vendor = "DrawView".encodeToByteArray()
        val bytes = Buffer()
        bytes.write("OpusTags".encodeToByteArray())
        bytes.writeIntLe(vendor.size)
        bytes.write(vendor)
        bytes.writeIntLe(0) // comments
        return bytes.readByteArray()
    }

    /** An Ogg page of [packets] (none longer than a page holds) ending at [granule]. */
    fun page(packets: List<ByteArray>, granule: Long, sequence: Int, flags: Int): ByteArray {
        val lacing = Buffer()
        var segments = 0
        for (packet in packets) {
            var left = packet.size
            while (left >= 255) {
                lacing.writeByte(255.toByte())
                left -= 255
                segments++
            }
            lacing.writeByte(left.toByte())
            segments++
        }
        require(segments <= 255) { "too many packets for a page" }
        val page = Buffer()
        page.write("OggS".encodeToByteArray())
        page.writeByte(0) // version
        page.writeByte(flags.toByte())
        page.writeLongLe(granule)
        page.writeIntLe(SERIAL)
        page.writeIntLe(sequence)
        page.writeIntLe(0) // checksum, below
        page.writeByte(segments.toByte())
        page.write(lacing, lacing.size)
        packets.forEach { page.write(it) }
        val bytes = page.readByteArray()
        val crc = crc(bytes)
        for (i in 0 until 4) bytes[22 + i] = (crc ushr (8 * i)).toByte()
        return bytes
    }

    const val FLAG_FIRST = 2
    const val FLAG_LAST = 4

    private val crcTable = IntArray(256) { index ->
        var value = index shl 24
        repeat(8) { value = if (value and 0x80000000.toInt() != 0) (value shl 1) xor 0x04C11DB7 else value shl 1 }
        value
    }

    /** The checksum of an Ogg page (its checksum field at zero). */
    fun crc(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) crc = (crc shl 8) xor crcTable[((crc ushr 24) xor (byte.toInt() and 0xFF)) and 0xFF]
        return crc
    }
}

/**
 * Writes an Opus recording into [sink] as it goes: [append] the samples (mono, at the rate of the
 * lectures), [finish] at the end. Pages are written (and flushed) every second.
 */
internal class OggOpusWriter(private val sink: RawSink, bitrate: Int = BITRATE) {
    private val encoder = OpusEncoder(LECTURE_SAMPLE_RATE, bitrate)
    private val frameSamples = LECTURE_SAMPLE_RATE * OPUS_FRAME_MS / 1000
    private val frame = ShortArray(frameSamples)
    private var filled = 0
    private val packets = mutableListOf<ByteArray>()
    private var sequence = 0
    private val preSkip = encoder.lookahead * OggOpus.RATE_FACTOR

    /** The samples given, and the ones encoded (whole frames). */
    private var samples = 0L
    private var encodedFrames = 0L

    init {
        writePage(listOf(OggOpus.head(preSkip, LECTURE_SAMPLE_RATE)), 0, OggOpus.FLAG_FIRST)
        writePage(listOf(OggOpus.tags()), 0, 0)
    }

    fun append(block: ShortArray, count: Int = block.size) {
        var offset = 0
        samples += count
        while (offset < count) {
            val copied = minOf(frameSamples - filled, count - offset)
            block.copyInto(frame, filled, offset, offset + copied)
            filled += copied
            offset += copied
            if (filled == frameSamples) {
                packets.add(encoder.encode(frame))
                encodedFrames++
                filled = 0
                if (packets.size == OggOpus.PACKETS_PER_PAGE) flushPage(last = false)
            }
        }
    }

    /** Ends the recording: the last frame (completed with silence), the last page. */
    fun finish() {
        if (filled > 0) {
            frame.fill(0, filled)
            packets.add(encoder.encode(frame))
            encodedFrames++
            filled = 0
        }
        flushPage(last = true)
        encoder.close()
        sink.flush()
        sink.close()
    }

    private fun flushPage(last: Boolean) {
        if (packets.isEmpty() && !last) return
        // The last page ends where the samples end (not where the silence completing the frame does)
        val granule = preSkip + if (last) samples * OggOpus.RATE_FACTOR else encodedFrames * frameSamples * OggOpus.RATE_FACTOR
        writePage(packets.toList(), granule, if (last) OggOpus.FLAG_LAST else 0)
        packets.clear()
    }

    private fun writePage(packets: List<ByteArray>, granule: Long, flags: Int) {
        val page = OggOpus.page(packets, granule, sequence++, flags)
        sink.write(Buffer().apply { write(page) }, page.size.toLong())
        sink.flush()
    }

    companion object {
        /** Speech: about 11 MB per hour. */
        const val BITRATE = 24_000
    }
}

/** A page of an Ogg Opus file: where it is, the position of its end (granule), its packets. */
internal class OggPage(val offset: Long, val size: Long, val granule: Long, val flags: Int, val packets: List<ByteArray>)

/**
 * Reads an Ogg Opus recording: its pages ([pages], their packets only when `withPackets`), the
 * pre-skip of its stream ([preSkip]).
 */
internal object OggOpusReader {
    /**
     * The pages of [source] from its start (a page cut at the end of the file is left out: a
     * recording interrupted, or still being written).
     */
    fun pages(source: Source, withPackets: Boolean, from: Long = 0, onPage: (OggPage) -> Boolean) {
        var offset = from
        var pending: ByteArray? = null
        while (source.request(27)) {
            val magic = source.readByteArray(4)
            if (magic.decodeToString() != "OggS") return
            source.readByte() // version
            val flags = source.readByte().toInt() and 0xFF
            val granule = source.readLongLe()
            source.readIntLe() // serial
            source.readIntLe() // sequence
            source.readIntLe() // checksum
            val segments = source.readUByte().toInt()
            if (!source.request(segments.toLong())) return
            val lacing = IntArray(segments) { source.readUByte().toInt() }
            val dataSize = lacing.sum()
            if (!source.request(dataSize.toLong())) return
            val packets = mutableListOf<ByteArray>()
            if (withPackets) {
                var current = pending?.let { Buffer().apply { write(it) } } ?: Buffer()
                pending = null
                for ((i, value) in lacing.withIndex()) {
                    current.write(source.readByteArray(value))
                    if (value < 255) {
                        packets.add(current.readByteArray())
                        current = Buffer()
                    } else if (i == lacing.lastIndex) {
                        // Goes on in the next page
                        pending = current.readByteArray()
                    }
                }
            } else {
                source.skip(dataSize.toLong())
            }
            val page = OggPage(offset, 27L + segments + dataSize, granule, flags, packets)
            offset += page.size
            if (!onPage(page)) return
        }
    }

    /** The pre-skip (at 48 kHz) of the identification header [packet]. */
    fun preSkip(packet: ByteArray): Int = (packet[10].toInt() and 0xFF) or ((packet[11].toInt() and 0xFF) shl 8)
}
