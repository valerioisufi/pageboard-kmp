package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.unit.Density
import io.github.valerioisufi.pageboard.canvas.controller.DrawViewController
import io.github.valerioisufi.pageboard.canvas.controller.ShapeToolStyle
import io.github.valerioisufi.pageboard.canvas.tool.Tool
import io.github.valerioisufi.pageboard.model.DrawCustomElement
import io.github.valerioisufi.pageboard.model.DrawDocument
import io.github.valerioisufi.pageboard.model.decode
import io.github.valerioisufi.pageboard.model.geometry.Point
import io.github.valerioisufi.pageboard.model.geometry.Rect
import io.github.valerioisufi.pageboard.model.shape.ShapeBox
import io.github.valerioisufi.pageboard.model.shape.ShapeDash
import io.github.valerioisufi.pageboard.model.shape.ShapeElement
import io.github.valerioisufi.pageboard.model.shape.ShapeElementType
import io.github.valerioisufi.pageboard.model.shape.ShapeGeometry
import io.github.valerioisufi.pageboard.model.shape.ShapeHead
import io.github.valerioisufi.pageboard.model.shape.ShapeKind
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
    fun aLineJoinedToABoxFollowsItWhenItMoves() = withCanvas { scene, controller ->
        val manager = controller.customElementManager
        val rectangle = ShapeElement(ShapeKind.RECTANGLE, BLACK, 0.5f)
        val box = manager.add(ShapeElementType, rectangle, 0, Rect(100f, 100f, 140f, 130f), select = false)!!
        // From (60, 115) to the middle of the left side of the box, where the snap puts it
        val anchor = ShapeGeometry.anchors(rectangle, 40f, 30f).single { it.y == 15f && it.x < 20f }.let { Point(100f + it.x, 100f + it.y) }
        val lineValue = ShapeElement(ShapeKind.LINE, BLACK, 0.5f)
        val lineBox = ShapeGeometry.lineBox(Point(60f, 115f), anchor, lineValue)
        manager.add(ShapeElementType, lineValue, 0, Rect(lineBox.x, lineBox.y, lineBox.x + lineBox.width, lineBox.y + lineBox.height), select = false)
        // A line apart from the box stays where it is
        val apartBox = ShapeGeometry.lineBox(Point(10f, 10f), Point(50f, 10f), lineValue)
        manager.add(ShapeElementType, lineValue, 0, Rect(apartBox.x, apartBox.y, apartBox.x + apartBox.width, apartBox.y + apartBox.height), select = false)

        // The box dragged by its inside with the tool of the shapes, 10 mm right and 20 mm down
        controller.selectTool(Tool.SHAPES)
        controller.selectionManager.selectCustomElements(0, listOf(box))
        scene.drag(controller.screen(120f, 115f), controller.screen(130f, 135f))
        near(110f, shapes(controller).single { ShapeElementType.decode(it)?.kind == ShapeKind.RECTANGLE }.x, 1f)

        fun ends() = shapes(controller).filter { ShapeElementType.decode(it)?.kind == ShapeKind.LINE }
            .map { ShapeGeometry.lineEnds(ShapeBox(it.x, it.y, it.width, it.height, it.rotation)) }
            .sortedBy { it.first.y }
        val (apart, joined) = ends()
        val moved = shapes(controller).single { ShapeElementType.decode(it)?.kind == ShapeKind.RECTANGLE }
        near(60f, joined.first.x); near(115f, joined.first.y)
        near(anchor.x + (moved.x - 100f), joined.second.x, 0.05f); near(anchor.y + (moved.y - 100f), joined.second.y, 0.05f)
        near(10f, apart.first.x); near(50f, apart.second.x); near(10f, apart.second.y)

        // The box and its line in one step
        controller.undo()
        val back = ends()[1]
        near(anchor.x, back.second.x, 0.05f); near(anchor.y, back.second.y, 0.05f)
        near(100f, shapes(controller).single { ShapeElementType.decode(it)?.kind == ShapeKind.RECTANGLE }.x)
    }

    @Test
    fun aLineJoinedToABoxFollowsItWhenItIsResized() = withCanvas { scene, controller ->
        controller.selectTool(Tool.SHAPES)
        scene.drag(controller.screen(40f, 40f), controller.screen(100f, 70f))
        val box = shapes(controller).single()
        // A line from the middle of the right side of the box (the anchor, inside the outline)
        val lineValue = ShapeElement(ShapeKind.LINE, BLACK, 0.5f)
        val value = ShapeElementType.decode(box)!!
        val right = ShapeGeometry.anchors(value, box.width, box.height).maxBy { it.x - kotlin.math.abs(it.y - box.height / 2f) }
            .let { Point(box.x + it.x, box.y + it.y) }
        val lineBox = ShapeGeometry.lineBox(right, Point(right.x + 40f, right.y), lineValue)
        controller.customElementManager.add(ShapeElementType, lineValue, 0, Rect(lineBox.x, lineBox.y, lineBox.x + lineBox.width, lineBox.y + lineBox.height), select = false)
        controller.selectionManager.selectCustomElements(0, listOf(box))
        // Its right side dragged 10 mm to the right
        scene.drag(controller.screen(right.x, right.y), controller.screen(right.x + 10f, right.y))
        val wider = shapes(controller).single { ShapeElementType.decode(it)?.kind == ShapeKind.RECTANGLE }
        val line = shapes(controller).single { ShapeElementType.decode(it)?.kind == ShapeKind.LINE }
        val (start, end) = ShapeGeometry.lineEnds(ShapeBox(line.x, line.y, line.width, line.height, line.rotation))
        val wideAnchor = ShapeGeometry.anchors(value, wider.width, wider.height).maxBy { it.x - kotlin.math.abs(it.y - wider.height / 2f) }
        near(wider.x + wideAnchor.x, start.x, 0.05f); near(wider.y + wideAnchor.y, start.y, 0.05f)
        near(right.x + 40f, end.x, 0.05f)
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
