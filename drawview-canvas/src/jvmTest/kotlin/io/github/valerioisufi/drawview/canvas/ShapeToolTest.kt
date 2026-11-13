package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.unit.Density
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.controller.ShapeToolStyle
import io.github.valerioisufi.drawview.canvas.tool.Tool
import io.github.valerioisufi.drawview.model.DrawCustomElement
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.decode
import io.github.valerioisufi.drawview.model.geometry.Point
import io.github.valerioisufi.drawview.model.geometry.Rect
import io.github.valerioisufi.drawview.model.shape.ShapeBox
import io.github.valerioisufi.drawview.model.shape.ShapeDash
import io.github.valerioisufi.drawview.model.shape.ShapeElement
import io.github.valerioisufi.drawview.model.shape.ShapeElementType
import io.github.valerioisufi.drawview.model.shape.ShapeGeometry
import io.github.valerioisufi.drawview.model.shape.ShapeHead
import io.github.valerioisufi.drawview.model.shape.ShapeKind
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** The shapes in the editor, sent to a [DrawCanvas] as pointer events. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ShapeToolTest {

    @Test
    fun theEraserErasesAShapeByItsOutline() = withCanvas { scene, controller ->
        controller.customElementManager.add(ShapeElementType, ShapeElement(ShapeKind.RECTANGLE, BLACK, 0.5f), 0, Rect(40f, 40f, 120f, 100f), select = false)
        controller.selectTool(Tool.ERASER)
        scene.render()
        // Across the inside of the box, without touching its sides: nothing erased
        scene.drag(controller.screen(60f, 70f), controller.screen(100f, 70f))
        assertEquals(1, shapes(controller).size)
        // Across its left side: erased, undone in one step
        scene.drag(controller.screen(30f, 70f), controller.screen(50f, 70f))
        assertEquals(0, shapes(controller).size)
        controller.undo()
        assertEquals(1, shapes(controller).size)
    }

    @Test
    fun aDragDrawsABoxSelectedUndoneInOneStep() = withCanvas { scene, controller ->
        controller.selectTool(Tool.SHAPES)
        scene.drag(controller.screen(40f, 40f), controller.screen(100f, 70f))
        val shape = shapes(controller).single()
        assertEquals(ShapeKind.RECTANGLE, ShapeElementType.decode(shape)?.kind)
        near(40f, shape.x); near(40f, shape.y); near(60f, shape.width); near(30f, shape.height)
        assertEquals(listOf(shape), controller.selectionManager.currentSelection?.customs?.toList())
        assertEquals(Tool.SHAPES, controller.selectedTool, "the tool stays chosen")
        assertNull(controller.shapeInserter.draft)
        controller.undo()
        assertEquals(0, shapes(controller).size)
    }

    @Test
    fun aBoxNearlySquareIsASquare() = withCanvas { scene, controller ->
        controller.selectTool(Tool.SHAPES)
        controller.shapeInserter.updateStyle(ShapeToolStyle(kind = ShapeKind.ELLIPSE))
        scene.drag(controller.screen(40f, 40f), controller.screen(80f, 78f))
        val shape = shapes(controller).single()
        near(40f, shape.width); near(40f, shape.height)
    }

    @Test
    fun aSecondFingerHeldForcesTheSquare() = withCanvas { scene, controller ->
        controller.selectTool(Tool.SHAPES)
        val from = controller.screen(40f, 40f)
        val to = controller.screen(100f, 60f)
        var time = 1_000L
        scene.send(PointerEventType.Press, from, time)
        for (i in 1..10) {
            time += 16L
            val position = from + (to - from) * (i / 10f)
            if (i == 3) scene.sendTwo(PointerEventType.Press, position, controller.screen(150f, 150f), time)
            else if (i > 3) scene.sendTwo(PointerEventType.Move, position, controller.screen(150f, 150f), time)
            else scene.send(PointerEventType.Move, position, time)
        }
        // The finger drawing lifted first, then the one held
        scene.sendPointerEvent(
            eventType = PointerEventType.Release,
            pointers = listOf(
                ComposeScenePointer(PointerId(0), to, pressed = false, type = PointerType.Touch),
                ComposeScenePointer(PointerId(1), controller.screen(150f, 150f), pressed = true, type = PointerType.Touch)
            ),
            timeMillis = time + 16L
        )
        scene.sendPointerEvent(
            eventType = PointerEventType.Release,
            pointers = listOf(ComposeScenePointer(PointerId(1), controller.screen(150f, 150f), pressed = false, type = PointerType.Touch)),
            timeMillis = time + 32L
        )
        scene.render()
        val shape = shapes(controller).single()
        near(60f, shape.width); near(60f, shape.height)
    }

    @Test
    fun aLineNearlyLevelIsLevelAndAnArrowHasItsHead() = withCanvas { scene, controller ->
        controller.selectTool(Tool.SHAPES)
        controller.shapeInserter.updateStyle(ShapeToolStyle(kind = ShapeKind.LINE, heads = 1))
        scene.drag(controller.screen(40f, 100f), controller.screen(100f, 102f))
        val shape = shapes(controller).single()
        val value = ShapeElementType.decode(shape)!!
        assertEquals(ShapeHead.OPEN, value.endHead)
        assertEquals(0f, shape.rotation)
        val (start, end) = ShapeGeometry.lineEnds(ShapeBox(shape.x, shape.y, shape.width, shape.height, shape.rotation))
        near(40f, start.x); near(100f, start.y)
        near(100f, end.y)
        near(Point(60f, 2f).distance, end.x - start.x)
    }

    @Test
    fun theEndOfALineSnapsToTheCornerOfABox() = withCanvas { scene, controller ->
        controller.customElementManager.add(ShapeElementType, ShapeElement(ShapeKind.RECTANGLE, BLACK, 0.5f), 0, Rect(100f, 100f, 140f, 130f), select = false)
        controller.selectTool(Tool.SHAPES)
        controller.shapeInserter.updateStyle(ShapeToolStyle(kind = ShapeKind.LINE, heads = 1))
        // Ends a little away from the top left corner of the box (its outline is inside the box)
        scene.drag(controller.screen(40f, 60f), controller.screen(101.5f, 101f))
        val line = shapes(controller).last()
        val (_, end) = ShapeGeometry.lineEnds(ShapeBox(line.x, line.y, line.width, line.height, line.rotation))
        near(100.25f, end.x, 0.05f); near(100.25f, end.y, 0.05f)
    }

    @Test
    fun aTapPutsAShapeOfAStandardSize() = withCanvas { scene, controller ->
        controller.selectTool(Tool.SHAPES)
        controller.shapeInserter.updateStyle(ShapeToolStyle(kind = ShapeKind.TRIANGLE))
        scene.drag(controller.screen(60f, 60f), controller.screen(60f, 60f))
        val shape = shapes(controller).single()
        near(20f, shape.width); near(20f, shape.height)
        near(50f, shape.x); near(50f, shape.y)
    }

    @Test
    fun aShapeOutsideOfThePagesIsNoShape() = withCanvas { scene, controller ->
        controller.selectTool(Tool.SHAPES)
        scene.drag(Offset(2f, 2f), Offset(5f, 300f))
        assertEquals(0, shapes(controller).size)
    }

    @Test
    fun anEndOfALineMovesAloneAndIsStraightened() = withCanvas { scene, controller ->
        controller.selectTool(Tool.SHAPES)
        controller.shapeInserter.updateStyle(ShapeToolStyle(kind = ShapeKind.LINE))
        scene.drag(controller.screen(40f, 100f), controller.screen(100f, 100f))
        // Its end dragged up and to the right, nearly at 45° from its start
        scene.drag(controller.screen(100f, 100f), controller.screen(130f, 12f))
        val line = shapes(controller).single()
        val (start, end) = ShapeGeometry.lineEnds(ShapeBox(line.x, line.y, line.width, line.height, line.rotation))
        near(40f, start.x); near(100f, start.y)
        near(end.x - start.x, start.y - end.y, 0.05f)
        assertEquals(listOf(line), controller.selectionManager.currentSelection?.customs?.toList(), "still selected")
        controller.undo()
        val back = shapes(controller).single()
        near(60f, back.width)
    }

    @Test
    fun aCornerResizesTheBoxItsOppositeCornerStill() = withCanvas { scene, controller ->
        controller.selectTool(Tool.SHAPES)
        scene.drag(controller.screen(40f, 40f), controller.screen(100f, 70f))
        // The bottom right corner (the frame is a few dp out of the box)
        scene.drag(controller.screen(100f, 70f), controller.screen(120f, 100f))
        val box = shapes(controller).single()
        near(40f, box.x, 1f); near(40f, box.y, 1f)
        near(80f, box.width, 1.5f); near(60f, box.height, 1.5f)
        // A side: its width only (the element changes in place: its numbers are kept first)
        val (x, y, width, height) = listOf(box.x, box.y, box.width, box.height)
        scene.drag(controller.screen(x + width, y + height / 2f), controller.screen(x + width + 10f, y + 5f))
        val wider = shapes(controller).single()
        near(width + 10f, wider.width, 1.5f); near(height, wider.height, 0.05f); near(y, wider.y, 0.05f); near(x, wider.x, 0.05f)
    }

    @Test
    fun theStyleOfTheShapesSelectedChangesAtOnce() = withCanvas { scene, controller ->
        val pageIndex = 0
        val a = controller.customElementManager.add(ShapeElementType, ShapeElement(ShapeKind.RECTANGLE, BLACK, 0.5f), pageIndex, Rect(40f, 40f, 80f, 60f), select = false)!!
        val b = controller.customElementManager.add(ShapeElementType, ShapeElement(ShapeKind.ELLIPSE, BLACK, 0.5f), pageIndex, Rect(90f, 40f, 120f, 60f), select = false)!!
        controller.selectionManager.selectCustomElements(pageIndex, listOf(a, b))
        assertEquals(2, controller.shapeEditing.selectedShapes.size)
        controller.shapeEditing.restyle { it.copy(dash = ShapeDash.DASHED, fill = 0x33FF0000) }
        assertTrue(shapes(controller).all { ShapeElementType.decode(it)?.dash == ShapeDash.DASHED })
        assertEquals(ShapeDash.DASHED, controller.shapeInserter.style.dash, "the style of the tool")
        controller.undo()
        assertTrue(shapes(controller).all { ShapeElementType.decode(it)?.dash == ShapeDash.SOLID }, "undone in one step")
        // A box made an arrow: its box as high as a line
        controller.selectionManager.selectCustomElements(pageIndex, listOf(a))
        controller.shapeEditing.changeKind(ShapeKind.LINE, heads = 1)
        val arrow = ShapeElementType.decode(a)!!
        assertEquals(ShapeKind.LINE, arrow.kind)
        assertEquals(ShapeHead.OPEN, arrow.endHead)
        near(ShapeGeometry.lineHeight(arrow), a.height, 0.01f)
        near(50f, a.y + a.height / 2f, 0.01f)
    }

    @Test
    fun aTapOfTheToolSelectsAShapeByItsOutline() = withCanvas { scene, controller ->
        controller.customElementManager.add(ShapeElementType, ShapeElement(ShapeKind.RECTANGLE, BLACK, 0.5f), 0, Rect(40f, 40f, 80f, 60f), select = false)
        controller.selectTool(Tool.SHAPES)
        scene.drag(controller.screen(40.3f, 50f), controller.screen(40.3f, 50f))
        assertEquals(1, shapes(controller).size, "no new shape")
        assertEquals(1, controller.shapeEditing.selectedShapes.size)
        // A tap away drops the selection only
        scene.drag(controller.screen(150f, 150f), controller.screen(150f, 150f))
        assertNull(controller.selectionManager.currentSelection)
        assertEquals(1, shapes(controller).size)
        // Inside the box without fill: a new shape
        scene.drag(controller.screen(60f, 50f), controller.screen(60f, 50f))
        assertEquals(2, shapes(controller).size)
    }

    private fun near(expected: Float, actual: Float, tolerance: Float = 0.3f) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected, was $actual")

    private fun ImageComposeScene.sendTwo(type: PointerEventType, first: Offset, second: Offset, timeMillis: Long, secondPressed: Boolean = true) {
        sendPointerEvent(
            eventType = type,
            pointers = listOf(
                ComposeScenePointer(PointerId(0), first, pressed = true, type = PointerType.Touch),
                ComposeScenePointer(PointerId(1), second, pressed = secondPressed, type = PointerType.Touch)
            ),
            timeMillis = timeMillis
        )
    }

    private fun DrawViewController.screen(xMm: Float, yMm: Float): Offset =
        viewport.pageMmToScreen(0)!!.mapPoint(xMm, yMm).let { Offset(it.x, it.y) }

    private fun ImageComposeScene.drag(from: Offset, to: Offset) {
        var time = nextTime
        send(PointerEventType.Press, from, time)
        for (i in 1..10) {
            time += 16L
            send(PointerEventType.Move, from + (to - from) * (i / 10f), time)
        }
        send(PointerEventType.Release, to, time + 16L)
        nextTime = time + 1_000L
        render()
    }

    private var nextTime = 1_000L

    private fun ImageComposeScene.send(type: PointerEventType, position: Offset, timeMillis: Long) {
        sendPointerEvent(
            eventType = type,
            pointers = listOf(ComposeScenePointer(PointerId(0), position, pressed = type != PointerEventType.Release, type = PointerType.Touch)),
            timeMillis = timeMillis
        )
    }

    private fun shapes(controller: DrawViewController): List<DrawCustomElement> =
        controller.document.pages.flatMap { it.customElements }.filter { it.type == ShapeElementType.id }

    private fun withCanvas(block: (ImageComposeScene, DrawViewController) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = DrawViewController(document = DrawDocument(), coroutineScope = scope)
        val scene = ImageComposeScene(width = 800, height = 1200, density = Density(1f)) {
            DrawCanvas(controller)
        }
        try {
            scene.render()
            block(scene, controller)
        } finally {
            scene.close()
            scope.cancel()
        }
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
    }
}
