package io.github.valerioisufi.pageboard.latex

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import io.github.valerioisufi.pageboard.platform.PageboardDesktop
import io.nano.tex.Graphics2D
import io.nano.tex.LaTeX
import io.nano.tex.LatexNativeLoader
import io.nano.tex.TeXRender
import java.io.File
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import io.github.valerioisufi.pageboard.canvas.render.tile.FormulaDrawing

internal actual object LatexPlatformEngine {

    private val lock = Any()

    actual fun init(resourcesDirectory: String): Boolean {
        synchronized(lock) {
            if (!LatexNativeLoader.isAvailable) return false
            val latex = LaTeX.instance()
            if (latex.isInitialized) return true
            return try {
                latex.init(resourcesDirectory)
                true
            } catch (e: Throwable) {
                System.err.println("LatexPlatformEngine: init failed: ${e.message}")
                false
            }
        }
    }

    actual fun isNativeReady(): Boolean {
        return try {
            LatexNativeLoader.isAvailable && LaTeX.instance().isInitialized
        } catch (_: Throwable) {
            false
        }
    }

    actual fun renderFormula(
        latex: String,
        fontSizePx: Float,
        colorArgb: Int,
        paddingPx: Float
    ): LatexResult? {
        if (!isNativeReady()) return null
        return try {
            synchronized(lock) {
                val size = fontSizePx.coerceAtLeast(1f)
                val render: TeXRender = LaTeX.instance().parse(latex, size, colorArgb)
                val pad = paddingPx.toInt()
                val w = (render.width + pad * 2).coerceAtLeast(1)
                val h = (render.height + pad * 2).coerceAtLeast(1)

                val skiaBmp = Bitmap().apply {
                    allocN32Pixels(w, h)
                }
                val skiaCanvas = Canvas(skiaBmp)
                skiaCanvas.clear(0) // Transparent

                val g2 = Graphics2D(skiaCanvas)
                render.draw(g2, pad, pad)
                skiaBmp.setImmutable()

                val baseline = render.baseline.coerceIn(0f, 1f)
                LatexResult(skiaBmp.asComposeImageBitmap(), baseline, w, h)
            }
        } catch (e: Throwable) {
            null
        }
    }

    actual fun drawing(latex: String, fontSizePx: Float, colorArgb: Int): FormulaDrawing? {
        if (!isNativeReady()) return null
        return synchronized(lock) {
            val render: TeXRender = LaTeX.instance().parse(latex, fontSizePx, colorArgb)
            FormulaDrawing(render.width, render.height, render.baseline.coerceIn(0f, 1f), render.ops())
        }
    }

    actual fun generateErrorBitmap(latex: String, fontSizePx: Float): ImageBitmap =
        SkiaLatexBadges.errorBadge(latex, fontSizePx)
}

internal actual fun texResourcesDirectory(): String =
    File(PageboardDesktop.dataDirectory, "tex").apply { mkdirs() }.absolutePath
