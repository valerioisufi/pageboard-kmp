package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.geometry.toCompose

/**
 * Live layer of the text of the PDFs: the passage being marked with the tool, the selected text
 * with its handles and the selected mark.
 */
internal object TextMarkupOverlay {
    private val markingPaint = Paint()

    fun draw(scope: DrawScope, controller: DrawViewController, primary: Color) = with(scope) {
        val manager = controller.textMarkupManager

        manager.marking?.takeIf { it.isShown }?.let { marking ->
            val range = marking.range
            manager.pointsToScreen(range.page, range.pointsToPageMm())?.let { toScreen ->
                drawIntoCanvas { canvas ->
                    TextMarkupPainter.draw(canvas, range.rects, marking.kind, Color(marking.color), toScreen, markingPaint)
                }
            }
        }

        manager.selectedMarkup?.let { selected ->
            val toScreen = manager.pointsToScreen(selected.page, selected.markup.pointsToPageMm(selected.page)) ?: return@let
            val inset = 2f * density
            for (rect in selected.markup.rects) {
                val r = toScreen.mapRect(rect).inflate(inset).toCompose()
                drawRect(primary.copy(alpha = 0.12f), topLeft = r.topLeft, size = r.size)
                drawRect(primary, topLeft = r.topLeft, size = r.size, style = Stroke(width = 1.5f * density))
            }
        }

        manager.textSelection?.let { selection ->
            val toScreen = manager.pointsToScreen(selection.page, selection.pointsToPageMm()) ?: return@let
            val lines = selection.rects.map { toScreen.mapRect(it).toCompose() }
            if (lines.isEmpty()) return@let
            for (line in lines) {
                drawRect(primary.copy(alpha = 0.25f), topLeft = line.topLeft, size = line.size)
            }
            val (start, end) = manager.handleKnobs() ?: return@let
            val radius = manager.handleKnobRadiusPx
            val stem = 2f * density
            drawLine(primary, Offset(lines.first().left, lines.first().top), start, strokeWidth = stem)
            drawLine(primary, Offset(lines.last().right, lines.last().top), end, strokeWidth = stem)
            drawCircle(primary, radius = radius, center = start)
            drawCircle(primary, radius = radius, center = end)
        }
    }
}
