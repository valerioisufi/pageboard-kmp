package io.github.valerioisufi.drawview.lecture.audio

import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readIntLe
import kotlinx.io.readShortLe

/**
 * The audio files of other applications: decoded into the audio of the lectures (an imported
 * recording) and encoded from it (an exported recording), with the codecs of the system.
 */
internal interface AudioCodec {
    /** Decodes [input] into [output] (raw 16-bit mono PCM at 16 kHz): the samples, -1 on error. */
    fun decode(input: String, output: String): Long

    /** Encodes [input] (a WAV) into [output], AAC in an m4a: whether it worked. */
    fun encodeM4a(input: String, output: String): Boolean
}

/** The codecs of this platform (Audio Toolbox on macOS), `null` elsewhere. */
internal expect fun audioCodec(): AudioCodec?

/** The audio of other applications this platform reads (the WAV files everywhere). */
internal fun importableExtensions(): Set<String> =
    if (audioCodec() != null) setOf("m4a", "mp4", "aac", "mp3", "wav", "aif", "aiff", "caf", "flac") else setOf("wav")

/**
 * Converts the audio file [input] into [output] (raw 16-bit mono PCM at 16 kHz), with the codecs
 * of the system where there are, otherwise as a WAV file ([WavDecoder]).
 *
 * @return the samples.
 * @throws IllegalArgumentException when the file cannot be read.
 */
internal fun decodeAudioFile(input: String, output: String): Long {
    audioCodec()?.let { codec ->
        val samples = codec.decode(input, output)
        if (samples >= 0) return samples
    }
    return SystemFileSystem.source(Path(input)).buffered().use { source ->
        SystemFileSystem.sink(Path(output)).buffered().use { sink ->
            WavDecoder.decode(source) { block -> sink.write(block.toLittleEndianBytes()) }
        }
    }
}

/**
 * Reads a WAV file (integer PCM of 8 to 32 bits or floats, any rate and channels) as the audio of
 * the lectures, block by block ([PcmResampler]).
 */
internal object WavDecoder {
    /** Decodes [source], giving [onBlock] the samples: their number. */
    fun decode(source: Source, onBlock: (ShortArray) -> Unit): Long {
        require(source.readByteArray(4).decodeToString() == "RIFF") { "not a WAV file" }
        source.skip(4)
        require(source.readByteArray(4).decodeToString() == "WAVE") { "not a WAV file" }
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        while (true) {
            require(source.request(8)) { "no audio in the WAV file" }
            val id = source.readByteArray(4).decodeToString()
            val size = source.readIntLe().toLong() and 0xFFFFFFFFL
            when (id) {
                "fmt " -> {
                    format = source.readShortLe().toInt() and 0xFFFF
                    channels = source.readShortLe().toInt()
                    rate = source.readIntLe()
                    source.skip(6)
                    bits = source.readShortLe().toInt()
                    var rest = size - 16
                    // WAVE_FORMAT_EXTENSIBLE: the actual format in the sub-format
                    if (format == 0xFFFE && rest >= 10) {
                        source.skip(8)
                        format = source.readShortLe().toInt() and 0xFFFF
                        rest -= 10
                    }
                    source.skip(rest + (size and 1))
                }
                "data" -> {
                    require(channels > 0 && rate > 0) { "WAV file without its format" }
                    require((format == 1 && bits in setOf(8, 16, 24, 32)) || (format == 3 && bits == 32)) { "unsupported WAV format ($format, $bits bits)" }
                    return decodeData(source, size, format, channels, rate, bits, onBlock)
                }
                else -> source.skip(size + (size and 1))
            }
        }
    }

    private fun decodeData(source: Source, size: Long, format: Int, channels: Int, rate: Int, bits: Int, onBlock: (ShortArray) -> Unit): Long {
        val resampler = PcmResampler(rate, channels)
        val frameBytes = channels * bits / 8
        val blockFrames = 16_384
        var remaining = size - size % frameBytes
        var total = 0L
        val buffer = ByteArray(blockFrames * frameBytes)
        while (remaining > 0) {
            // A block of whole frames (a file cut short ends where it ends)
            var filled = 0
            val wanted = minOf(remaining, buffer.size.toLong()).toInt()
            while (filled < wanted) {
                val read = source.readAtMostTo(buffer, filled, wanted)
                if (read <= 0) break
                filled += read
            }
            val frames = filled / frameBytes
            if (frames == 0) break
            remaining -= filled
            val bytes = buffer.copyOf(frames * frameBytes)
            val samples = ShortArray(bytes.size / (bits / 8)) { i -> sample(bytes, i * bits / 8, format, bits) }
            val block = resampler.process(samples)
            total += block.size
            if (block.isNotEmpty()) onBlock(block)
        }
        return total
    }

    private fun sample(bytes: ByteArray, offset: Int, format: Int, bits: Int): Short {
        fun byte(i: Int) = bytes[offset + i].toInt() and 0xFF
        return when {
            format == 3 -> (Float.fromBits(byte(0) or (byte(1) shl 8) or (byte(2) shl 16) or (byte(3) shl 24)) * 32767f)
                .coerceIn(-32768f, 32767f).toInt().toShort()
            bits == 8 -> ((byte(0) - 128) shl 8).toShort()
            bits == 16 -> (byte(0) or (byte(1) shl 8)).toShort()
            // The two most significant bytes
            bits == 24 -> (byte(1) or (byte(2) shl 8)).toShort()
            else -> (byte(2) or (byte(3) shl 8)).toShort()
        }
    }
}

/** Whether this platform encodes the recordings as m4a. */
internal fun canEncodeM4a(): Boolean = audioCodec() != null

/**
 * Saves [recording] in [target]: AAC in an m4a where the platform encodes it (the extension of
 * [target] becomes .m4a), otherwise its WAV file (.wav).
 *
 * @return the path written.
 */
internal fun exportRecording(recording: RecordingFile, target: String): String {
    val wav = recording.wavPath
    require(SystemFileSystem.exists(wav)) { "the recording is not finished" }
    val codec = audioCodec()
    val base = target.substringBeforeLast('.').takeIf { target.substringAfterLast('/').contains('.') } ?: target
    if (codec != null) {
        val m4a = "$base.m4a"
        check(codec.encodeM4a(wav.toString(), m4a)) { "the audio cannot be encoded" }
        return m4a
    }
    val copy = "$base.wav"
    SystemFileSystem.source(wav).buffered().use { source ->
        SystemFileSystem.sink(Path(copy)).buffered().use { sink -> source.transferTo(sink) }
    }
    return copy
}
