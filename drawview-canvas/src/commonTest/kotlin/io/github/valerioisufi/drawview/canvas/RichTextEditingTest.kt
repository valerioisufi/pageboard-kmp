package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.drawview.canvas.text.RichTextEditing
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The formatting of a text being written, as changes of its notation.
 */
class RichTextEditingTest {

    @Test
    fun wrapsTheSelectionWithDelimiters() {
        val original = TextFieldValue(text = "Hello World", selection = TextRange(6, 11))
        val wrapped = RichTextEditing.toggleDelimiters(original, "**", "**")
        assertEquals("Hello **World**", wrapped.text)
        assertEquals(TextRange(8, 13), wrapped.selection)
    }

    @Test
    fun unwrapsWhenTheSelectionIsAlreadyWrapped() {
        val original = TextFieldValue(text = "Hello **World**", selection = TextRange(8, 13))
        val unwrapped = RichTextEditing.toggleDelimiters(original, "**", "**")
        assertEquals("Hello World", unwrapped.text)
    }

    @Test
    fun insertsDelimitersAtTheCursor() {
        val original = TextFieldValue(text = "E = mc", selection = TextRange(6))
        val result = RichTextEditing.toggleDelimiters(original, "$", "$")
        assertEquals("E = mc$$", result.text)
        assertEquals(TextRange(7), result.selection)
    }

    @Test
    fun aHeadingStartsItsLineWhereverTheCaretIs() {
        val original = TextFieldValue(text = "Intro\nTitle here\nEnd", selection = TextRange(12))
        val heading = RichTextEditing.toggleHeading(original)
        assertEquals("Intro\n# Title here\nEnd", heading.text)
        assertEquals(TextRange(14), heading.selection, "the caret stays on its character")

        val plain = RichTextEditing.toggleHeading(heading)
        assertEquals(original, plain)

        val subtitle = RichTextEditing.toggleHeading(TextFieldValue("##  Sub", TextRange(1)))
        assertEquals("Sub", subtitle.text)
        assertEquals(TextRange(0), subtitle.selection)
    }

    @Test
    fun aFormatWrapsTheSelectionInItsDelimiters() {
        val value = TextFieldValue("area pi r^2", TextRange(5, 11))
        assertEquals("area \$pi r^2\$", RichTextEditing.toggle(value, RichTextEditing.Format.FORMULA).text)
        assertEquals("area ~~pi r^2~~", RichTextEditing.toggle(value, RichTextEditing.Format.STRIKETHROUGH).text)
    }

    @Test
    fun toggleBulletListOnSingleLineAddsAndRemovesBullet() {
        val plain = TextFieldValue("Hello World", TextRange(6))
        val bullet = RichTextEditing.toggleBulletList(plain)
        assertEquals("- Hello World", bullet.text)
        assertEquals(TextRange(8), bullet.selection, "the caret shifts with the added prefix")

        val reverted = RichTextEditing.toggleBulletList(bullet)
        assertEquals("Hello World", reverted.text)
        assertEquals(TextRange(6), reverted.selection, "the caret shifts back when prefix is removed")
    }

    @Test
    fun toggleNumberedListOnSingleLineAddsAndRemovesNumber() {
        val plain = TextFieldValue("First item", TextRange(2))
        val numbered = RichTextEditing.toggleNumberedList(plain)
        assertEquals("1. First item", numbered.text)
        assertEquals(TextRange(5), numbered.selection)

        val reverted = RichTextEditing.toggleNumberedList(numbered)
        assertEquals("First item", reverted.text)
        assertEquals(TextRange(2), reverted.selection)
    }

    @Test
    fun toggleListConvertsBetweenBulletAndNumbered() {
        val bullet = TextFieldValue("- Item", TextRange(3))
        val numbered = RichTextEditing.toggleNumberedList(bullet)
        assertEquals("1. Item", numbered.text)

        val backToBullet = RichTextEditing.toggleBulletList(numbered)
        assertEquals("- Item", backToBullet.text)
    }

    @Test
    fun toggleListsOnMultilineSelection() {
        val lines = "Apple\nBanana\nCherry"
        val value = TextFieldValue(lines, TextRange(0, lines.length))
        val bulleted = RichTextEditing.toggleBulletList(value)
        assertEquals("- Apple\n- Banana\n- Cherry", bulleted.text)

        val numbered = RichTextEditing.toggleNumberedList(bulleted)
        assertEquals("1. Apple\n2. Banana\n3. Cherry", numbered.text)

        val plain = RichTextEditing.toggleNumberedList(numbered)
        assertEquals("Apple\nBanana\nCherry", plain.text)
    }

    @Test
    fun toggleListPreservesIndentation() {
        val indented = TextFieldValue("  Subitem", TextRange(4))
        val bulleted = RichTextEditing.toggleBulletList(indented)
        assertEquals("  - Subitem", bulleted.text)
        assertEquals(TextRange(6), bulleted.selection)

        val plain = RichTextEditing.toggleBulletList(bulleted)
        assertEquals("  Subitem", plain.text)
        assertEquals(TextRange(4), plain.selection)
    }
}
