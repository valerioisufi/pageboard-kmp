package io.github.valerioisufi.pageboard.canvas.controller

import io.github.valerioisufi.pageboard.canvas.pointerClockMillis
import io.github.valerioisufi.pageboard.platform.SyncLock
import io.github.valerioisufi.pageboard.platform.syncLock

/**
 * The latency of the ink over one stroke, measured by the editor while
 * [DrawViewController.isInkLatencyMeasured]: from the inputs of the pointer to the frames of the
 * editor that drew them. The time the display takes to show a frame drawn is not counted.
 */
public class InkLatencyReport(
    /** The inputs of the stroke that a frame drew. */
    public val inputs: Int,
    /** The frames that drew the stroke. */
    public val frames: Int,
    /** Median time from an input to the first frame that drew it, in milliseconds. */
    public val inputToFrameMedianMs: Float,
    /** 95th percentile of the time from an input to the first frame that drew it, in milliseconds. */
    public val inputToFrameP95Ms: Float,
    /**
     * Median age of the tip of the ink when a frame drew it, in milliseconds: from the time the tip
     * stands for (the last input, or the end of its prediction) to the frame. Negative when the
     * prediction reaches beyond the frame.
     */
    public val tipAgeMedianMs: Float,
    /** 95th percentile of the age of the tip of the ink when a frame drew it, in milliseconds. */
    public val tipAgeP95Ms: Float,
    /** Median time the prediction reached beyond the last input when a frame drew it, in milliseconds. */
    public val predictionMedianMs: Float
) {
    override fun toString(): String =
        "ink latency: input to frame ${inputToFrameMedianMs.ms()} (p95 ${inputToFrameP95Ms.ms()}), " +
            "tip age ${tipAgeMedianMs.ms()} (p95 ${tipAgeP95Ms.ms()}), predicted ${predictionMedianMs.ms()}, " +
            "$inputs inputs in $frames frames"

    private fun Float.ms(): String = "${(this * 10f).toInt() / 10f} ms"
}

/**
 * Measures the latency of the ink of a stroke (see [InkLatencyReport]): the gesture tells it the
 * inputs and the prediction, the layer that draws the stroke tells it the frames, maybe from
 * another thread. Times are in the clock of the pointer events ([pointerClockMillis]).
 */
internal class InkLatencyMeter(private val clock: () -> Long = ::pointerClockMillis) {
    private val lock = SyncLock()

    /** Whether strokes are measured. */
    var isEnabled: Boolean = false
        set(value) {
            field = value
            syncLock(lock) { reset() }
        }

    private var measuring = false
    private val pending = ArrayList<Long>()
    private var lastInput = 0L
    private var predictedUntil = 0L
    private val inputLatencies = ArrayList<Float>()
    private val tipAges = ArrayList<Float>()
    private val predictions = ArrayList<Float>()

    fun strokeStarted() {
        if (!isEnabled) return
        syncLock(lock) {
            reset()
            measuring = true
        }
    }

    /** A real input of the stroke, at [timeMs]. */
    fun inputAdded(timeMs: Long) {
        if (!isEnabled) return
        syncLock(lock) {
            if (!measuring) return
            pending.add(timeMs)
            if (timeMs > lastInput) lastInput = timeMs
            predictedUntil = 0L
        }
    }

    /** The stroke is predicted up to [untilMs] (none: 0). */
    fun predicted(untilMs: Long) {
        if (!isEnabled) return
        syncLock(lock) { if (measuring) predictedUntil = untilMs }
    }

    /** A frame drew the stroke as it is now (one that drew no new input is not counted). */
    fun frameDrawn() {
        if (!isEnabled) return
        val now = clock()
        syncLock(lock) {
            if (!measuring || pending.isEmpty()) return
            // Times out of any plausible range come from clocks that do not match (the tests)
            if (now - lastInput !in -MAX_MS..MAX_MS) return
            for (input in pending) inputLatencies.add((now - input).toFloat())
            pending.clear()
            val tip = if (predictedUntil > lastInput) predictedUntil else lastInput
            tipAges.add((now - tip).toFloat())
            predictions.add((tip - lastInput).toFloat())
        }
    }

    /** The stroke ended: what was measured, if a frame drew it. */
    fun strokeEnded(): InkLatencyReport? {
        if (!isEnabled) return null
        return syncLock(lock) {
            val report = if (measuring && tipAges.isNotEmpty()) {
                InkLatencyReport(
                    inputs = inputLatencies.size,
                    frames = tipAges.size,
                    inputToFrameMedianMs = inputLatencies.percentile(0.5f),
                    inputToFrameP95Ms = inputLatencies.percentile(0.95f),
                    tipAgeMedianMs = tipAges.percentile(0.5f),
                    tipAgeP95Ms = tipAges.percentile(0.95f),
                    predictionMedianMs = predictions.percentile(0.5f)
                )
            } else null
            reset()
            report
        }
    }

    private fun reset() {
        measuring = false
        pending.clear()
        lastInput = 0L
        predictedUntil = 0L
        inputLatencies.clear()
        tipAges.clear()
        predictions.clear()
    }

    private fun List<Float>.percentile(p: Float): Float {
        if (isEmpty()) return 0f
        val sorted = sorted()
        return sorted[((sorted.size - 1) * p).toInt()]
    }

    private companion object {
        const val MAX_MS = 1_000L
    }
}
