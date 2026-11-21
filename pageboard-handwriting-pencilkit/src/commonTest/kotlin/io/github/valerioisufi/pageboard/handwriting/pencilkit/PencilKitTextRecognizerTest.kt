package io.github.valerioisufi.pageboard.handwriting.pencilkit

import io.github.valerioisufi.pageboard.handwriting.HandwritingEngines
import io.github.valerioisufi.pageboard.handwriting.HandwritingReading
import io.github.valerioisufi.pageboard.handwriting.InkSample
import io.github.valerioisufi.pageboard.model.StrokePoint
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class PencilKitTextRecognizerTest {
    @Test
    fun theOtherLinesOfTheContentAreTheOtherReadingsOfALine() {
        val reading = PencilKitTextRecognizer.readingOf(
            "a continuous function on a closed interval",
            "a continuous function on a closed interval\na continuous function on a closed interual\na continuous function on a closed interual\n"
        )
        assertEquals("a continuous function on a closed interval", reading.text)
        assertEquals(listOf("a continuous function on a closed interual"), reading.alternatives)
        assertTrue("interual" in reading.searchText)
    }

    @Test
    fun aFormulaReadAsLatexIsNoReadingOfText() {
        val reading = PencilKitTextRecognizer.readingOf("P=P(C(Z))", "P = P \\left( C \\left( Z \\right) \\right)\nP=P(C(Z))")
        assertEquals(emptyList(), reading.alternatives)
        // Found by the search all the same
        assertTrue("\\left" in reading.searchText)
    }

    @Test
    fun aBlockOfLinesHasNoOtherReadings() {
        val reading = PencilKitTextRecognizer.readingOf("first line\nsecond line", "first line\nsecond line\nfirst lime")
        assertEquals("first line\nsecond line", reading.text)
        assertEquals(emptyList(), reading.alternatives)
    }

    @Test
    fun theSamplesArePointsOnOneClockStrokeByStroke() {
        val samples = PencilKitSamples.of(
            InkSample(listOf(listOf(StrokePoint(1f, 2f, 0.3f, 0L), StrokePoint(2f, 2f, -1f, 10L)), listOf(StrokePoint(3f, 4f, 2f, 160L))))
        )
        val k = PencilKitTextRecognizer.POINTS_PER_MM
        assertContentEquals(floatArrayOf(1f * k, 2f * k, 2f * k, 2f * k, 3f * k, 4f * k), samples.xy)
        assertContentEquals(doubleArrayOf(0.0, 10.0, 160.0), samples.times)
        // A pressure unknown or out of range is the middle one
        assertContentEquals(floatArrayOf(0.3f, 0.5f, 0.5f), samples.pressures)
        assertContentEquals(intArrayOf(2, 1), samples.sizes)
    }

    @Test
    fun theAvailabilityIsCheckedAgainWhenItExpiresOrIsForgotten() = runTest {
        val time = TestTimeSource()
        var answer = true
        var checks = 0
        val availability = Availability({ checks++; answer }, whileAvailable = 60.seconds, whileUnavailable = 5.seconds, time = time)
        assertTrue(availability.isAvailable())
        answer = false
        time += 30.seconds
        assertTrue(availability.isAvailable())
        assertEquals(1, checks)
        time += 31.seconds
        assertEquals(false, availability.isAvailable())
        time += 4.seconds
        assertEquals(false, availability.isAvailable())
        assertEquals(2, checks)
        answer = true
        availability.forget()
        assertTrue(availability.isAvailable())
        assertEquals(3, checks)
    }

    @Test
    fun blockCapitalsAreReadWhereTheSystemReadsAndNothingElsewhere() = runTest {
        val recognizer = PencilKitTextRecognizer()
        val languages = recognizer.languages()
        println("PencilKit ${pencilKitVersion()} reads: $languages (of ${pencilKitLanguages()})")
        // In a test the system reads nothing (not an application in the foreground): the bench of
        // tools/handwriting-bench/apple reads with the same bridge
        if ("en" !in languages) return@runTest
        val read = HandwritingReading(HandwritingEngines(listOf(recognizer))).readText(capitals(), "en")
        assertTrue(read?.searchText.orEmpty().uppercase().contains("HELLO"), "read: ${read?.text}")
    }

    /** HELLO in block capitals, 10 mm high. */
    private fun capitals(): InkSample {
        val strokes = mutableListOf<List<Pair<Float, Float>>>()
        fun line(vararg p: Pair<Float, Float>) { strokes.add(p.toList()) }
        line(0f to 0f, 0f to 10f); line(6f to 0f, 6f to 10f); line(0f to 5f, 6f to 5f)
        line(10f to 0f, 10f to 10f); line(10f to 0f, 15f to 0f); line(10f to 5f, 14f to 5f); line(10f to 10f, 15f to 10f)
        line(19f to 0f, 19f to 10f, 24f to 10f); line(28f to 0f, 28f to 10f, 33f to 10f)
        line(*((0..24).map { i -> val a = 2 * PI * i / 24; (40f + 3.5f * cos(a).toFloat()) to (5f + 5f * sin(a).toFloat()) }).toTypedArray())
        var t = 0L
        return InkSample(strokes.map { stroke ->
            stroke.zipWithNext().flatMap { (a, b) -> (0 until 8).map { s -> (a.first + (b.first - a.first) * s / 8) to (a.second + (b.second - a.second) * s / 8) } }.plus(stroke.last())
                .map { (x, y) -> StrokePoint(x, y, 0.5f, t.also { t += 10L }) }.also { t += 200L }
        })
    }
}
