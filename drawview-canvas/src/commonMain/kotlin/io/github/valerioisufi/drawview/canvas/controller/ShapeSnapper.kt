package io.github.valerioisufi.drawview.canvas.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.valerioisufi.drawview.ink.InkStrokeEngine
import io.github.valerioisufi.drawview.model.BrushConfig
import io.github.valerioisufi.drawview.model.InputToolType
import io.github.valerioisufi.drawview.model.StrokePoint
import io.github.valerioisufi.drawview.model.geometry.Point
import io.github.valerioisufi.drawview.model.shape.Shape
import io.github.valerioisufi.drawview.model.shape.ShapeRecognizer
import io.github.valerioisufi.drawview.model.shape.followed
import io.github.valerioisufi.drawview.model.shape.pivot
import io.github.valerioisufi.drawview.model.shape.strokeInputs

/**
 * Turns the stroke being drawn into the shape it looks like (a line, an arrow, a circle, a
 * box...) when the pen is held still at its end, see [ShapeRecognizer]: the shape is a stroke of
 * the same brush, drawn through the points of the shape. On by default ([isEnabled]), a
 * preference of the user.
 *
 * While the pen stays down after the shape snapped, the shape follows it ([adjust]): it turns and
 * grows around its pivot (the start of a line, the middle of a closed shape) as the pen moves from
 * where it was held, until the pen is lifted.
 */
public class ShapeSnapper internal constructor(private val controller: DrawViewController) {

    /** Whether a stroke held still at its end becomes its shape (a preference of the user). */
    public var isEnabled: Boolean by mutableStateOf(true)
        private set

    public fun updateEnabled(enabled: Boolean) {
        if (isEnabled == enabled) return
        isEnabled = enabled
        controller.preferencesChanged()
    }

    internal fun applyPreferences(preferences: DrawPreferences) {
        isEnabled = preferences.isShapeRecognitionEnabled
    }

    /**
     * Turns the stroke in progress in [engine], drawn on the page [pageIndex] with [brush] by
     * [inputType], into its shape: the engine draws it again through the inputs of the shape.
     * Whether it looked like one (when not, the stroke is left as it is).
     */
    internal fun snap(engine: InkStrokeEngine, pageIndex: Int, brush: BrushConfig, inputType: InputToolType): Boolean {
        snapped = null
        if (!isEnabled) return false
        val drawn = engine.inputs.toList()
        if (drawn.isEmpty()) return false
        val tolerance = TOLERANCE_DP * controller.viewport.density / controller.viewport.pixelsPerMm(pageIndex).coerceAtLeast(0.01f)
        val shape = ShapeRecognizer.recognize(drawn.map { Point(it.x, it.y) }, tolerance) ?: return false
        draw(engine, shape, drawn, brush, inputType)
        val last = drawn.last()
        snapped = Snapped(shape, shape.pivot, Point(last.x, last.y), drawn, brush, inputType)
        return true
    }

    /** The shape that snapped, while the pen that drew it is down: what [adjust] turns and scales. */
    private class Snapped(
        val shape: Shape,
        val pivot: Point,
        val heldAt: Point,
        val drawn: List<StrokePoint>,
        val brush: BrushConfig,
        val inputType: InputToolType
    )

    private var snapped: Snapped? = null

    /**
     * The pen still down after the shape snapped is at [pen] (page millimeters): the shape drawn
     * again in [engine], turned and scaled around its pivot as the pen moved from where it was
     * held. Whether there was a shape to follow the pen.
     */
    internal fun adjust(engine: InkStrokeEngine, pen: Point): Boolean {
        val current = snapped ?: return false
        draw(engine, current.shape.followed(current.pivot, current.heldAt, pen), current.drawn, current.brush, current.inputType)
        return true
    }

    /** The pen that drew the shape was lifted (or the stroke cancelled): the shape stays as it is. */
    internal fun release() {
        snapped = null
    }

    private fun draw(engine: InkStrokeEngine, shape: Shape, drawn: List<StrokePoint>, brush: BrushConfig, inputType: InputToolType) {
        engine.startStroke(brush)
        for (input in shape.strokeInputs(drawn)) engine.addInput(input.x, input.y, input.pressure, input.timestampMs, inputType)
    }

    internal companion object {
        /** How far a hand drawing a shape may stray from it, on the screen. */
        const val TOLERANCE_DP = 4f

        /** How long the pen is held still at the end of a stroke for it to become its shape. */
        const val HOLD_MS = 500L

        /** How far the pen held still may wander, on the screen. */
        const val HOLD_SLOP_DP = 6f
    }
}
