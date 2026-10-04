package io.github.valerioisufi.drawview.lecture.audio

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Streaming conversion of interleaved 16-bit PCM to mono [outputRate] Hz, for the microphones that
 * do not capture 16 kHz mono directly (typically 44.1 or 48 kHz, sometimes stereo).
 *
 * The channels are averaged, the signal is low-pass filtered by a moving average as long as the
 * decimation ratio, then linearly interpolated. The state is kept between the blocks, so that a
 * stream can be converted in blocks of any size without discontinuities.
 */
class PcmResampler(
    private val inputRate: Int,
    private val channels: Int,
    private val outputRate: Int = LECTURE_SAMPLE_RATE
) {
    init {
        require(inputRate > 0 && outputRate > 0 && channels > 0)
    }

    private val step = inputRate.toDouble() / outputRate
    private val passthrough = inputRate == outputRate && channels == 1

    // Moving average (anti-aliasing) on the mono signal
    private val window = max(1, ceil(step).toInt())
    private val history = FloatArray(window)
    private var historyPos = 0
    private var historySum = 0.0

    // Exact positions (no drift, same output whatever the block sizes): output sample n is at
    // input position n * inputRate / outputRate
    private var outputCount = 0L
    private var inputConsumed = 0L
    private var previous = 0f

    /** Converts [frames] interleaved frames of [input]. */
    fun process(input: ShortArray, frames: Int = input.size / channels): ShortArray {
        if (frames <= 0) return ShortArray(0)
        if (passthrough) return input.copyOf(frames)

        val filtered = FloatArray(frames)
        for (f in 0 until frames) {
            var mono = 0f
            val base = f * channels
            for (c in 0 until channels) mono += input[base + c]
            mono /= channels
            historySum += mono - history[historyPos]
            history[historyPos] = mono
            historyPos = (historyPos + 1) % window
            filtered[f] = (historySum / window).toFloat()
        }

        val output = ArrayList<Short>((frames / step).toInt() + 2)
        while (true) {
            val scaled = outputCount * inputRate
            // Index in this block (-1 = last sample of the previous block)
            val index = (scaled / outputRate - inputConsumed).toInt()
            if (index + 1 >= frames) break
            val a = if (index < 0) previous else filtered[index]
            val b = filtered[index + 1]
            val fraction = (scaled % outputRate).toFloat() / outputRate
            val value = a + (b - a) * fraction
            output.add(value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            outputCount++
        }
        inputConsumed += frames
        previous = filtered[frames - 1]
        return output.toShortArray()
    }
}
