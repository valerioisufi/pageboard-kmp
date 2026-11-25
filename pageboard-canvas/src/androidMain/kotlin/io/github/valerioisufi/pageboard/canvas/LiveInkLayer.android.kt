package io.github.valerioisufi.pageboard.canvas

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.PixelFormat
import android.os.Build
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.viewinterop.AndroidView
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.canvas.geometry.toCompose
import io.github.valerioisufi.pageboard.ink.InkStrokeEngine
import io.github.valerioisufi.pageboard.model.StrokeMesh
import io.github.valerioisufi.pageboard.model.StrokeOutline
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import io.github.valerioisufi.pageboard.model.geometry.Rect
import io.github.valerioisufi.pageboard.platform.SyncLock
import io.github.valerioisufi.pageboard.platform.syncLock

@Composable
internal actual fun rememberLiveInkLayer(controller: PageboardController): LiveInkLayer =
    remember(controller) { FrontBufferedLiveInkLayer(controller) }

/**
 * The stroke in progress drawn in a front buffer over the canvas (see `docs/plans/ink-latency.md`): at
 * each input, outside the frames of Compose and of the compositor queue, into a single buffer that
 * stays on screen. Only what changed is drawn again: the region Ink updated is cleared and the
 * stroke filled again clipped to it.
 *
 * A translucent stroke stays in the frames of Compose ([ComposeLiveInkLayer]): the hand-off to the
 * tiles shows a stroke twice for a few frames, which an opaque one hides and a translucent one
 * would show darker. So does every stroke when the buffer is not there (the surface not ready, a
 * renderer that could not be created) or the modeller gives no outline.
 */
internal class FrontBufferedLiveInkLayer(private val controller: PageboardController) : LiveInkLayer {
    private enum class Mode { NONE, FRONT_BUFFER, COMPOSE }

    private enum class HandOffStep { NONE, AWAITING_TILES, AWAITING_COMMIT, REACHING_SCREEN }

    /** What the next pass of the renderer draws: the stroke as it is, and the part to draw again. */
    private class Frame(val outline: StrokeOutline, val color: Int, val transform: Matrix2D, val dirty: Rect)

    private val compose = ComposeLiveInkLayer()
    private val painter = StrokeMeshPainter()
    private val lock = SyncLock()

    /** The frame waiting for the renderer; older ones are merged into it (under [lock]). */
    private var pending: Frame? = null

    private var renderer: CanvasFrontBufferedRenderer<Unit>? = null
    private var surfaceReady = false
    private var mode = Mode.NONE
    private var color = 0
    private var transform: Matrix2D? = null

    /** All the stroke has covered in the buffer so far, in screen pixels. */
    private var strokeBounds: Rect? = null

    private var surfaceView: SurfaceView? = null

    /** Where the stroke ended is in its hand-off to the tiles. */
    private var handOffStep = HandOffStep.NONE

    /** Frames left before the buffer is hidden: to reach the screen, or to give up waiting. */
    private var handOffFrames = 0

    /** Whether the surface asked the display for its fastest refresh, for the stroke in the buffer. */
    private var fastRefreshAsked = false

    private val handOff = Choreographer.FrameCallback {
        if (handOffStep == HandOffStep.NONE) return@FrameCallback
        handOffFrames--
        if (handOffFrames > 0) scheduleHandOff() else hideBuffer()
    }

    private val frameCommitted = Runnable {
        if (handOffStep == HandOffStep.AWAITING_COMMIT) {
            handOffStep = HandOffStep.REACHING_SCREEN
            handOffFrames = COMPOSITOR_FRAMES
        }
    }

    private val callback = object : CanvasFrontBufferedRenderer.Callback<Unit> {
        override fun onDrawFrontBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: Unit) {
            // The passes of the inputs that came meanwhile have nothing left to draw
            val frame = syncLock(lock) { pending.also { pending = null } } ?: return
            val saveCount = canvas.save()
            val dirty = frame.dirty.inflate(ANTIALIASING_MARGIN_PX)
            canvas.clipRect(dirty.left, dirty.top, dirty.right, dirty.bottom)
            canvas.drawColor(AndroidColor.BLACK, BlendMode.CLEAR)
            painter.drawOutline(canvas, frame.outline, Color(frame.color), frame.transform)
            canvas.restoreToCount(saveCount)
            controller.inkLatencyMeter.frameDrawn()
        }

