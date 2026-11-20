package io.github.valerioisufi.pageboard.handwriting.ui

import io.github.valerioisufi.pageboard.canvas.port.EditorDocument
import io.github.valerioisufi.pageboard.handwriting.HandwritingEngines
import io.github.valerioisufi.pageboard.handwriting.HandwritingReading
import io.github.valerioisufi.pageboard.handwriting.InkReading
import io.github.valerioisufi.pageboard.handwriting.InkSample
import io.github.valerioisufi.pageboard.handwriting.TextInkRecognizer
import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.ChangeObserver
import io.github.valerioisufi.pageboard.model.change.DocumentChange
import io.github.valerioisufi.pageboard.model.change.RemoveElements
import io.github.valerioisufi.pageboard.model.change.StrokeData
import io.github.valerioisufi.pageboard.model.change.TextData
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandwritingConversionTest {
    private class Reader : TextInkRecognizer {
        override val version = "fake"
        override suspend fun languages() = setOf("it")
        override suspend fun recognize(ink: InkSample, language: String, context: String) = InkReading("ciao", listOf("cia0"))
    }

    /** A word of [letters] letters [heightMm] high at ([x], [y]), in [color]. */
    private fun word(x: Float, y: Float, letters: Int, heightMm: Float, color: Int) = (0 until letters).map { i ->
        StrokeData(
            "s$i",
            (0..6).map { k -> StrokePoint(x + i * 4f + k * 0.4f, y + if (k % 2 == 0) 0f else heightMm, 0.5f, k * 8L) },
            StrokeTool.PRESSURE_PEN,
            0.5f,
            color
        ).toElement()
    }

    /** A document that records the changes submitted and the groups they are made in. */
    private class RecordingDocument(override val isReadOnly: Boolean = false) : EditorDocument {
        val groups = mutableListOf<List<DocumentChange>>()
        private var current: MutableList<DocumentChange>? = null
        override val document: Document = Document()
        override fun submit(change: DocumentChange): DocumentChange? {
            (current ?: mutableListOf<DocumentChange>().also { groups.add(it) }).add(change)
            return change
        }

        override fun <T> group(block: () -> T): T {
            val changes = mutableListOf<DocumentChange>()
            current = changes
            try {
                return block()
            } finally {
                current = null
                groups.add(changes)
            }
        }

        override fun addObserver(observer: ChangeObserver) {}
        override fun removeObserver(observer: ChangeObserver) {}
    }

    private val conversion = HandwritingConversion(HandwritingReading(HandwritingEngines(listOf(Reader()))), language = { "it-IT" })

    @Test
    fun theStrokesAreReadAndReplacedByATextAtTheirPlaceAsOneChange() = runTest {
        val red = 0xFFD32F2F.toInt()
        val strokes = word(30f, 40f, 4, heightMm = 5f, color = red)
        assertTrue(conversion.readsText())
        val reading = conversion.read(strokes)
        assertEquals("ciao", reading?.text)
        assertEquals(listOf("cia0"), reading?.alternatives)

        val box = conversion.textBox(strokes, "ciao")
        assertEquals(30f, box.x)
        assertEquals(40f, box.y)
        assertEquals(red, box.color)
        assertTrue(box.width >= 14f, "as wide as the strokes: ${box.width}")
        // 5 mm high: a font of about 20 points (0.7 of a point is the height of the writing)
        assertEquals(20f, box.fontSize)

        val document = RecordingDocument()
        conversion.replace(document, "page", strokes, box)
        assertEquals(1, document.groups.size, "one change to undo")
        val (removed, added) = document.groups.single()
        assertEquals(RemoveElements("page", strokes.map { it.id }), removed)
        assertEquals("ciao", ((added as AddElements).elements.single() as TextData).text)
    }

    @Test
    fun aDocumentThatMayOnlyBeReadIsNotChanged() = runTest {
        val strokes = word(30f, 40f, 2, heightMm = 4f, color = 0)
        val document = RecordingDocument(isReadOnly = true)
        assertNull(conversion.replace(document, "page", strokes, conversion.textBox(strokes, "x")))
        assertTrue(document.groups.isEmpty())
    }

    @Test
    fun withoutAReaderOfTheLanguageNothingIsOffered() = runTest {
        val english = HandwritingConversion(HandwritingReading(HandwritingEngines(listOf(Reader()))), language = { "en" })
        assertEquals(false, english.readsText())
        assertNull(english.read(word(0f, 0f, 2, 4f, 0)))
    }
}
