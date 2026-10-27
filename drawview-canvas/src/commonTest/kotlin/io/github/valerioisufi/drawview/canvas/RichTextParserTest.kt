package io.github.valerioisufi.drawview.canvas

import io.github.valerioisufi.drawview.model.text.RichTextParser
import io.github.valerioisufi.drawview.model.text.RichTextParser.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The notation of the texts: Markdown with formulas, escapes, the rules of Pandoc and CommonMark.
 */
class RichTextParserTest {

    /** The kinds of the spans of [text] with their content. */
    private fun spans(text: String) = RichTextParser.parse(text).map { it.kind to text.substring(it.contentStart, it.contentEnd) }

    @Test
    fun formulasAreWhereTheyAreWritten() {
        assertEquals(listOf(Kind.FORMULA to "\\pi r^2"), spans("The area is \$\\pi r^2\$ here"))
        assertEquals(listOf(Kind.FORMULA to "a", Kind.FORMULA to "b"), spans("\$a\$ and \$b\$"))
    }

    @Test
    fun dollarsOfPricesAreText() {
        assertTrue(spans("It costs \$5 and \$10").isEmpty(), "a closing dollar follows a space")
        assertTrue(spans("From \$ 5 to 6\$").isEmpty(), "an opening dollar is followed by a space")
        assertTrue(spans("\$x\$5").isEmpty(), "a closing dollar is not followed by a digit")
        assertTrue(spans("one \$ two").isEmpty())
        assertTrue(spans("\$a\nb\$").isEmpty(), "a formula in the line stays on its line")
    }

    @Test
    fun escapesWriteTheirCharacter() {
        val text = "Price \\\$5, a \\*star\\* and \$x\$"
        val parsed = RichTextParser.parse(text)
        assertEquals(listOf(Kind.ESCAPE, Kind.ESCAPE, Kind.ESCAPE, Kind.FORMULA), parsed.map { it.kind })
        assertEquals("$", text.substring(parsed[0].contentStart, parsed[0].contentEnd))
        assertEquals(listOf(parsed[0].start until parsed[0].start + 1), parsed[0].delimiters, "the backslash is hidden")
    }

    @Test
    fun displayFormulasStandAloneOnTheirLines() {
        val alone = RichTextParser.parse("Before\n  \$\$\\int_0^1 x\\,dx\$\$ \nAfter").single()
        assertTrue(alone.display)
        assertTrue(alone.block)

        val inLine = RichTextParser.parse("So \$\$\\sum_i i\$\$ is it").single()
        assertTrue(inLine.display)
        assertFalse(inLine.block, "in a line: display style, in the line")

        assertTrue(RichTextParser.parse("\$\$ \$\$").isEmpty(), "an empty formula is text")
    }

    @Test
    fun theCodeAndTheFormulasAreWrittenAsTheyAre() {
        assertEquals(listOf(Kind.CODE to "a*b*c"), spans("`a*b*c`"))
        assertEquals(listOf(Kind.FORMULA to "x_1 + y_2"), spans("\$x_1 + y_2\$"))
        assertEquals(listOf(Kind.FORMULA to "a \\\$ b"), spans("\$a \\\$ b\$"), "an escaped dollar does not close it")
    }

    @Test
    fun underscoresInsideWordsAreText() {
        assertTrue(spans("file_name_here and snake_case").isEmpty())
        assertEquals(listOf(Kind.ITALIC to "word"), spans("an _word_ here"))
        assertEquals(listOf(Kind.BOLD to "strong"), spans("__strong__"))
    }

    @Test
    fun emphasisTouchesItsText() {
        assertTrue(spans("2 * 3 * 4").isEmpty(), "stars between spaces multiply")
        assertEquals(listOf(Kind.ITALIC to "a"), spans("*a*"))
        assertEquals(listOf(Kind.BOLD to "b", Kind.STRIKE to "c"), spans("**b** ~~c~~"))
    }

    @Test
    fun formattingNests() {
        assertEquals(
            listOf(Kind.BOLD to "bold *and italic* inside", Kind.ITALIC to "and italic"),
            spans("**bold *and italic* inside**")
        )
        val heading = RichTextParser.parse("## Area **of** \$\\pi\$")
        assertEquals(listOf(Kind.HEADING, Kind.BOLD, Kind.FORMULA), heading.map { it.kind })
        assertEquals(2, heading[0].level)
        assertEquals(listOf(Kind.BOLD to "has \$x\$", Kind.FORMULA to "x"), spans("**has \$x\$**"))
    }