        // Nothing is ever committed to it: the tiles take the stroke
        override fun onDrawMultiBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, params: Collection<Unit>) = Unit
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = Unit

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            surfaceReady = width > 0 && height > 0
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
        }
    }

    override fun strokeChanged(engine: InkStrokeEngine, color: Int, pageIndex: Int, replaced: Boolean) {
        if (mode == Mode.NONE) {
            val opaque = (color ushr 24) == 0xFF
            mode = if (opaque && surfaceReady && renderer != null && engine.isNative) Mode.FRONT_BUFFER else Mode.COMPOSE
            if (mode == Mode.FRONT_BUFFER) {
                // What is left of the stroke before stays until this one ends: it is in the tiles too
                cancelHandOff()
                this.color = color
                strokeBounds = null
                askFastRefresh(true)
            }
        }
        if (mode == Mode.COMPOSE) {
            compose.strokeChanged(engine, color, pageIndex, replaced)
            return
        }

        val outline = engine.currentOutline()
        val mmToScreen = controller.viewport.pageMmToScreen(pageIndex)
        val region = engine.takeUpdatedRegion()?.let { mmToScreen?.mapRect(it) }
        if (outline == null || mmToScreen == null || region == null) return
        transform = mmToScreen
        // A stroke replaced (by its shape) leaves nothing of what it covered
        val dirty = if (replaced) strokeBounds?.union(region) ?: region else region
        strokeBounds = strokeBounds?.union(region) ?: region
        draw(outline, dirty)
    }

    override fun strokeEnded(finished: StrokeMesh?) {
        val wasMode = mode
        mode = Mode.NONE
        when (wasMode) {
            Mode.NONE -> Unit
            Mode.COMPOSE -> compose.strokeEnded(finished)
            Mode.FRONT_BUFFER -> {
                val outline = finished?.outline?.takeUnless { it.isEmpty }
                val bounds = strokeBounds
                if (finished == null || outline == null || bounds == null) {
                    // Given up, or nothing to hand off
                    hideBuffer()
                } else {
                    // The last shape, without the prediction, until the tiles show it
                    draw(outline, bounds)
                    handOffStep = HandOffStep.AWAITING_TILES
                    handOffFrames = MAX_HAND_OFF_FRAMES
                    scheduleHandOff()
                }
            }
        }
    }

    override fun draw(scope: DrawScope, controller: PageboardController) = compose.draw(scope, controller)

    /**
     * The tiles recorded in this frame hold the stroke, unless a cell is still waiting for its first
     * tile (it asks for another frame when it comes). The frame is known to be on its way only when
     * the renderer has submitted it, which may take long for tiles to upload, and the callback for
     * that belongs to the frame after this one: so one more is asked for.
     */
    override fun tilesDrawn() {
        if (handOffStep != HandOffStep.AWAITING_TILES) return
        val bounds = strokeBounds ?: return
        if (!controller.tileManager.isScreenRegionDrawn(bounds.toCompose())) return
        val view = surfaceView ?: return
        handOffStep = HandOffStep.AWAITING_COMMIT
        view.viewTreeObserver.registerFrameCommitCallback(frameCommitted)
        view.postInvalidateOnAnimation()
    }

    /**
     * The surface over the canvas: on top of the window, translucent, and given no touch (the
     * gestures are on the parent of the canvas, and a view that takes no touch leaves them alone).
     */
    @Composable
    override fun Content() {
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    setZOrderOnTop(true)
                    holder.setFormat(PixelFormat.TRANSLUCENT)
                    holder.addCallback(surfaceCallback)
                    attach(this)
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { view ->
                view.holder.removeCallback(surfaceCallback)
                detach()
            }
        )
    }

    private fun attach(surfaceView: SurfaceView) {
        // Without the renderer the strokes stay in the frames of Compose
        this.surfaceView = surfaceView
        renderer = runCatching { CanvasFrontBufferedRenderer(surfaceView, callback) }.getOrNull()
    }

    private fun detach() {
        cancelHandOff()
        fastRefreshAsked = false
        surfaceReady = false
        renderer?.release(true)
        renderer = null
        surfaceView = null
    }

    private fun draw(outline: StrokeOutline, dirty: Rect) {
        val transform = transform ?: return
        syncLock(lock) {
            val merged = pending?.dirty?.union(dirty) ?: dirty
            pending = Frame(outline, color, transform, merged)
        }
        renderer?.renderFrontBufferedLayer(Unit)
    }

    private fun scheduleHandOff() {
        Choreographer.getInstance().postFrameCallback(handOff)
    }

    private fun cancelHandOff() {
        handOffStep = HandOffStep.NONE
        handOffFrames = 0
        Choreographer.getInstance().removeFrameCallback(handOff)
    }

    private fun hideBuffer() {
        cancelHandOff()
        syncLock(lock) { pending = null }
        strokeBounds = null
        renderer?.cancel()
        askFastRefresh(false)
    }

    /**
     * While a stroke is in the buffer, the fastest refresh of the display (Android 11): the stroke at
     * 90 or 120 Hz where the display has them, the display left to choose at rest. Nothing on a
     * display of 60 Hz only.
     */
    private fun askFastRefresh(drawing: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || drawing == fastRefreshAsked) return
        val view = surfaceView ?: return
        val surface = view.holder.surface?.takeIf { it.isValid } ?: return
        val rate = if (drawing) fastestRefreshRate(view) else 0f
        if (drawing && rate <= FAST_REFRESH_HZ) return
        // A surface going away meanwhile refuses the vote: the display keeps its rate
        if (runCatching { surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }.isSuccess) fastRefreshAsked = drawing
    }

    /** The fastest refresh of the display of [view] at its resolution, in Hz; 0 when it is not known. */
    private fun fastestRefreshRate(view: android.view.View): Float {
        val display = view.display ?: return 0f
        val current = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxOfOrNull { it.refreshRate } ?: 0f
    }

    private companion object {
        /** The edge of the stroke, antialiased, reaches a little beyond its geometry. */
        const val ANTIALIASING_MARGIN_PX = 2f

        /**
         * Frames from the frame with the tiles submitted to the buffer hidden: its turn in the
         * compositor, and one more for a buffer not ready yet. A gap would show the stroke disappear;
         * the stroke shown twice meanwhile is opaque.
         */
        const val COMPOSITOR_FRAMES = 3

        /** Frames from the pen up after which the buffer is hidden anyway, to never stay over the canvas. */
        const val MAX_HAND_OFF_FRAMES = 30

        /** Displays up to this refresh (and a little more, the rounding of 60 Hz) are left as they are. */
        const val FAST_REFRESH_HZ = 61f
    }
}
