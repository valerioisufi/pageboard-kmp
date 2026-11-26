package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.pageboard.canvas.text.RichTextEditing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The code of a block copied alone, and Enter and the closers indenting by the language of the block. */
class CodeBlockEditingTest {

    /** [text] with the caret at its `|`, taken out. */
    private fun at(text: String): TextFieldValue = TextFieldValue(text.replace("|", ""), TextRange(text.indexOf('|')))

    /** [value] as a text with its caret at a `|`. */
    private fun shown(value: TextFieldValue?): String? = value?.let { it.text.substring(0, it.selection.start) + "|" + it.text.substring(it.selection.start) }

    @Test
    fun theCodeOfABlockIsCopiedWithoutItsFences() {
        assertEquals("val a = 1\nval b = 2", RichTextEditing.codeOfBlockAt(at("Text\n```kotlin\nval a = 1\nval |b = 2\n```\nafter")))
        assertEquals("", RichTextEditing.codeOfBlockAt(at("```\n|\n```")).orEmpty(), "an empty block")
        assertNull(RichTextEditing.codeOfBlockAt(at("Te|xt\n```\ncode\n```")), "out of the block")
        assertEquals("f() {\n  g()\n}", RichTextEditing.codeOfBlockAt(at("- item\n  ```c\n  f() {\n    g()|\n  }\n  ```")), "without the spaces of an item")
        assertEquals("code", RichTextEditing.codeOfBlockAt(at("```\nco|de")), "a block not closed")
    }

    @Test
    fun enterAfterAnOpenerIndentsOneStepMore() {
        assertEquals("```kotlin\nfun f() {\n    |\n```", shown(RichTextEditing.handleEnter(at("```kotlin\nfun f() {|\n```"))))
        assertEquals("```kotlin\nif (a) {\n  b()\n  if (c) {\n    |\n```", shown(RichTextEditing.handleEnter(at("```kotlin\nif (a) {\n  b()\n  if (c) {|\n```"))), "the step of the block")
        assertEquals("```python\ndef f():\n    |\n```", shown(RichTextEditing.handleEnter(at("```python\ndef f():|\n```"))), "a colon in Python")
        assertEquals("```yaml\nserver:\n  |\n```", shown(RichTextEditing.handleEnter(at("```yaml\nserver:|\n```"))), "two spaces in YAML")
    }

    @Test
    fun enterBetweenTheBracketsPutsTheCloserOnALineOfItsOwn() {
        assertEquals("```js\n  f(() => {\n    |\n  })\n```", shown(RichTextEditing.handleEnter(at("```js\n  f(() => {|})\n```"))), "a step of the two spaces of the block")
        assertEquals("```c\nint a[] = [\n    |\n]\n```", shown(RichTextEditing.handleEnter(at("```c\nint a[] = [| ]\n```"))), "the spaces between them dropped")
    }

    @Test
    fun enterKeepsTheIndentationOtherwise() {
        assertEquals("```kotlin\n    a()\n    |\n```", shown(RichTextEditing.handleEnter(at("```kotlin\n    a()|\n```"))))
        assertEquals("```\n  x {\n  |\n```", shown(RichTextEditing.handleEnter(at("```\n  x {|\n```"))), "no language")
        assertEquals("```kotlin\na() // {\n|\n```", shown(RichTextEditing.handleEnter(at("```kotlin\na() // {|\n```"))), "an opener in a comment")
        assertEquals("```kotlin\nf {\n    |a\n```", shown(RichTextEditing.handleEnter(at("```kotlin\nf {|a\n```"))), "the rest of the line on the new one")
    }

    @Test
    fun aCloserTypedAloneGoesOneStepBack() {
        fun typed(text: String, char: Char): String? {
            val before = at(text)
            val caret = before.selection.start
            val after = TextFieldValue(before.text.substring(0, caret) + char + before.text.substring(caret), TextRange(caret + 1))
            return shown(RichTextEditing.closerTyped(before, after))
        }
        assertEquals("```kotlin\nfun f() {\n    a()\n}|\n```", typed("```kotlin\nfun f() {\n    a()\n    |\n```", '}'))
        assertEquals("```python\nx = [\n    1,\n]|\n```", typed("```python\nx = [\n    1,\n    |\n```", ']'), "a bracket of Python")
        assertNull(typed("```kotlin\nfun f() {\n    a(|\n```", ')'), "not alone on its line")
        assertNull(typed("```kotlin\nfun f() {\n|\n```", '}'), "a line not indented")
        assertNull(typed("```sql\nselect (\n    |\n```", '}'), "not a closer of the language")
        assertNull(typed("```\nf {\n    |\n```", '}'), "no language")
        assertNull(typed("Text\n    |", '}'), "out of a block")
        assertEquals("- a\n  ```c\n  f() {\n    g()\n  }|\n  ```", typed("- a\n  ```c\n  f() {\n    g()\n    |\n  ```", '}'), "in a list item")
        assertNull(typed("- a\n  ```c\n  f() {\n  |\n  ```", '}'), "the spaces of the fence stay")
    }
}
