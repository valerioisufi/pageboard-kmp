package io.github.valerioisufi.pageboard.lecture.audio

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * The audio of a lecture on disk: Opus in an Ogg file (`audio.opus`, see [OggOpus]), mono at
 * 16 kHz, about 11 MB per hour.
 *
 * While recording, the pages are written (and flushed) every second, so that nothing but the
 * last second is lost if the application stops abruptly; the recording is read from any position
 * meanwhile ([readSegment]), e.g. to listen to it or to transcribe it.
 */
internal class RecordingFile(directory: String) {
    private val directoryPath = Path(directory)
    val path = Path(directory, FILE_NAME)

    private var writer: OggOpusWriter? = null

    /** The pages of the file (by its size: the new ones read when it grew). */
    private var index: PageIndex? = null

    /** Starts a new recording (written as it goes). */
    fun open() {
        SystemFileSystem.createDirectories(directoryPath)
        index = null
        writer = OggOpusWriter(SystemFileSystem.sink(path))
    }

    fun append(samples: ShortArray) {
        writer?.append(samples)
    }

    /** Ends the recording (its last page written): whether there is a recording. */
    fun finish(): Boolean {
        writer?.finish()
        writer = null
        return exists
    }

    val exists: Boolean get() = SystemFileSystem.exists(path)

    /** The size of the file. */
    fun size(): Long = SystemFileSystem.metadataOrNull(path)?.size ?: 0L

    /** The samples recorded (as far as written). */
    fun samples(): Long = pageIndex()?.samples ?: 0L

    fun durationMs(): Long = samplesToMs(samples())

    /** Reads [durationMs] of audio from [startMs] (while recording or after [finish]). */
    fun readSegment(startMs: Long, durationMs: Long): ShortArray? =
        readSamples(msToSamples(startMs.coerceAtLeast(0L)), msToSamples(durationMs))

    /** Reads [count] samples from the sample [start] (as far as there are). */
    fun readSamples(start: Long, count: Long): ShortArray? {
        val total = pageIndex()?.samples ?: return null
        val length = count.coerceAtMost(total - start)
        if (length <= 0) return null
        val output = ShortArray(length.toInt())
        var written = 0
        decode(start, length) { samples ->
            samples.copyInto(output, written)
            written += samples.size
        }
        return if (written == output.size) output else output.copyOf(written).takeIf { written > 0 }
    }

    /** Decodes the whole recording, giving [onBlock] its samples in order. */
    fun decodeAll(onBlock: (ShortArray) -> Unit) {
        val total = pageIndex()?.samples ?: return
        decode(0, total, onBlock)
    }

    /** Decodes [length] samples from [start], from the page before the one holding it (the decoder settles). */
    private fun decode(start: Long, length: Long, onBlock: (ShortArray) -> Unit) {
        val index = pageIndex() ?: return
        val holding = index.pages.indexOfFirst { it.endSample > start }.takeIf { it >= 0 } ?: return
        val first = index.pages[(holding - 1).coerceAtLeast(0)]
        var left = length
        var position = first.startSample
        val decoder = OpusDecoder(LECTURE_SAMPLE_RATE)
        try {
            SystemFileSystem.source(path).buffered().use { source ->
                source.skip(first.offset)
                OggOpusReader.pages(source, withPackets = true, from = first.offset) { page ->
                    for (packet in page.packets) {
                        val samples = decoder.decode(packet)
                        val from = (start - position).coerceIn(0L, samples.size.toLong()).toInt()
                        val count = minOf((samples.size - from).toLong(), left).toInt()
                        position += samples.size
                        if (count <= 0) continue
                        onBlock(if (from == 0 && count == samples.size) samples else samples.copyOfRange(from, from + count))
                        left -= count
                        if (left == 0L) return@pages false
                    }
                    true
                }
            }
        } finally {
            decoder.close()
        }
    }

    /** The pages of the file; when it grew, the new ones are read (after the last page known). */
    private fun pageIndex(): PageIndex? {
        val size = size()
        if (size == 0L) return null
        val known = index?.takeIf { it.size <= size }
        if (known != null && known.size == size) return known
        val pages = known?.pages?.toMutableList() ?: mutableListOf()
        var granules = known?.granules ?: emptyList()
        val from = known?.scannedTo ?: 0L
        var scannedTo = from
        SystemFileSystem.source(path).buffered().use { source ->
            source.skip(from)
            val read = mutableListOf<OggPage>()
            OggOpusReader.pages(source, withPackets = false, from = from) { page ->
                read.add(page)
                true
            }
            read.lastOrNull()?.let { last -> scannedTo = last.offset + last.size }
            granules = granules + read.map { it.offset to it.granule }
        }
        // The identification header, the comments, then the audio
        if (granules.size < 2) return null
        val preSkip = known?.preSkip ?: readPreSkip()
        fun sampleAt(granule: Long) = (granule - preSkip) / OggOpus.RATE_FACTOR
        for (i in maxOf(2, pages.size + 2) until granules.size) {
            pages.add(IndexedPage(granules[i].first, sampleAt(granules[i - 1].second), sampleAt(granules[i].second)))
        }
        return PageIndex(size, scannedTo, preSkip, granules, pages).also { index = it }
    }

    /** The pre-skip of the identification header (the first packet of the file). */
    private fun readPreSkip(): Int = SystemFileSystem.source(path).buffered().use { source ->
        var preSkip = 0
        OggOpusReader.pages(source, withPackets = true) { page ->
            page.packets.firstOrNull()?.let { preSkip = OggOpusReader.preSkip(it) }
            false
        }
        preSkip
    }

    /** A page of the audio: where it is, the samples it starts and ends with. */
    private data class IndexedPage(val offset: Long, val startSample: Long, val endSample: Long)

    private class PageIndex(
        val size: Long,
        val scannedTo: Long,
        val preSkip: Int,
        val granules: List<Pair<Long, Long>>,
        val pages: List<IndexedPage>
    ) {
        val samples: Long get() = pages.lastOrNull()?.endSample?.coerceAtLeast(0) ?: 0L
    }

    companion object {
        const val FILE_NAME = "audio.opus"
    }
}
