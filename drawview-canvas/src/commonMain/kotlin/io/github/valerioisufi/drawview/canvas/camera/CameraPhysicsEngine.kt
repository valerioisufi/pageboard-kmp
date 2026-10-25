package io.github.valerioisufi.drawview.canvas.camera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.sign

/**
 * Unified physics engine for managing the document camera's viewport and transformations.
 *
 * Utilizes a framerate-independent spring physics model for X-axis, Y-axis, and scaling (zoom).
 * Handles fling deceleration, elastic bounce-back, and rubber-band resistance.
 *
 * @param density Screen pixel density (used for dp to px scale bounds).
 * @param getContentRect Lambda returning the unscaled bounding box of document pages.
 */
public class CameraPhysicsEngine(
    public var density: Float = 1f,
    private val getContentRect: () -> Rect
) {
    /** Friction coefficient applied during fling gesture deceleration. */
    internal var friction: Float = 3.5f

    /** Stiffness of the spring for elastic bounce-back when viewport exceeds boundaries. */
    internal var springStiffness: Float = 250f

    /** Damping factor of the spring to prevent infinite oscillations during bounce. */
    internal var springDamping: Float = 25f

    /** Visual tension resistance applied when dragging outside boundaries (rubber-banding). */
    internal var rubberBandTension: Float = 0.55f

    /** How far a pinch can zoom beyond the scale limits (rubber-banding), as a ratio of the limit. */
    internal var maxScaleOverscroll: Float = 1.5f

    /** Minimum allowed ratio of screen dp per mm of document (maximum zoom out). */
    internal var minDpPerMm: Float = 0.2f

    /** Maximum allowed ratio of screen dp per mm of document (maximum zoom in). */
    internal var maxDpPerMm: Float = 50f

    /** Dynamic minimum scale factor. */
    internal val minScale: Float
        get() = minDpPerMm * density

    /** Dynamic maximum scale factor. */
    internal val maxScale: Float
        get() = maxDpPerMm * density

    /** Top padding around document content in pixels. */
    internal var topPaddingPx: Float = 40f

    /** Bottom padding around document content in pixels. */
    internal var bottomPaddingPx: Float = 40f

    private val axisX = AxisPhysics1D()
    private val axisY = AxisPhysics1D()
    private val scaleAxis = ScalePhysics1D()

    /**
     * Incremented whenever the camera moves (gestures, fling, bounce): observable, so that what is
     * anchored to the pages follows the camera in the same frame as the canvas.
     */
    public var moveCount: Int by mutableIntStateOf(0)
        private set

    /**
     * Incremented when an animation starts (fling, bounce): observable, so that the canvas asks
     * for frames only while the camera animates and lets the device sleep at rest.
     */
    internal var animationStarts by mutableIntStateOf(0)
        private set

    private var isUserDragging = false
    private var viewportRect = Rect.Zero

    // Scale asked by the pinch in progress, beyond the limits too
    private var gestureScale = 1f

    // Focus of the last zoom, kept while the scale springs back into its limits
    private var scaleFocusX = 0f
    private var scaleFocusY = 0f

    private val renderMatrix = Matrix2D()

    init {
        val defaultStartScale = 1.0f * density
        scaleAxis.position = defaultStartScale.coerceIn(minScale, maxScale)
    }

    /**
     * Updates viewport dimensions and recalculates boundaries.
     */
    internal fun setViewport(width: Float, height: Float) {
        viewportRect = Rect(0f, 0f, width, height)
        updateDynamicBoundaries()
        moveCount++
    }

    /**
     * Centers camera on a specific document point at the specified scale.
     */
    internal fun centerOnWorldPoint(worldX: Float, worldY: Float, scale: Float, screenWidth: Float, screenHeight: Float) {
        stopAllAnimations()
        scaleAxis.position = scale.coerceIn(minScale, maxScale)

        axisX.position = (screenWidth / 2f) - (worldX * scaleAxis.position)
        axisY.position = (screenHeight / 2f) - (worldY * scaleAxis.position)

        updateDynamicBoundaries()
        moveCount++
    }

    public fun getCurrentScale(): Float = scaleAxis.position

    internal fun getCurrentTranslation(): Offset = Offset(axisX.position, axisY.position)

    internal fun isAnimating(): Boolean {
        return !isUserDragging && (
            axisX.state != PhysicsState.IDLE ||
            axisY.state != PhysicsState.IDLE ||
            scaleAxis.state != PhysicsState.IDLE
        )
    }

    public fun onDragStart() {
        isUserDragging = true
        stopAllAnimations()
        gestureScale = scaleAxis.position
    }

    /**
     * Processes drag and zoom gestures.
     *
     * @param dx Translation delta X in pixels.
     * @param dy Translation delta Y in pixels.
     * @param scaleFactor Scale multiplier.
     * @param focusX Screen X coordinate of gesture focus.
     * @param focusY Screen Y coordinate of gesture focus.
     */
    public fun onDrag(dx: Float, dy: Float, scaleFactor: Float = 1f, focusX: Float = 0f, focusY: Float = 0f) {
        if (scaleFactor != 1f && scaleFactor > 0f) {
            val oldScale = scaleAxis.position
            scaleAxis.position = if (isUserDragging) {
                // Pinch: beyond the limits the zoom resists, and springs back on release. Within
                // the limits it follows the scale, which may have changed apart from the pinch
                if (oldScale in minScale..maxScale) gestureScale = oldScale
                gestureScale *= scaleFactor
                rubberBandScale(gestureScale)
            } else {
                (oldScale * scaleFactor).coerceIn(minScale, maxScale)
            }
            scaleFocusX = focusX
            scaleFocusY = focusY
            scaleAround(scaleAxis.position / oldScale, focusX, focusY)
        }

        axisX.position += dx
        axisY.position += dy

        updateDynamicBoundaries()
        moveCount++
    }

    /**
     * Initiates fling or bounce animation based on release velocity.
     *
     * @param velocityX Velocity along X in pixels per second.
     * @param velocityY Velocity along Y in pixels per second.
     */
    internal fun onRelease(velocityX: Float = 0f, velocityY: Float = 0f) {
        isUserDragging = false
        updateDynamicBoundaries()

        if (axisX.calculateExcess() != 0f) {
            axisX.startBounce()
        } else if (abs(velocityX) > 20f) {
            axisX.startFling(velocityX)
        }

        if (axisY.calculateExcess() != 0f) {
            axisY.startBounce()
        } else if (abs(velocityY) > 20f) {
            axisY.startFling(velocityY)
        }

        if (scaleAxis.calculateExcess() != 0f) scaleAxis.startBounce()
        if (isAnimating()) animationStarts++
    }

    internal fun stopAllAnimations() {
        axisX.stop()
        axisY.stop()
        scaleAxis.stop()
    }

    internal fun restoreToBounds(animated: Boolean = true) {
        updateDynamicBoundaries()

        if (animated) {
            if (axisX.calculateExcess() != 0f) axisX.startBounce()
            if (axisY.calculateExcess() != 0f) axisY.startBounce()
            if (scaleAxis.calculateExcess() != 0f) scaleAxis.startBounce()
            if (isAnimating()) animationStarts++
        } else {
            if (axisX.calculateExcess() != 0f) axisX.position -= axisX.calculateExcess()
            if (axisY.calculateExcess() != 0f) axisY.position -= axisY.calculateExcess()
            if (scaleAxis.calculateExcess() != 0f) scaleAxis.position -= scaleAxis.calculateExcess()
            stopAllAnimations()
            moveCount++
        }
    }

    /**
     * Advances simulation step by delta time.
     *
     * @param deltaTimeMillis Elapsed milliseconds.
     */
    public fun update(deltaTimeMillis: Long) {
        if (isUserDragging || deltaTimeMillis <= 0 || !isAnimating()) return

        val dt = (deltaTimeMillis / 1000f).coerceAtMost(0.05f)
        updateDynamicBoundaries()

        val oldScale = scaleAxis.position
        scaleAxis.update(dt)
        if (scaleAxis.position != oldScale) {
            // The scale springs back around the focus of the pinch, then the pages into the edges
            scaleAround(scaleAxis.position / oldScale, scaleFocusX, scaleFocusY)
            updateDynamicBoundaries()
            if (axisX.state == PhysicsState.IDLE && axisX.calculateExcess() != 0f) axisX.startBounce()
            if (axisY.state == PhysicsState.IDLE && axisY.calculateExcess() != 0f) axisY.startBounce()
        }
        axisX.update(dt)
        axisY.update(dt)
        moveCount++
    }

    /** Keeps the point at ([focusX], [focusY]) in place while the scale is multiplied by [ratio]. */
    private fun scaleAround(ratio: Float, focusX: Float, focusY: Float) {
        axisX.position = focusX - (focusX - axisX.position) * ratio
        axisY.position = focusY - (focusY - axisY.position) * ratio
    }

    /**
     * Scale shown for the [scale] asked by a pinch: beyond the limits it resists more and more
     * (rubber-banding in logarithmic space), up to [maxScaleOverscroll] times the limit.
     */
    private fun rubberBandScale(scale: Float): Float {
        val limit = when {
            scale > maxScale -> maxScale
            scale < minScale -> minScale
            else -> return scale
        }
        val excess = ln(scale / limit)
        val dimension = ln(maxScaleOverscroll)
        val banded = (abs(excess) * dimension * rubberBandTension) / (dimension + rubberBandTension * abs(excess))
        return limit * exp(banded * sign(excess))
    }

    /**
     * Generates transformation matrix for rendering with scale, translation,
     * and rubber-band tension.
     *
     * The translation is on whole pixels, like the pages (see [CalcPage.calcPagesRectOnWindow]):
     * the tiles shown at their own scale are not filtered half a pixel off. The physics keeps the
     * exact position.
     */
    internal fun getRenderMatrix(outMatrix: Matrix2D = renderMatrix): Matrix2D {
        val renderScale = scaleAxis.position
        val renderX = axisX.getRubberBandPosition(viewportRect.width.coerceAtLeast(1f), rubberBandTension)
        val renderY = axisY.getRubberBandPosition(viewportRect.height.coerceAtLeast(1f), rubberBandTension)

        outMatrix.reset()
        outMatrix.postScale(renderScale, renderScale)
        outMatrix.postTranslate(round(renderX), round(renderY))

        return outMatrix
    }

    /**
     * Bounds of the translation for the current scale. Horizontally, content not wider than the
     * viewport is held at its center, and wider content moves until its edges (the margin of the
     * pages included) reach the edges of the viewport: at the width of the viewport both give the
     * same translation, so the bounds change continuously with the zoom.
     */
    private fun updateDynamicBoundaries() {
        if (viewportRect.isEmpty) return
        val content = getContentRect()
        if (content.isEmpty) return

        val currentScale = scaleAxis.position
        val scaledWidth = content.width * currentScale
        val scaledHeight = content.height * currentScale

        // Within a pixel: the layout rounds the pages, and on a phone they fill the width
        if (scaledWidth <= viewportRect.width + 1f) {
            val centeredX = (viewportRect.width - scaledWidth) / 2f - content.left * currentScale
            axisX.minValue = centeredX
            axisX.maxValue = centeredX
        } else {
            axisX.minValue = viewportRect.width - content.right * currentScale
            axisX.maxValue = -content.left * currentScale
        }

        if (scaledHeight <= viewportRect.height - (topPaddingPx + bottomPaddingPx)) {
            axisY.minValue = topPaddingPx
            axisY.maxValue = topPaddingPx
        } else {
            axisY.minValue = viewportRect.height - scaledHeight - bottomPaddingPx
            axisY.maxValue = topPaddingPx
        }
    }

    internal enum class PhysicsState { IDLE, FLINGING, BOUNCING }

    private inner class AxisPhysics1D {
        var state = PhysicsState.IDLE
        var position: Float = 0f
        var velocity: Float = 0f
        var minValue: Float = 0f
        var maxValue: Float = 0f
        private var bounceEdge: Int = 0

        fun startFling(v: Float) {
            velocity = v
            state = PhysicsState.FLINGING
        }

        fun startBounce() {
            bounceEdge = when {
                position > maxValue -> 1
                position < minValue -> -1
                else -> 0
            }
            state = PhysicsState.BOUNCING
        }

        fun stop() {
            state = PhysicsState.IDLE
            velocity = 0f
            bounceEdge = 0
        }

        fun calculateExcess(): Float {
            return when {
                position < minValue -> position - minValue
                position > maxValue -> position - maxValue
                else -> 0f
            }
        }

        fun update(dt: Float) {
            if (state == PhysicsState.IDLE) return

            when (state) {
                PhysicsState.FLINGING -> {
                    velocity -= velocity * friction * dt
                    position += velocity * dt

                    if (calculateExcess() != 0f) {
                        startBounce()
                    }

                    if (abs(velocity) < 10f && calculateExcess() == 0f) {
                        stop()
                    }
                }
                PhysicsState.BOUNCING -> {
                    val target = when (bounceEdge) {
                        1 -> maxValue
                        -1 -> minValue
                        else -> when {
                            position > maxValue -> maxValue.also { bounceEdge = 1 }
                            position < minValue -> minValue.also { bounceEdge = -1 }
                            else -> null
                        }
                    }
                    val excess = if (target != null) position - target else calculateExcess()
                    val springForce = (-springStiffness * excess) - (springDamping * velocity)
                    velocity += springForce * dt
                    position += velocity * dt

                    if (abs(excess) < 0.5f && abs(velocity) < 10f) {
                        if (target != null) position = target else position -= calculateExcess()
                        stop()
                    }
                }
                else -> {}
            }
        }

        fun getRubberBandPosition(dimension: Float, tension: Float): Float {
            val excess = calculateExcess()
            if (excess == 0f) return position

            val validPosition = position - excess
            val absExcess = abs(excess)
            val rubberBandedExcess = (absExcess * dimension * tension) / (dimension + tension * absExcess)

            return validPosition + (rubberBandedExcess * sign(excess))
        }
    }

    private inner class ScalePhysics1D {
        var state = PhysicsState.IDLE
        var position: Float = 1f
        var velocity: Float = 0f
        private var bounceEdge: Int = 0

        fun startBounce() {
            bounceEdge = when {
                position > maxScale -> 1
                position < minScale -> -1
                else -> 0
            }
            state = PhysicsState.BOUNCING
        }

        fun stop() {
            state = PhysicsState.IDLE
            velocity = 0f
            bounceEdge = 0
        }

        fun calculateExcess(): Float {
            return when {
                position < minScale -> position - minScale
                position > maxScale -> position - maxScale
                else -> 0f
            }
        }

        fun update(dt: Float) {
            if (state != PhysicsState.BOUNCING) return
            val target = when (bounceEdge) {
                1 -> maxScale
                -1 -> minScale
                else -> when {
                    position > maxScale -> maxScale.also { bounceEdge = 1 }
                    position < minScale -> minScale.also { bounceEdge = -1 }
                    else -> null
                }
            }
            val excess = if (target != null) position - target else calculateExcess()

            val springForce = (-springStiffness * 1.5f * excess) - (springDamping * velocity)
            velocity += springForce * dt
            position += velocity * dt

            if (abs(excess) < 0.005f && abs(velocity) < 0.05f) {
                if (target != null) position = target else position -= calculateExcess()
                stop()
            }
        }
    }
}
