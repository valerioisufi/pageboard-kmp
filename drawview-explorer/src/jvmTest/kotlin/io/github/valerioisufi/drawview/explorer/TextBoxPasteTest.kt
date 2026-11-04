package io.github.valerioisufi.drawview.explorer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A real paste in the field of a text and on the canvas (docs/text-box-fixes.md). */
@OptIn(ExperimentalTestApi::class)
class TextBoxPasteTest {

    /** The modifier of the shortcuts of the text fields where the tests run. */
    private val shortcut = if (System.getProperty("os.name").startsWith("Mac")) Key.MetaLeft else Key.CtrlLeft

    private fun DesktopComposeUiTest.pressWithShortcut(key: Key) {
        onNode(hasSetTextAction()).performKeyInput { withKeyDown(shortcut) { pressKey(key) } }
        waitForIdle()
    }

    private val bullets = "• Primo\r\n• Secondo che era a capo\r\nsu due righe\r\n• Terzo"
    private val list = "- Primo\n- Secondo che era a capo su due righe\n- Terzo"

    @Test
    fun aPasteInTheFieldIsCleanedAndOneUndoTakesItAway() {
        val clipboard = TextBoxClipboard(bullets)
        textBoxScene(clipboard) { controller ->
            startBox(controller, 30f, 60f, widthMm = 120f)
            val editor = controller.textEditorManager
            type("Prima ")
            pressWithShortcut(Key.V)
            assertEquals("Prima $list", editor.value.text)
            assertEquals(("Prima $list").length, editor.value.selection.start)
            pressWithShortcut(Key.Z)
            assertEquals("Prima ", editor.value.text)
        }
    }

    @Test
    fun aPasteInABlockOfCodeOnlyChangesItsLineEndings() {
        val clipboard = TextBoxClipboard("• uno\r\n• due")
        textBoxScene(clipboard) { controller ->
            startBox(controller, 30f, 60f, widthMm = 120f)
            val editor = controller.textEditorManager
            type("```\nx\n```")
            runOnIdle { editor.updateValue(editor.value.copy(selection = TextRange(5))) }
            waitForIdle()
            pressWithShortcut(Key.V)
            assertEquals("```\nx• uno\n• due\n```", editor.value.text)
        }
    }

    @Test
    fun aPasteOverASelectionInACellKeepsTheTable() {
        val clipboard = TextBoxClipboard("Riga | uno\r\nRiga due")
        textBoxScene(clipboard) { controller ->
            startBox(controller, 30f, 60f, widthMm = 120f)
            val editor = controller.textEditorManager
            runOnIdle { editor.insertTable(2, 2) }
            waitForIdle()
            type("Test Cella")
            val start = editor.value.text.indexOf("Test")
            runOnIdle { editor.updateValue(editor.value.copy(selection = TextRange(start, start + 4))) }
            waitForIdle()
            pressWithShortcut(Key.V)
            assertEquals("| Riga \\| uno Riga due Cella  |   |", editor.value.text.lines()[0])
            assertTrue(editor.inTable)
        }
    }

    @Test
    fun aShortTextPastedOnTheCanvasIsCleaned() = textBoxScene { controller ->
        runOnIdle { controller.textEditorManager.pasteText(bullets, Offset(600f, 300f)) }
        waitForIdle()
        assertEquals(list, controller.document.pages[0].texts.single().text)
    }
}
