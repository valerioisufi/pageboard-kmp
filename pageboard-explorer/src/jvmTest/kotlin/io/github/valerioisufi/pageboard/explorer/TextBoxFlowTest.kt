package io.github.valerioisufi.pageboard.explorer

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import io.github.valerioisufi.pageboard.canvas.controller.DrawViewController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Writing past the bottom of a page, with real keys: the writing is not interrupted (docs/plans/text-box-fixes.md). */
@OptIn(ExperimentalTestApi::class)
class TextBoxFlowTest {

    /** A box low on the first page with lines reaching its bottom margin. */
    private fun androidx.compose.ui.test.DesktopComposeUiTest.fillToTheBottom(controller: DrawViewController) {
        startBox(controller, 30f, 262f, widthMm = 120f)
        type("one\ntwo\nthree")
    }

    private fun androidx.compose.ui.test.DesktopComposeUiTest.pressKeys(keys: List<Key>) {
        onNode(hasSetTextAction()).performKeyInput { keys.forEach { pressKey(it) } }
        waitForIdle()
    }


    @Test
    fun anEnterAtTheBottomOfAPageMakesNoPage() = textBoxScene { controller ->
        fillToTheBottom(controller)
        val editor = controller.textEditorManager
        repeat(6) { pressKeys(listOf(Key.Enter)) }
        assertEquals(1, controller.document.pages.size)
        assertEquals(0, editor.activeTextPageIndex)
        runOnIdle { editor.finishEditing() }
        waitForIdle()
        assertEquals(1, controller.document.pages.size, "nor on closing")
    }

    @Test
    fun theNextCharacterGoesOnThereTheFieldKeepingTheFocus() = textBoxScene { controller ->
        fillToTheBottom(controller)
        val editor = controller.textEditorManager
        var keys = 0
        while (controller.document.pages.size == 1 && keys < 20) {
            pressKeys(listOf(Key.Enter))
            type("a")
            keys++
        }
        assertEquals(2, controller.document.pages.size)
        assertEquals(1, editor.activeTextPageIndex)
        onNode(hasSetTextAction() and isFocused()).assertExists()
        // The keys go on in the new box, by themselves
        val written = editor.value.text
        type("b")
        assertEquals(written + "b", editor.value.text)
    }

    @Test
    fun theCaretOnTheNewPageIsInSightWithoutTheCameraJumping() = textBoxScene { controller ->
        fillToTheBottom(controller)
        val editor = controller.textEditorManager
        var keys = 0
        while (controller.document.pages.size == 1 && keys < 20) {
            pressKeys(listOf(Key.Enter))
            type("a")
            keys++
        }
        waitForIdle()
        val corner = checkNotNull(controller.viewport.pageMmToScreen(editor.activeTextPageIndex)).mapPoint(editor.boxX, editor.boxY)
        assertTrue(corner.y in 0f..controller.viewport.height, "the corner of the new box at ${corner.y}")
    }
}

