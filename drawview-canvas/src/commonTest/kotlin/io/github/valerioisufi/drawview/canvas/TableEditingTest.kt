package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.drawview.canvas.text.TableEditing
import io.github.valerioisufi.drawview.canvas.text.TableEditing.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The editing of the tables of a text: inserting them, moving from cell to cell, changing their shape. */
class TableEditingTest {

    private fun at(text: String, caret: Int) = TextFieldValue(text, TextRange(caret))

    /** [value] with its caret shown as `^`. */
    private fun shown(value: TextFieldValue) = value.text.substring(0, value.selection.start) + "^" + value.text.substring(value.selection.start)

    private val table = "| a | b |\n| --- | --- |\n| c | d |\n"

    @Test
    fun aTableIsInsertedOnItsLinesTheCaretInItsFirstCell() {
        assertEquals("Notes\n| ^  |   |\n| --- | --- |\n|   |   |\n|   |   |\n", shown(TableEditing.insert(at("Notes", 5), 3, 2)))
        assertEquals("| ^  |\n| --- |\n|   |\n", shown(TableEditing.insert(at("", 0), 2, 1)), "in place of an empty line, a line after it")
    }

    @Test
    fun tabGoesFromCellToCellAndAddsARowAtTheEnd() {
        assertEquals("| a | b^ |", shown(TableEditing.nextCell(at(table, 3), backwards = false)!!).lines()[0])
        assertEquals("| c^ | d |", shown(TableEditing.nextCell(at(table, 7), backwards = false)!!).lines()[2])
        assertEquals("| a^ | b |", shown(TableEditing.nextCell(at(table, 7), backwards = true)!!).lines()[0])
        val added = TableEditing.nextCell(at(table, table.indexOf('d') + 1), backwards = false)!!
        assertEquals("| a | b |\n| --- | --- |\n| c | d |\n| ^  |   |\n", shown(added))
        assertNull(TableEditing.nextCell(at("no table", 2), backwards = false))
    }

    @Test
    fun enterGoesToTheCellBelow() {
        assertEquals("| c | d^ |", shown(TableEditing.cellBelow(at(table, 7))!!).lines()[2])
    }

    @Test
    fun theArrowsMoveInAndOutOfTheCells() {
        val text = "intro\n" + table + "end"
        val a = text.indexOf('a')
        assertEquals(text.indexOf('b'), TableEditing.moveHorizontally(at(text, a + 1), left = false)!!.selection.start, "to the start of the next cell")
        assertEquals(a + 1, TableEditing.moveHorizontally(at(text, text.indexOf('b')), left = true)!!.selection.start, "to the end of the previous cell")
        assertEquals(5, TableEditing.moveVertically(at(text, a), up = true)!!.selection.start, "out of the table, on the line before")
        assertEquals(text.indexOf("end"), TableEditing.moveVertically(at(text, text.indexOf('c')), up = false)!!.selection.start, "on the line after")
        assertEquals(text.indexOf('c') + 1, TableEditing.moveVertically(at(text, a), up = false)!!.selection.start)
    }

    @Test
    fun theRowsAndColumnsAreAddedDeletedAndAligned() {
        val inB = at(table, table.indexOf('b'))
        assertEquals("| a | b | ^  |\n| --- | --- | --- |\n| c | d |   |\n", shown(TableEditing.apply(inB, Action.ADD_COLUMN_RIGHT)!!))
        assertEquals("| a^ |\n| --- |\n| c |\n", shown(TableEditing.apply(inB, Action.DELETE_COLUMN)!!))
        assertEquals("| a | b |\n| --- | --- |\n|   | ^  |\n| c | d |\n", shown(TableEditing.apply(inB, Action.ADD_ROW_BELOW)!!), "under the header")
        assertEquals("| a | b |\n| --- | :-: |\n| c | d |", TableEditing.apply(inB, Action.ALIGN_CENTER)!!.text.trimEnd())
        assertEquals("^", shown(TableEditing.apply(inB, Action.DELETE_TABLE)!!))
    }

    @Test
    fun deletingTheLastRowLeavesTheHeaderAndDeletingTheHeaderTheTable() {
        val text = "| a | b |\n| --- | --- |\n| c | d |\n| e | f |\nend"
        assertEquals("| a | b |\n| --- | --- |\n| c | d^ |\nend", shown(TableEditing.apply(at(text, text.indexOf('f')), Action.DELETE_ROW)!!))
        assertEquals("^end", shown(TableEditing.apply(at(text, text.indexOf('a')), Action.DELETE_ROW)!!))
    }

    @Test
    fun aPipeTypedInACellIsEscapedAndTheEdgesOfTheCellsHold() {
        val old = at(table, table.indexOf('a') + 1)
        val typed = TextFieldValue(table.replaceFirst("a ", "a| "), TextRange(old.selection.start + 1))
        assertEquals("| a\\|^ | b |", shown(TableEditing.keepInCells(old, typed)!!).lines()[0])
        // Backspace at the start of the cell b: to the end of the cell a, nothing deleted
        val atB = at(table, table.indexOf('b'))
        val backspace = TextFieldValue(table.removeRange(table.indexOf('b') - 1, table.indexOf('b')), TextRange(table.indexOf('b') - 1))
        assertEquals(TextFieldValue(table, TextRange(table.indexOf('a') + 1)), TableEditing.keepInCells(atB, backspace))
        // A line break of a soft keyboard: the cell below
        val newline = TextFieldValue(table.replaceFirst("a", "a\n"), TextRange(table.indexOf('a') + 2))
        assertEquals(table.indexOf('c') + 1, TableEditing.keepInCells(at(table, table.indexOf('a') + 1), newline)!!.selection.start)
    }
}
