package io.github.valerioisufi.drawview.lecture.audio

/**
 * A segment of the recording sent to the model.
 *
 * @param index progressive number of the chunk in the recording.
 * @param startSample index, in the whole recording, of the first sample of [samples].
 * @param overlapSamples leading samples repeating the end of the previous chunk: context for the
 *   model, already analyzed with the previous chunk.
 */
internal class AudioChunk(
    val index: Int,
    val startSample: Long,
    val overlapSamples: Int,
    val samples: ShortArray
) {
    val endSample: Long get() = startSample + samples.size

    /** First sample of the new (not yet analyzed) audio. */
    val newStartSample: Long get() = startSample + overlapSamples

    val startMs: Long get() = samplesToMs(startSample)
    val newStartMs: Long get() = samplesToMs(newStartSample)
    val endMs: Long get() = samplesToMs(endSample)
}

/**
 * Cuts the audio stream of the lecture into chunks of about [targetSeconds] of new audio.
 *
 * Once the target duration is reached, the chunk is cut at the first pause of the speech (two
 * consecutive quiet frames, measured against the noise floor of the chunk), so that words and
 * sentences are not split; it is cut anyway at [maxSeconds]. Every chunk starts with the last
 * [overlapSeconds] of the previous one, so that the model has the context of the boundary.
 */
internal class SpeechChunker(
    targetSeconds: Int,
    maxSeconds: Int = targetSeconds + targetSeconds / 2,
    overlapSeconds: Int = 5,
    private val sampleRate: Int = LECTURE_SAMPLE_RATE
) {
    private val targetSamples = targetSeconds * sampleRate
    private val maxSamples = maxOf(maxSeconds, targetSeconds) * sampleRate
    private val overlapSamples = overlapSeconds * sampleRate
    private val frameSamples = sampleRate * FRAME_MS / 1000

    private var buffer = ShortArray(maxSamples + overlapSamples + sampleRate)
    private var size = 0

    /** Index in the recording of buffer[0]. */
    private var bufferStart = 0L

    /** Leading samples of the buffer repeating the previous chunk. */
    private var overlapInBuffer = 0

    /** RMS of the complete frames of the new audio of the buffer. */
    private val frameRms = ArrayList<Float>()
    private var quietThreshold: Float? = null
    private var nextIndex = 0

    /** Samples received so far. */
    var totalSamples = 0L
        private set

    /** Appends [samples] to the stream, returning the chunks completed by them. */
    fun push(samples: ShortArray): List<AudioChunk> {
        val chunks = mutableListOf<AudioChunk>()
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(samples.size - offset, buffer.size - size)
            samples.copyInto(buffer, size, offset, offset + count)
            size += count
            offset += count
            totalSamples += count
            measureFrames()
            findCut()?.let { chunks.add(cut(it)) }
        }
        return chunks
    }

    /**
     * Cuts the new audio received so far (e.g. when the student asks what they missed), or
     * returns `null` when there is less than [minSeconds] of new audio.
     */
    fun cutNow(minSeconds: Float = 1f): AudioChunk? {
        val newSamples = size - overlapInBuffer
        if (newSamples < minSeconds * sampleRate) return null
        return cut(size)
    }

    private fun measureFrames() {
        while (overlapInBuffer + (frameRms.size + 1) * frameSamples <= size) {
            val from = overlapInBuffer + frameRms.size * frameSamples
            frameRms.add(rms(buffer, from, from + frameSamples))
        }
    }

    /** End (exclusive, in buffer samples) of the chunk to emit now, or `null`. */
    private fun findCut(): Int? {
        val newSamples = size - overlapInBuffer
        if (newSamples >= maxSamples) return overlapInBuffer + maxSamples
        if (newSamples < targetSamples) return null

        val threshold = quietThreshold ?: computeQuietThreshold().also { quietThreshold = it }
        val firstFrame = targetSamples / frameSamples
        for (frame in maxOf(firstFrame, 1) until frameRms.size) {
            if (frameRms[frame - 1] <= threshold && frameRms[frame] <= threshold) {
                // Middle of the pause
                return overlapInBuffer + frame * frameSamples
            }
        }
        return null
    }

    /**
     * Quiet level of the chunk: a little above its noise floor (low percentile of the frames), and
     * well below its typical level (a steady sound has no pauses); a nearly silent frame is always
     * quiet.
     */
    private fun computeQuietThreshold(): Float {
        if (frameRms.isEmpty()) return MIN_QUIET_RMS
        val sorted = frameRms.sorted()
        val floor = sorted[(sorted.size * 0.10f).toInt().coerceIn(0, sorted.lastIndex)]
        val median = sorted[sorted.size / 2]
        return maxOf(ALWAYS_QUIET_RMS, minOf(maxOf(MIN_QUIET_RMS, floor * 1.5f), median * 0.5f))
    }

    private fun cut(end: Int): AudioChunk {
        val chunk = AudioChunk(
            index = nextIndex++,
            startSample = bufferStart,
            overlapSamples = overlapInBuffer,
            samples = buffer.copyOf(end)
        )
        // The next chunk starts with the end of this one
        val keepFrom = maxOf(0, end - overlapSamples)
        val remaining = size - keepFrom
        val next = ShortArray(buffer.size)
        buffer.copyInto(next, 0, keepFrom, size)
        buffer = next
        size = remaining
        bufferStart += keepFrom
        overlapInBuffer = end - keepFrom
        frameRms.clear()
        quietThreshold = null
        measureFrames()
        return chunk
    }

    private companion object {
        const val FRAME_MS = 30
        const val MIN_QUIET_RMS = 120f

        /** About -50 dBFS: silence, whatever the rest of the chunk. */
        const val ALWAYS_QUIET_RMS = 100f
    }
}
