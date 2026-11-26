package io.github.valerioisufi.pageboard.html

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The rich text of an application with no HTML on the clipboard, in the notation. */
class RtfToMarkdownTest {

    private fun rtf(body: String) =
        "{\\rtf1\\ansi\\ansicpg1252\\deff0{\\fonttbl{\\f0\\fswiss Helvetica;}}{\\colortbl;\\red255\\green0\\blue0;}\n" +
            "{\\*\\generator Writer;}\\f0\\fs24 $body}"

    private fun convert(body: String) = RtfToMarkdown().convert(rtf(body))

    @Test
    fun theFormatsAndTheParagraphsAreWritten() {
        assertEquals("A **bold** and *italic* word.\n\nNext ~~gone~~.", convert("A {\\b bold} and \\i italic\\i0  word.\\par Next {\\strike gone}.\\par"))
    }

    @Test
    fun theCharactersOfTheCodePageAndOfUnicodeAreRead() {
        assertEquals("**Città** – “ok” π", convert("{\\b Citt\\'e0} \\'96 \\ldblquote ok\\rdblquote  \\uc1\\u960?"))
    }

    @Test
    fun scriptsKeepTheirShift() {
        assertEquals("H₂O is **water**", convert("H{\\sub 2}O is {\\b water}"))
    }

    @Test
    fun whatIsNotTheDocumentIsLeftOut() {
        assertEquals("Text **here**", convert("{\\*\\unknown hidden}{\\info{\\title T}}Text {\\b here}"))
    }

    @Test
    fun whatIsNotRtfOrHasNoFormattingGivesNothing() {
        assertNull(RtfToMarkdown().convert("plain text"))
        assertNull(convert("only words"), "the plain text is taken")
        assertNull(RtfToMarkdown().convert("{\\rtf1 " + "{".repeat(10_000) + "x"), "nested too deep")
    }
}
