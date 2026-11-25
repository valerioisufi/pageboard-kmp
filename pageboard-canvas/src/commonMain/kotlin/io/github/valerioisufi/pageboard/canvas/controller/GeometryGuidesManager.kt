package io.github.valerioisufi.pageboard.canvas.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

/**
 * Interactive straightedge guide on the canvas in screen coordinates: draws ticks calibrated to
 * the active page and projects nearby ink strokes cleanly onto either long edge.
 */
public data class RulerGuide(
    val centerX: Float,
    val centerY: Float,
    val angleDeg: Float = 0f,
    val lengthPx: Float = 420f,
    val thicknessPx: Float = 56f
)

/**
 * Interactive semicircular protractor guide on the canvas in screen coordinates: draws angular
 * graduations and projects nearby ink strokes cleanly onto its outer arc.
 */
public data class ProtractorGuide(
    val centerX: Float,
    val centerY: Float,
    val angleDeg: Float = 0f,
    val radiusPx: Float = 150f
)

/**
 * Manages the interactive on-canvas [ruler] and [protractor] geometry tools: positioning,
 * rotating (with 15° snap), hit-testing, and projecting pen strokes along their edges/rim.
 */
public class GeometryGuidesManager internal constructor(private val controller: PageboardController) {

    public var ruler: RulerGuide? by mutableStateOf(null)
        private set

    public var protractor: ProtractorGuide? by mutableStateOf(null)
        private set

    public val isRulerVisible: Boolean
        get() = ruler != null

    public val isProtractorVisible: Boolean
        get() = protractor != null

    internal enum class GuideHit {
        RULER_BODY,
        RULER_ROTATE_START,
        RULER_ROTATE_END,
        PROTRACTOR_BODY,
        PROTRACTOR_ROTATE
    }

    private enum class LockedEdge {
        RULER_TOP,
        RULER_BOTTOM,
        PROTRACTOR_ARC
    }

    private var lockedEdge: LockedEdge? = null

    /** Shows or hides the interactive straightedge ruler centered in the viewport. */
    public fun toggleRuler() {
        if (ruler != null) {
            ruler = null
            if (lockedEdge == LockedEdge.RULER_TOP || lockedEdge == LockedEdge.RULER_BOTTOM) lockedEdge = null
        } else {
            val density = controller.viewport.density.coerceAtLeast(1f)
            val vw = controller.viewport.width.takeIf { it > 0f } ?: (800f * density)
            val vh = controller.viewport.height.takeIf { it > 0f } ?: (600f * density)
            val length = (vw * 0.55f).coerceIn(260f * density, 520f * density)
            val thickness = 56f * density
            ruler = RulerGuide(centerX = vw / 2f, centerY = vh / 2f, angleDeg = 0f, lengthPx = length, thicknessPx = thickness)
        }
        controller.requestRedraw()
    }

    /** Shows or hides the interactive semicircular protractor centered in the viewport. */
    public fun toggleProtractor() {
        if (protractor != null) {
            protractor = null
            if (lockedEdge == LockedEdge.PROTRACTOR_ARC) lockedEdge = null
        } else {
            val density = controller.viewport.density.coerceAtLeast(1f)
            val vw = controller.viewport.width.takeIf { it > 0f } ?: (800f * density)
            val vh = controller.viewport.height.takeIf { it > 0f } ?: (600f * density)
            val radius = (minOf(vw, vh) * 0.25f).coerceIn(110f * density, 190f * density)
            protractor = ProtractorGuide(centerX = vw / 2f, centerY = vh * 0.55f, angleDeg = 0f, radiusPx = radius)
        }
        controller.requestRedraw()
    }

    /** Hides both interactive geometry guides. */
    public fun hideAll() {
        ruler = null
        protractor = null
        lockedEdge = null
        controller.requestRedraw()
    }

