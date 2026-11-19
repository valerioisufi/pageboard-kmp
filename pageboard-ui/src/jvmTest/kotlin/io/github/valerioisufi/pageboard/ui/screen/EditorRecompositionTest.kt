package io.github.valerioisufi.pageboard.ui.screen

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.SkikoComposeUiTest
import io.github.valerioisufi.pageboard.canvas.controller.DrawViewController
import io.github.valerioisufi.pageboard.model.DrawDocument
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.RenameDocument
import io.github.valerioisufi.pageboard.model.geometry.Point
import io.github.valerioisufi.pageboard.ui.theme.DrawViewTheme
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * The editor follows the document and the camera without composing again what does not change
 * (see `docs/plans/performance.md`, phase 5): over 100 strokes it composes nothing again, and what it
 * shows of the document still follows the changes that matter; the field of the text being
 * written follows a pan without composing again, and a zoom composes it at its new scale.
 */
@OptIn(ExperimentalTestApi::class)
class EditorRecompositionTest {

    @BeforeTest
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    @Test
    fun aStrokeComposesNothingAgain() = runDesktopComposeUiTest(width = 1280, height = 800) {
        val controller = DrawViewController(document = DrawDocument(), coroutineScope = CoroutineScope(Dispatchers.Unconfined))
        val counter = editor(controller)
        val page = controller.document.pages.first()
        fun stroke() = runOnIdle { controller.session.submit(AddElements.of(page, listOf(CompositionCounter.stroke()))) }
        undo().assertIsNotEnabled()

        // The first stroke enables Undo
        stroke()
        undo().assertIsEnabled()
        counter.reset()
        repeat(STROKES) { stroke(); waitForIdle() }
        assertEquals(0, counter.entered, "Entered again: ${counter.names}")

        // What the bar shows of the document follows it
        onNodeWithText("1 page").assertExists()
        runOnIdle { controller.pageManager.addNewPageAtBottom() }
        onNodeWithText("2 pages").assertExists()
        runOnIdle { controller.session.submit(RenameDocument("Analysis")) }
        onNodeWithText("Analysis").assertExists()
        runOnIdle { repeat(STROKES + 1) { controller.undo() } }
        undo().assertIsNotEnabled()
    }

    @Test
    fun theFieldOfATextFollowsAPanWithoutComposingAgain() = runDesktopComposeUiTest(width = 1280, height = 800) {
        val controller = DrawViewController(document = DrawDocument(), coroutineScope = CoroutineScope(Dispatchers.Unconfined))
        val counter = editor(controller)
        val editor = controller.textEditorManager
        runOnIdle { editor.startNewText(pageIndex = 0, xMm = 40f, yMm = 60f) }
        waitForIdle()
        val field = onNode(hasSetTextAction())
        val before = field.getBoundsInRoot()
        val boxBefore = runOnIdle { controller.boxOnScreen() }

        counter.reset()
        repeat(FRAMES) { runOnIdle { controller.viewport.panBy(0f, -2f) }; waitForIdle() }
        assertEquals(0, counter.entered, "Entered again: ${counter.names}")
        // Moved with its box
        val after = field.getBoundsInRoot()
        val boxAfter = runOnIdle { controller.boxOnScreen() }
        assertTrue(boxAfter.y < boxBefore.y, "The camera did not move")
        assertEquals(boxAfter.x - boxBefore.x, px(after.left - before.left), 1f)
        assertEquals(boxAfter.y - boxBefore.y, px(after.top - before.top), 1f)

        // A zoom composes it again, as wide as its box at the new scale
        runOnIdle { controller.viewport.zoomIn() }
        waitForIdle()
        assertTrue(counter.entered > 0)
        val scale = runOnIdle { controller.viewport.pageMmToScreen(0)!!.scaleFactor }
        assertEquals((editor.boxWidth * scale).toInt().toFloat(), px(field.getBoundsInRoot().run { right - left }), 1f)
    }

    /** Composes the editor of [controller], counting what it composes again. */
    private fun SkikoComposeUiTest.editor(controller: DrawViewController): CompositionCounter {
        val counter = CompositionCounter().apply { named = true }
        setContent {
            DrawViewTheme(dynamicColor = false) {
                counter.Observe { DrawScreen(controller, onNavigateBack = {}) }
            }
        }
        waitForIdle()
        return counter
    }

    private fun SkikoComposeUiTest.undo(): SemanticsNodeInteraction =
        onNode(hasClickAction() and hasContentDescription("Undo"))

    private fun SkikoComposeUiTest.px(dp: androidx.compose.ui.unit.Dp): Float = with(density) { dp.toPx() }

    /** Where the box of the text being written is on the canvas. */
    private fun DrawViewController.boxOnScreen(): Point =
        viewport.pageMmToScreen(textEditorManager.activeTextPageIndex)!!.mapPoint(textEditorManager.boxX, textEditorManager.boxY)

    private companion object {
        const val STROKES = 100
        const val FRAMES = 100
    }
}
