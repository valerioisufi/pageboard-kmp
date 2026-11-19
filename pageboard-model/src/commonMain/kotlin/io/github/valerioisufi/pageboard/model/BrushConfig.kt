package io.github.valerioisufi.pageboard.model

import io.github.valerioisufi.pageboard.model.geometry.Measure

/**
 * Brush used to model a stroke.
 *
 * @property size tip size in stroke units (page millimeters).
 * @property epsilon modelling tolerance in stroke units (page millimeters): smaller values
 * produce smoother meshes with more vertices.
 */
public data class BrushConfig(
    val tool: StrokeTool = StrokeTool.PRESSURE_PEN,
    val size: Float = 12f,
    val color: Int = 0xFF000000.toInt(),
    val epsilon: Float = 0.1f
)

/**
 * The brush a stroke drawn with [tool], [size] and [color] is modeled again with (loaded, or
 * received from another device): a fine modelling tolerance, as in the original app.
 */
public fun remodelingBrush(tool: StrokeTool, size: Float, color: Int): BrushConfig =
    BrushConfig(tool = tool, size = size, color = color, epsilon = (size * 0.01f).coerceIn(0.005f, 0.05f))