    internal fun hitTest(screenPos: Offset, density: Float): GuideHit? {
        val handleRadius = HANDLE_HIT_DP * density
        ruler?.let { r ->
            val rad = r.angleDeg * PI.toFloat() / 180f
            val ux = cos(rad)
            val uy = sin(rad)
            val startHandle = Offset(r.centerX - ux * (r.lengthPx / 2f - 18f * density), r.centerY - uy * (r.lengthPx / 2f - 18f * density))
            val endHandle = Offset(r.centerX + ux * (r.lengthPx / 2f - 18f * density), r.centerY + uy * (r.lengthPx / 2f - 18f * density))
            if (distance(screenPos, startHandle) <= handleRadius) return GuideHit.RULER_ROTATE_START
            if (distance(screenPos, endHandle) <= handleRadius) return GuideHit.RULER_ROTATE_END

            val dx = screenPos.x - r.centerX
            val dy = screenPos.y - r.centerY
            val localX = dx * ux + dy * uy
            val localY = -dx * uy + dy * ux
            if (abs(localX) <= r.lengthPx / 2f && abs(localY) <= r.thicknessPx / 2f) {
                return GuideHit.RULER_BODY
            }
        }
        protractor?.let { p ->
            val rad = p.angleDeg * PI.toFloat() / 180f
            val ux = cos(rad)
            val uy = sin(rad)
            // Rotation knob sits at the top apex of the semicircular protractor (localY = -radius)
            val rotHandle = Offset(p.centerX + uy * p.radiusPx, p.centerY - ux * p.radiusPx)
            if (distance(screenPos, rotHandle) <= handleRadius) return GuideHit.PROTRACTOR_ROTATE

            val dx = screenPos.x - p.centerX
            val dy = screenPos.y - p.centerY
            val localY = -dx * uy + dy * ux
            if (hypot(dx, dy) <= p.radiusPx && localY <= 8f * density) {
                return GuideHit.PROTRACTOR_BODY
            }
        }
        return null
    }

    internal fun drag(hit: GuideHit, from: Offset, to: Offset, free: Boolean = false) {
        val dx = to.x - from.x
        val dy = to.y - from.y
        when (hit) {
            GuideHit.RULER_BODY -> ruler = ruler?.let { it.copy(centerX = it.centerX + dx, centerY = it.centerY + dy) }
            GuideHit.RULER_ROTATE_START -> ruler = ruler?.let { r ->
                val raw = atan2(r.centerY - to.y, r.centerX - to.x) * 180f / PI.toFloat()
                r.copy(angleDeg = snapAngle(raw, free))
            }
            GuideHit.RULER_ROTATE_END -> ruler = ruler?.let { r ->
                val raw = atan2(to.y - r.centerY, to.x - r.centerX) * 180f / PI.toFloat()
                r.copy(angleDeg = snapAngle(raw, free))
            }
            GuideHit.PROTRACTOR_BODY -> protractor = protractor?.let { it.copy(centerX = it.centerX + dx, centerY = it.centerY + dy) }
            GuideHit.PROTRACTOR_ROTATE -> protractor = protractor?.let { p ->
                // Apex knob points along local -Y, i.e. world angle (angleDeg - 90°)
                val raw = atan2(to.y - p.centerY, to.x - p.centerX) * 180f / PI.toFloat() + 90f
                p.copy(angleDeg = snapAngle(raw, free))
            }
        }
        controller.requestRedraw()
    }

    /**
     * Checks if [screenPos] starts along the snap zone of a visible guide edge/rim and locks
     * subsequent points of the ink stroke to it. Returns whether a guide edge was captured.
     */
    internal fun beginInkStroke(screenPos: Offset, density: Float): Boolean {
        lockedEdge = detectEdge(screenPos, density)
        return lockedEdge != null
    }

    /** Projects [screenPos] onto the locked or nearby guide edge/arc, or returns [screenPos] unchanged. */
    internal fun snapInkPoint(screenPos: Offset, density: Float): Offset {
        val edge = lockedEdge ?: detectEdge(screenPos, density)?.also { lockedEdge = it } ?: return screenPos
        return when (edge) {
            LockedEdge.RULER_TOP -> ruler?.let { projectOntoRuler(it, screenPos, topEdge = true) } ?: screenPos
            LockedEdge.RULER_BOTTOM -> ruler?.let { projectOntoRuler(it, screenPos, topEdge = false) } ?: screenPos
            LockedEdge.PROTRACTOR_ARC -> protractor?.let { projectOntoProtractor(it, screenPos) } ?: screenPos
        }
    }

