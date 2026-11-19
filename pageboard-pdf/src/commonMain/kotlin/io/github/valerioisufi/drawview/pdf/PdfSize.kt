package io.github.valerioisufi.drawview.pdf

/**
 * Dimensions of a PDF page in points (1/72 inch) and millimeters.
 */
public data class PdfSize(
    val widthPt: Double,
    val heightPt: Double
) {
    val widthMm: Float
        get() = (widthPt * (25.4 / 72.0)).toFloat()

    val heightMm: Float
        get() = (heightPt * (25.4 / 72.0)).toFloat()

    val aspectRatio: Float
        get() = if (heightPt > 0.0) (widthPt / heightPt).toFloat() else 1f
}
