package io.github.valerioisufi.drawview.model.text

import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.AddPage
import io.github.valerioisufi.drawview.model.change.DocumentSession
import io.github.valerioisufi.drawview.model.change.UpdateTexts
import io.github.valerioisufi.drawview.model.change.toData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The chains of text boxes across the pages: read from their links, the links not trusted. */
class TextChainsTest {

    private val session = DocumentSession(DrawDocument())
    private val document get() = session.document

    /** A page with a text of [content] going on into [next]. */
    private fun page(id: String, content: String, next: String? = null, continuesLine: Boolean = false): DrawText {
        val page = DrawPage(orderKey = document.orderKeyAt(document.pages.size))
        session.submit(AddPage.of(page))
        val text = DrawText(id).apply {
            text = content; x = 10f; y = 10f; width = 100f
            this.next = next
            this.continuesLine = continuesLine
        }
        session.submit(AddElements.of(document.pageById(page.id)!!, listOf(text)))
        return document.pageById(page.id)!!.texts.single()
    }

    @Test
    fun aChainGoesFromItsFirstTextThroughItsLinks() {
        page("a", "one", next = "b")
        page("b", "two", next = "c")
        page("c", "three", continuesLine = true)
        val chains = TextChains.of(document)
        assertEquals(listOf("a", "b", "c"), chains.chainOf("b").map { it.id })
        assertEquals("b", chains.next("a")?.id)
        assertEquals("b", chains.previous("c")?.id)
        assertEquals("one\ntwothree", TextChains.joined(chains.chainOf("a")), "a line going on without a break")
        assertTrue(chains.isChained("c"))
    }

    @Test
    fun aLinkToNothingACycleOrATextTakenIsNoLink() {
        page("a", "one", next = "missing")
        page("b", "two", next = "c")
        page("c", "three", next = "b")
        page("d", "four", next = "c")
        val chains = TextChains.of(document)
        assertNull(chains.next("a"))
        assertEquals(listOf("b", "c"), chains.chainOf("c").map { it.id }, "the cycle cut")
        assertNull(chains.next("d"), "c is taken by b")
        assertFalse(chains.isChained("a"))
    }

    @Test
    fun theLinksAreKeptWithTheContentAndSent() {
        val first = page("a", "one")
        page("b", "two")
        val inverse = session.submit(UpdateTexts(document.pages[0].id, listOf(first.toData().copy(next = "b"))))!!
        assertEquals("b", first.next)
        assertEquals("b", first.toData().next)
        session.submit(inverse)
        assertNull(first.next, "undone by its inverse")
    }
}
