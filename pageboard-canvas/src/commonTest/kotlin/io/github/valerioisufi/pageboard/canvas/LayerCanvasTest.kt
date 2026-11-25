package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.geometry.Offset
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.canvas.geometry.mapOffset
import io.github.valerioisufi.pageboard.ink.InkStrokeModeller
import io.github.valerioisufi.pageboard.model.BrushConfig
import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.ImageElement
import io.github.valerioisufi.pageboard.model.InputToolType
import io.github.valerioisufi.pageboard.model.StrokeElement
import io.github.valerioisufi.pageboard.model.StrokeMesh
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.LockElements
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class LayerCanvasTest {

    private fun controller(scope: kotlinx.coroutines.CoroutineScope): PageboardController {
        val doc = Document(strokeModeller = InkStrokeModeller)
        return PageboardController(document = doc, coroutineScope = scope).also { it.viewport.setSize(800f, 1200f) }
    }

    private fun image(x: Float, y: Float, w: Float = 40f, h: Float = 40f) = ImageElement().apply {
        this.x = x; this.y = y; width = w; height = h
    }

    private fun horizontalStroke(y: Float): StrokeElement {
        val pts = listOf(StrokePoint(20f, y, 0.5f, 1L), StrokePoint(60f, y, 0.5f, 16L))
        val brush = BrushConfig(StrokeTool.PRESSURE_PEN, 2f, -16777216)
        val mesh = InkStrokeModeller.buildMesh(pts, brush, InputToolType.STYLUS)!!
        return StrokeElement(mesh = mesh, brushConfig = brush)
    }

    @Test
    fun lockedElementsAreProtectedFromLassoTransformAndEraser() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val page = controller.document.pages.single()
        val lockedStroke = horizontalStroke(30f).apply { locked = true }
        val freeStroke = horizontalStroke(50f)
        val lockedImage = image(10f, 10f).apply { locked = true }
        controller.session.submit(AddElements.of(page, listOf(lockedImage, lockedStroke, freeStroke)))

        // Lasso enclosing everything skips locked elements
        val lasso = floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)
        controller.selectionManager.selectWithLasso(0, lasso)
        val selected = assertNotNull(controller.selectionManager.currentSelection)
        assertEquals(listOf(freeStroke.id), selected.elements().map { it.id })

        // Locking selection clears it and protects it
        assertTrue(controller.selectionManager.canLockSelection())
        assertTrue(controller.selectionManager.lockSelection(true))
        assertNull(controller.selectionManager.currentSelection)
        assertTrue(page.isElementLocked(freeStroke))

        // Eraser sweeps directly across both locked strokes and removes neither
        val m = controller.viewport.pageMmToScreen(0)!!
        val from = m.mapOffset(Offset(30f, 30f))
        val to = m.mapOffset(Offset(50f, 50f))
        assertEquals(0, controller.eraserManager.eraseAlongSegment(from, to))
        assertEquals(2, page.strokes.size)

        // Unlock restores erasibility
        assertTrue(controller.selectionManager.unlockElement(page, freeStroke))
        assertFalse(page.isElementLocked(freeStroke))
        val strokePt = m.mapOffset(Offset(40f, 50f))
        assertEquals(1, controller.eraserManager.eraseAlongSegment(strokePt, strokePt))
        assertEquals(1, page.strokes.size)
    }

    @Test
    fun layerManagerCrudVisibilityLockingAndActiveLayer() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val page = controller.document.pages.single()
        val bg = assertNotNull(controller.layers.createLayer(page, "Background"))
        val fg = assertNotNull(controller.layers.createLayer(page, "Ink"))
        assertEquals(listOf(bg.id, fg.id), controller.layers.layersOf(page).map { it.id })
        assertEquals(fg.id, controller.layers.activeLayerIdOf(page))

        // Reorder Ink beneath Background
        assertTrue(controller.layers.moveLayer(page, fg.id, 0))
        assertEquals(listOf(fg.id, bg.id), controller.layers.layersOf(page).map { it.id })

        // New committed strokes land on active writable layer
        val s1 = horizontalStroke(40f)
        controller.commitInkStroke(s1.mesh, 0, s1.brushConfig)
        assertEquals(fg.id, page.strokes.single().layerId)

        // Select stroke and assign to Background layer, then lock Background -> selection clears
        controller.selectionManager.selectElement(0, page.strokes.single())
        assertTrue(controller.layers.assignSelectionToLayer(bg.id))
        assertEquals(bg.id, page.strokes.single().layerId)
        assertTrue(controller.layers.setLayerLocked(page, bg.id, true))
        assertNull(controller.selectionManager.currentSelection)
        assertTrue(page.isElementLocked(page.strokes.single()))

        // Unlock element clears locking layer when locked through layer
        assertTrue(controller.selectionManager.unlockElement(page, page.strokes.single()))
        assertFalse(page.isElementLocked(page.strokes.single()))

        // Hiding a layer omits its elements from stack & hitTestStack
        assertTrue(controller.layers.setLayerVisible(page, bg.id, false))
        assertTrue(page.stack.isEmpty())
        assertTrue(controller.selectionManager.hitTestStack(0, Offset(40f, 40f), 5f).isEmpty())
    }

    @Test
    fun hitTestStackOrdersOverlappingElementsTopToBottom() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val page = controller.document.pages.single()
        val bottom = image(10f, 10f, 50f, 50f)
        val middle = image(15f, 15f, 40f, 40f)
        val top = horizontalStroke(30f)
        controller.session.submit(AddElements.of(page, listOf(bottom, middle, top)))

        val hits = controller.selectionManager.hitTestStack(0, Offset(30f, 30f), 4f)
        assertEquals(listOf(top.id, middle.id, bottom.id), hits.map { it.id })
    }
}
