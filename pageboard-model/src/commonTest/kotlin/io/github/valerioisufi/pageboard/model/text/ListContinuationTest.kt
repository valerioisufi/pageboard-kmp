package io.github.valerioisufi.pageboard.model.text

import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.TextElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** A numbered list going on from the list of another text (`docs/plans/checklists-and-lists.md`). */
class ListContinuationTest {

    private fun text(id: String, content: String, from: String? = null, y: Float = 0f) = TextElement(id).apply {
        text = content
        continueNumberingFrom = from
        this.y = y
    }

    private fun startOf(text: TextElement, vararg others: TextElement): Int? {
        val all = (others.toList() + text).associateBy { it.id }
        return ListContinuation.startOf(text) { all[it] }
    }

    @Test
    fun aListGoesOnAfterTheLastNumberOfTheTextItNames() {
        val first = text("a", "Intro\n\n1. x\n2. y\n3. z")
        val second = text("b", "1. w\n2. v", from = "a")
        assertEquals(4, startOf(second, first))
        val labels = ListNumbering.items(second.text, RichTextParser.parse(second.text), 4).map { it.label }
        assertEquals(listOf("4.", "5."), labels)
        // Only the first list of the first level
        val nested = "1. a\n  1. b\n\ntext\n\n1. c"
        assertEquals(listOf("7.", "a.", "1."), ListNumbering.items(nested, RichTextParser.parse(nested), 7).map { it.label })
    }

    @Test
    fun aTextGoingOnFromATextThatGoesOnCountsFromBoth() {
        val a = text("a", "1. x\n2. y")
        val b = text("b", "1. z", from = "a")
        val c = text("c", "1. w", from = "b")
        assertEquals(4, startOf(c, a, b))
    }

    @Test
    fun aCycleOrAMissingTextContinuesNothing() {
        val a = text("a", "1. x", from = "b")
        val b = text("b", "1. y", from = "a")
        assertNull(startOf(a, b))
        assertNull(startOf(b, a))
        assertNull(startOf(text("c", "1. x", from = "nowhere")))
        assertNull(startOf(text("d", "1. x", from = "d")))
        assertNull(startOf(text("e", "1. x", from = "f"), text("f", "no list here")))
    }

    @Test
    fun theStartsAreSetInTheDocumentAndFollowTheTextBefore() {
        val document = Document().apply { addPage() }
        val page = document.pages[0]
        val first = text("a", "1. x\n2. y", y = 10f)
        val second = text("b", "1. z", from = "a", y = 50f)
        page.add(first)
        page.add(second)
        assertEquals(listOf(page to second), ListContinuation.apply(document))
        assertEquals(3, second.continuedListStart)
        assertEquals(emptyList(), ListContinuation.apply(document), "nothing changed")
        first.text = "1. x\n2. y\n3. more"
        ListContinuation.apply(document)
        assertEquals(4, second.continuedListStart)
        second.continueNumberingFrom = null
        ListContinuation.apply(document)
        assertNull(second.continuedListStart)
        // The text before it with a list
        assertSame(first, ListContinuation.previousListText(document, page, "b", 50f))
        assertNull(ListContinuation.previousListText(document, page, "a", 10f))
    }
}
