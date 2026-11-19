package io.github.valerioisufi.pageboard.lecture.audio

import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * Writes AAC-LC mono frames into the m4a file [target] (MP4, ISO/IEC 14496-12 and 14), the same on
 * every platform (the frames of [AacEncoder]): the frames go to a file beside it as they come,
 * then [finish] writes the m4a, its description (`moov`) first so that it plays as it downloads.
 */
internal class M4aWriter(private val target: Path, private val sampleRate: Int, private val bitrate: Int) {
    private val framesPath = Path("$target.frames")
    private val frames = SystemFileSystem.sink(framesPath).buffered()
    private val sizes = mutableListOf<Int>()
    private var dataBytes = 0L

    /** Adds a frame (of [AAC_FRAME_SAMPLES] samples). */
    fun add(frame: ByteArray) {
        if (frame.isEmpty()) return
        frames.write(frame)
        sizes.add(frame.size)
        dataBytes += frame.size
    }

    /** Writes the m4a. */
    fun finish() {
        frames.close()
        try {
            val ftyp = box("ftyp", "M4A ".encodeToByteArray() + int32(0) + "M4A mp42isom".encodeToByteArray())
            // The size of moov does not depend on where the frames are (co64)
            val moovSize = moov(0).size
            val moov = moov(ftyp.size.toLong() + moovSize + MDAT_HEADER)
            SystemFileSystem.sink(target).buffered().use { sink ->
                sink.write(ftyp)
                sink.write(moov)
                // A large mdat (its size in 64 bits)
                sink.write(int32(1) + "mdat".encodeToByteArray() + int64(MDAT_HEADER + dataBytes))
                SystemFileSystem.source(framesPath).buffered().use { it.transferTo(sink) }
            }
        } finally {
            SystemFileSystem.delete(framesPath, mustExist = false)
        }
    }

    /** Leaves the m4a unwritten. */
    fun cancel() {
        frames.close()
        SystemFileSystem.delete(framesPath, mustExist = false)
    }

    private fun moov(firstFrame: Long): ByteArray {
        val samples = sizes.size.toLong() * AAC_FRAME_SAMPLES
        val durationMs = samples * 1000 / sampleRate
        val mvhd = fullBox("mvhd", 0, int32(0) + int32(0) + int32(1000) + int32(durationMs.toInt()) +
            int32(0x00010000) + int16(0x0100) + ByteArray(10) + matrix() + ByteArray(24) + int32(2))
        val tkhd = fullBox("tkhd", 3, int32(0) + int32(0) + int32(1) + int32(0) + int32(durationMs.toInt()) +
            ByteArray(8) + int16(0) + int16(0) + int16(0x0100) + int16(0) + matrix() + int32(0) + int32(0))
        val mdhd = fullBox("mdhd", 0, int32(0) + int32(0) + int32(sampleRate) + int32(samples.toInt()) + int16(0x55C4) + int16(0))
        val hdlr = fullBox("hdlr", 0, int32(0) + "soun".encodeToByteArray() + ByteArray(12) + "SoundHandler".encodeToByteArray() + byteArrayOf(0))
        val smhd = fullBox("smhd", 0, int16(0) + int16(0))
        val dref = fullBox("dref", 0, int32(1) + fullBox("url ", 1, ByteArray(0)))
        val dinf = box("dinf", dref)
        val stbl = box(
            "stbl",
            stsd() +
                fullBox("stts", 0, int32(1) + int32(sizes.size) + int32(AAC_FRAME_SAMPLES)) +
                fullBox("stsc", 0, int32(1) + int32(1) + int32(sizes.size) + int32(1)) +
                fullBox("stsz", 0, int32(0) + int32(sizes.size) + sizes.fold(ByteArray(0)) { all, size -> all + int32(size) }) +
                fullBox("co64", 0, int32(1) + int64(firstFrame))
        )
        val minf = box("minf", smhd + dinf + stbl)
        val mdia = box("mdia", mdhd + hdlr + minf)
        val trak = box("trak", tkhd + mdia)
        return box("moov", mvhd + trak)
    }

    /** The description of the samples: AAC-LC, mono, with its AudioSpecificConfig. */
    private fun stsd(): ByteArray {
        val config = audioSpecificConfig()
        val decoderSpecific = descriptor(0x05, config)
        val decoderConfig = descriptor(
            0x04,
            byteArrayOf(0x40, 0x15) + byteArrayOf(0, 0x18, 0) + int32(bitrate) + int32(bitrate) + decoderSpecific
        )
        val slConfig = descriptor(0x06, byteArrayOf(0x02))
        val esds = fullBox("esds", 0, descriptor(0x03, int16(1) + byteArrayOf(0) + decoderConfig + slConfig))
        val mp4a = box("mp4a", ByteArray(6) + int16(1) + ByteArray(8) + int16(1) + int16(16) + int16(0) + int16(0) + int32(sampleRate shl 16) + esds)
        return fullBox("stsd", 0, int32(1) + mp4a)
    }

    /** AAC-LC (2), the index of the sample rate, one channel. */
    private fun audioSpecificConfig(): ByteArray {
        val rateIndex = RATES.indexOf(sampleRate).takeIf { it >= 0 } ?: error("unsupported sample rate $sampleRate")
        val value = (2 shl 11) or (rateIndex shl 7) or (1 shl 3)
        return byteArrayOf((value shr 8).toByte(), value.toByte())
    }

    private fun descriptor(tag: Int, content: ByteArray): ByteArray {
        // The size in 4 bytes of 7 bits each
        val size = content.size
        return byteArrayOf(
            tag.toByte(),
            (0x80 or (size shr 21 and 0x7F)).toByte(),
            (0x80 or (size shr 14 and 0x7F)).toByte(),
            (0x80 or (size shr 7 and 0x7F)).toByte(),
            (size and 0x7F).toByte()
        ) + content
    }

    private fun matrix(): ByteArray = int32(0x00010000) + int32(0) + int32(0) + int32(0) + int32(0x00010000) + int32(0) + int32(0) + int32(0) + int32(0x40000000)

    private companion object {
        const val MDAT_HEADER = 16L
        val RATES = listOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000)

        fun box(type: String, content: ByteArray): ByteArray = int32(8 + content.size) + type.encodeToByteArray() + content

        fun fullBox(type: String, flags: Int, content: ByteArray): ByteArray = box(type, int32(flags) + content)

        fun int16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

        fun int32(value: Int) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

        fun int64(value: Long) = Buffer().apply { writeLong(value) }.readByteArray()
    }
}
