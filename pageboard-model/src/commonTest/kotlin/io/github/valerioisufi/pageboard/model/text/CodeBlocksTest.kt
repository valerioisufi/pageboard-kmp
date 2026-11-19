package io.github.valerioisufi.pageboard.model.text

import io.github.valerioisufi.pageboard.model.text.RichTextParser.Kind
import io.github.valerioisufi.pageboard.model.text.RichTextParser.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The blocks of code of the texts: their fences as in CommonMark, their language, their content
 * written as it is.
 */
class CodeBlocksTest {

    /** The only block of [text]. */
    private fun block(text: String): Span = RichTextParser.parse(text).single { it.kind == Kind.CODE_BLOCK }

    private fun Span.content(text: String) = text.substring(contentStart, contentEnd)

    @Test
    fun aBlockIsBetweenItsFences() {
        val text = "Before\n```kotlin\nval a = 1\nval b = 2\n```\nAfter"
        val block = block(text)
        assertEquals("val a = 1\nval b = 2", block.content(text))
        assertEquals("kotlin", block.language)
        assertEquals(listOf("```kotlin\n", "\n```"), block.delimiters.map { text.substring(it.first, it.last + 1) }, "the fences are hidden")
        assertEquals(text.indexOf("```"), block.start)
        assertEquals(text.indexOf("\nAfter"), block.end)
    }

    @Test
    fun theFencesAreBackticksOrTildes() {
        val text = "~~~\nx\n~~~"
        assertEquals("x", block(text).content(text))
        assertEquals("", block(text).language)
    }

    @Test
    fun theNotationIsNotReadInsideABlock() {
        val text = "```\n# not a heading\n- not an item\n*not italic* \$x\$ `y` \\*\n```"
        assertEquals(listOf(Kind.CODE_BLOCK), RichTextParser.parse(text).map { it.kind })
    }

    @Test
    fun aFenceClosesWithAsManyOfItsCharactersOrMore() {
        val longer = "````\na\n```\nb\n````"
        assertEquals("a\n```\nb", block(longer).content(longer), "a shorter fence is code")
        val other = "```\na\n~~~\n```"
        assertEquals("a\n~~~", block(other).content(other), "tildes do not close backticks")
        val closedLonger = "```\na\n`````"
        assertEquals("a", block(closedLonger).content(closedLonger))
    }

    @Test
    fun aClosingFenceHasOnlySpacesAfterIt() {
        val spaces = "```\nx\n```  "
        assertEquals(spaces.length, block(spaces).end)
        val words = "```\nx\n``` y\n```"
        assertEquals("x\n``` y", block(words).content(words))
    }

    @Test
    fun aFenceNotClosedRunsToTheEnd() {
        val text = "a\n```py\nprint(1)\n"
        val block = block(text)
        assertEquals("print(1)\n", block.content(text))
        assertEquals(text.length, block.end)
        assertEquals("py", block.language)

        val alone = "Notes\n```"
        assertEquals("", block(alone).content(alone), "a fence on the last line opens an empty block")
        assertEquals(alone.length, block(alone).end)
    }

    @Test
    fun anEmptyBlockHasNoContent() {
        val text = "```\n```"
        val block = block(text)
        assertEquals("", block.content(text))
        assertEquals(text.length, block.end)
    }

    @Test
    fun aFenceIsIndentedByThreeSpacesAtMost() {
        val three = "   ```\nx\n   ```"
        assertEquals(3, block(three).level)
        assertEquals("x", block(three).content(three))
        assertTrue(RichTextParser.parse("    ```\nx\n    ```").none { it.kind == Kind.CODE_BLOCK }, "four spaces: no fence")
    }

    @Test
    fun aFenceStartsALine() {
        val text = "a ```kotlin\nb"
        assertTrue(RichTextParser.parse(text).none { it.kind == Kind.CODE_BLOCK })
    }

    @Test
    fun aFenceOfBackticksHasNoBacktickAfterIt() {
        assertTrue(RichTextParser.parse("```a`b```\nc").none { it.kind == Kind.CODE_BLOCK })
        val tildes = "~~~ a`b\nc\n~~~"
        assertEquals("a`b", block(tildes).language)
    }

