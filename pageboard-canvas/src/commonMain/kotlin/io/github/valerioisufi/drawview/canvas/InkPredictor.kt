package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.geometry.Offset
import kotlin.math.hypot
import kotlin.math.min

/** A place where the pointer will be: canvas pixels, at [timeMs] in the clock of the pointer events. */
internal class PredictedPoint(val position: Offset, val timeMs: Long)

/**
 * Where the pointer drawing a stroke will be (see `docs/plans/ink-latency.md`): told the real samples of
 * the stroke, it predicts the next ones, a little ahead of the last. The prediction is only drawn:
 * never an input of the stroke.
 */
internal interface InkPredictor {
    /** A stroke starts: what came before is forgotten. */
    fun start()

    /** A real sample of the pointer drawing the stroke, in canvas pixels, at [timeMs]. */
    fun record(position: Offset, timeMs: Long)

    /**
     * Where the pointer will be up to [horizonMs] after its last sample, oldest first; empty when
     * nothing is predicted (too few samples, the pointer slowing down to a stop).
     */
    fun predict(horizonMs: Long): List<PredictedPoint>
}

/**
 * [InkPredictor] of the pointer filtered by a Kalman filter on each axis (position, velocity and
 * acceleration, the jerk as the noise of the motion), extrapolated over the horizon. It works in dp
 * (the [density] of the screen), so that a pen moves alike on every screen. The prediction stops
 * where the pen would stop: a pen slowing down shows no tail beyond where it stays.
 */
internal class KalmanInkPredictor(private val density: () -> Float) : InkPredictor {
    private val x = AxisFilter()
    private val y = AxisFilter()
    private var samples = 0
    private var lastTime = 0L

    override fun start() {
        samples = 0
    }

    override fun record(position: Offset, timeMs: Long) {
        val dp = density().coerceAtLeast(0.1f)
        val px = position.x / dp
        val py = position.y / dp
        if (samples == 0) {
            x.reset(px.toDouble())
            y.reset(py.toDouble())
        } else {
            // Samples of the same time (or out of order) carry no motion of their own
            val dt = (timeMs - lastTime).toDouble()
            if (dt <= 0.0) return
            x.update(px.toDouble(), dt)
            y.update(py.toDouble(), dt)
        }
        samples++
        lastTime = timeMs
    }

    override fun predict(horizonMs: Long): List<PredictedPoint> {
        if (samples < MIN_SAMPLES || horizonMs <= 0L) return emptyList()
        val speed = hypot(x.v, y.v)
        if (speed < MIN_SPEED_DP_PER_MS) return emptyList()

        // Where the velocity along the motion reaches zero, if the pen slows down: no further
        val along = (x.v * x.a + y.v * y.a) / speed
        var horizon = horizonMs.toDouble()
        if (along < 0.0) horizon = min(horizon, speed / -along)
        if (horizon < STEP_MS) return emptyList()

        val dp = density().coerceAtLeast(0.1f)
        val points = ArrayList<PredictedPoint>()
        var t = STEP_MS
        while (t <= horizon + 0.001) {
            points += PredictedPoint(Offset((x.at(t) * dp).toFloat(), (y.at(t) * dp).toFloat()), lastTime + t.toLong())
            t += STEP_MS
        }
        return points
    }

    /**
     * A Kalman filter of position [p] (dp), velocity [v] (dp/ms) and acceleration [a] (dp/ms²) on one
     * axis, for a motion of white jerk.
     */
    private class AxisFilter {
        var p = 0.0
        var v = 0.0
        var a = 0.0
        private val cov = DoubleArray(9)
        private val predicted = DoubleArray(9)

        fun reset(position: Double) {
            p = position
            v = 0.0
            a = 0.0
            cov.fill(0.0)
            cov[0] = MEASUREMENT_VARIANCE
            cov[4] = INITIAL_VELOCITY_VARIANCE
            cov[8] = INITIAL_ACCELERATION_VARIANCE
        }

        fun update(measured: Double, dt: Double) {
            // Predict: x = F x, P = F P Fᵀ + Q
            val dt2 = dt * dt / 2.0
            val pp = p + v * dt + a * dt2
            val vp = v + a * dt
            val f = doubleArrayOf(1.0, dt, dt2, 0.0, 1.0, dt, 0.0, 0.0, 1.0)
            for (i in 0..2) for (j in 0..2) {
                var sum = 0.0
                for (k in 0..2) for (l in 0..2) sum += f[i * 3 + k] * cov[k * 3 + l] * f[j * 3 + l]
                predicted[i * 3 + j] = sum
            }
            val q = JERK_DENSITY
            val dt3 = dt * dt * dt
            predicted[0] += q * dt3 * dt * dt / 20.0
            predicted[1] += q * dt3 * dt / 8.0
            predicted[2] += q * dt3 / 6.0
            predicted[3] += q * dt3 * dt / 8.0
            predicted[4] += q * dt3 / 3.0
            predicted[5] += q * dt * dt / 2.0
            predicted[6] += q * dt3 / 6.0
            predicted[7] += q * dt * dt / 2.0
            predicted[8] += q * dt

            // Update with the position measured: K = P Hᵀ / (H P Hᵀ + R), H = [1 0 0]
            val s = predicted[0] + MEASUREMENT_VARIANCE
            val k0 = predicted[0] / s
            val k1 = predicted[3] / s
            val k2 = predicted[6] / s
            val residual = measured - pp
            p = pp + k0 * residual
            v = vp + k1 * residual
            a += k2 * residual
            val gains = doubleArrayOf(k0, k1, k2)
            for (i in 0..2) for (j in 0..2) cov[i * 3 + j] = predicted[i * 3 + j] - gains[i] * predicted[j]
        }

        /** The position [t] ms after the last sample. */
        fun at(t: Double): Double = p + v * t + a * t * t / 2.0
    }

    private companion object {
        /** Samples before anything is predicted: the filter has a velocity by then. */
        const val MIN_SAMPLES = 4

        /** Slower than this (about 8 mm/s) the pen is still, or moved by the noise of the digitizer: nothing is predicted. */
        const val MIN_SPEED_DP_PER_MS = 0.05

        /** The time between two points predicted. */
        const val STEP_MS = 4.0

        /** Variance of a position measured, in dp²: a stylus reports a fraction of a dp. */
        const val MEASUREMENT_VARIANCE = 0.25

        const val INITIAL_VELOCITY_VARIANCE = 1.0
        const val INITIAL_ACCELERATION_VARIANCE = 0.01

        /** Spectral density of the jerk of a hand writing, in dp²/ms⁵. */
        const val JERK_DENSITY = 2e-5
    }
}
