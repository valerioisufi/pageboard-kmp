package io.github.valerioisufi.drawview.explorer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.text.TextRange
import io.github.valerioisufi.drawview.canvas.SelectionFrame
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.model.geometry.Point
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A text box larger than the window, at the zoom of a page read closely (docs/plans/text-box-fixes.md). */
@OptIn(ExperimentalTestApi::class)
class TextBoxLargeTest {

    private val longLines = (1..12).joinToString("\n") { "Line $it: a long line going on past the right edge of the window when the document is zoomed." }

    @Test
    fun theFieldIsAsLargeAsItsBox() = textBoxScene { controller ->
        startBox(controller, 15f, 20f, widthMm = 180f, factor = 2.5f)
        type(longLines)
        bringBoxTo(controller, 40f, 40f)
        val editor = controller.textEditorManager
        val scale = controller.viewport.pixelsPerMm(editor.activeTextPageIndex)
        val field = fieldBounds()
        assertTrue(abs(field.width - editor.boxWidth * scale) <= 2f, "the field ${field.width} wide, its box ${editor.boxWidth * scale}")
        assertTrue(abs(field.height - editor.boxHeight * scale) <= 2f, "the field ${field.height} high, its box ${editor.boxHeight * scale}")
    }

    @Test
    fun aTextTakesTheSameHeightWrittenAndSaved() = textBoxScene { controller ->
        startBox(controller, 15f, 20f, widthMm = 180f, factor = 2.5f)
        type(longLines)
        val editor = controller.textEditorManager
        val written = editor.boxHeight
        runOnIdle { editor.finishEditing() }
        waitForIdle()
        val saved = controller.document.pages[0].texts.single().height
        // The same lines: within the rounding of their heights to the pixels of each, far from a line (7 mm)
        assertTrue(abs(written - saved) < 2f, "written $written mm high, saved $saved mm")
    }

    @Test
    fun aDragOnTheTextOfALargeBoxSelectsAndNeverMovesIt() = textBoxScene { controller ->
        startBox(controller, 15f, 20f, widthMm = 180f, factor = 2.5f)
        type(longLines)
        bringBoxTo(controller, 40f, -400f)
        val editor = controller.textEditorManager
        val corner = editor.boxX to editor.boxY
        // In the frame, past the height the canvas used to give the field from its corner
        val from = Offset(600f, 700f)
        onRoot().performMouseInput {
            moveTo(from)
            press()
            moveTo(from - Offset(100f, 100f))
            moveTo(from - Offset(200f, 200f))
            release()
        }
        waitForIdle()
        assertEquals(corner, editor.boxX to editor.boxY)
        assertTrue(!editor.value.selection.collapsed, "the text selected")
    }

    private fun cornerOnScreen(controller: DrawViewController): Point {
        val editor = controller.textEditorManager
        return checkNotNull(controller.viewport.pageMmToScreen(editor.activeTextPageIndex)).mapPoint(editor.boxX, editor.boxY)
    }

    @Test
    fun typingOnTheFirstLineOfATallBoxKeepsItsCaretInSight() = textBoxScene { controller ->
        startBox(controller, 30f, 30f, widthMm = 100f)
        type((1..28).joinToString("\n") { "Line $it" })
        val editor = controller.textEditorManager
        assertTrue(cornerOnScreen(controller).y < 0f, "the first line out of the screen, the last one in sight")
        runOnIdle { editor.updateValue(editor.value.copy(selection = TextRange(0))) }
        waitForIdle()
        type("X")
        assertTrue(cornerOnScreen(controller).y >= 0f, "the corner of the box at ${cornerOnScreen(controller).y}")
    }

    @Test
    fun typingPastTheRightEdgeOfTheWindowFollowsTheCaret() = textBoxScene { controller ->
        startBox(controller, 15f, 20f, widthMm = 190f, factor = 2.5f)
        bringBoxTo(controller, 40f, 40f)
        type("x".repeat(60))
        val corner = cornerOnScreen(controller)
        assertTrue(corner.x < 40f, "the box moved to the left: its corner at ${corner.x}")
        assertTrue(abs(corner.y - 40f) < 1f, "the box moved up by nothing: its corner at ${corner.y}")
    }

    @Test
    fun aPanByHandIsNotUndoneBeforeTheNextKey() = textBoxScene { controller ->
        startBox(controller, 30f, 30f, widthMm = 100f)
        type("A line")
        runOnIdle { controller.viewport.panBy(0f, -500f) }
        waitForIdle()
        val panned = cornerOnScreen(controller)
        assertTrue(panned.y < 0f)
        waitForIdle()
        assertEquals(panned, cornerOnScreen(controller))
        type("!")
        assertTrue(cornerOnScreen(controller).y >= 0f, "the next key brings the caret back")
    }

    @Test
    fun theHandlesOfATallBoxAreInSight() = textBoxScene { controller ->
        startBox(controller, 15f, 20f, widthMm = 180f, factor = 2.5f)
        type(longLines)
        bringBoxTo(controller, 40f, -900f)
        val frame = assertNotNull(SelectionFrame.ofEditedText(controller))
        // The grip of the left edge, where its middle would be below the window
        val height = controller.viewport.height
        val grip = Offset(frame.corners[0].x, frame.corners[3].y.coerceAtMost(height) / 2f + 64f)
        val width = controller.textEditorManager.boxWidth
        onRoot().performMouseInput {
            moveTo(grip)
            press()
            moveTo(grip + Offset(60f, 0f))
            moveTo(grip + Offset(120f, 0f))
            release()
        }
        waitForIdle()
        assertTrue(controller.textEditorManager.boxWidth < width, "the handle taken: the box ${controller.textEditorManager.boxWidth} wide, $width before")
    }
}
