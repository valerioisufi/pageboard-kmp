package io.github.valerioisufi.drawview.core.model

import androidx.compose.ui.graphics.Color
import io.github.valerioisufi.drawview.model.geometry.Measure

/**
 * A user preset of a drawing tool (pen, highlighter, eraser, lasso).
 */
data class BrushPreset(
    val color: Int,
    val size: Measure
)
