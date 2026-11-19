package io.github.valerioisufi.pageboard.model.text

import io.github.valerioisufi.pageboard.model.text.RichTextParser.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The block quotes in the notation: their lines, the lists and the formats in them, what stays text. */
class BlockQuoteTest {

    private fun spans(text: String, kind: Kind) = RichTextParser.parse(text).filter { it.kind == kind }

    private fun quotes(text: String) = spans(text, Kind.QUOTE).map { text.substring(it.start, it.end) }

    private fun labels(text: String) = ListNumbering.items(text, RichTextParser.parse(text)).map { it.label }

    @Test
    fun aRunOfMarkedLinesIsAQuote() {
        assertEquals(listOf("> one\n> two"), quotes("> one\n> two\nout"))
        assertEquals(listOf("> one", "> two"), quotes("> one\n\n> two"))
        assertEquals(listOf("> one\n>\n> two"), quotes("> one\n>\n> two"), "`>` alone keeps it going")
        assertEquals(listOf("   > three spaces"), quotes("   > three spaces"))
        val quote = spans("x\n> one\n> two", Kind.QUOTE).single()
        assertEquals("one\n> two", "x\n> one\n> two".substring(quote.contentStart, quote.contentEnd))
    }

    @Test
    fun whatIsNotAMarkerStaysText() {
        for (text in listOf(">5", ">= 3", "a > b", "    > four spaces", "\\> escaped", "- > in an item", "`> code`")) {
            assertEquals(emptyList(), quotes(text), text)
        }
    }

    @Test
    fun theCodeAndTheFormulasAreNotQuotes() {
        assertEquals(emptyList(), quotes("```\n> code\n```"))
        assertEquals(emptyList(), quotes("$$\n> x\n$$"))
    }

    @Test
    fun theFormatsWorkInAQuote() {
        val text = "> **bold** *italic* `code` \$x\$\n> * not italic **bold**"
        assertEquals(2, spans(text, Kind.BOLD).size)
        assertEquals(1, spans(text, Kind.ITALIC).size)
        assertEquals(1, spans(text, Kind.CODE).size)
        assertEquals(1, spans(text, Kind.FORMULA).size)
    }

    @Test
    fun aListInAQuoteIsAList() {
        val text = "> **SCTLR:**\n> * first\n>   its line\n> * second\n>   * nested\n>\n> 1. one\n> 2. two"
        val items = spans(text, Kind.LIST_ITEM)
        assertEquals(listOf("first\n>   its line", "second", "nested", "one", "two"), items.map { text.substring(it.contentStart, it.contentEnd) })
        assertEquals(listOf("* first\n>   its line", "* second", "  * nested", "1. one", "2. two"), items.map { text.substring(it.start, it.end) })
        assertEquals(listOf("•", "•", "◦", "1.", "2."), labels(text))
        assertEquals(listOf(0, 0, 1, 0, 0), ListNumbering.items(text, RichTextParser.parse(text)).map { it.depth })
    }

    @Test
    fun aListStaysOnItsSideOfAQuote() {
        assertEquals(listOf("1.", "1."), labels("> 1. in\n1. out"))
        assertEquals(listOf("1.", "1."), labels("1. out\n> 1. in"))
        val text = "> * in\n  out"
        assertEquals("* in", text.substring(spans(text, Kind.LIST_ITEM).single().start, spans(text, Kind.LIST_ITEM).single().end), "no line from out of the quote")
        val quoted = "- item\n  > quote"
        assertEquals("- item", quoted.substring(0, spans(quoted, Kind.LIST_ITEM).single().end), "no line of a quote under an item")
    }

    @Test
    fun aHeadingAndATableInAQuoteAreItsTextForNow() {
        assertTrue(spans("> # text", Kind.HEADING).isEmpty())
        assertTrue(spans("> | a |\n> | - |\n> | b |", Kind.TABLE).isEmpty())
        assertTrue(spans("> ```\n> a\n> ```", Kind.CODE_BLOCK).isEmpty())
    }

    @Test
    fun theNumbersOfAListInAQuoteAreMadeRight() {
        val old = "> 1. a\n> 2. b\n> 3. c"
        assertEquals("> 1. b\n> 2. c", ListRenumbering.after(old, "> 2. b\n> 3. c")?.text)
        assertEquals("> 1. a\n> 2. c", ListRenumbering.after(old, "> 1. a\n> 3. c")?.text)
        val nine = (1..9).joinToString("\n") { "> $it. i" }
        val long = "$nine\n> 9. j\n>    more"
        assertEquals("$nine\n> 10. j\n>     more", ListRenumbering.after("$nine\n> 10. j", long)?.text, "the lines of an item follow it, after their marker")
    }

    @Test
    fun aMarkerIsRead() {
        assertEquals(2, BlockQuote.markerLength("> a", 0))
        assertEquals(1, BlockQuote.markerLength(">", 0))
        assertEquals(1, BlockQuote.markerLength(">\nb", 0))
        assertEquals(0, BlockQuote.markerLength(">a", 0))
        assertTrue(BlockQuote.isQuotedAt("x\n> a", 4))
        assertFalse(BlockQuote.isQuotedAt("x\n> a", 1))
    }

    @Test
    fun aLongQuoteIsLinear() {
        val text = (1..20_000).joinToString("\n") { "> * line **$it**" }
        val spans = RichTextParser.parse(text)
        assertEquals(20_000, spans.count { it.kind == Kind.LIST_ITEM })
    }
}
