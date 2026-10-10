package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import io.github.valerioisufi.drawview.canvas.geometry.toCompose
import io.github.valerioisufi.drawview.model.TextMarkupKind
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import io.github.valerioisufi.drawview.model.geometry.Rect
import kotlin.math.max

/**
 * Draws the highlights, underlines and strikeouts of the text of the PDFs.
 */
object TextMarkupPainter {
    /**
     * Draws the lines [rects] (points of the PDF page) marked as [kind] with [color], mapping
     * them to [canvas] through [pointsToCanvas].
     *
     * A highlight multiplies the colors below it, like a highlighter on paper: the text stays
     * dark and the paper takes the color.
     */
    fun draw(canvas: Canvas, rects: List<Rect>, kind: TextMarkupKind, color: Color, pointsToCanvas: Matrix2D, paint: Paint = Paint()) {
        paint.color = color
        paint.isAntiAlias = true
        paint.blendMode = if (kind == TextMarkupKind.HIGHLIGHT) BlendMode.Multiply else BlendMode.SrcOver
        for (rect in rects) {
            canvas.drawRect(pointsToCanvas.mapRect(markRect(rect, kind)).toCompose(), paint)
        }
        paint.blendMode = BlendMode.SrcOver
    }

    /**
     * Part of the line [line] (points) covered by a mark of [kind]: the whole line for a
     * highlight, a stroke under the baseline for an underline, through the lowercase letters
     * for a strikeout. The boxes of the lines go from the ascent to the descent of the font.
     */
    fun markRect(line: Rect, kind: TextMarkupKind): Rect {
        val height = line.height
        val thickness = max(height * 0.065f, 0.5f)
        return when (kind) {
            TextMarkupKind.HIGHLIGHT -> line
            TextMarkupKind.UNDERLINE -> centeredLine(line, line.top + height * 0.9f, thickness)
            TextMarkupKind.STRIKEOUT -> centeredLine(line, line.top + height * 0.58f, thickness)
        }
    }

    private fun centeredLine(line: Rect, centerY: Float, thickness: Float) =
        Rect(line.left, centerY - thickness / 2f, line.right, centerY + thickness / 2f)
}
