package io.github.valerioisufi.drawview.latex

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.drawview.core.render.tile.LatexTileAdapter

internal class LatexTileAdapterImpl(
    private val engine: LatexEngine
) : LatexTileAdapter {

    override fun renderLatex(
        latex: String,
        fontSizePx: Float,
        color: Color
    ): ImageBitmap? {
        val clean = engine.sanitizeLatexString(latex)
        if (clean.isBlank()) return null
        return engine.renderToBitmap(clean, fontSizePx, color) ?: engine.generateErrorBitmap(clean, fontSizePx)
    }
}
