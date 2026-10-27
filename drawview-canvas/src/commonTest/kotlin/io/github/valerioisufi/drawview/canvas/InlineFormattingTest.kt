package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.drawview.canvas.text.InlineFormatting
import io.github.valerioisufi.drawview.canvas.text.RichTextEditing.Format
import io.github.valerioisufi.drawview.model.text.RichTextParser
import io.github.valerioisufi.drawview.model.text.RichTextParser.Kind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The formats of the characters added to a selection or taken off it by the spans of the
 * notation, not by the characters around the selection.
 */
class InlineFormattingTest {

    private fun select(text: String, part: String, occurrence: Int = 0): TextFieldValue {
        var start = text.indexOf(part)
        repeat(occurrence) { start = text.indexOf(part, start + 1) }
        return TextFieldValue(text, TextRange(start, start + part.length))
    }

    private fun kinds(text: String) = RichTextParser.parse(text).map { it.kind }.toSet()

    @Test
    fun italicOnBoldTextKeepsTheBold() {
        val result = InlineFormatting.toggle(select("**x**", "x"), Format.ITALIC)
        assertEquals("**_x_**", result.text)
        assertEquals(TextRange(3, 4), result.selection)
        assertEquals(setOf(Kind.BOLD, Kind.ITALIC), kinds(result.text))
        assertEquals("**x**", InlineFormatting.toggle(result, Format.ITALIC).text, "and taken off again")
    }

    @Test
    fun boldOnItalicTextKeepsTheItalic() {
        val result = InlineFormatting.toggle(select("*x*", "x"), Format.BOLD)
        assertEquals("*__x__*", result.text)
        assertEquals(setOf(Kind.BOLD, Kind.ITALIC), kinds(result.text))
    }

    @Test
    fun aSelectionWithItsDelimitersLosesTheFormat() {
        val result = InlineFormatting.toggle(select("a **x** b", "**x**"), Format.BOLD)
        assertEquals("a x b", result.text)
        assertEquals(TextRange(2, 3), result.selection)
    }

    @Test
    fun theSpacesAtTheEndsOfTheSelectionStayOutside() {
        val result = InlineFormatting.toggle(select("a word here", "word "), Format.BOLD)
        assertEquals("a **word** here", result.text)
        assertEquals(TextRange(4, 8), result.selection)
        val leading = InlineFormatting.toggle(select("a word here", " word"), Format.STRIKETHROUGH)
        assertEquals("a ~~word~~ here", leading.text)
    }

    @Test
    fun aSelectionOverSeveralLinesIsFormattedLineByLine() {
        val result = InlineFormatting.toggle(TextFieldValue("- one\n\n# two", TextRange(0, 12)), Format.BOLD)
        assertEquals("- **one**\n\n# **two**", result.text)
        assertEquals(setOf(Kind.LIST_ITEM, Kind.HEADING, Kind.BOLD), kinds(result.text))
        assertEquals("- one\n\n# two", InlineFormatting.toggle(result, Format.BOLD).text, "every line has it: taken off")
    }

    @Test
    fun aLineWithoutTheFormatGetsIt() {
        val result = InlineFormatting.toggle(TextFieldValue("**one**\ntwo", TextRange(0, 11)), Format.BOLD)
        assertEquals("**one**\n**two**", result.text)
    }

    @Test
    fun aPartOfASpanLosesTheFormatTheRestKeepsIt() {
        val result = InlineFormatting.toggle(select("**abc**", "b"), Format.BOLD)
        assertEquals("**a**b**c**", result.text)
        assertEquals(TextRange(5, 6), result.selection)
        assertEquals(2, RichTextParser.parse(result.text).count { it.kind == Kind.BOLD })

        val words = InlineFormatting.toggle(select("**ab cd ef**", "cd"), Format.BOLD)
        assertEquals("**ab** cd **ef**", words.text, "never a delimiter next to a space")
        val start = InlineFormatting.toggle(select("**ab cd**", "ab"), Format.BOLD)
        assertEquals("ab **cd**", start.text)
    }

    @Test
    fun theSpansTouchedAreTakenIn() {
        assertEquals("**abcd**", InlineFormatting.toggle(select("**ab**cd", "b**cd"), Format.BOLD).text)
        assertEquals("**a b c**", InlineFormatting.toggle(select("a **b** c", "a **b** c"), Format.BOLD).text)
    }

    @Test
    fun theFormatsDoNotApplyInCodeOrFormulas() {
        assertEquals("`code`", InlineFormatting.toggle(select("`code`", "code"), Format.BOLD).text)
        assertEquals("\$x+y\$", InlineFormatting.toggle(select("\$x+y\$", "x+y"), Format.ITALIC).text)
        assertEquals("`code`", InlineFormatting.toggle(TextFieldValue("`code`", TextRange(3)), Format.BOLD).text)
        assertEquals("code", InlineFormatting.toggle(select("`code`", "code"), Format.CODE).text, "its own format is taken off")
        assertEquals("[**guide**](https://a.org)", InlineFormatting.toggle(select("[guide](https://a.org)", "guide"), Format.BOLD).text)
        assertEquals("[guide](https://a.org)", InlineFormatting.toggle(select("[guide](https://a.org)", "https"), Format.BOLD).text, "not in the address")
    }

    @Test
    fun atTheCaretTheSpanLosesTheFormatOrTheDelimitersAreInserted() {
        val inside = InlineFormatting.toggle(TextFieldValue("a **word** b", TextRange(5)), Format.BOLD)
        assertEquals("a word b", inside.text)
        assertEquals(TextRange(3), inside.selection)
        val outside = InlineFormatting.toggle(TextFieldValue("a b", TextRange(2)), Format.ITALIC)
        assertEquals("a **b", outside.text)
        assertEquals(TextRange(3), outside.selection)
    }

    @Test
    fun theFormatsOfTheSelection() {
        assertEquals(setOf(Format.BOLD, Format.ITALIC), InlineFormatting.formatsAt(TextFieldValue("**_x_**", TextRange(3))))
        assertEquals(emptySet(), InlineFormatting.formatsAt(TextFieldValue("**x** y", TextRange(6))))
        assertEquals(setOf(Format.BOLD), InlineFormatting.formatsAt(TextFieldValue("- **a**\n- **b**", TextRange(0, 15))))
        assertEquals(emptySet(), InlineFormatting.formatsAt(TextFieldValue("- **a**\n- b", TextRange(0, 11))))
    }
}
