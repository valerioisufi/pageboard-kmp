package io.github.valerioisufi.drawview.canvas

import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.StrokePoint
import io.github.valerioisufi.drawview.model.StrokeTool
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.ChangeObserver
import io.github.valerioisufi.drawview.model.change.StrokeData
import io.github.valerioisufi.drawview.model.newElementId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** What the code added to an editor reaches through its ports, and that it acts on the editor. */
class EditorPortsTest {

    private fun stroke(x: Float) = StrokeData(
        id = newElementId(),
        points = (0 until 5).map { StrokePoint(x = x + it, y = 10f + it, pressure = 0.5f, timestampMs = it * 8L) },
        tool = StrokeTool.PRESSURE_PEN,
        size = 0.5f,
        color = 0xFF0000FF.toInt()
    ).toElement()

    @Test
    fun theChangesSubmittedInAGroupAreUndoneTogetherAndObserved() = runTest {
        val document = DrawDocument(defaultBackground = PageBackground.Ruled())
        val controller = DrawViewController(document = document, coroutineScope = backgroundScope)
        val ports = controller.ports
        var observed = 0
        val observer = ChangeObserver { observed++ }
        ports.document.addObserver(observer)

        ports.document.group {
            ports.document.submit(AddElements.of(document.pages[0], listOf(stroke(10f))))
            ports.document.submit(AddElements.of(document.pages[0], listOf(stroke(40f))))
        }
        assertEquals(2, document.pages[0].strokes.count { it.removed == null })
        assertTrue(observed >= 2)

        controller.undo()
        assertEquals(0, document.pages[0].strokes.count { it.removed == null }, "one undo for the group")

        ports.document.removeObserver(observer)
        val before = observed
        ports.document.submit(AddElements.of(document.pages[0], listOf(stroke(70f))))
        assertEquals(before, observed)
    }

    @Test
    fun thePagesAndTheEditsAreThoseOfTheEditor() = runTest {
        val document = DrawDocument(defaultBackground = PageBackground.Ruled())
        document.addPage()
        val controller = DrawViewController(document = document, coroutineScope = backgroundScope)
        controller.viewport.setSize(800f, 1200f)
        val ports = controller.ports

        ports.pages.selection.toggle(document.pages[1].id)
        assertEquals(1, controller.pageManager.selection.count)
        assertEquals(0, ports.pages.firstVisiblePageIndex())
        assertEquals(controller.viewport.pageMmToScreen(0)?.mapPoint(0f, 0f), ports.pages.pageMmToScreen(0)?.mapPoint(0f, 0f))

        assertEquals(false, ports.edits.isEditingText)
        assertEquals(controller.engines, ports.rendering.engines)
        assertEquals(controller.presence, ports.presence)
    }
}
