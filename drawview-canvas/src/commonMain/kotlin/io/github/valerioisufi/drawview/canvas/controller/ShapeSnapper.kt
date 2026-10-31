package io.github.valerioisufi.drawview.canvas.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.valerioisufi.drawview.ink.InkStrokeEngine
import io.github.valerioisufi.drawview.model.BrushConfig
import io.github.valerioisufi.drawview.model.InputToolType
import io.github.valerioisufi.drawview.model.geometry.Point
import io.github.valerioisufi.drawview.model.shape.ShapeRecognizer
import io.github.valerioisufi.drawview.model.shape.strokeInputs

/**
 * Turns the stroke being drawn into the shape it looks like (a line, an arrow, a circle, a
 * box...) when the pen is held still at its end, see [ShapeRecognizer]: the shape is a stroke of
 * the same brush, drawn through the points of the shape. On by default ([isEnabled]), a
 * preference of the user.
 */
public class ShapeSnapper(private val controller: DrawViewController) {

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
        if (!isEnabled) return false
        val drawn = engine.inputs.toList()
        if (drawn.isEmpty()) return false
        val tolerance = TOLERANCE_DP * controller.viewport.density / controller.viewport.pixelsPerMm(pageIndex).coerceAtLeast(0.01f)
        val shape = ShapeRecognizer.recognize(drawn.map { Point(it.x, it.y) }, tolerance) ?: return false
        engine.startStroke(brush)
        for (input in shape.strokeInputs(drawn)) engine.addInput(input.x, input.y, input.pressure, input.timestampMs, inputType)
        return true
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
