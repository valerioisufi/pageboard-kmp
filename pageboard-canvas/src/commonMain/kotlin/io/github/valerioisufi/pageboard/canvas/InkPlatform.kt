package io.github.valerioisufi.pageboard.canvas

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import kotlin.math.hypot

/**
 * What the platform gives the ink beyond the pointer events of Compose (see
 * `docs/plans/ink-rendering-camera.md`): the pressures of the samples an event carries, its own
 * prediction of the pen (the Kalman filter where it has none), and the interval of a frame of its
 * display, as far as the prediction reaches. The gesture knows none of the platforms.
 */
internal interface InkPlatform {
    /** The pressures of the samples of the pointer, as the platform reported them. */
    val pressures: SamplePressures

    /** Where the pen will be: the predictor of the platform, else the Kalman filter. */
    val predictor: InkPredictor

    /** A frame of the display showing the canvas, in milliseconds: 16 at 60 Hz, 8 at 120 Hz. */
    val frameMillis: Long

    /**
     * [listener] is called when the platform has a prediction for an event the gesture has already
     * read (iOS, where the touches may reach it after Compose): the stroke is predicted again.
     */
    fun setPredictionListener(listener: (() -> Unit)?) = Unit
}

/** The ink platform of this platform, for the canvas of [controller]. */
@Composable
internal expect fun rememberInkPlatform(controller: PageboardController): InkPlatform

/**
 * The canvas seen by [platform]: its place, and the events of its pointers as the platform reports
 * them (Android: unbuffered for a stylus, and for a finger when [fingerDraws]).
 */
internal expect fun Modifier.inkInput(platform: InkPlatform, fingerDraws: () -> Boolean): Modifier

/** The ink platform with nothing of its own: no pressures beyond the events, the Kalman filter, a display of [hz]. */
internal class CommonInkPlatform(controller: PageboardController, hz: Float = 60f) : InkPlatform {
    override val pressures: SamplePressures = SamplePressures()
    override val predictor: InkPredictor = KalmanInkPredictor { controller.viewport.density }
    override val frameMillis: Long = frameMillisOf(hz)
}

/**
 * A frame of a display refreshing [hz] times a second, in whole milliseconds (16 at 60 Hz, 11 at
 * 90 Hz, 8 at 120 Hz): the 60 Hz of most displays when the platform does not know.
 */
internal fun frameMillisOf(hz: Float): Long = if (hz >= 1f) (1000f / hz).toLong().coerceIn(MIN_FRAME_MS, MAX_FRAME_MS) else DEFAULT_FRAME_MS

private const val MIN_FRAME_MS = 4L
private const val MAX_FRAME_MS = 33L
private const val DEFAULT_FRAME_MS = 16L

/**
 * The pressures of the samples of the pointers as the platform reported them: Compose gives the
 * samples of an event (its historical changes) without their pressure, so the platform keeps them
 * here as it sees the event, before the gesture, which looks them up by their time and their place.
 * The latest [CAPACITY] samples only: a few events of a pen at 240 Hz.
 */
internal class SamplePressures {
    private val times = LongArray(CAPACITY)
    private val xs = FloatArray(CAPACITY)
    private val ys = FloatArray(CAPACITY)
    private val pressures = FloatArray(CAPACITY)
    private var size = 0
    private var next = 0

    /** A sample at ([x], [y]) in the pixels of the canvas, at [timeMs] in the clock of the pointer events, pressed by [pressure]. */
    fun record(timeMs: Long, x: Float, y: Float, pressure: Float) {
        times[next] = timeMs
        xs[next] = x
        ys[next] = y
        pressures[next] = pressure
        next = (next + 1) % CAPACITY
        if (size < CAPACITY) size++
    }

    /**
     * The pressure of the sample at [timeMs] nearest to [position], within [TOLERANCE_PX] of it (two
     * pointers at the same time are told apart by their place); `null` when the platform reported none.
     */
    fun pressureAt(timeMs: Long, position: Offset): Float? {
        var found = -1
        var nearest = TOLERANCE_PX
        for (i in 0 until size) {
            if (times[i] != timeMs) continue
            val distance = hypot(xs[i] - position.x, ys[i] - position.y)
            if (distance <= nearest) {
                found = i
                nearest = distance
            }
        }
        return if (found >= 0) pressures[found] else null
    }

    private companion object {
        const val CAPACITY = 64

        /** The rounding of the positions between the platform and Compose, in pixels. */
        const val TOLERANCE_PX = 2f
    }
}

/**
 * [InkPredictor] of a platform that predicts the pen only at times ([preferred], the predictor of
 * Android 14 for the pens it knows, the predicted touches of UIKit), the Kalman filter ([fallback])
 * otherwise: both are told every sample, so the filter is ready when the platform has nothing.
 */
internal class PreferredInkPredictor(
    private val preferred: PlatformPrediction,
    private val fallback: InkPredictor
) : InkPredictor {
    override fun start() {
        preferred.start()
        fallback.start()
    }

    override fun record(position: Offset, timeMs: Long) {
        preferred.record(position, timeMs)
        fallback.record(position, timeMs)
    }

    override fun predict(horizonMs: Long): List<PredictedPoint> =
        preferred.predict(horizonMs) ?: fallback.predict(horizonMs)
}

/** The prediction of a platform: like [InkPredictor], `null` when it has nothing for the stroke in progress. */
internal interface PlatformPrediction {
    fun start()

    fun record(position: Offset, timeMs: Long)

    /** The points up to [horizonMs] after the last sample, oldest first; `null` when the platform has no prediction for it. */
    fun predict(horizonMs: Long): List<PredictedPoint>?
}
