package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.canvas.controller.TextEditorManager
import io.github.valerioisufi.pageboard.canvas.text.RichTextEditing.Format
import io.github.valerioisufi.pageboard.model.Document
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** The formats chosen at the caret before typing: nothing written, what is typed next written in them. */
class PendingFormatsTest {

    private fun editor(text: String, caret: Int = text.length): TextEditorManager {
        val controller = PageboardController(document = Document(), coroutineScope = CoroutineScope(Dispatchers.Unconfined))
        val editor = controller.textEditorManager
        editor.startNewText(0, 10f, 10f)
        editor.updateValue(TextFieldValue(text, TextRange(caret)))
        return editor
    }

    /** [this] typing [typed] at its caret. */
    private fun TextEditorManager.typeAtCaret(typed: String) {
        val caret = value.selection.start
        updateValue(TextFieldValue(value.text.substring(0, caret) + typed + value.text.substring(caret), TextRange(caret + typed.length)))
    }

    @Test
    fun aFormatChosenAtTheCaretWritesNothingAndWritesWhatIsTyped() {
        val editor = editor("Hello ")
        editor.toggle(Format.BOLD)
        assertEquals("Hello ", editor.value.text, "nothing written")
        assertTrue(editor.isActive(Format.BOLD), "shown on")
        editor.typeAtCaret("w")
        assertEquals("Hello **w**", editor.value.text)
        assertEquals(TextRange(9), editor.value.selection, "the caret in the format")
        editor.typeAtCaret("orld")
        assertEquals("Hello **world**", editor.value.text, "the next characters in it too")
    }

    @Test
    fun severalFormatsWaitTogether() {
        val editor = editor("a ")
        editor.toggle(Format.BOLD)
        editor.toggle(Format.ITALIC)
        editor.typeAtCaret("b")
        assertTrue(editor.isActive(Format.BOLD) && editor.isActive(Format.ITALIC), editor.value.text)
        assertEquals("b", editor.value.text.trim('a', ' ', '*', '_'))
    }

    @Test
    fun aFormatChosenAgainOrACaretMovedDropsIt() {
        val editor = editor("Hello ")
        editor.toggle(Format.BOLD)
        editor.toggle(Format.BOLD)
        assertFalse(editor.isActive(Format.BOLD))
        editor.typeAtCaret("x")
        assertEquals("Hello x", editor.value.text)

        editor.toggle(Format.ITALIC)
        editor.updateValue(editor.value.copy(selection = TextRange(0)))
        assertFalse(editor.isActive(Format.ITALIC), "the caret moved")
        editor.typeAtCaret("y")
        assertEquals("yHello x", editor.value.text)
    }

    @Test
    fun aTextComposedGoesInTheFormatOnceCommitted() {
        val editor = editor("a ")
        editor.toggle(Format.BOLD)
        editor.updateValue(TextFieldValue("a か", TextRange(3), composition = TextRange(2, 3)))
        assertEquals("a か", editor.value.text, "composing: not yet")
        editor.updateValue(TextFieldValue("a 感", TextRange(3)))
        assertEquals("a **感**", editor.value.text)
    }

    @Test
    fun aCaretInTheFormatTakesItOffAsBefore() {
        val editor = editor("a **bold** c", caret = 6)
        editor.toggle(Format.BOLD)
        assertEquals("a bold c", editor.value.text)
    }
}
