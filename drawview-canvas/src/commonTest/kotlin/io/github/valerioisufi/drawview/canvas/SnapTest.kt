package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.geometry.toCompose
import io.github.valerioisufi.drawview.canvas.render.tile.TextBlockSize
import io.github.valerioisufi.drawview.canvas.render.tile.TextLineMetrics
import io.github.valerioisufi.drawview.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.drawview.canvas.snap.SnapEngine
import io.github.valerioisufi.drawview.canvas.snap.SnapLine
import io.github.valerioisufi.drawview.canvas.snap.SnapTargets
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import io.github.valerioisufi.drawview.model.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Snapping: the boxes aligned with what is around them, the texts on the lines of the paper.
 */
class SnapTest {

    @Test
    fun aBoxSnapsToTheNearestLineWithinTheTolerance() {
        val targets = SnapTargets(
            xs = listOf(SnapLine(50f, 0f, 10f), SnapLine(100f, 200f, 220f)),
            ys = listOf(SnapLine(80f, 0f, 30f))
        )
        // Its left edge 1 mm from x = 50, its bottom 2 mm from y = 80
        val snapped = SnapEngine.snapBox(Rect(51f, 58f, 71f, 78f), targets, tolerance = 2f)
        assertEquals(-1f, snapped.dx)
        assertEquals(2f, snapped.dy)
        // Guides from the line to the box moved
        val vertical = snapped.guides.single { it.vertical }
        assertEquals(50f, vertical.position)
        assertEquals(0f, vertical.from)
        assertEquals(80f, vertical.to)

        // Too far: stays
        val far = SnapEngine.snapBox(Rect(55f, 40f, 75f, 50f), targets, tolerance = 2f)
        assertEquals(0f, far.dx)
        assertEquals(0f, far.dy)
        assertTrue(far.guides.isEmpty())
    }

    @Test
    fun aBaselineSitsOnTheRowsOfThePaper() {
        val targets = SnapTargets(xs = emptyList(), ys = emptyList(), rows = floatArrayOf(40f, 48f, 56f))
        val snapped = SnapEngine.snapBox(Rect(10f, 41f, 60f, 51f), targets, tolerance = 3f, baseline = 49f)
        assertEquals(-1f, snapped.dy)
        assertTrue(snapped.guides.isEmpty(), "the line of the paper shows itself")

        assertEquals(48f, SnapEngine.rowAtOrBelow(45f, floatArrayOf(40f, 48f, 56f), reach = 8f))
        assertEquals(48f, SnapEngine.rowAtOrBelow(48f, floatArrayOf(40f, 48f, 56f), reach = 8f))
        assertNull(SnapEngine.rowAtOrBelow(60f, floatArrayOf(40f, 48f, 56f), reach = 8f))
    }

    /** Lines 0.5 mm per point high, their baseline 0.4 mm per point from their top. */
    private class FakeTextAdapter : TextTileAdapter {
        override fun drawText(canvas: Canvas, text: DrawText, mmToCanvas: Matrix2D) {}
        override fun measure(text: DrawText) = TextBlockSize(minOf(text.width, text.text.length * text.fontSize * 0.5f), text.fontSize * 0.5f)
        override fun lineMetrics(fontSize: Float) = TextLineMetrics(fontSize * 0.5f, fontSize * 0.4f)
    }

    private fun controller(scope: CoroutineScope, background: PageBackground = PageBackground.Solid()): DrawViewController {
        val controller = DrawViewController(document = DrawDocument(defaultBackground = background), coroutineScope = scope)
        controller.viewport.setSize(800f, 1200f)
        controller.tileManager.tileRenderer.textAdapter = FakeTextAdapter()
        return controller
    }

    @Test
    fun aSelectionMovedSnapsToTheTextsAroundIt() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val page = controller.document.pages[0]
        val anchor = DrawText().apply { text = "Anchor"; x = 30f; y = 20f; width = 40f; height = 8f }
        val moved = DrawText().apply { text = "Moved"; x = 100f; y = 100f; width = 40f; height = 8f }
        controller.session.submit(AddElements.of(page, listOf(anchor, moved)))
        val selection = controller.selectionManager

        selection.selectText(0, moved)
        selection.beginTransformation()
        // Its left edge brought 0.3 mm from the one of the anchor: aligned
        selection.moveBy(-70.3f, 20f)
        assertTrue(controller.snapManager.guides.any { it.vertical && it.position == 30f })
        selection.applyTransformation()
        assertEquals(30f, moved.x, 0.001f)
        assertTrue(controller.snapManager.guides.isEmpty(), "no guide once the gesture is over")

        // ⌥ / Alt held: where it is put
        selection.beginTransformation()
        selection.moveBy(0.3f, 0f, free = true)
        selection.applyTransformation()
        assertEquals(30.3f, moved.x, 0.001f)

        // Snapping off
        controller.snapManager.updateEnabled(false)
        selection.beginTransformation()
        selection.moveBy(-0.2f, 0f)
        selection.applyTransformation()
        assertEquals(30.1f, moved.x, 0.001f)
    }

    @Test
    fun aTextIsWrittenOnTheLinesOfThePaper() = runTest(UnconfinedTestDispatcher()) {
        // Lines every 8 mm
        val controller = controller(backgroundScope, PageBackground.Ruled(spacingMm = 8f))
        val editor = controller.textEditorManager

        // Tapped between the lines at 48 and 56 mm: written on the one at 56 mm (16 pt: its baseline 6.4 mm from its top)
        editor.startNewText(0, 30f, 50f)
        assertEquals(56f - 6.4f, editor.boxY, 0.001f)
        editor.updateValue(TextFieldValue("Line", TextRange(4)))

        // Moved near a line, it sits on it
        editor.moveTo(30f, 58f)
        assertEquals(64f - 6.4f, editor.boxY, 0.001f)
        editor.moveTo(30f, 58f, free = true)
        assertEquals(58f, editor.boxY, 0.001f)

        // Without snapping, where it was tapped
        editor.finishEditing()
        controller.snapManager.updateEnabled(false)
        editor.startNewText(0, 30f, 100f)
        assertEquals(100f - 4f, editor.boxY, 0.001f)
    }

    @Test
    fun theSideOfAWrittenTextSnapsToTheTextsAroundIt() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val page = controller.document.pages[0]
        val anchor = DrawText().apply { text = "Anchor"; x = 30f; y = 20f; width = 100f; height = 8f }
        controller.session.submit(AddElements.of(page, listOf(anchor)))
        val editor = controller.textEditorManager
        editor.startDrawnText(0, Rect(30f, 60f, 90f, 70f).toCompose())

        // Its right edge brought 0.4 mm from the one of the anchor: aligned
        editor.resizeTo(129.6f, leftEdge = false)
        assertEquals(130f, editor.boxX + editor.boxWidth, 0.001f)
        editor.resizeTo(129.6f, leftEdge = false, free = true)
        assertEquals(129.6f, editor.boxX + editor.boxWidth, 0.001f)
    }
}
