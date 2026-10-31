package io.github.valerioisufi.drawview.model.text

import io.github.valerioisufi.drawview.model.text.ListNumbering.Bullet
import io.github.valerioisufi.drawview.model.text.RichTextParser.Kind
import kotlin.test.Test
import kotlin.test.assertEquals

/** The lists of the texts: the continuation lines of their items, their nesting, their labels. */
class ListNumberingTest {

    private fun items(text: String) = ListNumbering.items(text, RichTextParser.parse(text))

    private fun labels(text: String) = items(text).map { it.label }

    @Test
    fun anItemTakesTheLinesIndentedAtItsText() {
        val text = "- one\n  more\n  and more\n- two\nout"
        val spans = RichTextParser.parse(text).filter { it.kind == Kind.LIST_ITEM }
        assertEquals(listOf("- one\n  more\n  and more", "- two"), spans.map { text.substring(it.start, it.end) })
        assertEquals("one\n  more\n  and more", text.substring(spans[0].contentStart, spans[0].contentEnd))
    }

    @Test
    fun aLineLessIndentedOrBlankEndsTheItem() {
        val text = "1. one\n  two spaces\n\n   after a blank"
        val spans = RichTextParser.parse(text).filter { it.kind == Kind.LIST_ITEM }
        assertEquals(listOf("1. one"), spans.map { text.substring(it.start, it.end) }, "the text of a numbered item is at 3")
    }

    @Test
    fun aBlockOfCodeUnderAnItemIsNotALineOfIt() {
        val text = "- item\n  ```\n  code\n  ```"
        val item = RichTextParser.parse(text).single { it.kind == Kind.LIST_ITEM }
        assertEquals("- item", text.substring(item.start, item.end))
    }

    @Test
    fun theOrdinalsFollowThePlaceOfTheItems() {
        assertEquals(listOf("1.", "2.", "3."), labels("1. a\n1. b\n1. c"))
        assertEquals(listOf("4.", "5."), labels("4. a\n9. b"), "from the number of the first")
        assertEquals(listOf("1.", "2.", "1."), labels("1. a\n1. b\n\nother\n1. c"), "a paragraph ends the list")
        assertEquals(listOf("1.", "2."), labels("1. a\n\n1. b"), "a blank line does not")
    }

    @Test
    fun theNestedNumbersAreLettersThenRomanNumerals() {
        assertEquals(listOf("1.", "a.", "b.", "i.", "ii.", "c.", "2."), labels("1. a\n   1. b\n   1. c\n      1. d\n      1. e\n   1. f\n1. g"))
        assertEquals(listOf("1.", "a.", "2."), labels("1. a\n    1. b\n1. c"), "any indent deeper than the parent nests")
    }

    @Test
    fun theBulletsFollowTheDepth() {
        val items = items("- a\n  - b\n    - c\n      - d\n  - e")
        assertEquals(listOf(Bullet.DISC, Bullet.CIRCLE, Bullet.SQUARE, Bullet.DISC, Bullet.CIRCLE), items.map { it.bullet })
        assertEquals(listOf(-1, 0, 1, 2, 0), items.map { it.parent })
    }

    @Test
    fun aKindChangeStartsAnotherList() {
        val items = items("1. a\n- b\n1. c")
        assertEquals(listOf("1.", "•", "1."), items.map { it.label })
        assertEquals(3, items.map { it.list }.toSet().size)
    }

    @Test
    fun theNumbersAreWritten() {
        assertEquals("xiv", ListNumbering.format(14, ListNumbering.Numbering.LOWER_ROMAN))
        assertEquals("aa", ListNumbering.format(27, ListNumbering.Numbering.LOWER_ALPHA))
        assertEquals("0", ListNumbering.format(0, ListNumbering.Numbering.LOWER_ALPHA))
    }
}
