package io.github.valerioisufi.pageboard.handwriting

import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.change.StrokeData
import io.github.valerioisufi.pageboard.model.geometry.AffineTransform
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandwritingReadingTest {
    /** A letter: a zigzag [heightMm] high at [x], [top], drawn from [startMs]. */
    private fun letter(x: Float, top: Float, heightMm: Float = 4f, startMs: Long = 0L): List<StrokePoint> =
        (0..8).map { i -> StrokePoint(x + i * 0.3f, top + if (i % 2 == 0) 0f else heightMm, 0.5f, startMs + i * 8L) }

    private fun dot(x: Float, y: Float) = listOf(StrokePoint(x, y, 0.5f, 0L), StrokePoint(x + 0.1f, y + 0.1f, 0.5f, 8L))

    private fun circle(cx: Float, cy: Float, r: Float) = (0..40).map { i ->
        val a = 2 * PI * i / 40
        StrokePoint(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), 0.5f, i * 8L)
    }

    private fun line(top: Float, letters: Int, startX: Float = 10f) = (0 until letters).map { letter(startX + it * 4f, top) }

    @Test
    fun aBlockIsSplitIntoItsLinesFromTheTop() {
        val first = line(10f, 6)
        val second = line(20f, 5)
        // Written in any order: the second line first
        val layout = InkLines().split(InkSample(second + first))
        assertEquals(2, layout.lines.size)
        assertEquals(6, layout.lines[0].strokes.size)
        assertEquals(5, layout.lines[1].strokes.size)
        assertTrue(layout.drawing.isEmpty)
    }

    @Test
    fun theDotsGoToTheLineBelowThemAndADrawingIsLeftOut() {
        val writing = line(30f, 6) + listOf(dot(14f, 28.8f), dot(22f, 28.8f))
        val drawing = circle(40f, 60f, 20f)
        val layout = InkLines().split(InkSample(writing + listOf(drawing)))
        assertEquals(1, layout.lines.size, "the dots on their line")
        assertEquals(8, layout.lines.single().strokes.size)
        assertEquals(listOf(drawing), layout.drawing.strokes)
    }

    @Test
    fun blockCapitalsWithMoreBarsThanStemsAreWritingNotDrawing() {
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) = (0..8).map { k -> StrokePoint(x0 + (x1 - x0) * k / 8, y0 + (y1 - y0) * k / 8, 0.5f, k * 8L) }
        // FILE, 20 mm high: four stems and six bars
        val file = listOf(
            line(0f, 0f, 0f, 20f), line(0f, 0f, 10f, 0f), line(0f, 10f, 8f, 10f),
            line(18f, 0f, 18f, 20f),
            line(27f, 0f, 27f, 20f), line(27f, 20f, 37f, 20f),
            line(45f, 0f, 45f, 20f), line(45f, 0f, 55f, 0f), line(45f, 10f, 53f, 10f), line(45f, 20f, 55f, 20f)
        )
        val layout = InkLines().split(InkSample(file))
        assertTrue(layout.drawing.isEmpty, "nothing left out as drawing")
        assertEquals(1, layout.lines.size)
        assertEquals(10, layout.lines.single().strokes.size)
    }

    @Test
    fun theTimesAreMadeOneClockAcrossTheStrokes() {
        // Two strokes of two sessions: the second recorded earlier than the first
        val ink = InkSample(listOf(letter(0f, 0f, startMs = 900_000L), letter(4f, 0f, startMs = 1_000L), listOf(StrokePoint(9f, 0f), StrokePoint(9f, 3f))))
        val times = ink.retimed().strokes.flatten().map { it.timestampMs }
        assertEquals(0L, times.first())
        assertTrue(times.zipWithNext().all { (a, b) -> b >= a }, "increasing: $times")
        val strokes = ink.retimed().strokes
        assertEquals(64L, strokes[0].last().timestampMs - strokes[0].first().timestampMs, "a stroke keeps its durations")
        assertEquals(InkSample.STROKE_GAP_MS, strokes[1].first().timestampMs - strokes[0].last().timestampMs)
        assertEquals(InkSample.SAMPLE_MS, strokes[2][1].timestampMs - strokes[2][0].timestampMs, "a stroke without times")
    }

    @Test
    fun theStoredStrokesArePlacedAndTheHighlightsLeftOut() {
        val pen = StrokeData("a", letter(0f, 0f), StrokeTool.PRESSURE_PEN, 0.5f, 0, transform = AffineTransform(1f, 0f, 0f, 1f, 10f, 20f))
        val highlight = StrokeData("b", letter(0f, 0f), StrokeTool.HIGHLIGHTER, 4f, 0)
        val empty = StrokeData("c", emptyList(), StrokeTool.PRESSURE_PEN, 0.5f, 0)
        val ink = InkSample.ofData(listOf(pen, highlight, empty))
        assertEquals(1, ink.strokes.size)
        assertEquals(1, ink.missing, "a stroke without samples is counted")
        assertEquals(10f, ink.strokes.single().first().x)
        assertEquals(20f, ink.strokes.single().first().y)
    }

    /** Reads every line as the number of its strokes; knows only Italian. */
    private class CountingReader(override val readsBlocks: Boolean = false) : TextInkRecognizer {
        val contexts = mutableListOf<String>()
        override val version = "counting 1"
        override suspend fun languages() = setOf("it-IT")
        override suspend fun recognize(ink: InkSample, language: String, context: String): InkReading {
            contexts.add(context)
            val times = ink.strokes.flatten().map { it.timestampMs }
            check(times.zipWithNext().all { (a, b) -> b >= a }) { "one clock" }
            return InkReading("${ink.strokes.size} tratti", listOf("${ink.strokes.size} tratto"), "${ink.strokes.size} tratti tratto")
        }
    }

    @Test
    fun eachLineIsReadWithTheLineBeforeAsItsContext() = runTest {
        val reader = CountingReader()
        val reading = HandwritingReading(HandwritingEngines(listOf(reader)))
        val ink = InkSample(line(10f, 3) + line(20f, 4))
        val read = assertNotNull(reading.readText(ink, "it", context = "titolo"))
        assertEquals("3 tratti\n4 tratti", read.text)
        assertEquals(listOf("titolo", "3 tratti"), reader.contexts)
        assertEquals("3 tratti tratto\n4 tratti tratto", read.searchText)
        assertEquals(emptyList(), read.alternatives, "the readings of several lines are not put together")
        assertEquals("counting 1; lines ${InkLines.RULES}", reading.version("it-CH"))
    }

    @Test
    fun aReaderOfBlocksTakesTheWholeBlockWithoutItsDrawing() = runTest {
        val reader = CountingReader(readsBlocks = true)
        val reading = HandwritingReading(HandwritingEngines(listOf(reader)))
        val read = assertNotNull(reading.readText(InkSample(line(10f, 3) + line(20f, 4) + listOf(circle(40f, 60f, 20f))), "it"))
        assertEquals("7 tratti", read.text)
        assertEquals(listOf("7 tratto"), read.alternatives)
    }

    @Test
    fun nothingIsReadWithoutAReaderOfTheLanguage() = runTest {
        val reading = HandwritingReading(HandwritingEngines(listOf(CountingReader())))
        assertNull(reading.readText(InkSample(line(10f, 3)), "en"))
        assertNull(HandwritingReading(HandwritingEngines.None).readText(InkSample(line(10f, 3)), "it"))
        assertNull(reading.readFormula(InkSample(line(10f, 3))))
        assertEquals(false, reading.readsText("en"))
    }

    @Test
    fun aReaderFailingReadsNothing() = runTest {
        val failing = object : TextInkRecognizer {
            override val version = "failing"
            override suspend fun languages() = setOf("it")
            override suspend fun recognize(ink: InkSample, language: String, context: String): InkReading? = error("the system")
        }
        assertNull(HandwritingReading(HandwritingEngines(listOf(failing))).readText(InkSample(line(10f, 3)), "it"))
    }

    @Test
    fun anInkGoesThroughInkMlAsItIs() {
        val ink = InkSample(listOf(letter(1.25f, 2f, startMs = 10L), dot(3f, 4f)))
        val read = InkMl.read(InkMl.write(ink, mapOf("label" to "a < b & \"c\"")))
        assertEquals("a < b & \"c\"", read.label)
        // To the thousandth of a millimeter
        fun rounded(sample: InkSample) = sample.strokes.map { s -> s.map { Triple(kotlin.math.round(it.x * 1000), kotlin.math.round(it.y * 1000), it.timestampMs) } }
        assertEquals(rounded(ink), rounded(read.sample))
    }

    @Test
    fun anInkOfMathWritingIsRead() {
        val text = """
            <ink xmlns="http://www.w3.org/2003/InkML">
            <annotation type="normalizedLabel">x^{2}</annotation>
            <trace>10.5 20 0, 11 21.5 16</trace>
            <trace>12 22 40</trace>
            </ink>
        """.trimIndent()
        val read = InkMl.read(text)
        assertEquals("x^{2}", read.label)
        assertEquals(listOf(2, 1), read.sample.strokes.map { it.size })
        assertEquals(16L, read.sample.strokes[0][1].timestampMs)
    }
}
