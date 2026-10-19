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
import io.github.valerioisufi.drawview.canvas.geometry.toCompose
import io.github.valerioisufi.drawview.canvas.tool.Tool
import io.github.valerioisufi.drawview.model.BrushConfig
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawStroke
import io.github.valerioisufi.drawview.model.StrokeMesh
import io.github.valerioisufi.drawview.model.StrokePoint
import io.github.valerioisufi.drawview.model.StrokeTool
import io.github.valerioisufi.drawview.model.change.AddElements
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Multi-touch gestures sent to a [DrawCanvas] as touch events: two-finger pan, fling, zoom deadzone,
 * and asymmetric release.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class TouchGesturesTest {

    private fun withCanvas(pages: Int = 5, block: (ImageComposeScene, DrawViewController) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val document = DrawDocument()
        repeat(pages - 1) { document.addPage() }
        val controller = DrawViewController(document = document, coroutineScope = scope)
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

    private fun ImageComposeScene.touch(
        type: PointerEventType,
        pointers: List<Pair<Long, Offset>>,
        timeMillis: Long
    ) {
        val scenePointers = pointers.map { (id, pos) ->
            ComposeScenePointer(
                id = PointerId(id),
                position = pos,
                pressed = type != PointerEventType.Release,
                type = PointerType.Touch
            )
        }
        sendPointerEvent(
            eventType = type,
            pointers = scenePointers,
            timeMillis = timeMillis
        )
    }

    @Test
    fun twoFingerDragPansTheCanvas() = withCanvas { scene, controller ->
        val camera = controller.viewport.camera
        val before = camera.getCurrentTranslation()

        var time = 1_000L
        val p1 = Offset(300f, 400f)
        val p2 = Offset(500f, 400f)

        // Down
        scene.touch(PointerEventType.Press, listOf(0L to p1, 1L to p2), time)

        // Move down by 60 px
        time += 16L
        scene.touch(PointerEventType.Move, listOf(0L to p1 + Offset(0f, 60f), 1L to p2 + Offset(0f, 60f)), time)

        assertEquals(before.y + 60f, camera.getCurrentTranslation().y, 1f)

        // Release
        time += 16L
        scene.touch(PointerEventType.Release, listOf(0L to p1 + Offset(0f, 60f), 1L to p2 + Offset(0f, 60f)), time)
    }

    @Test
    fun twoFingerPanWithinZoomSlopMaintainsScale() = withCanvas { scene, controller ->
        val camera = controller.viewport.camera
        val initialScale = camera.getCurrentScale()

        var time = 1_000L
        val p1 = Offset(300f, 400f)
        val p2 = Offset(500f, 400f)

        scene.touch(PointerEventType.Press, listOf(0L to p1, 1L to p2), time)

        // Pan down while slightly jittering finger distance (by 8 px, well below touchSlop * 2)
        for (i in 1..10) {
            time += 16L
            val jitter = if (i % 2 == 0) 4f else -4f
            val movedP1 = Offset(300f - jitter, 400f + i * 15f)
            val movedP2 = Offset(500f + jitter, 400f + i * 15f)
            scene.touch(PointerEventType.Move, listOf(0L to movedP1, 1L to movedP2), time)
        }

        // Scale must remain strictly identical
        assertEquals(initialScale, camera.getCurrentScale(), 1e-4f)

        scene.touch(PointerEventType.Release, listOf(0L to p1, 1L to p2), time + 16L)
    }

    @Test
    fun twoFingerPinchBeyondZoomSlopZoomsSmoothly() = withCanvas { scene, controller ->
        val camera = controller.viewport.camera
        val initialScale = camera.getCurrentScale()

        var time = 1_000L
        val p1 = Offset(350f, 400f)
        val p2 = Offset(450f, 400f)

        scene.touch(PointerEventType.Press, listOf(0L to p1, 1L to p2), time)

        // Pinch out significantly (distance from 100px to 300px, well beyond touchSlop * 2)
        for (i in 1..10) {
            time += 16L
            val movedP1 = Offset(350f - i * 10f, 400f)
            val movedP2 = Offset(450f + i * 10f, 400f)
            scene.touch(PointerEventType.Move, listOf(0L to movedP1, 1L to movedP2), time)
        }

        assertTrue(camera.getCurrentScale() > initialScale * 1.5f, "Zoom must engage beyond threshold")

        scene.touch(PointerEventType.Release, listOf(0L to p1, 1L to p2), time + 16L)
    }

    @Test
    fun twoFingerFlingStartsCameraPhysicsAnimation() = withCanvas { scene, controller ->
        val camera = controller.viewport.camera
        camera.restoreToBounds(animated = false)

        var time = 1_000L
        val p1 = Offset(300f, 600f)
        val p2 = Offset(500f, 600f)

        scene.touch(PointerEventType.Press, listOf(0L to p1, 1L to p2), time)

        // Fast swipe up into content: 50 px per frame (over 3000 px/sec)
        for (i in 1..5) {
            time += 16L
            val dy = -i * 50f
            scene.touch(PointerEventType.Move, listOf(0L to p1 + Offset(0f, dy), 1L to p2 + Offset(0f, dy)), time)
        }

        val dy = -250f
        scene.touch(PointerEventType.Release, listOf(0L to p1 + Offset(0f, dy), 1L to p2 + Offset(0f, dy)), time)

        // Camera must fling
        assertTrue(camera.isAnimating(), "Camera must be animating with fling")

        val positionAtRelease = camera.getCurrentTranslation().y
        // Update physics frames
        repeat(10) { camera.update(16L) }
        assertTrue(camera.getCurrentTranslation().y < positionAtRelease, "Fling must advance the camera position further")
    }

    @Test
    fun twoFingerPauseBeforeReleaseDoesNotFling() = withCanvas { scene, controller ->
        val camera = controller.viewport.camera
        camera.restoreToBounds(animated = false)

        var time = 1_000L
        val p1 = Offset(300f, 600f)
        val p2 = Offset(500f, 600f)

        scene.touch(PointerEventType.Press, listOf(0L to p1, 1L to p2), time)

        // Move into content
        for (i in 1..4) {
            time += 16L
            val dy = -i * 30f
            scene.touch(PointerEventType.Move, listOf(0L to p1 + Offset(0f, dy), 1L to p2 + Offset(0f, dy)), time)
        }

        // Pause for 300ms at the final position
        val dy = -120f
        time += 300L
        scene.touch(PointerEventType.Release, listOf(0L to p1 + Offset(0f, dy), 1L to p2 + Offset(0f, dy)), time)

        assertFalse(camera.isAnimating(), "Camera must not animate after a pause before release")
    }

    @Test
    fun asymmetricFingerLiftDuringFlingStillFlings() = withCanvas { scene, controller ->
        val camera = controller.viewport.camera
        camera.restoreToBounds(animated = false)

        var time = 1_000L
        val p1 = Offset(300f, 400f)
        val p2 = Offset(500f, 400f)

        scene.touch(PointerEventType.Press, listOf(0L to p1, 1L to p2), time)

        // Move fast
        for (i in 1..5) {
            time += 16L
            val dy = i * 50f
            scene.touch(PointerEventType.Move, listOf(0L to p1 + Offset(0f, dy), 1L to p2 + Offset(0f, dy)), time)
        }

        // Finger 0 is lifted first
        time += 10L
        val p2Pos = p2 + Offset(0f, 270f)
        scene.sendPointerEvent(
            eventType = PointerEventType.Release,
            pointers = listOf(
                ComposeScenePointer(id = PointerId(0L), position = p1 + Offset(0f, 270f), pressed = false, type = PointerType.Touch),
                ComposeScenePointer(id = PointerId(1L), position = p2Pos, pressed = true, type = PointerType.Touch)
            ),
            timeMillis = time
        )

        // Finger 1 moves slightly and lifts 10ms later
        time += 10L
        val p2Final = p2Pos + Offset(0f, 20f)
        scene.sendPointerEvent(
            eventType = PointerEventType.Release,
            pointers = listOf(
                ComposeScenePointer(id = PointerId(1L), position = p2Final, pressed = false, type = PointerType.Touch)
            ),
            timeMillis = time
        )

        assertTrue(camera.isAnimating(), "Asymmetric finger lift must still fling")
    }

    private fun squareMesh(left: Float, top: Float, size: Float): StrokeMesh = StrokeMesh(
        positions = floatArrayOf(left, top, left + size, top, left + size, top + size, left, top + size),
        indices = shortArrayOf(0, 1, 2, 0, 2, 3),
        color = 0xFF000000.toInt(),
        tool = StrokeTool.MARKER,
        size = 1f,
        points = listOf(StrokePoint(left, top), StrokePoint(left + size, top + size))
    )

    @Test
    fun twoFingerTouchWithEraserActiveCancelsAccidentalErasure() = withCanvas { scene, controller ->
        val page = controller.document.pages[0]
        val stroke = DrawStroke(mesh = squareMesh(20f, 20f, 30f), brushConfig = BrushConfig(size = 1f))
        controller.session.submit(AddElements.of(page, listOf(stroke)))
        assertEquals(1, page.strokes.size)

        controller.selectTool(Tool.ERASER)

        val mmToScreen = controller.viewport.pageMmToScreen(0)!!
        val strokeCenterScreen = mmToScreen.mapPoint(35f, 35f).toCompose()

        var time = 1_000L

        // Finger 0 lands on the stroke (temporarily erasing it in gesture)
        scene.sendPointerEvent(
            eventType = PointerEventType.Press,
            pointers = listOf(
                ComposeScenePointer(id = PointerId(0L), position = strokeCenterScreen, pressed = true, type = PointerType.Touch)
            ),
            timeMillis = time
        )
        // Stroke is removed in the live eraser gesture
        assertTrue(page.strokes.isEmpty(), "Stroke should be removed during live single-finger eraser contact")

        // 15ms later, finger 1 lands -> two-finger pan starts, accidental erasure must be undone!
        time += 15L
        scene.sendPointerEvent(
            eventType = PointerEventType.Press,
            pointers = listOf(
                ComposeScenePointer(id = PointerId(0L), position = strokeCenterScreen, pressed = true, type = PointerType.Touch),
                ComposeScenePointer(id = PointerId(1L), position = strokeCenterScreen + Offset(150f, 0f), pressed = true, type = PointerType.Touch)
            ),
            timeMillis = time
        )

        assertEquals(1, page.strokes.size, "Stroke must be restored upon second finger touch")

        // Release fingers
        time += 15L
        scene.touch(PointerEventType.Release, listOf(0L to strokeCenterScreen, 1L to strokeCenterScreen + Offset(150f, 0f)), time)
        assertEquals(1, page.strokes.size, "Stroke must remain safe after release")
    }

    @Test
    fun twoFingerTouchWithPenActiveDoesNotCommitStroke() = withCanvas { scene, controller ->
        val page = controller.document.pages[0]
        controller.selectTool(Tool.INK_PEN)

        val mmToScreen = controller.viewport.pageMmToScreen(0)!!
        val startScreen = mmToScreen.mapPoint(35f, 35f).toCompose()

        var time = 1_000L

        // Finger 0 lands
        scene.sendPointerEvent(
            eventType = PointerEventType.Press,
            pointers = listOf(
                ComposeScenePointer(id = PointerId(0L), position = startScreen, pressed = true, type = PointerType.Touch)
            ),
            timeMillis = time
        )

        // Finger 1 lands 15ms later
        time += 15L
        scene.sendPointerEvent(
            eventType = PointerEventType.Press,
            pointers = listOf(
                ComposeScenePointer(id = PointerId(0L), position = startScreen, pressed = true, type = PointerType.Touch),
                ComposeScenePointer(id = PointerId(1L), position = startScreen + Offset(150f, 0f), pressed = true, type = PointerType.Touch)
            ),
            timeMillis = time
        )

        // Drag down to scroll
        for (i in 1..4) {
            time += 16L
            val dy = i * 20f
            scene.touch(PointerEventType.Move, listOf(0L to startScreen + Offset(0f, dy), 1L to startScreen + Offset(150f, dy)), time)
        }

        // Release fingers
        time += 16L
        scene.touch(PointerEventType.Release, listOf(0L to startScreen, 1L to startScreen + Offset(150f, 0f)), time)

        assertTrue(page.strokes.isEmpty(), "No stroke must be committed when two-finger scrolling with pen active")
    }
}
