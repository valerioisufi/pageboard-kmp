package io.github.valerioisufi.pageboard.canvas.render

import androidx.compose.ui.graphics.Canvas

/**
 * Draws [text] centered inside a shape box of [widthMm] × [heightMm] millimeters (or slightly
 * above the centerline when [isLine] is true), at [fontSizeMm] millimeters in [colorArgb].
 */
internal expect fun drawShapeLabel(
    canvas: Canvas,
    text: String,
    colorArgb: Int,
    fontSizeMm: Float,
    widthMm: Float,
    heightMm: Float,
    isLine: Boolean
)
