package io.github.valerioisufi.pageboard.canvas

/**
 * Encapsulates the UI theme colors required by the drawing engine and canvas.
 *
 * @property backgroundColor Background color of the "desk" (behind pages).
 * @property surfaceColor Default color of the paper sheet.
 * @property primaryColor Accent color for bounding boxes, lasso selection, etc.
 * @property onSurfaceColor Global text layout or secondary static graphic representation coloring.
 */
public data class PageboardColors(
    val backgroundColor: Int = 0xFFDFE2E6.toInt(),
    val surfaceColor: Int = 0xFFFCFCFF.toInt(),
    val primaryColor: Int = 0xFF00639B.toInt(),
    val onSurfaceColor: Int = 0xFF1A1C1E.toInt()
)
