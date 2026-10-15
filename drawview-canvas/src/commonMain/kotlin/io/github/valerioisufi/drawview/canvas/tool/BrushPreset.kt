package io.github.valerioisufi.drawview.canvas.tool

import androidx.compose.ui.graphics.Color
import io.github.valerioisufi.drawview.model.geometry.Measure

/**
 * A user preset of a drawing tool (pen, highlighter, eraser, lasso).
 */
internal data class BrushPreset(
    val color: Int,
    val size: Measure
)
