package io.github.valerioisufi.drawview.explorer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What moves the canvas over the field of the text being written, and what stays with the field (docs/plans/text-box-fixes.md). */
@OptIn(ExperimentalTestApi::class)
class TextBoxGesturesOverFieldTest {

    private fun DesktopComposeUiTest.openBox(controller: DrawViewController) {
        startBox(controller, 30f, 60f, widthMm = 120f)
        type("Some lines of text\nto put the fingers on\nat the middle of a page\nof a note")
    }

    private fun cornerY(controller: DrawViewController): Float {
        val editor = controller.textEditorManager
        return checkNotNull(controller.viewport.pageMmToScreen(editor.activeTextPageIndex)).mapPoint(editor.boxX, editor.boxY).y
    }

    @Test
    fun aFingerDraggedOnTheFieldPansTheCanvas() = textBoxScene { controller ->
        openBox(controller)
        val before = cornerY(controller)
        val selection = controller.textEditorManager.value.selection
        val center = fieldBounds().center
        onRoot().performTouchInput {
            down(center)
            repeat(4) { moveBy(Offset(0f, -40f)) }
            up()
        }
        waitForIdle()
        assertTrue(cornerY(controller) <= before - 100f, "the corner from $before to ${cornerY(controller)}")
        assertEquals(selection, controller.textEditorManager.value.selection, "the caret where it was")
        assertTrue(controller.textEditorManager.isEditing)
    }

    @Test
    fun twoFingersOnTheFieldZoomTheCanvas() = textBoxScene { controller ->
        openBox(controller)
        val before = controller.viewport.pixelsPerMm(0)
        val selection = controller.textEditorManager.value.selection
        val center = fieldBounds().center
        onRoot().performTouchInput {
            down(0, center - Offset(30f, 0f))
            down(1, center + Offset(30f, 0f))
            repeat(4) {
                moveBy(0, Offset(-30f, 0f))
                moveBy(1, Offset(30f, 0f))
            }
            up(0)
            up(1)
        }
        waitForIdle()
        assertTrue(controller.viewport.pixelsPerMm(0) > before * 1.2f, "the scale from $before to ${controller.viewport.pixelsPerMm(0)}")
        assertEquals(selection, controller.textEditorManager.value.selection, "the caret where it was")
    }

    @Test
    fun aTapOnTheFieldStillPutsTheCaret() = textBoxScene { controller ->
        openBox(controller)
        val before = cornerY(controller)
        val selection = controller.textEditorManager.value.selection
        val field = fieldBounds()
        onRoot().performTouchInput {
            down(Offset(field.left + 20f, field.top + 10f))
            up()
        }
        waitForIdle()
        assertTrue(controller.textEditorManager.value.selection != selection, "the caret moved")
        assertEquals(before, cornerY(controller))
    }

    @Test
    fun aFingerMovingAfterItsLongPressIsTheField() = textBoxScene { controller ->
        openBox(controller)
        val before = cornerY(controller)
        val center = fieldBounds().center
        onRoot().performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(4) { moveBy(Offset(0f, -40f)) }
            up()
        }
        waitForIdle()
        assertEquals(before, cornerY(controller))
    }

    @Test
    fun aWheelOnTheFieldPansTheCanvas() = textBoxScene { controller ->
        openBox(controller)
        val before = cornerY(controller)
        val center = fieldBounds().center
        onRoot().performMouseInput {
            moveTo(center)
            scroll(3f)
        }
        waitForIdle()
        assertTrue(cornerY(controller) < before, "the corner from $before to ${cornerY(controller)}")
    }

    @Test
    fun aMouseOnTheFieldSelectsAndTheMiddleButtonPans() = textBoxScene { controller ->
        openBox(controller)
        val before = cornerY(controller)
        val onLine = fieldBounds().let { Offset(it.left + 20f, it.top + 10f) }
        onRoot().performMouseInput {
            moveTo(onLine)
            press()
            moveTo(onLine + Offset(50f, 0f))
            moveTo(onLine + Offset(100f, 0f))
            release()
        }
        waitForIdle()
        assertEquals(before, cornerY(controller))
        assertTrue(!controller.textEditorManager.value.selection.collapsed, "the text selected")
        onRoot().performMouseInput {
            moveTo(onLine)
            press(androidx.compose.ui.test.MouseButton.Tertiary)
            moveTo(onLine - Offset(0f, 100f))
            moveTo(onLine - Offset(0f, 200f))
            release(androidx.compose.ui.test.MouseButton.Tertiary)
        }
        waitForIdle()
        assertTrue(cornerY(controller) <= before - 100f, "the corner from $before to ${cornerY(controller)}")
    }
}
