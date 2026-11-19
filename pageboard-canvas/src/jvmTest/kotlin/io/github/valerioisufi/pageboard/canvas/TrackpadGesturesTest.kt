package io.github.valerioisufi.pageboard.canvas

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
import io.github.valerioisufi.pageboard.canvas.PageCanvas
import io.github.valerioisufi.pageboard.canvas.MacTrackpad
import io.github.valerioisufi.pageboard.canvas.TrackpadPinchPhase
import io.github.valerioisufi.pageboard.canvas.TrackpadScrollPhase
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.canvas.scrollUnitPx
import io.github.valerioisufi.pageboard.model.Document
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Trackpad gestures sent to a [PageCanvas] as Compose delivers them: two-finger pan and pinch
 * (Android, iOS), scroll and pinch of a macOS trackpad. The pan and the pinch are sent with
 * [ComposeScenePointer], internal to Compose (the desktop does not produce them).
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class TrackpadGesturesTest {

    private val center = Offset(400f, 600f)

    private fun withCanvas(pages: Int, block: (ImageComposeScene, PageboardController) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val document = Document()
        repeat(pages - 1) { document.addPage() }
        val controller = PageboardController(document = document, coroutineScope = scope)
        val scene = ImageComposeScene(width = 800, height = 1200, density = Density(1f)) {
            PageCanvas(controller)
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
        val camera = controller.viewport.camera
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
        val camera = controller.viewport.camera
        val before = camera.getCurrentTranslation()

        scene.sendGesture(PointerEventType.PanStart)
        // Scrolling down moves the content up
        scene.sendGesture(PointerEventType.PanMove, panOffset = Offset(0f, 30f))

        assertEquals(before.y - 30f, camera.getCurrentTranslation().y, 1e-3f)
        scene.sendGesture(PointerEventType.PanEnd)
    }

    @Test
    fun horizontalScrollOfAMacTrackpadPansHorizontally() = withCanvas(pages = 1) { scene, controller ->
        val camera = controller.viewport.camera
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
        val camera = controller.viewport.camera
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
        val camera = controller.viewport.camera
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

    /** Zooms in around the center, so that the page can move on both axes. */
    private fun PageboardController.zoomIn() {
        viewport.camera.onDrag(0f, 0f, 3f, center.x, center.y)
        viewport.camera.restoreToBounds(animated = false)
        assertTrue(viewport.camera.canMoveX && viewport.camera.canMoveY)
    }

    @Test
    fun aMostlyVerticalScrollOfAMacTrackpadStaysVerticalWithItsFling() = withCanvas(pages = 5) { scene, controller ->
        val camera = controller.viewport.camera
        controller.zoomIn()
        val before = camera.getCurrentTranslation()
        val unit = scrollUnitPx(1f)

        MacTrackpad.dispatchScrollPhase(TrackpadScrollPhase.FINGERS_DOWN, 1_000L)
        // AWT reports the sideways part of the fingers apart, as horizontal deltas
        for (i in 0..9) {
            scene.scroll(Offset(0f, 1f), timeMillis = 1_000L + i * 10L)
            scene.scroll(Offset(0.2f, 0f), timeMillis = 1_005L + i * 10L)
        }
        val moved = camera.getCurrentTranslation()
        assertEquals(before.x, moved.x, 1e-3f)
        assertEquals(before.y - 10f * unit, moved.y, 1e-3f)

        // Lifted while moving: the fling stays vertical too
        MacTrackpad.dispatchScrollPhase(TrackpadScrollPhase.FINGERS_UP, 1_095L)
        assertTrue(camera.isAnimating())
        repeat(120) { scene.render((it + 1) * 16_000_000L) }
        val flung = camera.getCurrentTranslation()
        assertEquals(before.x, flung.x, 1e-3f)
        assertTrue(flung.y < moved.y, "${flung.y} after ${moved.y}")
    }

    @Test
    fun aNewGestureOfAMacTrackpadChoosesItsAxisAgain() = withCanvas(pages = 5) { scene, controller ->
        val camera = controller.viewport.camera
        controller.zoomIn()
        val unit = scrollUnitPx(1f)

        MacTrackpad.dispatchScrollPhase(TrackpadScrollPhase.FINGERS_DOWN, 1_000L)
        scene.scroll(Offset(0f, 1f), timeMillis = 1_000L)
        scene.scroll(Offset(0f, 1f), timeMillis = 1_010L)
        // Lifted after a pause: no fling
        MacTrackpad.dispatchScrollPhase(TrackpadScrollPhase.FINGERS_UP, 1_200L)
        val before = camera.getCurrentTranslation()

        MacTrackpad.dispatchScrollPhase(TrackpadScrollPhase.FINGERS_DOWN, 2_000L)
        scene.scroll(Offset(1f, 0f), timeMillis = 2_000L)
        scene.scroll(Offset(0f, 0.3f), timeMillis = 2_005L)
        scene.scroll(Offset(1f, 0f), timeMillis = 2_010L)
        val after = camera.getCurrentTranslation()
        assertEquals(before.x - 2f * unit, after.x, 1e-3f)
        assertEquals(before.y, after.y, 1e-3f)
        MacTrackpad.dispatchScrollPhase(TrackpadScrollPhase.FINGERS_UP, 2_200L)
    }

    @Test
    fun aDiagonalScrollOfAMacTrackpadBreaksFreeAfterAShortMove() = withCanvas(pages = 5) { scene, controller ->
        val camera = controller.viewport.camera
        controller.zoomIn()
        val before = camera.getCurrentTranslation()
        // 5 px, whatever a unit of scroll is on this platform
        val step = 5f / scrollUnitPx(1f)

        MacTrackpad.dispatchScrollPhase(TrackpadScrollPhase.FINGERS_DOWN, 1_000L)
        // AWT reports the two axes apart: the first event, vertical, chooses the axis...
        for (i in 0..5) {
            scene.scroll(Offset(0f, step), timeMillis = 1_000L + i * 10L)
            scene.scroll(Offset(step, 0f), timeMillis = 1_005L + i * 10L)
        }
        // ...and 15 px across break the gesture free: the last four horizontal events move it
        val after = camera.getCurrentTranslation()
        assertEquals(before.y - 30f, after.y, 1e-3f)
        assertEquals(before.x - 20f, after.x, 1e-3f)
        MacTrackpad.dispatchScrollPhase(TrackpadScrollPhase.FINGERS_UP, 1_200L)
    }

    @Test
    fun aPageThatFitsDoesNotFollowTheSidewaysPartOfAScroll() = withCanvas(pages = 5) { scene, controller ->
        val camera = controller.viewport.camera
        // At the zoom of the opening the page fills the width: it cannot move sideways
        assertFalse(camera.canMoveX)
        val before = camera.getCurrentTranslation()
        val unit = scrollUnitPx(1f)

        MacTrackpad.dispatchScrollPhase(TrackpadScrollPhase.FINGERS_DOWN, 1_000L)
        scene.scroll(Offset(1f, 0f), timeMillis = 1_000L)
        // No rubber band sideways while the fingers are down
        assertEquals(before.x, camera.getCurrentTranslation().x, 1e-3f)
        scene.scroll(Offset(0f, 1f), timeMillis = 1_010L)
        assertEquals(before.y - unit, camera.getCurrentTranslation().y, 1e-3f)
        MacTrackpad.dispatchScrollPhase(TrackpadScrollPhase.FINGERS_UP, 1_200L)
    }

    @Test
    fun aWheelWithoutPhasesKeepsItsAxisUntilAPause() = withCanvas(pages = 5) { scene, controller ->
        val camera = controller.viewport.camera
        controller.zoomIn()
        val before = camera.getCurrentTranslation()
        // 10 px, below the move that breaks a gesture free, whatever a unit of scroll is here
        val step = 10f / scrollUnitPx(1f)

        scene.scroll(Offset(0f, step), timeMillis = 1_000L)
        assertEquals(before.y - 10f, camera.getCurrentTranslation().y, 1e-3f)
        // Within the gesture the other axis is left out...
        scene.scroll(Offset(step, 0f), timeMillis = 1_100L)
        assertEquals(before.x, camera.getCurrentTranslation().x, 1e-3f)
        // ...after a pause it starts a gesture of its own
        scene.scroll(Offset(step, 0f), timeMillis = 1_500L)
        assertEquals(before.x - 10f, camera.getCurrentTranslation().x, 1e-3f)
        assertEquals(before.y - 10f, camera.getCurrentTranslation().y, 1e-3f)
    }

    @Test
    fun theTwoFingerPanOfATrackpadFollowsTheAxisItStartedOn() = withCanvas(pages = 5) { scene, controller ->
        val camera = controller.viewport.camera
        controller.zoomIn()
        val before = camera.getCurrentTranslation()

        scene.sendGesture(PointerEventType.PanStart)
        scene.sendGesture(PointerEventType.PanMove, panOffset = Offset(3f, 30f))
        scene.sendGesture(PointerEventType.PanMove, panOffset = Offset(4f, 0f))

        val after = camera.getCurrentTranslation()
        assertEquals(before.x, after.x, 1e-3f)
        assertEquals(before.y - 30f, after.y, 1e-3f)
        scene.sendGesture(PointerEventType.PanEnd)
    }

    @Test
    fun pinchOfAMacTrackpadZoomsAroundThePointerAndSpringsBackIntoTheLimits() = withCanvas(pages = 1) { scene, controller ->
        val camera = controller.viewport.camera
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
