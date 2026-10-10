package io.github.valerioisufi.drawview.core

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
}
