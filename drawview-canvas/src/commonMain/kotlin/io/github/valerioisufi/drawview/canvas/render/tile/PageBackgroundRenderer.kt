package io.github.valerioisufi.drawview.canvas.render.tile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import io.github.valerioisufi.drawview.canvas.geometry.toModel
import io.github.valerioisufi.drawview.model.PageBackground
import kotlin.math.max
import kotlin.math.min

/**
 * Dedicated renderer for page backgrounds (solid colors and patterns: ruled, grid, dotted).
 * Renders into any Compose Canvas with exact millimeter-to-pixel scaling and viewport clipping.
 */
internal class PageBackgroundRenderer {

    private val bgPaint = Paint().apply {
        style = PaintingStyle.Fill
    }

    private val patternStrokePaint = Paint().apply {
        style = PaintingStyle.Stroke
        isAntiAlias = true
    }

    private val patternFillPaint = Paint().apply {
        style = PaintingStyle.Fill
        isAntiAlias = true
    }

    /**
     * Draws background and patterns into [canvas] for the given page bounds.
     *
     * @param canvas Target destination canvas.
     * @param pageRect On-canvas boundaries of the page.
     * @param background Styling configuration.
     * @param pageWidthMm Logical physical width of the page in millimeters.
     * @param targetWidth Tile/viewport width for clipping.
     * @param targetHeight Tile/viewport height for clipping.
     */
    fun drawBackground(
        canvas: Canvas,
        pageRect: Rect,
        background: PageBackground,
        pageWidthMm: Float,
        targetWidth: Float = pageRect.width,
        targetHeight: Float = pageRect.height
    ) {
        // 1. Fill base paper color
        bgPaint.color = Color(background.backgroundColor)
        canvas.drawRect(pageRect, bgPaint)

        if (background is PageBackground.Solid) return
        if (pageWidthMm <= 0f) return

        val pixelsPerMm = pageRect.width / pageWidthMm
        val pageHeightMm = pageRect.height / pixelsPerMm

        // Visible part of the page, in page millimeters
        val visible = Rect(
            max(0f, -pageRect.left) / pixelsPerMm,
            max(0f, -pageRect.top) / pixelsPerMm,
            min(pageRect.width, targetWidth - pageRect.left) / pixelsPerMm,
            min(pageRect.height, targetHeight - pageRect.top) / pixelsPerMm
        )
        if (visible.left >= visible.right || visible.top >= visible.bottom) return
        val pattern = background.pattern(pageWidthMm, pageHeightMm, visible.toModel()) ?: return
        // Too dense at this zoom level: not drawn
        val spacingPx = pattern.spacingMm * pixelsPerMm
        if (spacingPx < if (pattern.dots) 4f else 3f) return

        canvas.save()
        canvas.clipRect(pageRect)
        canvas.translate(pageRect.left, pageRect.top)

        if (pattern.dots) {
            val radiusPx = max(1f, pattern.sizeMm * pixelsPerMm)
            patternFillPaint.color = Color(pattern.color)
            for (x in pattern.columns) {
                for (y in pattern.rows) {
                    canvas.drawCircle(Offset(x * pixelsPerMm, y * pixelsPerMm), radiusPx, patternFillPaint)
                }
            }
        } else {
            patternStrokePaint.strokeWidth = max(1f, pattern.sizeMm * pixelsPerMm)
            patternStrokePaint.color = Color(pattern.color)
            for (y in pattern.rows) {
                canvas.drawLine(Offset(0f, y * pixelsPerMm), Offset(pageRect.width, y * pixelsPerMm), patternStrokePaint)
            }
            for (x in pattern.columns) {
                canvas.drawLine(Offset(x * pixelsPerMm, 0f), Offset(x * pixelsPerMm, pageRect.height), patternStrokePaint)
            }
        }

        canvas.restore()
    }
}
