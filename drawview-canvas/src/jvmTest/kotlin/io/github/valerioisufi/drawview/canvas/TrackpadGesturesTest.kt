package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.unit.Density
import io.github.valerioisufi.drawview.canvas.DrawCanvas
import io.github.valerioisufi.drawview.canvas.MacTrackpad
import io.github.valerioisufi.drawview.canvas.TrackpadPinchPhase
import io.github.valerioisufi.drawview.canvas.TrackpadScrollPhase
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.scrollUnitPx
import io.github.valerioisufi.drawview.model.DrawDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Trackpad gestures sent to a [DrawCanvas] as Compose delivers them: two-finger pan and pinch
 * (Android, iOS), scroll and pinch of a macOS trackpad. The pan and the pinch are sent with
 * [ComposeScenePointer], internal to Compose (the desktop does not produce them).
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class TrackpadGesturesTest {

    private val center = Offset(400f, 600f)

    private fun withCanvas(pages: Int, block: (ImageComposeScene, DrawViewController) -> Unit) {
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

    private fun ImageComposeScene.sendGesture(
        type: PointerEventType,
        scaleFactor: Float = 1f,
        panOffset: Offset = Offset.Zero
    ) {
        sendPointerEvent(
            eventType = type,
            pointers = listOf(ComposeScenePointer(id = PointerId(0), position = center, pressed = false, type = PointerType.Mouse)),
            scaleGestureFactor = scaleFactor,
            panGestureOffset = panOffset
        )
    }

    private fun ImageComposeScene.scroll(delta: Offset, timeMillis: Long) {
        sendPointerEvent(eventType = PointerEventType.Scroll, position = center, scrollDelta = delta, timeMillis = timeMillis)
    }

    @Test
    fun trackpadPinchZoomsAroundThePointer() = withCanvas(pages = 3) { scene, controller ->
        val camera = controller.cameraPhysics
        val scale = camera.getCurrentScale()
        val world = (center - camera.getCurrentTranslation()) / scale

        scene.sendGesture(PointerEventType.ScaleStart)
        scene.sendGesture(PointerEventType.ScaleChange, scaleFactor = 2f)

        assertEquals(scale * 2f, camera.getCurrentScale(), 1e-4f)
        // The point under the pointer stays under it
        val focus = camera.getCurrentTranslation() + world * camera.getCurrentScale()
        assertEquals(center.x, focus.x, 0.5f)
        assertEquals(center.y, focus.y, 0.5f)
        scene.sendGesture(PointerEventType.ScaleEnd)
    }

    @Test
    fun trackpadTwoFingerPanMovesTheCanvas() = withCanvas(pages = 5) { scene, controller ->
        val camera = controller.cameraPhysics
        val before = camera.getCurrentTranslation()

        scene.sendGesture(PointerEventType.PanStart)
        // Scrolling down moves the content up
        scene.sendGesture(PointerEventType.PanMove, panOffset = Offset(0f, 30f))

        assertEquals(before.y - 30f, camera.getCurrentTranslation().y, 1e-3f)
        scene.sendGesture(PointerEventType.PanEnd)
    }

    @Test
    fun horizontalScrollOfAMacTrackpadPansHorizontally() = withCanvas(pages = 1) { scene, controller ->
        val camera = controller.cameraPhysics
        // Zoom in, so that the page is wider than the viewport
        camera.onDrag(0f, 0f, 6f, center.x, center.y)
        camera.restoreToBounds(animated = false)
        val before = camera.getCurrentTranslation()

        // AWT reports the horizontal scroll of a macOS trackpad as a horizontal delta with Shift
        scene.sendPointerEvent(
            eventType = PointerEventType.Scroll,
            position = center,
            scrollDelta = Offset(-1f, 0f),
            keyboardModifiers = PointerKeyboardModifiers(isShiftPressed = true)
        )

        val after = camera.getCurrentTranslation()
        assertEquals(before.x + scrollUnitPx(1f), after.x, 1e-3f)
        assertEquals(before.y, after.y, 1e-3f)
    }

    @Test
    fun twoFingerScrollOfAMacTrackpadOverscrollsAndBouncesBack() = withCanvas(pages = 1) { scene, controller ->
        val camera = controller.cameraPhysics
        // At the top of the page
        camera.onDrag(0f, 10_000f)
        camera.restoreToBounds(animated = false)
        val top = camera.getCurrentTranslation().y
        assertEquals(camera.topPaddingPx, top)
        val unit = scrollUnitPx(1f)

        MacTrackpad.dispatchScrollPhase(TrackpadScrollPhase.FINGERS_DOWN, 1_000L)
        // Scrolling up at the top of the page: the canvas follows the fingers beyond its edge
        scene.scroll(Offset(0f, -2f), timeMillis = 1_000L)
        scene.scroll(Offset(0f, -2f), timeMillis = 1_016L)
        assertEquals(top + 4f * unit, camera.getCurrentTranslation().y, 1e-3f)

        // Fingers lifted after a pause: no fling, the canvas springs back into its edge
        MacTrackpad.dispatchScrollPhase(TrackpadScrollPhase.FINGERS_UP, 1_200L)
        assertTrue(camera.isAnimating())
        repeat(120) { scene.render((it + 1) * 16_000_000L) }
        assertEquals(top, camera.getCurrentTranslation().y, 1e-3f)
    }

    @Test
    fun inertiaOfTheSystemIsReplacedByTheFlingOfTheCanvas() = withCanvas(pages = 5) { scene, controller ->
        val camera = controller.cameraPhysics
        camera.restoreToBounds(animated = false)

        MacTrackpad.dispatchScrollPhase(TrackpadScrollPhase.FINGERS_DOWN, 1_000L)
        for (i in 0..5) scene.scroll(Offset(0f, 3f), timeMillis = 1_000L + i * 10L)
        MacTrackpad.dispatchScrollPhase(TrackpadScrollPhase.FINGERS_UP, 1_050L)
        // Lifted while moving: the canvas flings by itself...
        assertTrue(camera.isAnimating())

        // ...and ignores the scroll events of the inertia of the system
        MacTrackpad.dispatchScrollPhase(TrackpadScrollPhase.MOMENTUM_START, 1_060L)
        val position = camera.getCurrentTranslation()
        scene.scroll(Offset(0f, 3f), timeMillis = 1_070L)
        assertEquals(position, camera.getCurrentTranslation())
        MacTrackpad.dispatchScrollPhase(TrackpadScrollPhase.MOMENTUM_END, 1_500L)
    }

    @Test
    fun pinchOfAMacTrackpadZoomsAroundThePointerAndSpringsBackIntoTheLimits() = withCanvas(pages = 1) { scene, controller ->
        val camera = controller.cameraPhysics
        val scale = camera.getCurrentScale()

        // The pinch of a pointer outside the canvas is not for it
        MacTrackpad.dispatchPinch(TrackpadPinchPhase.START, 1f)
        MacTrackpad.dispatchPinch(TrackpadPinchPhase.CHANGE, 2f)
        MacTrackpad.dispatchPinch(TrackpadPinchPhase.END, 1f)
        assertEquals(scale, camera.getCurrentScale())

        scene.sendPointerEvent(eventType = PointerEventType.Move, position = center)
        val world = (center - camera.getCurrentTranslation()) / scale
        fun assertPointUnderThePointer() {
            val focus = camera.getCurrentTranslation() + world * camera.getCurrentScale()
            assertEquals(center.x, focus.x, 1f)
            assertEquals(center.y, focus.y, 1f)
        }

        // Far beyond the maximum zoom: the zoom resists, around the pointer
        MacTrackpad.dispatchPinch(TrackpadPinchPhase.START, 1f)
        repeat(40) { MacTrackpad.dispatchPinch(TrackpadPinchPhase.CHANGE, 1.25f) }
        val stretched = camera.getCurrentScale()
        assertTrue(stretched > camera.maxScale && stretched < camera.maxScale * camera.maxScaleOverscroll, "$stretched")
        assertPointUnderThePointer()

        // Fingers lifted: back into the limits, still around the pointer
        MacTrackpad.dispatchPinch(TrackpadPinchPhase.END, 1f)
        repeat(120) { scene.render((it + 1) * 16_000_000L) }
        assertFalse(camera.isAnimating())
        assertTrue(camera.getCurrentScale() <= camera.maxScale && camera.getCurrentScale() > camera.maxScale * 0.9f)
        assertPointUnderThePointer()
    }
}
