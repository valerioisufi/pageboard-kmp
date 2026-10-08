package io.github.valerioisufi.drawview.core

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.drawview.core.text.RichTextEditing
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
}
