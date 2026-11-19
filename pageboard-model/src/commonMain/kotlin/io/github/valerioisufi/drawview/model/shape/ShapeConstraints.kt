package io.github.valerioisufi.drawview.model.shape

import io.github.valerioisufi.drawview.model.geometry.Point
import io.github.valerioisufi.drawview.model.geometry.Rect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The constraints of a shape being drawn from a point to the pointer, in page millimeters: a line
 * straight at a multiple of [ANGLE_STEP_DEGREES], a box square, a box drawn from its center. They
 * apply by themselves when the shape is near them, always when they are forced (Shift, a second
 * finger held).
 */
public object ShapeConstraints {
    /** The angles a line snaps to are multiples of this, in degrees. */
    public const val ANGLE_STEP_DEGREES: Float = 15f

    /** A line within this many degrees of a multiple of [ANGLE_STEP_DEGREES] takes it. */
    public const val ANGLE_TOLERANCE_DEGREES: Float = 4f

    /** A box whose sides differ by less than this part of the longer one becomes a square. */
    public const val SQUARE_TOLERANCE: Float = 0.06f

    /**
     * A line from a start to [end]: [angleDegrees] is its direction as it is read on the page,
     * counterclockwise from the right (0 to 360); [snapped] when it took a multiple of
     * [ANGLE_STEP_DEGREES].
     */
    public class ConstrainedLine(public val end: Point, public val angleDegrees: Float, public val snapped: Boolean) {
        /** Its length from [start]. */
        public fun length(start: Point): Float = (end - start).distance
    }

    /**
     * A box of a shape: [box] on the page; [regular] when its sides were made equal (a square, a
     * circle).
     */
    public class ConstrainedBox(public val box: Rect, public val regular: Boolean)

    /** The line from [start] towards [pointer], straightened when near an angle or when [forced]. */
    public fun line(start: Point, pointer: Point, forced: Boolean): ConstrainedLine {
        val delta = pointer - start
        val length = delta.distance
        if (length == 0f) return ConstrainedLine(pointer, 0f, false)
        val angle = atan2(delta.y, delta.x) * 180f / PI.toFloat()
        val nearest = (angle / ANGLE_STEP_DEGREES).roundToInt() * ANGLE_STEP_DEGREES
        val snapped = forced || abs(angle - nearest) <= ANGLE_TOLERANCE_DEGREES
        val chosen = if (snapped) nearest else angle
        val end = if (snapped) {
            val rad = chosen * PI.toFloat() / 180f
            val direction = Point(cos(rad), sin(rad))
            // The end follows the pointer along the line (its projection on it)
            val along = if (forced) delta.x * direction.x + delta.y * direction.y else length
            start + direction * max(along, 0f)
        } else {
            pointer
        }
        return ConstrainedLine(end, readAngle(-chosen), snapped)
    }

    /**
     * The box from [start] to [pointer] (or around [start] when [fromCenter]), square when its
     * sides are near each other or when [forced].
     */
    public fun box(start: Point, pointer: Point, forced: Boolean, fromCenter: Boolean): ConstrainedBox {
        var dx = pointer.x - start.x
        var dy = pointer.y - start.y
        val longer = max(abs(dx), abs(dy))
        val regular = longer > 0f && (forced || abs(abs(dx) - abs(dy)) <= SQUARE_TOLERANCE * longer)
        if (regular) {
            dx = if (dx < 0f) -longer else longer
            dy = if (dy < 0f) -longer else longer
        }
        val box = if (fromCenter) {
            Rect(start.x - abs(dx), start.y - abs(dy), start.x + abs(dx), start.y + abs(dy))
        } else {
            Rect(minOf(start.x, start.x + dx), minOf(start.y, start.y + dy), maxOf(start.x, start.x + dx), maxOf(start.y, start.y + dy))
        }
        return ConstrainedBox(box, regular)
    }

    /** [degrees] within 0 (included) and 360 (excluded). */
    private fun readAngle(degrees: Float): Float {
        val turned = degrees % 360f
        val positive = if (turned < 0f) turned + 360f else turned
        return if (positive >= 359.995f) 0f else positive
    }
}
