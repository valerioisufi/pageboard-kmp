package io.github.valerioisufi.drawview.explorer

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The notation the page hides stays hidden while the text is written (docs/plans/text-fidelity.md): the
 * arrows cross it with one press per character seen, nothing moves, and Backspace takes what is seen.
 */
@OptIn(ExperimentalTestApi::class)
class TextBoxNotationTest {

    private val text = "Testo **grassetto** e `codice` e ~~barrato~~ e _corsivo_ e \\* fine"

    private fun DesktopComposeUiTest.press(key: Key) {
        onNode(hasSetTextAction()).performKeyInput { pressKey(key) }
        waitForIdle()
    }

    private fun DesktopComposeUiTest.fieldLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    @Test
    fun theArrowsCrossTheNotationOneCharacterSeenAtATime() = textBoxScene { controller ->
        startBox(controller, 20f, 40f, widthMm = 180f)
        type(text)
        val editor = controller.textEditorManager
        runOnIdle { editor.updateValue(editor.value.copy(selection = TextRange(0))) }
        waitForIdle()
        val shown = fieldLayout().layoutInput.text.text
        val seen = shown.count { it != '​' && it != '￼' && it != '⁠' }
        val height = fieldBounds().height
        var presses = 0
        while (editor.value.selection.start < text.length && presses < text.length) {
            val before = editor.value.selection.start
            press(Key.DirectionRight)
            presses++
            assertTrue(editor.value.selection.start > before, "press $presses moved nothing from $before")
            assertEquals(shown, fieldLayout().layoutInput.text.text, "the text shown the same at press $presses")
            assertEquals(height, fieldBounds().height, "the field as high at press $presses")
        }
        assertEquals(seen, presses, "one press per character seen of \"$shown\"")
        // And back
        presses = 0
        while (editor.value.selection.start > 0 && presses < text.length) {
            press(Key.DirectionLeft)
            presses++
        }
        assertEquals(seen, presses, "back, one press per character seen")
    }

    @Test
    fun backspaceAfterAFormatTakesItsLastLetter() = textBoxScene { controller ->
        startBox(controller, 20f, 40f, widthMm = 180f)
        type(text)
        val editor = controller.textEditorManager
        // The caret after the closing delimiters, as after typing them
        runOnIdle { editor.updateValue(editor.value.copy(selection = TextRange(text.indexOf(" e `")))) }
        waitForIdle()
        press(Key.Backspace)
        assertEquals(text.replace("grassetto**", "grassett**"), editor.value.text)
    }
}
