package io.github.valerioisufi.pageboard.model

import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.DocumentSession
import io.github.valerioisufi.pageboard.model.geometry.Rect
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The orders of the elements: the pages written before the one stack keep their look, a key can
 * always be made between two others, the versions before never read a key as a stamp, and a
 * malformed key is no order.
 */
class ElementOrderTest {

    /** An order as the versions before the one stack wrote it. */
    private fun before(time: Long, index: Int, replica: String = "ada"): ElementOrder =
        assertNotNull(ElementOrder.parse("${Stamp(time, 0, replica)}/" + index.toString(16).padStart(8, '0')))

    /** How the versions before the one stack read an order: its stamp, or `null` for no order. */
    private fun stampReadBefore(text: String): Stamp? {
        val slash = text.lastIndexOf('/')
        if (slash < 0) return null
        val stamp = Stamp.parse(text.substring(0, slash)) ?: return null
        text.substring(slash + 1).toIntOrNull(16) ?: return null
        return stamp
    }

    private fun stroke(x: Float = 0f): DrawStroke {
        val mesh = StrokeMesh(
            positions = floatArrayOf(x, 0f, x + 5f, 0f, x + 5f, 5f),
            indices = shortArrayOf(0, 1, 2),
            color = 0,
            tool = StrokeTool.MARKER,
            size = 1f,
            points = listOf(StrokePoint(x, 0f), StrokePoint(x + 5f, 5f))
        )
        return DrawStroke(mesh = mesh, brushConfig = BrushConfig(tool = StrokeTool.MARKER, size = 1f))
    }

    private fun markup() = DrawTextMarkup(
        resourceId = "pdf", pdfPageIndex = 0, pdfWidthPt = 595f, pdfHeightPt = 842f, startIndex = 0, endIndex = 4,
        text = "wave", rects = listOf(Rect(0f, 0f, 10f, 10f)), kind = TextMarkupKind.HIGHLIGHT, color = 0
    )

    @Test
    fun aPageWrittenBeforeTheOneStackKeepsItsLook() {
        // Stamps mixing the kinds: the newest is a PDF page, the oldest a stroke
        val stroke = stroke().apply { order = before(100, 0) }
        val newerStroke = stroke().apply { order = before(200, 0) }
        val text = DrawText().apply { order = before(400, 0) }
        val image = DrawImage().apply { order = before(500, 1) }
        val lowerImage = DrawImage().apply { order = before(500, 0) }
        val custom = DrawCustomElement("com.example.shape").apply { order = before(300, 0) }
        val pdf = DrawPdf().apply { order = before(600, 0) }
        val markup = markup().apply { order = before(50, 0) }
        val page = DrawPage()
        listOf(stroke, newerStroke, text, image, lowerImage, custom, pdf, markup).shuffled(Random(3)).forEach(page::add)

        assertEquals(listOf(pdf, markup, lowerImage, image, custom, text, stroke, newerStroke), page.snapshotContent().paintOrder)
        assertEquals(listOf(lowerImage, image, custom, text, stroke, newerStroke), page.stack)
        // Kept as it is, read in the layer of its kind
        assertEquals("U" + before(100, 0).key, stroke.order.key)
    }

    @Test
    fun aPageBuiltOutsideOfTheChangesKeepsTheLayersOfItsKinds() {
        val page = DrawPage()
        val strokes = List(3) { stroke(it * 10f) }
        val image = DrawImage()
        val text = DrawText()
        strokes.forEach(page::add)
        page.add(image)
        page.add(text)

        assertEquals(listOf(image, text) + strokes, page.stack)
    }

    @Test
    fun aNewElementIsOnTopOfEverything() {
        val document = DrawDocument()
        val page = document.pages[0]
        val stroke = stroke().apply { order = before(100, 0) }
        page.add(stroke)
        val session = DocumentSession(document, replica = "ada")
        val image = DrawImage().apply { width = 10f; height = 10f }

        session.submit(AddElements.of(page, listOf(image)))

        assertEquals(listOf(stroke, image), page.stack)
        assertTrue(image.order.key.startsWith("V"))
    }

