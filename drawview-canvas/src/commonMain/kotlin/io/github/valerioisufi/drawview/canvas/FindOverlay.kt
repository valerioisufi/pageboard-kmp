package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.geometry.toCompose

/**
 * Live layer of the search in the document (see `DocumentFind`): every place found on the pages on
 * screen marked like a highlighter, the current one stronger and outlined.
 */
internal object FindOverlay {
    private val FOUND = Color(0xFFFFE082)
    private val CURRENT = Color(0xFFFFB74D)
    private val OUTLINE = Color(0xFFE65100)

    fun draw(scope: DrawScope, controller: DrawViewController) = with(scope) {
        val find = controller.find
        if (!find.isOpen || find.matches.isEmpty()) return@with
        val pages = controller.viewport.visiblePages().mapTo(HashSet()) { it.index }
        val current = find.matches.getOrNull(find.current)
        val inset = 1.5f * density
        for (match in find.matches) {
            val pageIndex = controller.document.pages.indexOfFirst { it.id == match.pageId }.takeIf { it >= 0 } ?: continue
            if (pageIndex !in pages) continue
            val mmToScreen = controller.viewport.pageMmToScreen(pageIndex) ?: continue
            val isCurrent = match === current
            for (rect in match.rects) {
                val r = mmToScreen.mapRect(rect).toCompose().inflate(inset)
                val corner = CornerRadius(2f * density)
                drawRoundRect(if (isCurrent) CURRENT else FOUND, r.topLeft, r.size, corner, blendMode = BlendMode.Multiply)
                if (isCurrent) drawRoundRect(OUTLINE, r.topLeft, r.size, corner, style = Stroke(width = 1.5f * density))
            }
        }
    }
}
