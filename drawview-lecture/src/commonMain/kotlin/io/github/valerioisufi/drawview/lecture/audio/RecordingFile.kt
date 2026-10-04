package io.github.valerioisufi.drawview.lecture.audio

import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * The audio of a lecture on disk.
 *
 * While recording, the raw PCM is appended (and flushed) to `audio.pcm`, so that nothing is lost
 * if the application stops abruptly; [finish] converts it into `audio.wav` (a raw `audio.pcm` left
 * by an interrupted recording is still 16-bit mono PCM at [LECTURE_SAMPLE_RATE] Hz).
 */
class RecordingFile(directory: String) {
    private val directoryPath = Path(directory)
    val pcmPath = Path(directory, "audio.pcm")
    val wavPath = Path(directory, "audio.wav")

    private var sink: Sink? = null

    fun open() {
        SystemFileSystem.createDirectories(directoryPath)
        sink = SystemFileSystem.sink(pcmPath, append = true).buffered()
    }

    fun append(samples: ShortArray) {
        val current = sink ?: return
        current.write(samples.toLittleEndianBytes())
        current.flush()
    }

    /** Closes the recording and converts it into `audio.wav`. */
    fun finish(): Boolean {
        sink?.close()
        sink = null
        if (!SystemFileSystem.exists(pcmPath)) return SystemFileSystem.exists(wavPath)
        val dataBytes = SystemFileSystem.metadataOrNull(pcmPath)?.size ?: return false
        SystemFileSystem.sink(wavPath).buffered().use { wav ->
            wav.write(Wav.header(dataBytes))
            SystemFileSystem.source(pcmPath).buffered().use { pcm -> pcm.transferTo(wav) }
        }
        SystemFileSystem.delete(pcmPath)
        return true
    }

    /** Size of the recorded audio in bytes of PCM. */
    fun pcmBytes(): Long = when {
        SystemFileSystem.exists(pcmPath) -> SystemFileSystem.metadataOrNull(pcmPath)?.size ?: 0L
        SystemFileSystem.exists(wavPath) -> ((SystemFileSystem.metadataOrNull(wavPath)?.size ?: 0L) - Wav.HEADER_SIZE).coerceAtLeast(0L)
        else -> 0L
    }

    /** Reads [durationMs] of audio from [startMs] (while recording or after [finish]). */
    fun readSegment(startMs: Long, durationMs: Long): ShortArray? {
        val (path, headerBytes) = when {
            SystemFileSystem.exists(pcmPath) -> pcmPath to 0L
            SystemFileSystem.exists(wavPath) -> wavPath to Wav.HEADER_SIZE.toLong()
            else -> return null
        }
        val fileSize = SystemFileSystem.metadataOrNull(path)?.size ?: return null
        val offset = headerBytes + msToSamples(startMs.coerceAtLeast(0L)) * 2
        if (offset >= fileSize) return null
        val length = minOf(msToSamples(durationMs) * 2, fileSize - offset).toInt() and 1.inv()
        if (length <= 0) return null
        return SystemFileSystem.source(path).buffered().use { source ->
            source.skip(offset)
            source.readByteArray(length).littleEndianToShorts()
        }
    }

    /** The complete WAV file, once [finish]ed. */
    fun readWav(): ByteArray? {
        if (!SystemFileSystem.exists(wavPath)) return null
        return SystemFileSystem.source(wavPath).buffered().use { it.readByteArray() }
    }
}
