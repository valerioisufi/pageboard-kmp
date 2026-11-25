package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the platforms add to the pointer events for the ink (see `docs/plans/ink-rendering-camera.md`). */
class InkPlatformTest {

    @Test
    fun aSampleTakesThePressureThePlatformReportedForItsTimeAndPlace() {
        val pressures = SamplePressures()
        pressures.record(100L, 10f, 20f, 0.3f)
        pressures.record(104L, 14f, 20f, 0.5f)
        // Two pointers at the same time, told apart by their place
        pressures.record(104L, 300f, 20f, 0.9f)
        assertEquals(0.3f, pressures.pressureAt(100L, Offset(10.5f, 20f)))
        assertEquals(0.5f, pressures.pressureAt(104L, Offset(14f, 20f)))
        assertEquals(0.9f, pressures.pressureAt(104L, Offset(300f, 21f)))
        assertNull(pressures.pressureAt(102L, Offset(12f, 20f)), "no sample at that time")
        assertNull(pressures.pressureAt(100L, Offset(40f, 20f)), "a sample elsewhere")
    }

    @Test
    fun onlyTheLatestSamplesAreKept() {
        val pressures = SamplePressures()
        for (i in 0 until 200) pressures.record(i.toLong(), i.toFloat(), 0f, i / 200f)
        assertNull(pressures.pressureAt(0L, Offset(0f, 0f)))
        assertEquals(199 / 200f, pressures.pressureAt(199L, Offset(199f, 0f)))
    }

    @Test
    fun theKalmanFilterPredictsWhereThePlatformHasNothing() {
        val platform = object : PlatformPrediction {
            var available = false
            var recorded = 0
            override fun start() = Unit
            override fun record(position: Offset, timeMs: Long) {
                recorded++
            }
            override fun predict(horizonMs: Long): List<PredictedPoint>? =
                if (available) listOf(PredictedPoint(Offset(1000f, 0f), 1L)) else null
        }
        val predictor = PreferredInkPredictor(platform, KalmanInkPredictor { 1f })
        predictor.start()
        for (i in 0..20) predictor.record(Offset(i * 4f, 0f), i * 4L)
        assertEquals(21, platform.recorded, "both told every sample")
        val filtered = predictor.predict(16L)
        assertTrue(filtered.isNotEmpty() && filtered.all { it.position.x in 80f..200f }, "the filter: $filtered")
        platform.available = true
        assertEquals(1000f, predictor.predict(16L).single().position.x, "the platform when it has one")
    }

    @Test
    fun thePredictionReachesAFrameOfTheDisplay() {
        assertEquals(16L, frameMillisOf(60f))
        assertEquals(11L, frameMillisOf(90f))
        assertEquals(8L, frameMillisOf(120f))
        assertEquals(16L, frameMillisOf(0f), "not known: 60 Hz")
        assertEquals(4L, frameMillisOf(480f))
    }
}
