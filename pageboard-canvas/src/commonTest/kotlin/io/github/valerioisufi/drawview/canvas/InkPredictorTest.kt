package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The prediction of the pen (see `docs/plans/ink-latency.md`): a frame ahead, the tip predicted is nearer
 * to where the pen really is than the last sample, and a pen that stops is not drawn beyond.
 */
class InkPredictorTest {
    private val density = 2f

    /** A line of handwriting, in dp at a time in ms: a drift to the right with loops. */
    private fun handwriting(t: Double) = Offset((0.25 * t + 6 * sin(2 * PI * t / 180)).toFloat(), (10 * sin(2 * PI * t / 150 + 1)).toFloat())

    /** A circle drawn in 600 ms. */
    private fun circle(t: Double) = Offset((40 * cos(2 * PI * t / 600)).toFloat(), (40 * sin(2 * PI * t / 600)).toFloat())

    /** A line of 300 dp that starts and stops smoothly in 600 ms, then stays. */
    private fun line(t: Double) = Offset((300 * (1 - cos(PI * minOf(t, 600.0) / 600)) / 2).toFloat(), 0f)

    @Test
    fun theTipIsNearerToThePen() {
        for ((name, path) in listOf("handwriting" to ::handwriting, "circle" to ::circle, "line" to ::line)) {
            for (hz in listOf(240, 120, 60)) {
                val (predicted, last) = errors(path, hz, durationMs = 600.0)
                println("PREDICTION $name at $hz Hz: predicted ${predicted.mean()} (p95 ${predicted.p95()}) dp, last sample ${last.mean()} (p95 ${last.p95()}) dp")
                assertTrue(predicted.mean() < 0.6f * last.mean(), "$name at $hz Hz: ${predicted.mean()} against ${last.mean()}")
                assertTrue(predicted.p95() < last.p95(), "$name at $hz Hz: p95 ${predicted.p95()} against ${last.p95()}")
            }
        }
    }

    @Test
    fun aPenThatStopsIsNotDrawnBeyond() {
        for (hz in listOf(240, 120, 60)) {
            val predictor = KalmanInkPredictor { density }
            predictor.start()
            val random = Random(1)
            val step = 1000.0 / hz
            var t = 0.0
            var overshoot = 0f
            while (t < 900.0) {
                val position = line(t)
                predictor.record(noisy(position, random) * density, t.toLong())
                // Once stopped (from 600 ms) the tip stays near the end of the line
                if (t >= 600.0 + 3 * step) {
                    val tip = predictor.predict(16L).lastOrNull()?.position?.div(density)
                    if (tip != null) overshoot = maxOf(overshoot, tip.x - 300f)
                }
                t += step
            }
            println("PREDICTION stop at $hz Hz: overshoot $overshoot dp")
            assertTrue(overshoot < 1f, "at $hz Hz the tip went $overshoot dp beyond where the pen stopped")
        }
    }

    /**
     * The distance from where the pen is 16 ms after each sample to the tip predicted then, and to
     * the last sample (what is drawn without prediction), in dp.
     */
    private fun errors(path: (Double) -> Offset, hz: Int, durationMs: Double): Pair<List<Float>, List<Float>> {
        val predictor = KalmanInkPredictor { density }
        predictor.start()
        val random = Random(7)
        val step = 1000.0 / hz
        val predicted = mutableListOf<Float>()
        val last = mutableListOf<Float>()
        var t = 0.0
        var count = 0
        while (t < durationMs) {
            val sample = noisy(path(t), random)
            predictor.record(sample * density, t.toLong())
            val truth = path(t.toLong() + 16.0)
            val tip = predictor.predict(16L).lastOrNull()?.position?.div(density) ?: sample
            if (count >= 6) {
                predicted += (tip - truth).getDistance()
                last += (sample - truth).getDistance()
            }
            count++
            t += step
        }
        return predicted to last
    }

    /** [position] as a stylus reports it, within a fifth of a dp. */
    private fun noisy(position: Offset, random: Random) =
        position + Offset(random.nextFloat() * 0.4f - 0.2f, random.nextFloat() * 0.4f - 0.2f)

    private fun List<Float>.mean() = sum() / size
    private fun List<Float>.p95() = sorted()[(size - 1) * 95 / 100]
}