    @Test
    fun spansNeverOverlapPartially() {
        val text = "**a *b** c* `d **e` f**"
        val parsed = RichTextParser.parse(text)
        for (a in parsed) for (b in parsed) {
            if (a === b) continue
            val disjoint = a.end <= b.start || b.end <= a.start
            val nested = (a.start >= b.contentStart && a.end <= b.contentEnd) || (b.start >= a.contentStart && b.end <= a.contentEnd)
            assertTrue(disjoint || nested, "$a and $b")
        }
    }

    @Test
    fun bulletListsAreParsedWithTheirMarkersAndIndentation() {
        val text = "- Item 1\n* Item 2\n+ Item 3\n  - Subitem\n    - Deep subitem"
        val parsed = RichTextParser.parse(text)
        assertEquals(listOf(Kind.LIST_ITEM, Kind.LIST_ITEM, Kind.LIST_ITEM, Kind.LIST_ITEM, Kind.LIST_ITEM), parsed.map { it.kind })
        assertEquals(listOf("Item 1", "Item 2", "Item 3", "Subitem", "Deep subitem"), parsed.map { text.substring(it.contentStart, it.contentEnd) })
        assertEquals(listOf(0, 0, 0, 1, 2), parsed.map { it.level })
        assertTrue(parsed.none { it.ordered })
    }

    @Test
    fun numberedListsAreParsedWithNumbers() {
        val text = "1. First\n2. Second\n  1. Sub first\n42. Answer"
        val parsed = RichTextParser.parse(text)
        assertEquals(listOf(Kind.LIST_ITEM, Kind.LIST_ITEM, Kind.LIST_ITEM, Kind.LIST_ITEM), parsed.map { it.kind })
        assertEquals(listOf("First", "Second", "Sub first", "Answer"), parsed.map { text.substring(it.contentStart, it.contentEnd) })
        assertEquals(listOf(1, 2, 1, 42), parsed.map { it.number })
        assertEquals(listOf(0, 0, 1, 0), parsed.map { it.level })
        assertTrue(parsed.all { it.ordered })
    }

    @Test
    fun formattingNestsInsideListItems() {
        val text = "- Item with **bold** and \$x^2\$"
        val parsed = RichTextParser.parse(text)
        assertEquals(listOf(Kind.LIST_ITEM, Kind.BOLD, Kind.FORMULA), parsed.map { it.kind })
        assertEquals("Item with **bold** and \$x^2\$", text.substring(parsed[0].contentStart, parsed[0].contentEnd))
        assertEquals("bold", text.substring(parsed[1].contentStart, parsed[1].contentEnd))
        assertEquals("x^2", text.substring(parsed[2].contentStart, parsed[2].contentEnd))
    }

    @Test
    fun linksHaveTheirTextAndAddress() {
        val text = "Read [the guide](https://example.com/a_b_(1)) now"
        val link = RichTextParser.parse(text).single()
        assertEquals(Kind.LINK, link.kind)
        assertEquals("the guide", text.substring(link.contentStart, link.contentEnd))
        assertEquals("https://example.com/a_b_(1)", RichTextParser.linkUrl(text, link))
        assertEquals(listOf(5 until 6, 15 until 45), link.delimiters)
    }

    @Test
    fun linksNestWithEmphasisAndLeaveAddressesVerbatim() {
        assertEquals(
            listOf(Kind.LINK to "**bold** and \$x\$", Kind.BOLD to "bold", Kind.FORMULA to "x"),
            spans("[**bold** and \$x\$](https://example.com/a_b_c)")
        )
        assertEquals(
            listOf(Kind.BOLD to "[link](https://example.com)", Kind.LINK to "link"),
            spans("**[link](https://example.com)**")
        )
        assertTrue(spans("![image](https://example.com)").isEmpty(), "an image is not a link")
        assertTrue(spans("[](https://example.com)").isEmpty(), "a link has text")
        assertTrue(spans("[text]()").isEmpty(), "a link has an address")
    }
}
