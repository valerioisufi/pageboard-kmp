package io.github.valerioisufi.drawview.latex

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Result of rendering a LaTeX formula.
 *
 * @property bitmap The rendered raster bitmap with transparent background.
 * @property baselineRatio Sub-pixel vertical baseline ratio (0..1) for text alignment.
 * @property width Width of formula in pixels.
 * @property height Height of formula in pixels.
 */
data class LatexResult(
    val bitmap: ImageBitmap,
    val baselineRatio: Float,
    val width: Int,
    val height: Int
)
