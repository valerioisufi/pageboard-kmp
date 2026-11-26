package io.github.valerioisufi.pageboard.model.text

import kotlin.test.Test
import kotlin.test.assertEquals

/** The number of the first line of a block of code, written after its fence as Pandoc writes it. */
class CodeFirstLineTest {

    private fun block(text: String) = RichTextParser.parse(text).first { it.kind == RichTextParser.Kind.CODE_BLOCK }

    @Test
    fun aBlockStartsItsLinesFromOne() {
        assertEquals(1, block("```kotlin showLineNumbers\nx\n```").number)
        assertEquals(1, block("```\nx").number, "a block not closed")
    }

    @Test
    fun startFromIsReadAsPandocWritesIt() {
        assertEquals(10, block("```kotlin showLineNumbers startFrom=\"10\"\nx\n```").number)
        val braced = block("``` {.kotlin .numberLines startFrom=\"10\"}\nx\n```")
        assertEquals(10, braced.number, "in the attributes")
        assertEquals("kotlin", braced.language)
        assertEquals(true, braced.numbered)
        assertEquals(7, block("```py startfrom=7\nx\n```").number, "in any case, without quotes")
        assertEquals(0, block("```c startFrom=0\nx\n```").number)
        assertEquals("", block("``` startFrom=\"3\" numberLines\nx\n```").language, "an attribute is no language")
        assertEquals("", block("``` {#main .numberLines}\nx\n```").language, "nor is an identifier")
    }

    @Test
    fun aNumberNotWrittenRightStartsFromOne() {
        assertEquals(1, block("```c startFrom=abc\nx\n```").number)
        assertEquals(1, block("```c startFrom=-5\nx\n```").number, "no negative numbers")
        assertEquals(1, block("```c startFrom=1000000\nx\n```").number, "past the largest")
        assertEquals(1, block("```c startFrom=\nx\n```").number)
    }
}