    @Test
    fun theLanguageIsTheFirstWordAfterTheFence() {
        val attributes = "``` {.python .numberLines}\nx\n```"
        assertEquals("python", block(attributes).language)
        val title = "```Kotlin title=\"Main.kt\"\nx\n```"
        assertEquals("Kotlin", block(title).language)
    }

    @Test
    fun anEmphasisDoesNotGoAcrossABlock() {
        val across = "**a\n```\nx\n```\nb**"
        assertEquals(listOf(Kind.CODE_BLOCK), RichTextParser.parse(across).map { it.kind })

        val around = "**a** before\n```\nx\n```\n*c* and **d**"
        assertEquals(listOf(Kind.BOLD, Kind.CODE_BLOCK, Kind.ITALIC, Kind.BOLD), RichTextParser.parse(around).map { it.kind })
        val after = "**a\n```\n```\n**b**"
        assertEquals(listOf(Kind.CODE_BLOCK, Kind.BOLD), RichTextParser.parse(after).map { it.kind }, "the emphasis after the block is its own")
    }

    @Test
    fun aDisplayFormulaDoesNotGoAcrossABlock() {
        val text = "\$\$a\n```\n\$\$\n```"
        assertEquals(listOf(Kind.CODE_BLOCK), RichTextParser.parse(text).map { it.kind })
    }

    @Test
    fun theNotationAroundABlockIsRead() {
        val text = "```\nx\n```\n# Title\n- item \$y\$"
        assertEquals(listOf(Kind.CODE_BLOCK, Kind.HEADING, Kind.LIST_ITEM, Kind.FORMULA), RichTextParser.parse(text).map { it.kind })
    }

    @Test
    fun aBlockAsksForItsLineNumbersByAWordAfterItsFence() {
        for (info in listOf("kotlin showLineNumbers", "{.kotlin .numberLines}", "kotlin {.numberLines}", "kotlin linenums", "KOTLIN NumberLines")) {
            val text = "```$info\nx\n```"
            val block = block(text)
            assertTrue(block.numbered, info)
            assertEquals("kotlin", block.language.lowercase(), info)
        }
        val alone = "```showLineNumbers\nx\n```"
        assertTrue(block(alone).numbered)
        assertEquals("", block(alone).language, "no language")
        assertFalse(block("```kotlin title\nx\n```").numbered)
    }

    @Test
    fun aBlockIndentedUnderAListItemIsTheBlockOfTheItem() {
        val text = "- Compile:\n  ```c\n  int x;\n  ```\nafter"
        val block = block(text)
        assertEquals("  int x;", block.content(text))
        assertEquals(2, block.level, "the spaces taken off its lines")
        val item = assertNotNull(block.listItem)
        assertEquals("Compile:", text.substring(item.contentStart, item.contentEnd))
        assertEquals(text.indexOf("\nafter"), block.end, "closed by its fence, indented as its item")
    }

    @Test
    fun aNestedItemHasItsBlocksToo() {
        val text = "- a\n  - b\n    ```\n    x\n    ```"
        val block = block(text)
        assertEquals(1, assertNotNull(block.listItem).level, "the item b")
        assertEquals("    x", block.content(text))
        val deeper = "1. one\n   ```py\n   y\n   ```"
        val numbered = assertNotNull(block(deeper).listItem)
        assertTrue(numbered.ordered, "under a numbered item, its text 3 columns in")
        assertEquals(1, numbered.number)
    }

    @Test
    fun aLineLessIndentedThanItsItemEndsTheBlock() {
        val text = "- a\n  ```\n  x\nout\n```"
        val spans = RichTextParser.parse(text).filter { it.kind == Kind.CODE_BLOCK }
        val first = spans.first()
        assertEquals("  x", first.content(text))
        assertEquals(text.indexOf("\nout"), first.end)
        assertEquals(null, spans.last().listItem, "the fence after it opens a block of its own")
    }

    @Test
    fun outOfTheListsAFenceHasThreeSpacesAtMost() {
        assertEquals(null, block("   ```\nx\n   ```").listItem)
        assertTrue(RichTextParser.parse("    ```\nx").none { it.kind == Kind.CODE_BLOCK })
        assertTrue(RichTextParser.parse("- a\n      ```\nx").none { it.kind == Kind.CODE_BLOCK }, "more than 3 spaces past the text of the item")
    }
}
