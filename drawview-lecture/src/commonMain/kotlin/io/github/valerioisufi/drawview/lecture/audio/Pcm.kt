package io.github.valerioisufi.drawview.lecture.audio

import kotlin.math.sqrt

/**
 * Sample rate of the lecture recordings: 16-bit mono PCM at 16 kHz is enough for speech (Gemini
 * downsamples every audio input to 16 kHz anyway) and takes 115 MB per hour.
 */
const val LECTURE_SAMPLE_RATE = 16_000

/** Bytes per second of the lecture recordings (16-bit mono). */
const val LECTURE_BYTES_PER_SECOND = LECTURE_SAMPLE_RATE * 2

fun samplesToMs(samples: Long, sampleRate: Int = LECTURE_SAMPLE_RATE): Long = samples * 1000 / sampleRate

fun msToSamples(ms: Long, sampleRate: Int = LECTURE_SAMPLE_RATE): Long = ms * sampleRate / 1000

/**
 * WAV (RIFF) container of 16-bit mono PCM.
 */
object Wav {
    const val HEADER_SIZE = 44

    /** Header of a WAV file holding [dataBytes] bytes of 16-bit mono PCM. */
    fun header(dataBytes: Long, sampleRate: Int = LECTURE_SAMPLE_RATE): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        fun ascii(offset: Int, value: String) = value.forEachIndexed { i, c -> header[offset + i] = c.code.toByte() }
        fun int32(offset: Int, value: Long) {
            for (i in 0 until 4) header[offset + i] = (value shr (8 * i) and 0xFF).toByte()
        }
        fun int16(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = (value shr 8 and 0xFF).toByte()
        }
        val data = dataBytes.coerceIn(0L, 0xFFFFFFFFL - 36)
        ascii(0, "RIFF")
        int32(4, 36 + data)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        int32(16, 16)
        int16(20, 1) // PCM
        int16(22, 1) // mono
        int32(24, sampleRate.toLong())
        int32(28, sampleRate * 2L) // byte rate
        int16(32, 2) // block align
        int16(34, 16) // bits per sample
        ascii(36, "data")
        int32(40, data)
        return header
    }

    /** Encodes [samples] as a complete WAV file. */
    fun encode(samples: ShortArray, sampleRate: Int = LECTURE_SAMPLE_RATE): ByteArray {
        val pcm = samples.toLittleEndianBytes()
        return header(pcm.size.toLong(), sampleRate) + pcm
    }
}

fun ShortArray.toLittleEndianBytes(): ByteArray {
    val bytes = ByteArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt()
        bytes[2 * i] = (v and 0xFF).toByte()
        bytes[2 * i + 1] = (v shr 8 and 0xFF).toByte()
    }
    return bytes
}

fun ByteArray.littleEndianToShorts(): ShortArray {
    val samples = ShortArray(size / 2)
    for (i in samples.indices) {
        samples[i] = ((this[2 * i].toInt() and 0xFF) or (this[2 * i + 1].toInt() shl 8)).toShort()
    }
    return samples
}

/** Root mean square of [samples] in `[from, to)`, in sample units (0..32768). */
fun rms(samples: ShortArray, from: Int = 0, to: Int = samples.size): Float {
    if (to <= from) return 0f
    var sum = 0.0
    for (i in from until to) {
        val v = samples[i].toDouble()
        sum += v * v
    }
    return sqrt(sum / (to - from)).toFloat()
}

/**
 * Whether [samples] (from [from]) hold no speech worth analyzing: even the loudest moments are
 * quiet, or the level never rises much above the noise floor (a steady hum, e.g. a projector).
 * The loudest moments are the 95th percentile of the 30 ms frames: a few isolated words in a
 * minute of silence count as silence.
 */
fun isSilent(samples: ShortArray, from: Int = 0, sampleRate: Int = LECTURE_SAMPLE_RATE): Boolean {
    val frame = sampleRate * 30 / 1000
    if (samples.size - from < frame) return true
    val levels = (from..samples.size - frame step frame).map { rms(samples, it, it + frame) }.sorted()
    val floor = levels[(levels.size * 0.10f).toInt().coerceAtMost(levels.lastIndex)]
    val peak = levels[(levels.size * 0.95f).toInt().coerceAtMost(levels.lastIndex)]
    return peak < SILENT_PEAK_RMS || (peak < STEADY_SOUND_PEAK_RMS && peak < floor * 2.5f)
}

/** Below this level (about -40 dBFS) even the loudest moments of a chunk are silence. */
private const val SILENT_PEAK_RMS = 300f

/** A sound that never exceeds this level (about -30 dBFS) nor rises above its floor is a hum. */
private const val STEADY_SOUND_PEAK_RMS = 1000f
