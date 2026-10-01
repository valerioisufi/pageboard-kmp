package io.github.valerioisufi.drawview.core.render.tile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import io.github.valerioisufi.drawview.core.model.PageBackground
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Dedicated renderer for page backgrounds (solid colors and patterns: ruled, grid, dotted).
 * Renders into any Compose Canvas with exact millimeter-to-pixel scaling and viewport clipping.
 */
class PageBackgroundRenderer {

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

        canvas.save()
        canvas.clipRect(pageRect)
        canvas.translate(pageRect.left, pageRect.top)

        val visibleLeft = max(0f, -pageRect.left)
        val visibleRight = min(pageRect.width, targetWidth - pageRect.left)
        val visibleTop = max(0f, -pageRect.top)
        val visibleBottom = min(pageRect.height, targetHeight - pageRect.top)

        if (visibleLeft < visibleRight && visibleTop < visibleBottom) {
            when (background) {
                is PageBackground.Ruled -> {
                    val spacingPx = background.spacingMm * pixelsPerMm
                    if (spacingPx >= 3f) {
                        patternStrokePaint.strokeWidth = max(1f, background.thicknessMm * pixelsPerMm)
                        patternStrokePaint.color = Color(background.lineColor)

                        val startY = (floor(visibleTop / spacingPx) * spacingPx).coerceAtLeast(spacingPx)
                        var y = startY
                        val width = pageRect.width
                        while (y < visibleBottom) {
                            canvas.drawLine(Offset(0f, y), Offset(width, y), patternStrokePaint)
                            y += spacingPx
                        }
                    }
                }

                is PageBackground.Grid -> {
                    val spacingPx = background.spacingMm * pixelsPerMm
                    if (spacingPx >= 3f) {
                        patternStrokePaint.strokeWidth = max(1f, background.thicknessMm * pixelsPerMm)
                        patternStrokePaint.color = Color(background.lineColor)

                        val width = pageRect.width
                        val height = pageRect.height

                        // Horizontal grid lines
                        val startY = (floor(visibleTop / spacingPx) * spacingPx).coerceAtLeast(spacingPx)
                        var y = startY
                        while (y < visibleBottom) {
                            canvas.drawLine(Offset(0f, y), Offset(width, y), patternStrokePaint)
                            y += spacingPx
                        }

                        // Vertical grid lines
                        val startX = (floor(visibleLeft / spacingPx) * spacingPx).coerceAtLeast(spacingPx)
                        var x = startX
                        while (x < visibleRight) {
                            canvas.drawLine(Offset(x, 0f), Offset(x, height), patternStrokePaint)
                            x += spacingPx
                        }
                    }
                }

                is PageBackground.Dotted -> {
                    val spacingPx = background.spacingMm * pixelsPerMm
                    if (spacingPx >= 4f) {
                        val radiusPx = max(1f, background.dotRadiusMm * pixelsPerMm)
                        patternFillPaint.color = Color(background.dotColor)

                        val startX = (floor(visibleLeft / spacingPx) * spacingPx).coerceAtLeast(spacingPx)
                        val startY = (floor(visibleTop / spacingPx) * spacingPx).coerceAtLeast(spacingPx)

                        var x = startX
                        while (x < visibleRight) {
                            var y = startY
                            while (y < visibleBottom) {
                                canvas.drawCircle(Offset(x, y), radiusPx, patternFillPaint)
                                y += spacingPx
                            }
                            x += spacingPx
                        }
                    }
                }

                is PageBackground.Solid -> Unit
            }
        }

        canvas.restore()
    }
}