    @Test
    fun aKeyBetweenTwoIsBetweenThem() {
        val random = Random(11)
        val replicas = listOf("ada", "bob", "8f7e2c1a-4b3d-4e5f-9a8b-7c6d5e4f3a2b")
        val keys = mutableListOf<ElementOrder>()
        repeat(20) { i ->
            keys += before(random.nextLong(1, 1_000_000), random.nextInt(4), replicas.random(random)).inLayer("RSTU"[i % 4])
            keys += ElementOrder.onTop(Stamp(random.nextLong(1, 1_000_000), random.nextInt(3), replicas.random(random)), random.nextInt(4))
        }
        repeat(3000) {
            val sorted = keys.distinct().sorted()
            val at = random.nextInt(sorted.size)
            val below = if (at == 0 || random.nextInt(8) == 0) null else sorted[at - 1]
            val above = sorted[at]
            if (below != null && below >= above) return@repeat
            val count = if (random.nextInt(5) == 0) random.nextInt(2, 7) else 1
            val made = ElementOrder.between(below, above, count)
            assertEquals(count, made.size)
            for ((i, key) in made.withIndex()) {
                assertTrue(below == null || below < key, "$below < $key")
                assertTrue(key < above, "$key < $above")
                assertTrue(i == 0 || made[i - 1] < key)
                assertEquals(key, ElementOrder.parse(key.key), "well formed: $key")
            }
            keys += made
        }
        assertTrue(keys.all { it.key.length <= ElementOrder.MAX_LENGTH })
    }

    @Test
    fun aKeyOnTopIsAboveTheKeysMadeBefore() {
        val older = Stamp(1_000, 0, "bob")
        val keys = listOf(
            before(5_000, 7).inLayer('U'),
            ElementOrder.onTop(older, 3),
            ElementOrder.between(ElementOrder.onTop(older, 3), ElementOrder.onTop(Stamp(1_000, 1, "ada"), 0))
        )
        val later = ElementOrder.onTop(Stamp(1_000, 1, "bob"), 0)
        assertTrue(keys.all { it < later })
    }

    @Test
    fun theVersionsBeforeTheOneStackReadTheNewKeysAsNoOrder() {
        val keys = listOf(
            before(1_729_000_000_000, 2).inLayer('U'),
            before(0, 0).inLayer('R'),
            ElementOrder.onTop(Stamp(1_729_000_000_000, 4, "ada"), 1),
            ElementOrder.between(null, ElementOrder.onTop(Stamp(9, 0, "ada"), 0)),
            ElementOrder.between(before(10, 0).inLayer('T'), before(10, 1).inLayer('T'))
        ) + ElementOrder.spread(100)
        for (key in keys) assertNull(stampReadBefore(key.key), key.key)
    }

    @Test
    fun aMalformedKeyIsNoOrder() {
        for (text in listOf(
            "", "!", "A0", "F", "G!", "G\u0007", "Gé", "G" + "x".repeat(ElementOrder.MAX_LENGTH),
            "000000000064.00000000.ada/xyz", "000000000064.00000000.ada/0000000", "0zz"
        )) {
            assertNull(ElementOrder.parse(text), text)
        }
        assertNotNull(ElementOrder.parse("GV"))
        assertNotNull(ElementOrder.parse("000000000064.00000000.ada/00000001"))
    }

    @Test
    fun aStackKeyedAgainHasShortKeysUnderTheOnesOnTop() {
        val keys = ElementOrder.spread(10_000)
        assertEquals(keys.sorted(), keys)
        assertEquals(keys.size, keys.distinct().size)
        assertTrue(keys.all { ElementOrder.parse(it.key) == it && it.key.length <= 4 })
        assertTrue(keys.last() < ElementOrder.onTop(Stamp.ZERO, 0))
        assertTrue(keys.first() > ElementOrder.between(null, keys.first()))
    }
}
