package io.github.valerioisufi.drawview.core.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.PageBackground

/**
 * Renders physical pages, backgrounds (ruled, grid, dotted), and borders/shadows on Compose Canvas.
 */
object PageRenderer {

    /**
     * Renders a page paper sheet, shadow, and background pattern.
     *
     * @param scope Compose Canvas DrawScope
     * @param page Target page domain model
     * @param screenRect Current screen-space boundary of the page
     */
    fun drawPage(
        scope: DrawScope,
        page: DrawPage,
        screenRect: Rect
    ) {
        with(scope) {
            // 1. Subtle drop shadow
            drawRect(
                color = Color(0x18000000),
                topLeft = Offset(screenRect.left + 4f, screenRect.top + 4f),
                size = Size(screenRect.width, screenRect.height)
            )

            // 2. Paper background fill
            val bg = page.background
            drawRect(
                color = Color(bg.backgroundColor),
                topLeft = screenRect.topLeft,
                size = screenRect.size,
                style = Fill
            )

            // 3. Background Pattern
            drawBackgroundPattern(this, page, screenRect, bg)

            // 4. Page Border
            drawRect(
                color = Color(0x22000000),
                topLeft = screenRect.topLeft,
                size = screenRect.size,
                style = Stroke(width = 1f)
            )
        }
    }

    private fun drawBackgroundPattern(
        scope: DrawScope,
        page: DrawPage,
        screenRect: Rect,
        bg: PageBackground
    ) {
        if (bg is PageBackground.Solid) return

        val scaleX = screenRect.width / page.widthMm
        val scaleY = screenRect.height / page.heightMm

        when (bg) {
            is PageBackground.Ruled -> {
                val spacingPx = bg.spacingMm * scaleY
                val thicknessPx = (bg.thicknessMm * scaleY).coerceAtLeast(1f)
                val lineColor = Color(bg.lineColor)

                var y = screenRect.top + spacingPx
                while (y < screenRect.bottom) {
                    scope.drawLine(
                        color = lineColor,
                        start = Offset(screenRect.left, y),
                        end = Offset(screenRect.right, y),
                        strokeWidth = thicknessPx
                    )
                    y += spacingPx
                }
            }

            is PageBackground.Grid -> {
                val spacingPxX = bg.spacingMm * scaleX
                val spacingPxY = bg.spacingMm * scaleY
                val thicknessPx = (bg.thicknessMm * scaleY).coerceAtLeast(1f)
                val lineColor = Color(bg.lineColor)

                // Vertical lines
                var x = screenRect.left + spacingPxX
                while (x < screenRect.right) {
                    scope.drawLine(
                        color = lineColor,
                        start = Offset(x, screenRect.top),
                        end = Offset(x, screenRect.bottom),
                        strokeWidth = thicknessPx
                    )
                    x += spacingPxX
                }

                // Horizontal lines
                var y = screenRect.top + spacingPxY
                while (y < screenRect.bottom) {
                    scope.drawLine(
                        color = lineColor,
                        start = Offset(screenRect.left, y),
                        end = Offset(screenRect.right, y),
                        strokeWidth = thicknessPx
                    )
                    y += spacingPxY
                }
            }

            is PageBackground.Dotted -> {
                val spacingPxX = bg.spacingMm * scaleX
                val spacingPxY = bg.spacingMm * scaleY
                val dotRadiusPx = (bg.dotRadiusMm * scaleX).coerceAtLeast(1f)
                val dotColor = Color(bg.dotColor)

                var y = screenRect.top + spacingPxY
                while (y < screenRect.bottom) {
                    var x = screenRect.left + spacingPxX
                    while (x < screenRect.right) {
                        scope.drawCircle(
                            color = dotColor,
                            radius = dotRadiusPx,
                            center = Offset(x, y)
                        )
                        x += spacingPxX
                    }
                    y += spacingPxY
                }
            }

            is PageBackground.Solid -> Unit
        }
    }
}
