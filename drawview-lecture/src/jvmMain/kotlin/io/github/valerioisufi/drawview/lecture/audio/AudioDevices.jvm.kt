package io.github.valerioisufi.drawview.lecture.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine

actual fun createAudioCapture(): AudioCapture? = JavaSoundAudioCapture()

actual fun createAudioPlayer(): AudioPlayer? = JavaSoundAudioPlayer()

/**
 * Microphone of the desktop (Java Sound): 16 kHz mono when the device supports it, otherwise the
 * first supported format among the common ones, converted by [PcmResampler].
 *
 * On macOS the microphone access is granted to the application launching the JVM (the terminal or
 * the IDE when running from Gradle); without it the line delivers silence.
 */
class JavaSoundAudioCapture : AudioCapture {
    @Volatile
    private var running = false
    private var line: TargetDataLine? = null
    private var thread: Thread? = null

    override fun start(onSamples: (ShortArray) -> Unit) {
        check(!running) { "The capture is already running" }
        val (opened, format) = openLine()
        opened.start()
        line = opened
        running = true
        val channels = format.channels
        val bigEndian = format.isBigEndian
        val resampler = PcmResampler(format.sampleRate.toInt(), channels)
        thread = Thread({
            // ~100 ms blocks
            val frameBytes = 2 * channels
            val buffer = ByteArray(maxOf(frameBytes, (format.sampleRate.toInt() / 10) * frameBytes))
            while (running) {
                val read = try {
                    opened.read(buffer, 0, buffer.size)
                } catch (_: Exception) {
                    break
                }
                if (read <= 0) continue
                val samples = ShortArray(read / 2)
                for (i in samples.indices) {
                    val a = buffer[2 * i].toInt()
                    val b = buffer[2 * i + 1].toInt()
                    samples[i] = if (bigEndian) ((a shl 8) or (b and 0xFF)).toShort() else ((b shl 8) or (a and 0xFF)).toShort()
                }
                val converted = resampler.process(samples, samples.size / channels)
                if (converted.isNotEmpty()) onSamples(converted)
            }
        }, "lecture-audio-capture").apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        running = false
        line?.let {
            it.stop()
            it.close()
        }
        thread?.join(2000)
        line = null
        thread = null
    }

    private fun openLine(): Pair<TargetDataLine, AudioFormat> {
        var lastError: Exception? = null
        for (format in CANDIDATE_FORMATS) {
            val info = DataLine.Info(TargetDataLine::class.java, format)
            if (!AudioSystem.isLineSupported(info)) continue
            try {
                val line = AudioSystem.getLine(info) as TargetDataLine
                line.open(format, (format.sampleRate.toInt() / 2) * format.frameSize)
                return line to format
            } catch (e: LineUnavailableException) {
                lastError = e
            } catch (e: SecurityException) {
                lastError = e
            } catch (e: IllegalArgumentException) {
                lastError = e
            }
        }
        throw AudioCaptureException("No microphone available", lastError)
    }

    private companion object {
        val CANDIDATE_FORMATS = listOf(16000f, 48000f, 44100f, 32000f, 22050f).flatMap { rate ->
            listOf(1, 2).flatMap { channels ->
                listOf(false, true).map { bigEndian -> AudioFormat(rate, 16, channels, true, bigEndian) }
            }
        }
    }
}

/** Plays the excerpts of the recordings with a Java Sound [Clip]. */
class JavaSoundAudioPlayer : AudioPlayer {
    private var clip: Clip? = null

    @Synchronized
    override fun play(samples: ShortArray) {
        stop()
        if (samples.isEmpty()) return
        try {
            val format = AudioFormat(LECTURE_SAMPLE_RATE.toFloat(), 16, 1, true, false)
            val bytes = samples.toLittleEndianBytes()
            val newClip = AudioSystem.getClip()
            newClip.open(format, bytes, 0, bytes.size)
            newClip.start()
            clip = newClip
        } catch (e: Exception) {
            println("DrawView: cannot play the recording: ${e.message}")
        }
    }

    @Synchronized
    override fun stop() {
        clip?.let {
            it.stop()
            it.close()
        }
        clip = null
    }
}
