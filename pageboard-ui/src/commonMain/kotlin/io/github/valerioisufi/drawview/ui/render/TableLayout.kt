package io.github.valerioisufi.drawview.ui.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.TextPainter
import androidx.compose.ui.text.font.FontWeight
import io.github.valerioisufi.drawview.model.TextAlignment
import io.github.valerioisufi.drawview.model.text.RichTextParser.Span
import io.github.valerioisufi.drawview.model.text.TableShape
import io.github.valerioisufi.drawview.ui.editor.Presentation
import io.github.valerioisufi.drawview.ui.editor.PresentedFormula
import io.github.valerioisufi.drawview.ui.editor.PresentedText
import io.github.valerioisufi.drawview.ui.editor.RichTextPresenter
import io.github.valerioisufi.drawview.ui.editor.formulaRect
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A table of a text laid out as a grid (see [TableShape]): its columns as wide as their content
 * when the line has room, else sharing the line (the narrow ones keeping their width, the others
 * wrapping their lines); the header in bold on a faint background; thin rules around the cells.
 * The cells are presented as the rest of the text (their formatting, their formulas).
 *
 * [columnEdges] and [rowEdges] are the lines of the rules from the top-left corner of the table,
 * [widthPx] x [heightPx] its size.
 */
internal class TableLayout<T>(
    val widthPx: Float,
    val heightPx: Float,
    val cells: List<TableCellLayout<T>>,
    val columnEdges: FloatArray,
    val rowEdges: FloatArray,
    val ruleWidthPx: Float,
    val ruleColor: Color,
    val headerColor: Color
) {
    private val headerPaint = Paint().apply { color = headerColor }
    private val rulePaint = Paint().apply { color = ruleColor }

    /** The rectangles of the background of the header and of the rules, from the top-left corner of the table. */
    fun backgrounds(): List<Pair<Rect, Color>> {
        val out = mutableListOf<Pair<Rect, Color>>()
        out += Rect(0f, 0f, widthPx, rowEdges[1]) to headerColor
        val half = ruleWidthPx / 2f
        for (y in rowEdges) out += Rect(0f, (y - half).coerceAtLeast(0f), widthPx, (y + half).coerceAtMost(heightPx)) to ruleColor
        for (x in columnEdges) out += Rect((x - half).coerceAtLeast(0f), 0f, (x + half).coerceAtMost(widthPx), heightPx) to ruleColor
        return out
    }

    /** Draws the table on [canvas], its top-left corner at [left], [top] (its formulas apart, see [formulas]). */
    fun draw(canvas: Canvas, left: Float, top: Float) {
        for ((rect, color) in backgrounds()) {
            canvas.drawRect(left + rect.left, top + rect.top, left + rect.right, top + rect.bottom, if (color == headerColor) headerPaint else rulePaint)
        }
        for (cell in cells) {
            canvas.save()
            canvas.translate(left + cell.left, top + cell.top)
            TextPainter.paint(canvas, cell.layout)
            canvas.restore()
        }
    }

    /** The formulas of the cells, where they are drawn from the top-left corner of the table. */
    fun formulas(): List<Pair<PresentedFormula<T>, Rect>> = cells.flatMap { cell ->
        cell.presented.formulas.map { it to cell.layout.formulaRect(it).translate(cell.left, cell.top) }
    }

    /** The cell under [point] (from the top-left corner of the table), or `null`. */
    fun cellAt(point: Offset): TableCellLayout<T>? {
        if (point.x < 0f || point.y < 0f || point.x > widthPx || point.y > heightPx) return null
        val column = (1 until columnEdges.size).firstOrNull { point.x <= columnEdges[it] }?.minus(1) ?: return null
        val row = (1 until rowEdges.size).firstOrNull { point.y <= rowEdges[it] }?.minus(1) ?: return null
        return cells.firstOrNull { it.row == row && it.column == column }
    }

    internal companion object {
        /** The margins of a cell, per pixel of the size of the font: on the sides, above and below. */
        private const val PADDING_SIDE = 0.45f
        private const val PADDING_TOP = 0.2f

        /** The thickness of the rules, per pixel of the size of the font (1 pixel at least). */
        private const val RULE = 0.05f

        /** The narrowest a column of text is made when the line has no room for all, per pixel of the size. */
        private const val MIN_COLUMN = 2.5f

        private const val RULE_ALPHA = 0.35f

        /**
         * The table [table] of [text] laid out with [p] (its cells measured by its
         * [Presentation.measureText]) on the width of its line, the cell of [reveal] showing the source
         * it touches; `null` when [p] measures nothing or has no width of line.
         */
        fun <T> layout(text: String, table: Span, p: Presentation<T>, reveal: TextRange? = null): TableLayout<T>? {
            val measure = p.measureText ?: return null
            val lineWidth = p.lineWidthPx ?: return null
            val shape = TableShape.of(text, table)
            val cellPresentation = p.copy(lineWidthPx = null, quotes = false)
            val padSide = PADDING_SIDE * p.fontSizePx
            val padTop = PADDING_TOP * p.fontSizePx
            val rule = max(1f, RULE * p.fontSizePx)
            val columns = shape.columns
            // Each cell presented, the header in bold
            val shown = shape.rows.mapIndexed { row, cells ->
                cells.map { range ->
                    // The selection in the cell reveals its source there (a formula being written)
                    val inCell = reveal?.takeIf { it.min >= range.first && it.max <= range.last + 1 }?.let { TextRange(it.start - range.first, it.end - range.first) }
                    val presented = RichTextPresenter.present(text.substring(range.first, range.last + 1), cellPresentation, inCell)
                    val styled = if (row == 0) bold(presented.text) else presented.text
                    Triple(range, presented, styled)
                }
            }
            // The columns as wide as their widest cell, sharing the line when it has no room for them
            val natural = FloatArray(columns)
            // The widest formula of each column: a formula does not wrap
            val least = FloatArray(columns) { MIN_COLUMN * p.fontSizePx }
            for (cells in shown) for ((column, cell) in cells.withIndex()) {
                for (formula in cell.second.formulas) least[column] = max(least[column], formula.widthPx + 1f)
                // A pixel more: laid out on its own width, a line may not fit it after the rounding
                natural[column] = max(natural[column], measure(cell.third, null, TextAlignment.START).size.width + 1f)
            }
            val chrome = columns * 2 * padSide + (columns + 1) * rule
            val inner = fitColumns(natural, lineWidth - chrome, least)
            val columnEdges = FloatArray(columns + 1)
            for (column in 0 until columns) columnEdges[column + 1] = columnEdges[column] + rule + 2 * padSide + inner[column]
            columnEdges[columns] += rule
            // The rows as high as their highest cell
            val laidOut = shown.mapIndexed { row, cells ->
                cells.mapIndexed { column, (range, presented, styled) ->
                    val alignment = shape.alignments[column] ?: TextAlignment.START
                    Triple(range, presented, measure(styled, inner[column], alignment)) to row
                }
            }
            val rowEdges = FloatArray(shape.rows.size + 1)
            val cells = mutableListOf<TableCellLayout<T>>()
            for ((row, rowCells) in laidOut.withIndex()) {
                val height = rowCells.maxOf { it.first.third.size.height }.toFloat() + 2 * padTop
                rowEdges[row + 1] = rowEdges[row] + rule + height
                for ((column, cell) in rowCells.withIndex()) {
                    val (range, presented, layout) = cell.first
                    cells += TableCellLayout(row, column, range, columnEdges[column] + rule + padSide, rowEdges[row] + rule + padTop, layout, presented)
                }
            }
            rowEdges[shape.rows.size] += rule
            val textColor = p.color
            val paper = Color(p.paperColor).takeIf { it.alpha > 0f }
            val ruleColor = textColor.copy(alpha = textColor.alpha * RULE_ALPHA).let { faint -> paper?.let { faint.compositeOver(it) } ?: faint }
            return TableLayout(
                widthPx = columnEdges[columns],
                heightPx = rowEdges[shape.rows.size],
                cells = cells,
                columnEdges = columnEdges,
                rowEdges = rowEdges,
                ruleWidthPx = rule,
                ruleColor = ruleColor,
                headerColor = CodeTheme.of(textColor, p.paperColor).background
            )
        }

        /**
         * The widths of columns of [natural] widths in [available]: all of it when they fit; else
         * the narrowest keep theirs and the others share what is left, none below its [least] (or
         * its natural width, when narrower). The columns may then take more than [available].
         */
        fun fitColumns(natural: FloatArray, available: Float, least: FloatArray): FloatArray {
            if (natural.sum() <= available) return natural.copyOf()
            val widths = FloatArray(natural.size)
            var left = available
            var count = natural.size
            for (column in natural.indices.sortedBy { natural[it] }) {
                val share = left / count
                widths[column] = max(min(natural[column], share), min(least[column], natural[column]))
                left -= widths[column]
                count--
            }
            return widths
        }

        /**
         * [text] of a cell measured by [measurer] in [style]: on [widthPx] aligned by [alignment], or
         * on one line (`null`).
         */
        fun measureCell(measurer: TextMeasurer, text: AnnotatedString, style: TextStyle, widthPx: Float?, alignment: TextAlignment): TextLayoutResult =
            measurer.measure(
                text,
                style.copy(textAlign = alignment.toTextAlign()),
                softWrap = widthPx != null,
                constraints = if (widthPx == null) Constraints() else Constraints.fixedWidth(ceil(widthPx).toInt().coerceAtLeast(1)),
                skipCache = true
            )

        private fun bold(text: AnnotatedString): AnnotatedString =
            AnnotatedString.Builder(text).apply { addStyle(SpanStyle(fontWeight = FontWeight.Bold), 0, text.length) }.toAnnotatedString()
    }
}

/**
 * A cell of a table laid out: its [row] and [column], the [source] range of its content in the
 * text, its [layout] drawn from [left], [top] of the table, the text it [presented].
 */
internal class TableCellLayout<T>(
    val row: Int,
    val column: Int,
    val source: IntRange,
    val left: Float,
    val top: Float,
    val layout: TextLayoutResult,
    val presented: PresentedText<T>
)
