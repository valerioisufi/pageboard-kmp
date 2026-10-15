package io.github.valerioisufi.drawview.latex

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.valerioisufi.drawview.platform.DrawViewAndroid
import io.nano.tex.Graphics2D
import io.nano.tex.LaTeX
import java.io.File

internal actual object LatexPlatformEngine {

    private val lock = Any()

    actual fun init(resourcesDirectory: String): Boolean = synchronized(lock) {
        if (!LaTeX.isLibraryAvailable) return false
        val latex = LaTeX.instance()
        if (latex.isInitialized) return true
        try {
            latex.init(resourcesDirectory)
            true
        } catch (e: Throwable) {
            android.util.Log.e("LatexPlatformEngine", "init failed", e)
            false
        }
    }

    actual fun isNativeReady(): Boolean = LaTeX.isLibraryAvailable && LaTeX.instance().isInitialized

    actual fun renderFormula(latex: String, fontSizePx: Float, colorArgb: Int, paddingPx: Float): LatexResult? {
        if (!isNativeReady()) return null
        return try {
            synchronized(lock) {
                val render = LaTeX.instance().parse(latex, fontSizePx.coerceAtLeast(1f), colorArgb)
                try {
                    val pad = paddingPx.toInt()
                    val w = (render.width + pad * 2).coerceAtLeast(1)
                    val h = (render.height + pad * 2).coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    render.draw(Graphics2D(Canvas(bitmap)), pad, pad)
                    LatexResult(bitmap.asImageBitmap(), render.baseline.coerceIn(0f, 1f), w, h)
                } finally {
                    render.release()
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    actual fun drawing(latex: String, fontSizePx: Float, colorArgb: Int): TexDrawing? {
        if (!isNativeReady()) return null
        return synchronized(lock) {
            val render = LaTeX.instance().parse(latex, fontSizePx, colorArgb)
            try {
                TexDrawing(render.width, render.height, render.baseline.coerceIn(0f, 1f), render.ops())
            } finally {
                render.release()
            }
        }
    }

    actual fun generateErrorBitmap(latex: String, fontSizePx: Float): ImageBitmap {
        val size = fontSizePx.coerceIn(12f, 60f)
        val text = "⚠ [LaTeX: $latex]"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFD32F2F.toInt()
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
        }
        val bounds = android.graphics.Rect()
        textPaint.getTextBounds(text, 0, text.length, bounds)
        val pad = 12f
        val w = (bounds.width() + pad * 2).toInt().coerceAtLeast(10)
        val h = (bounds.height() + pad * 2).toInt().coerceAtLeast(10)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rect = RectF(1f, 1f, w - 1f, h - 1f)
        canvas.drawRoundRect(rect, 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22D32F2F })
        canvas.drawRoundRect(rect, 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x80D32F2F.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f
        })
        canvas.drawText(text, pad - bounds.left, pad - bounds.top, textPaint)
        return bitmap.asImageBitmap()
    }
}

internal actual fun texResourcesDirectory(): String =
    File(DrawViewAndroid.context.filesDir, "tex").apply { mkdirs() }.absolutePath
