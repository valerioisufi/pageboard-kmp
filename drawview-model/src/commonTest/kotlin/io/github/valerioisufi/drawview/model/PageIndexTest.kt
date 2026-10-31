package io.github.valerioisufi.drawview.model

import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.DocumentSession
import io.github.valerioisufi.drawview.model.change.PlaceElements
import io.github.valerioisufi.drawview.model.change.RemoveElements
import io.github.valerioisufi.drawview.model.change.StrokeData
import io.github.valerioisufi.drawview.model.change.TextData
import io.github.valerioisufi.drawview.model.change.placementOf
import io.github.valerioisufi.drawview.model.change.toData
import io.github.valerioisufi.drawview.model.geometry.Rect
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The elements of a page found by their identifier (see `docs/performance.md`, phase 4): a page
 * loaded at once is the page built element by element, and its elements are found wherever the
 * changes put them.
 */
class PageIndexTest {

    @Test
    fun aPageLoadedAtOnceIsThePageBuiltElementByElement() {
        val built = DrawPage()
        val shown = elements()
        shown.forEach(built::add)
        val removed = elements(prefix = "gone").onEach { it.removed = Stamp(9L, 0, "ada") }
        removed.forEach(built::keepRemoved)

        val loaded = DrawPage()
        val shownAgain = elements()
        val removedAgain = elements(prefix = "gone").onEach { it.removed = Stamp(9L, 0, "ada") }
        loaded.load(shownAgain, removedAgain)

        fun List<DrawElement>.described() = map { "${it.id} ${it.order.key}" }
        assertEquals(built.strokes.described(), loaded.strokes.described())
        assertEquals(built.texts.described(), loaded.texts.described())
        assertEquals(built.images.described(), loaded.images.described())
        assertEquals(built.pdfs.described(), loaded.pdfs.described())
        assertEquals(built.markups.described(), loaded.markups.described())
        assertEquals(built.customElements.described(), loaded.customElements.described())
        assertEquals(built.stack.described(), loaded.stack.described())
        assertEquals(built.removedElements.described(), loaded.removedElements.described())
        for (element in shownAgain) {
            assertSame(element, loaded.find(element.id))
            assertNull(loaded.findRemoved(element.id))
        }
        for (element in removedAgain) {
            assertSame(element, loaded.findRemoved(element.id))
            assertNull(loaded.find(element.id))
        }

        // Loaded on a page with elements: after them, as add puts them
        val more = elements(prefix = "more")
        val moreAgain = elements(prefix = "more")
        more.forEach(built::add)
        loaded.load(moreAgain)
        assertEquals(built.stack.described(), loaded.stack.described())
        assertSame(moreAgain.first(), loaded.find(moreAgain.first().id))
    }

    @Test
    fun theElementsAreFoundWhereverTheChangesPutThem() {
        val random = Random(4)
        val document = DrawDocument()
        repeat(2) { document.addPage() }
        val session = DocumentSession(document, replica = "ada")
        val known = mutableSetOf<String>()
        repeat(STEPS) { step ->
            val page = document.pages.random(random)
            val shown = document.pages.flatMap { p -> p.elements().map { p to it } }
            val hidden = document.pages.flatMap { p -> p.removedElements.map { p to it } }
            when (random.nextInt(6)) {
                0, 1 -> {
                    val element = if (random.nextBoolean()) stroke("s$step") else text("t$step")
                    known.add(element.id)
                    session.submit(AddElements.of(page, listOf(element)))
                }
                2 -> shown.randomOrNull(random)?.let { (from, element) -> session.submit(RemoveElements(from.id, listOf(element.id))) }
                3 -> hidden.randomOrNull(random)?.let { (from, element) -> session.submit(AddElements(from.id, listOf(element.toData()))) }
                4 -> (shown + hidden).randomOrNull(random)?.let { (from, element) ->
                    // Moved to another page, shown or hidden
                    session.submit(PlaceElements(from.id, listOfNotNull(placementOf(element)), toPageId = page.id))
                }
                else -> if (random.nextInt(4) == 0) document.purgeHidden { true }
            }
            checkFound(document, known, "step $step")
        }
    }

    /** Every element known is found where the lists of the pages have it, and only there. */
    private fun checkFound(document: DrawDocument, known: Set<String>, step: String) {
        for (id in known) {
            val shownOn = document.allPages().filter { page -> page.elements().any { it.id == id } }
            val hiddenOn = document.allPages().filter { page -> page.removedElements.any { it.id == id } }
            assertEquals(1, shownOn.size + hiddenOn.size + (if (document.findElement(id) == null && document.findRemovedElement(id) == null) 1 else 0), "$step: $id once")
            for (page in document.allPages()) {
                assertSame(page.elements().firstOrNull { it.id == id }, page.find(id), "$step: $id shown on ${page.id}")
                assertSame(page.removedElements.firstOrNull { it.id == id }, page.findRemoved(id), "$step: $id hidden on ${page.id}")
            }
            assertSame(shownOn.singleOrNull(), document.findElement(id)?.first, "$step: the page of $id")
            assertSame(hiddenOn.singleOrNull(), document.findRemovedElement(id)?.first, "$step: the page of $id hidden")
        }
    }

    /** Elements of every kind, some without order, some with orders of the changes or of a page built. */
    private fun elements(prefix: String = "e"): List<DrawElement> = listOf(
        stroke("$prefix-s1"),
        stroke("$prefix-s2").withOrder(ElementOrder.onTop(Stamp(5L, 0, "ada"), 0)),
        stroke("$prefix-s3"),
        stroke("$prefix-s4").withOrder(ElementOrder.built(ElementOrder.STROKE_LAYER, 7)),
        stroke("$prefix-s5"),
        text("$prefix-t1"),
        text("$prefix-t2").withOrder(ElementOrder.onTop(Stamp(4L, 0, "ada"), 1)),
        DrawImage("$prefix-i1").apply { resourceId = "image" },
        DrawPdf(2, "$prefix-p1").apply { resourceId = "slides" },
        DrawTextMarkup("$prefix-m1", "slides", 2, 100f, 100f, 0, 4, "text", listOf(Rect(0f, 0f, 10f, 2f)), TextMarkupKind.HIGHLIGHT, 0x66FFFF00),
        DrawCustomElement("formula", "$prefix-c1"),
        stroke("$prefix-s6").withOrder(ElementOrder.onTop(Stamp(6L, 0, "ada"), 0))
    )

    private fun <T : DrawElement> T.withOrder(order: ElementOrder): T = apply { this.order = order }

    private fun stroke(id: String): DrawStroke = StrokeData(
        id = id,
        points = List(4) { i -> StrokePoint(10f + i, 20f, 0.5f, i * 8L) },
        tool = StrokeTool.PRESSURE_PEN,
        size = 0.5f,
        color = 0xFF000000.toInt()
    ).toElement(StrokeModeller { _, _, _ -> null })

    private fun text(id: String): DrawText =
        TextData(id, "text $id", x = 20f, y = 30f, width = 50f, color = 0xFF000000.toInt(), fontSize = 5f).toElement(StrokeModeller { _, _, _ -> null })

    private companion object {
        const val STEPS = 400
    }
}
