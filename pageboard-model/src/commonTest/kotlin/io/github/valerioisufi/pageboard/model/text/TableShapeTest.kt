package io.github.valerioisufi.pageboard.model.text

import io.github.valerioisufi.pageboard.model.TextAlignment
import io.github.valerioisufi.pageboard.model.text.RichTextParser.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The tables of the texts (GitHub's notation): where they are, their cells, their alignments. */
class TableShapeTest {

    private fun tables(text: String) = RichTextParser.parse(text).filter { it.kind == Kind.TABLE }

    private fun cells(text: String): List<List<String>> {
        val table = tables(text).single()
        return TableShape.of(text, table).rows.map { row -> row.map { text.substring(it.first, it.last + 1) } }
    }

    @Test
    fun aTableIsAHeaderADelimiterRowAndItsRows() {
        val text = "Before\n| a | b |\n|---|:-:|\n| 1 | 2 |\n| 3 | 4 |\n\nAfter"
        val table = tables(text).single()
        assertEquals("| a | b |\n|---|:-:|\n| 1 | 2 |\n| 3 | 4 |", text.substring(table.start, table.end))
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2"), listOf("3", "4")), cells(text))
    }

    @Test
    fun theOuterPipesAreOptional() {
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), cells("a | b\n--- | ---\n1 | 2"))
    }

    @Test
    fun theAlignmentsAreWrittenInTheDelimiterRow() {
        val text = "| a | b | c | d |\n|---|:--|:-:|--:|\n| 1 | 2 | 3 | 4 |"
        assertEquals(listOf(null, TextAlignment.START, TextAlignment.CENTER, TextAlignment.END), TableShape.of(text, tables(text).single()).alignments)
    }

    @Test
    fun theRowsHaveTheCellsOfTheHeader() {
        assertEquals(listOf(listOf("a", "b"), listOf("1", ""), listOf("3", "4")), cells("| a | b |\n|---|---|\n| 1 |\n| 3 | 4 | 5 |"))
    }

    @Test
    fun aPipeInCodeOrEscapedIsText() {
        assertEquals(listOf(listOf("a", "b"), listOf("`x | y`", "c \\| d")), cells("| a | b |\n|---|---|\n| `x | y` | c \\| d |"))
    }

    @Test
    fun notATableWithoutItsDelimiterRow() {
        assertTrue(tables("a | b\nc | d").isEmpty())
        assertTrue(tables("| a | b |\n|---|").isEmpty(), "a delimiter row of other cells")
        assertTrue(tables("```\n| a |\n|---|\n```").isEmpty(), "in a block of code")
    }

    @Test
    fun theFormattingInTheCellsIsParsed() {
        val text = "| **a** | \$x\$ |\n|---|---|"
        val spans = RichTextParser.parse(text)
        assertTrue(spans.any { it.kind == Kind.BOLD } && spans.any { it.kind == Kind.FORMULA })
    }

    @Test
    fun theCellOfAnOffset() {
        val text = "| a | bb |\n|---|---|\n| c |  |"
        val shape = TableShape.of(text, tables(text).single())
        assertEquals(0 to 1, shape.cellAt(text.indexOf("bb") + 2))
        assertEquals(1 to 0, shape.cellAt(text.indexOf('c')))
        assertEquals(1 to 1, shape.cellAt(shape.rows[1][1].first), "an empty cell")
    }
}
