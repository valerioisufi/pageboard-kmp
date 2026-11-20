package io.github.valerioisufi.pageboard.handwriting.windows

import io.github.valerioisufi.pageboard.handwriting.HandwritingEngines
import io.github.valerioisufi.pageboard.handwriting.HandwritingReading
import io.github.valerioisufi.pageboard.handwriting.InkSample
import io.github.valerioisufi.pageboard.model.StrokePoint
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsInkRecognizerTest {
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    @Test
    fun blockCapitalsAreReadOnWindowsAndNothingElsewhere() = runTest {
        val reading = HandwritingReading(HandwritingEngines(listOf(WindowsInkRecognizer())))
        val languages = WindowsInkRecognizer().languages()
        if (!windows) {
            assertEquals(emptySet(), languages)
            return@runTest
        }
        println("The recognizers of Windows read: $languages")
        // A Windows with the English handwriting of its languages (the default of an English or Italian Windows)
        if ("en" !in languages) return@runTest
        val read = reading.readText(capitals(), "en-US")
        println("Read: ${read?.text} (${read?.searchText})")
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