    internal fun endInkStroke() {
        lockedEdge = null
    }

    private fun detectEdge(screenPos: Offset, density: Float): LockedEdge? {
        val margin = SNAP_MARGIN_DP * density
        ruler?.let { r ->
            val rad = r.angleDeg * PI.toFloat() / 180f
            val ux = cos(rad)
            val uy = sin(rad)
            val dx = screenPos.x - r.centerX
            val dy = screenPos.y - r.centerY
            val localX = dx * ux + dy * uy
            val localY = -dx * uy + dy * ux
            if (abs(localX) <= r.lengthPx / 2f + margin) {
                val halfThick = r.thicknessPx / 2f
                val distTop = abs(localY + halfThick)
                val distBottom = abs(localY - halfThick)
                if (distTop <= margin && distTop <= distBottom) return LockedEdge.RULER_TOP
                if (distBottom <= margin) return LockedEdge.RULER_BOTTOM
            }
        }
        protractor?.let { p ->
            val rad = p.angleDeg * PI.toFloat() / 180f
            val ux = cos(rad)
            val uy = sin(rad)
            val dx = screenPos.x - p.centerX
            val dy = screenPos.y - p.centerY
            val localY = -dx * uy + dy * ux
            val distToRim = abs(hypot(dx, dy) - p.radiusPx)
            if (distToRim <= margin && localY <= margin) {
                return LockedEdge.PROTRACTOR_ARC
            }
        }
        return null
    }

    private fun projectOntoRuler(r: RulerGuide, screenPos: Offset, topEdge: Boolean): Offset {
        val rad = r.angleDeg * PI.toFloat() / 180f
        val ux = cos(rad)
        val uy = sin(rad)
        val nx = -uy
        val ny = ux
        val dx = screenPos.x - r.centerX
        val dy = screenPos.y - r.centerY
        val projX = (dx * ux + dy * uy).coerceIn(-r.lengthPx / 2f, r.lengthPx / 2f)
        val edgeOffset = if (topEdge) -r.thicknessPx / 2f else r.thicknessPx / 2f
        return Offset(r.centerX + ux * projX + nx * edgeOffset, r.centerY + uy * projX + ny * edgeOffset)
    }

    private fun projectOntoProtractor(p: ProtractorGuide, screenPos: Offset): Offset {
        val rad = p.angleDeg * PI.toFloat() / 180f
        val ux = cos(rad)
        val uy = sin(rad)
        val dx = screenPos.x - p.centerX
        val dy = screenPos.y - p.centerY
        val localX = dx * ux + dy * uy
        val localY = (-dx * uy + dy * ux).coerceAtMost(0f)
        val len = hypot(localX, localY)
        if (len <= 0.001f) {
            return Offset(p.centerX + uy * p.radiusPx, p.centerY - ux * p.radiusPx)
        }
        val clx = localX / len * p.radiusPx
        val cly = localY / len * p.radiusPx
        return Offset(p.centerX + clx * ux - cly * uy, p.centerY + clx * uy + cly * ux)
    }

    private fun snapAngle(rawDegrees: Float, free: Boolean): Float {
        var norm = ((rawDegrees + 180f) % 360f + 360f) % 360f - 180f
        if (!free) {
            val nearest = round(norm / SNAP_ANGLE_STEP) * SNAP_ANGLE_STEP
            if (abs(norm - nearest) <= SNAP_ANGLE_WINDOW) norm = nearest
        }
        return norm
    }

    private fun distance(a: Offset, b: Offset): Float = hypot(a.x - b.x, a.y - b.y)

    internal companion object {
        private const val HANDLE_HIT_DP = 20f
        private const val SNAP_MARGIN_DP = 18f
        private const val SNAP_ANGLE_STEP = 15f
        private const val SNAP_ANGLE_WINDOW = 2.5f
    }
}
