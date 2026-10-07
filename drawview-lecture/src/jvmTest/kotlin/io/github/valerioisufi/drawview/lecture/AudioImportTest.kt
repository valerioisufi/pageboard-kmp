package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.audio.AudioCodecNative
import io.github.valerioisufi.drawview.lecture.audio.LECTURE_SAMPLE_RATE
import io.github.valerioisufi.drawview.lecture.audio.RecordingFile
import io.github.valerioisufi.drawview.lecture.audio.decodeAudioFile
import io.github.valerioisufi.drawview.lecture.audio.exportRecording
import io.github.valerioisufi.drawview.lecture.audio.rms
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioImportTest {
    private val directory: File = Files.createTempDirectory("import").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    /** A WAV file of [seconds] of a 440 Hz tone, as another application writes it. */
    private fun wav(name: String, rate: Int, channels: Int, bits: Int, float: Boolean = false, seconds: Double = 2.0): File {
        val frames = (rate * seconds).toInt()
        val bytesPerSample = bits / 8
        val data = ByteBuffer.allocate(frames * channels * bytesPerSample).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until frames) {
            val value = 0.5 * sin(2 * PI * 440 * f / rate)
            repeat(channels) {
                when {
                    float -> data.putFloat(value.toFloat())
                    bits == 16 -> data.putShort((value * 32767).toInt().toShort())
                    bits == 24 -> {
                        val v = (value * 8_388_607).toInt()
                        data.put(v.toByte()).put((v shr 8).toByte()).put((v shr 16).toByte())
                    }
                    else -> data.putInt((value * Int.MAX_VALUE).toInt())
                }
            }
        }
        val header = ByteBuffer.allocate(44 + 26).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + 26 + data.capacity()).put("WAVE".toByteArray())
            // A chunk the decoder must skip
            put("LIST".toByteArray()).putInt(18).put(ByteArray(18))
            put("fmt ".toByteArray()).putInt(16).putShort((if (float) 3 else 1).toShort()).putShort(channels.toShort())
            putInt(rate).putInt(rate * channels * bytesPerSample).putShort((channels * bytesPerSample).toShort()).putShort(bits.toShort())
            put("data".toByteArray()).putInt(data.capacity())
        }
        return File(directory, name).apply { writeBytes(header.array() + data.array()) }
    }

    /** [file] imported as the recording of a lecture (Opus), read back. */
    private fun decoded(file: File): ShortArray {
        val recording = RecordingFile(File(directory, file.name + ".lecture").absolutePath)
        val samples = decodeAudioFile(file.absolutePath, recording)
        assertEquals(samples, recording.samples())
        return recording.readSegment(0, recording.durationMs()) ?: ShortArray(0)
    }

    @Test
    fun wavFilesOfAnyKindBecomeTheAudioOfTheLectures() {
        for (file in listOf(
            wav("stereo-44k.wav", 44_100, 2, 16),
            wav("mono-48k-float.wav", 48_000, 1, 32, float = true),
            wav("mono-16k-24bit.wav", 16_000, 1, 24),
            wav("stereo-32k-32bit.wav", 32_000, 2, 32)
        )) {
            val pcm = decoded(file)
            assertTrue(abs(pcm.size - 2 * LECTURE_SAMPLE_RATE) < LECTURE_SAMPLE_RATE / 20, "${file.name}: ${pcm.size}")
            // Half of the full scale, as a sine: about 0.35 of 32767
            val level = rms(pcm, LECTURE_SAMPLE_RATE / 2, LECTURE_SAMPLE_RATE)
            assertTrue(level in 10_000f..13_500f, "${file.name}: $level")
        }
    }

    @Test
    fun notAudioIsRefused() {
        val text = File(directory, "notes.wav").apply { writeText("not audio at all") }
        assertFailsWith<Exception> { decoded(text) }
    }

    @Test
    fun aRecordingIsExportedAndImportedAgain() {
        val recording = RecordingFile(File(directory, "lecture").absolutePath).apply {
            open()
            append(ShortArray(10 * LECTURE_SAMPLE_RATE) { (8000 * sin(2 * PI * 300 * it / LECTURE_SAMPLE_RATE)).toInt().toShort() })
            finish()
        }
        val exported = File(exportRecording(recording, File(directory, "Analisi 1.wav").absolutePath))
        assertEquals("Analisi 1.m4a", exported.name)
        // About 32 kbit/s: 40 KB instead of the 320 KB of a WAV file
        assertTrue(exported.length() in 20_000L..60_000L, "${exported.length()} bytes")
        assertEquals("ftyp", exported.readBytes().copyOfRange(4, 8).decodeToString())
        assertFalse(File(exported.path + ".frames").exists())
        // Read again by the codecs of the system (macOS)
        if (!AudioCodecNative.isAvailable) return
        val pcm = decoded(exported)
        assertTrue(abs(pcm.size - 10 * LECTURE_SAMPLE_RATE) < LECTURE_SAMPLE_RATE / 5, "${pcm.size}")
        assertTrue(rms(pcm, LECTURE_SAMPLE_RATE, 2 * LECTURE_SAMPLE_RATE) > 4_000f)
    }
}
