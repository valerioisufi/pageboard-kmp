package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.pageboard.canvas.text.SelectionInput
import io.github.valerioisufi.pageboard.model.text.RichTextParser
import io.github.valerioisufi.pageboard.model.text.RichTextParser.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the input of the user does to a selection of the text being written: a delimiter typed over
 * a selection surrounds it instead of replacing it.
 */
class SelectionInputTest {

    private fun select(text: String, part: String): TextFieldValue {
        val start = text.indexOf(part)
        return TextFieldValue(text, TextRange(start, start + part.length))
    }

    /** Simulates typing [typed] over the selection of [before]. */
    private fun type(before: TextFieldValue, typed: String, composition: TextRange? = null): TextFieldValue {
        val min = minOf(before.selection.start, before.selection.end)
        val max = maxOf(before.selection.start, before.selection.end)
        val after = TextFieldValue(
            text = before.text.substring(0, min) + typed + before.text.substring(max),
            selection = TextRange(min + typed.length),
            composition = composition
        )
        return SelectionInput.surround(before, after) ?: after
    }

    @Test
    fun typingADelimiterSurroundsTheSelectionAndKeepsItSelected() {
        val first = type(select("Hello world!", "world"), "*")
        assertEquals("Hello *world*!", first.text)
        assertEquals(TextRange(7, 12), first.selection)
        val second = type(first, "*")
        assertEquals("Hello **world**!", second.text, "typing * a second time makes it bold")
        assertEquals(TextRange(8, 13), second.selection)
        assertEquals(listOf(Kind.BOLD), RichTextParser.parse(second.text).map { it.kind })

        val struck = type(type(select("a word", "word"), "~"), "~")
        assertEquals("a ~~word~~", struck.text)
        assertEquals(listOf(Kind.STRIKE), RichTextParser.parse(struck.text).map { it.kind })

        assertEquals("`code`", type(select("code", "code"), "`").text)
        assertEquals("\$x^2\$", type(select("x^2", "x^2"), "\$").text)
        assertEquals("_em_", type(select("em", "em"), "_").text)
    }

    @Test
    fun bracketsAndQuotesSurroundTheSelectionWithTheirClosingTwin() {
        assertEquals("(a + b)", type(select("a + b", "a + b"), "(").text)
        assertEquals("[item]", type(select("item", "item"), "[").text)
        assertEquals("{x}", type(select("x", "x"), "{").text)
        assertEquals("\"quoted \"", type(select("quoted ", "quoted "), "\"").text, "spaces kept inside quotes")
        assertEquals("'word'", type(select("word", "word"), "'").text)
        assertEquals("“word”", type(select("word", "word"), "“").text)
        assertEquals("‘word’", type(select("word", "word"), "‘").text)
        assertEquals("«word»", type(select("word", "word"), "«").text)
    }

    @Test
    fun notationDelimitersLeaveSpacesAtTheEndsOutside() {
        val result = type(select("a word here", "word "), "*")
        assertEquals("a *word* here", result.text)
        assertEquals(TextRange(3, 7), result.selection)
        assertEquals("a **word** here", type(result, "*").text)
        assertEquals("*", type(select("   ", "   "), "*").text, "only spaces: replaced as usual")
    }

    @Test
    fun nothingIsSurroundedInCodeAndOnlyBracketsInFormulas() {
        assertEquals("`*`", type(select("`code`", "code"), "*").text)
        assertEquals("`(`", type(select("`code`", "code"), "(").text)
        assertEquals("```\n*\n```", type(select("```\ncode\n```", "code"), "*").text)
        assertEquals("\$*\$", type(select("\$x+y\$", "x+y"), "*").text)
        assertEquals("\$(x+y)\$", type(select("\$x+y\$", "x+y"), "(").text, "brackets work inside a formula")
    }

    @Test
    fun regularTypingAndCompositionsAreLeftAlone() {
        val before = select("Hello", "ell")
        assertNull(SelectionInput.surround(before, TextFieldValue("Hao", TextRange(2))))
        assertNull(SelectionInput.surround(before, TextFieldValue("H'o", TextRange(2), composition = TextRange(1, 2))), "dead key composition")
        assertNull(SelectionInput.surround(TextFieldValue("Hello", TextRange(2)), TextFieldValue("He*llo", TextRange(3))), "no selection")
    }

    /** Simulates pasting [pasted] over the selection of [before]. */
    private fun paste(before: TextFieldValue, pasted: String): TextFieldValue {
        val min = minOf(before.selection.start, before.selection.end)
        val max = maxOf(before.selection.start, before.selection.end)
        val after = TextFieldValue(
            text = before.text.substring(0, min) + pasted + before.text.substring(max),
            selection = TextRange(min + pasted.length)
        )
        return SelectionInput.link(before, after) ?: after
    }

    @Test
    fun pastingAnAddressOverTheSelectionMakesItALink() {
        val linked = paste(select("Read the guide now", "the guide "), "https://example.com")
        assertEquals("Read [the guide](https://example.com) now", linked.text, "trailing space stays outside the link")
        assertEquals(TextRange(6, 15), linked.selection, "selection stays on the linked text")
        assertEquals("http://a.org", RichTextParser.linkUrl(paste(select("site", "site"), "http://a.org").text, RichTextParser.parse(paste(select("site", "site"), "http://a.org").text).single()))
        assertEquals("Read [mail](mailto:me@example.com)", paste(select("Read mail", "mail"), "mailto:me@example.com").text)
        assertEquals("Visit [us](www.example.com)", paste(select("Visit us", "us"), "www.example.com").text)
    }

    @Test
    fun pastingPlainTextOrAnAddressOverAnAddressReplacesTheSelection() {
        assertEquals("Hello there", paste(select("Hello world", "world"), "there").text)
        assertEquals("https://new.org", paste(select("https://old.org", "https://old.org"), "https://new.org").text, "replacing a URL with a URL")
        assertEquals("https://a.org", paste(select("line 1\nline 2", "line 1\nline 2"), "https://a.org").text, "multiline selection")
        assertEquals("`https://a.org`", paste(select("`code`", "code"), "https://a.org").text, "inside code")
        assertEquals("\$https://a.org\$", paste(select("\$x\$", "x"), "https://a.org").text, "inside formula")
        assertEquals("[https://b.org](https://a.org)", paste(select("[old](https://a.org)", "old"), "https://b.org").text, "already inside a link")
    }
}
