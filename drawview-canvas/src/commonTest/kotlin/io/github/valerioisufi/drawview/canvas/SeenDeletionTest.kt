package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.drawview.canvas.text.RichTextEditing
import io.github.valerioisufi.drawview.canvas.text.SeenDeletion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A deletion in the text being written takes what is seen: the delimiters, not shown, stay with the
 * part of the format left (docs/text-fidelity.md).
 */
class SeenDeletionTest {

    private fun backspace(text: String, caret: Int): TextFieldValue {
        val old = TextFieldValue(text, TextRange(caret))
        val new = TextFieldValue(text.removeRange(caret - 1, caret), TextRange(caret - 1))
        return SeenDeletion.of(old, new) ?: new
    }

    private fun delete(text: String, caret: Int): TextFieldValue {
        val old = TextFieldValue(text, TextRange(caret))
        val new = TextFieldValue(text.removeRange(caret, caret + 1), TextRange(caret))
        return SeenDeletion.of(old, new) ?: new
    }

    private fun replace(text: String, selection: TextRange, typed: String = ""): TextFieldValue {
        val old = TextFieldValue(text, selection)
        val new = TextFieldValue(text.replaceRange(selection.min, selection.max, typed), TextRange(selection.min + typed.length))
        return SeenDeletion.of(old, new) ?: new
    }

    @Test
    fun backspaceAfterAFormatTakesItsLastCharacter() {
        assertEquals(TextFieldValue("a **bol** c", TextRange(7)), backspace("a **bold** c", "a **bold**".length))
        assertEquals(TextFieldValue("a ~~struc~~", TextRange(9)), backspace("a ~~struck~~", "a ~~struck~~".length))
        assertEquals(TextFieldValue("a `cod`", TextRange(6)), backspace("a `code`", "a `code`".length))
    }

    @Test
    fun theLastCharacterOfAFormatTakesItsDelimitersWithIt() {
        assertEquals(TextFieldValue("a  c", TextRange(2)), backspace("a **d** c", "a **d**".length))
        assertEquals(TextFieldValue("a ", TextRange(2)), backspace("a \\*", "a \\*".length), "an escape with what it escapes")
    }

    @Test
    fun deleteBeforeAFormatTakesItsFirstCharacter() {
        assertEquals(TextFieldValue("a **old** c", TextRange(2)), delete("a **bold** c", 2))
    }

    @Test
    fun aSelectionTakesWhatItShows() {
        val text = "**bold** plain"
        // Across the end of the bold: its first letters stay bold
        assertEquals(TextFieldValue("**bo**in", TextRange(4)), replace(text, TextRange(4, "**bold** pla".length)))
        // Typed over: what is typed goes on in the bold, on its left
        assertEquals(TextFieldValue("**boX**ain", TextRange(5)), replace(text, TextRange(4, "**bold** pl".length), "X"))
        // The whole content of a span: its delimiters go
        assertEquals(TextFieldValue("a  b", TextRange(2)), replace("a **bold** b", TextRange(4, 8)))
        // Nested, the outer one too
        assertEquals(TextFieldValue("a  b", TextRange(2)), replace("a **_x_** b", TextRange(5, 6)))
    }

    @Test
    fun aDeletionOfWhatIsSeenAlreadyNeedsNothing() {
        assertNull(SeenDeletion.of(TextFieldValue("a **bold** c", TextRange(6)), TextFieldValue("a **bld** c", TextRange(5))))
        assertNull(SeenDeletion.of(TextFieldValue("plain", TextRange(3)), TextFieldValue("plin", TextRange(2))))
    }

    @Test
    fun backspaceAtTheStartOfAHeadingMakesItText() {
        val old = TextFieldValue("Intro\n## Title", TextRange("Intro\n## ".length))
        val new = TextFieldValue("Intro\n##Title", TextRange("Intro\n##".length))
        assertEquals(TextFieldValue("Intro\nTitle", TextRange("Intro\n".length)), RichTextEditing.handleConcealedDeletion(old, new))
    }
}
