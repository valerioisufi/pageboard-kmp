package io.github.valerioisufi.drawview.core.model

import androidx.compose.ui.graphics.Color
import io.github.valerioisufi.drawview.core.geometry.Measure

/**
 * Brush used to model a stroke.
 *
 * @property size tip size in stroke units (page millimeters).
 * @property epsilon modelling tolerance in stroke units (page millimeters): smaller values
 * produce smoother meshes with more vertices.
 */
data class BrushConfig(
    val tool: StrokeTool = StrokeTool.PRESSURE_PEN,
    val size: Float = 12f,
    val color: Color = Color.Black,
    val epsilon: Float = 0.1f
)

/**
 * A user preset of a drawing tool (pen, highlighter, eraser, lasso).
 */
data class BrushPreset(
    val color: Color,
    val size: Measure
)
