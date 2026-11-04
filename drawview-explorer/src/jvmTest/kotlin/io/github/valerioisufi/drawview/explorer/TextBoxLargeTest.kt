package io.github.valerioisufi.drawview.explorer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A text box larger than the window, at the zoom of a page read closely (docs/text-box-fixes.md). */
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
}
