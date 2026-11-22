package io.github.valerioisufi.pageboard.lecture.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import io.github.valerioisufi.pageboard.platform.PageboardLog
import java.io.File
import java.nio.ByteOrder

/**
 * The audio files of other applications (m4a, mp3, aac, ogg, flac, wav...) decoded by the codecs
 * of Android (MediaExtractor and MediaCodec).
 */
internal object MediaCodecAudio : AudioCodec {
    private const val TIMEOUT_US = 10_000L

    override fun decode(input: String, output: String): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(input)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return -1
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            decoder.configure(format, null, null, 0)
            decoder.start()
            var pcm = PcmFormat.of(format)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var total = 0L
            File(output).outputStream().buffered().use { sink ->
                while (true) {
                    if (!inputDone) {
                        val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (index >= 0) {
                            val size = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        pcm = PcmFormat.of(decoder.outputFormat)
                    } else if (index >= 0) {
                        val buffer = decoder.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val samples = if (pcm.float) {
                            val floats = buffer.asFloatBuffer()
                            ShortArray(floats.remaining()) { (floats.get() * 32767f).coerceIn(-32768f, 32767f).toInt().toShort() }
                        } else {
                            val shorts = buffer.asShortBuffer()
                            ShortArray(shorts.remaining()).also { shorts.get(it) }
                        }
                        decoder.releaseOutputBuffer(index, false)
                        val block = pcm.resampler.process(samples)
                        if (block.isNotEmpty()) {
                            sink.write(block.toLittleEndianBytes())
                            total += block.size
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            total
        } catch (e: Exception) {
            PageboardLog.warn("Cannot decode a recording", e)
            -1
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            extractor.release()
        }
    }

    /** The PCM the decoder gives (16-bit, or floats), converted into the audio of the lectures. */
    private class PcmFormat(val float: Boolean, val resampler: PcmResampler) {
        companion object {
            fun of(format: MediaFormat): PcmFormat {
                val float = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                return PcmFormat(float, PcmResampler(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)))
            }
        }
    }
}

internal actual fun audioCodec(): AudioCodec? = MediaCodecAudio
