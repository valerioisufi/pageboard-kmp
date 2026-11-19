package io.github.valerioisufi.pageboard.latex

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.RRect

/**
 * Error badge shown in place of formulas that cannot be parsed.
 */
internal object SkiaLatexBadges {
    fun errorBadge(latex: String, fontSizePx: Float): ImageBitmap {
        val size = fontSizePx.coerceIn(12f, 60f)
        val text = "⚠ [LaTeX: $latex]"
        val tf = FontMgr.default.matchFamilyStyle(null, FontStyle.BOLD)
        val font = org.jetbrains.skia.Font(tf, size)
        val textBounds = font.measureText(text)
        val pad = 12f
        val w = (textBounds.width + pad * 2).toInt().coerceAtLeast(10)
        val h = (textBounds.height + pad * 2).toInt().coerceAtLeast(10)

        val bmp = Bitmap().apply { allocN32Pixels(w, h) }
        val canvas = Canvas(bmp)
        canvas.clear(0)

        val bgPaint = Paint().apply {
            color = 0x22D32F2F
            mode = PaintMode.FILL
        }
        val borderPaint = Paint().apply {
            color = 0x80D32F2F.toInt()
            mode = PaintMode.STROKE
            strokeWidth = 2f
        }
        val rrect = RRect.makeXYWH(1f, 1f, (w - 2).toFloat(), (h - 2).toFloat(), 8f, 8f)
        canvas.drawRRect(rrect, bgPaint)
        canvas.drawRRect(rrect, borderPaint)

        val textPaint = Paint().apply {
            color = 0xFFD32F2F.toInt()
            mode = PaintMode.FILL
        }
        canvas.drawString(text, pad, pad - textBounds.top, font, textPaint)
        bmp.setImmutable()
        return bmp.asComposeImageBitmap()
    }
}
