package io.github.valerioisufi.drawview.lecture.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Plays speech faster without raising its pitch (WSOLA: waveform similarity overlap-add): frames
 * of 40 ms are taken from the input every `hop × speed` samples, each shifted by a few
 * milliseconds to the position most similar to the natural continuation of the previous one, and
 * overlapped with a Hann window every `hop` samples of output.
 *
 * Streaming: the input can be given in blocks of any size, the output comes with a delay of about
 * one frame.
 */
class TimeStretcher(val speed: Float, sampleRate: Int = LECTURE_SAMPLE_RATE) {
    init {
        require(speed in 0.5f..4f)
    }

    private val frame = sampleRate * FRAME_MS / 1000
    private val hop = frame / 2
    private val tolerance = sampleRate * TOLERANCE_MS / 1000
    private val window = FloatArray(frame) { (0.5 - 0.5 * cos(2 * PI * it / frame)).toFloat() }

    private var input = FloatArray(0)
    private var inputSize = 0

    /** Nominal position of the next frame in [input]. */
    private var position = 0.0

    /** The natural continuation of the last frame taken (hop samples after its start). */
    private var continuation: FloatArray? = null

    /** Second half of the last frame, added to the first half of the next one. */
    private val overlap = FloatArray(hop)

    /** The output of [samples] (and of the input kept from the previous blocks). */
    fun process(samples: ShortArray): ShortArray {
        append(samples)
        val output = ArrayList<Short>((samples.size / speed).toInt() + hop)
        while (true) {
            val nominal = position.roundToInt()
            // The frame, its search range and the continuation must be in the input
            if (nominal + tolerance + hop + frame > inputSize) break
            val start = continuation?.let { bestStart(nominal, it) } ?: nominal
            for (i in 0 until hop) {
                val value = overlap[i] + input[start + i] * window[i]
                output.add(value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
            for (i in 0 until hop) overlap[i] = input[start + hop + i] * window[hop + i]
            continuation = input.copyOfRange(start + hop, start + hop + frame)
            position += hop * speed
        }
        discardConsumed()
        return output.toShortArray()
    }

    /** The start near [nominal] whose frame is the most similar to [target]. */
    private fun bestStart(nominal: Int, target: FloatArray): Int {
        var best = nominal
        var bestScore = Double.NEGATIVE_INFINITY
        val from = (nominal - tolerance).coerceAtLeast(0)
        val to = nominal + tolerance
        // Every other offset, on every other sample: as good for speech, four times faster
        var start = from
        while (start <= to) {
            var correlation = 0.0
            var i = 0
            while (i < hop) {
                correlation += input[start + i] * target[i]
                i += 2
            }
            if (correlation > bestScore) {
                bestScore = correlation
                best = start
            }
            start += 2
        }
        return best
    }

    private fun append(samples: ShortArray) {
        if (inputSize + samples.size > input.size) input = input.copyOf(maxOf(input.size * 2, inputSize + samples.size))
        for (i in samples.indices) input[inputSize + i] = samples[i].toFloat()
        inputSize += samples.size
    }

    /** Drops the input before the earliest position a next frame can start from. */
    private fun discardConsumed() {
        val drop = (position.toInt() - tolerance).coerceIn(0, inputSize)
        if (drop == 0) return
        input.copyInto(input, 0, drop, inputSize)
        inputSize -= drop
        position -= drop
    }

    private companion object {
        const val FRAME_MS = 40
        const val TOLERANCE_MS = 10
    }
}
