package io.github.valerioisufi.pageboard.ui.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.pageboard.ui.editor.FontRoom
import io.github.valerioisufi.pageboard.ui.editor.MarkdownTextUtil
import io.github.valerioisufi.pageboard.ui.editor.Presentation
import io.github.valerioisufi.pageboard.ui.editor.RichTextPresenter
import io.github.valerioisufi.pageboard.ui.editor.Struts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The tables of the texts laid out as grids: their columns, their cells, their formulas. */
class TableLayoutTest {

    private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr, cacheSize = 0)
    private val struts = Struts(null, null, FontFamily.SansSerif, FontRoom.of(measurer, FontFamily.SansSerif))
    private val style = TextStyle(fontSize = 20.sp, fontFamily = FontFamily.SansSerif, letterSpacing = 0.sp)

    /** [text] presented on a line of [lineWidth], its formulas 4 px a character of their source, its table laid out. */
    private fun table(text: String, lineWidth: Float = 500f): TableLayout<String> {
        val p = Presentation(
            fontSizePx = 20f, color = Color.Black, struts = struts, lineWidthPx = lineWidth,
            measureText = { t, w, a -> TableLayout.measureCell(measurer, t, style, w, a) }
        ) { latex, _ -> MarkdownTextUtil.RenderedFormula("f", latex.length * 4f, 40f, 0.6f) }
        val shown = RichTextPresenter.present(text, p)
        return shown.tables.single().layout
    }

    @Test
    fun theColumnsAreAsWideAsTheirContentWhenTheLineHasRoom() {
        val layout = table("| a | bb |\n|---|---|\n| ccc | d |")
        assertTrue(layout.widthPx < 200f, "narrower than the line")
        val widths = (1 until layout.columnEdges.size).map { layout.columnEdges[it] - layout.columnEdges[it - 1] }
        assertTrue(widths[0] > widths[1] - 5f)
    }

    @Test
    fun theColumnsShareALineTooNarrowTheLongCellsWrapping() {
        val layout = table("| a | b |\n|---|---|\n| short | " + "word ".repeat(40).trim() + " |", lineWidth = 300f)
        assertTrue(layout.widthPx <= 300.5f)
        val long = layout.cells.single { it.row == 1 && it.column == 1 }
        assertTrue(long.layout.lineCount > 1, "the long cell wraps")
        assertEquals(1, layout.cells.single { it.row == 1 && it.column == 0 }.layout.lineCount, "the short one keeps its line")
    }

    @Test
    fun aFormulaKeepsItsWidthInItsCell() {
        val layout = table("| a | b |\n|---|---|\n| \$" + "x".repeat(40) + "\$ | " + "word ".repeat(40).trim() + " |", lineWidth = 300f)
        val (formula, rect) = layout.formulas().single()
        val cell = layout.cells.single { it.row == 1 && it.column == 0 }
        assertEquals(cell.left, rect.left, 0.5f, "at the start of its cell")
        assertTrue(rect.right <= layout.columnEdges[1], "inside its column")
        assertEquals(formula.widthPx, rect.width, 0.01f)
    }

    @Test
    fun theHeaderIsBoldAndTheColumnsAligned() {
        val layout = table("| a | b |\n|---|--:|\n| 1 | 2 |")
        val header = layout.cells.first()
        assertTrue(header.layout.layoutInput.text.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        val right = layout.cells.single { it.row == 1 && it.column == 1 }
        assertTrue(right.layout.getLineLeft(0) > 0f || right.layout.size.width.toFloat() <= right.layout.getLineRight(0) + 1f, "at the end of its column")
        assertEquals(right, layout.cellAt(Offset(right.left + 1f, right.top + 1f)))
    }

    @Test
    fun theWidthsSetByHandShareTheLineInTheirProportions() {
        val layout = table("| a | b |\n|---|------|\n| c | d |", lineWidth = 400f)
        val widths = (1 until layout.columnEdges.size).map { layout.columnEdges[it] - layout.columnEdges[it - 1] }
        assertTrue(widths[1] > widths[0] * 1.5f, "the second twice as wide, the margins apart: $widths")
        assertTrue(layout.widthPx > 390f, "the whole line: ${layout.widthPx}")
    }

    @Test
    fun aMergedCellTakesTheColumnsItSpansAndNoRuleCrossesIt() {
        val layout = table("| a | b |\n|---|---|\n| wide | < |\n| c | d |")
        val wide = layout.cells.single { it.row == 1 }
        assertEquals(2, wide.span)
        assertEquals(layout.cells.size, 5, "the cell merged is not laid out")
        val inner = layout.columnEdges[1]
        val rules = layout.backgrounds().map { it.first }.filter { it.left < inner && it.right > inner && it.width < it.height }
        assertTrue(rules.none { it.top >= layout.rowEdges[1] - 0.5f && it.bottom <= layout.rowEdges[2] + 0.5f }, "no rule across the merged cell")
        assertEquals(wide, layout.cellAt(androidx.compose.ui.geometry.Offset(inner + 5f, layout.rowEdges[1] + 5f)))
    }
}
