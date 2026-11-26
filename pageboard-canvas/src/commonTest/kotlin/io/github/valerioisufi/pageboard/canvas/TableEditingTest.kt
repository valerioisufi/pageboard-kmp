package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.pageboard.canvas.text.TableEditing
import io.github.valerioisufi.pageboard.canvas.text.TableEditing.Action
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

    /** [text] with what is between [from] and [to] (the ends of the first of each) selected and replaced by [inserted], as the field does it. */
    private fun replacing(text: String, from: String, to: String, inserted: String): TextFieldValue? {
        val start = text.indexOf(from)
        val end = text.indexOf(to, start) + to.length
        val old = TextFieldValue(text, TextRange(start, end))
        val new = TextFieldValue(text.substring(0, start) + inserted + text.substring(end), TextRange(start + inserted.length))
        return TableEditing.keepInCells(old, new)
    }

    @Test
    fun aPasteOverASelectionInACellStaysInTheCell() {
        val text = "| abc | b |\n| --- | --- |\n| c | d |\n"
        assertEquals("| ax\\|y z^c | b |\n| --- | --- |\n| c | d |\n", shown(replacing(text, "b", "b", "x|y\r\nz")!!))
    }

    @Test
    fun aPasteAtTheCaretOfACellLeavesNoCarriageReturn() {
        val typed = TextFieldValue(table.replaceFirst("a", "ax\r\ny"), TextRange(table.indexOf('a') + 5))
        assertEquals("| ax y^ | b |", shown(TableEditing.keepInCells(at(table, table.indexOf('a') + 1), typed)!!).lines()[0])
    }

    @Test
    fun aSelectionAcrossTheCellsKeepsTheShapeOfTheTable() {
        assertEquals("| ^  |   |\n| --- | --- |\n|   |   |\n", shown(replacing(table, "a", "d", "")!!))
        assertEquals("| X^ |   |\n| --- | --- |\n|   | d |\n", shown(replacing(table, "a", "c", "X")!!))
        // Across the row of the delimiters
        assertEquals("| ^  |   |\n| --- | --- |\n| c | d |\n", shown(replacing(table, "a", "---", "")!!))
    }

    @Test
    fun aSelectionFromOutsideATableKeepsWhatItLeavesOfIt() {
        assertEquals("No^\n|   |   |\n| --- | --- |\n|   | d |\n", shown(replacing("Note\n$table", "te", "c", "")!!))
        assertEquals("| a | b |\n| --- | --- |\n| c | ^  |\nter", shown(replacing(table.trimEnd() + "\nafter", "d", "af", "")!!))
    }

    @Test
    fun aSelectionOutOfATableOrOfAWholeOneNeedsNothing() {
        assertNull(replacing("Some text\n$table", "Some", "text", "X"))
        assertNull(replacing("Note\n${table.trimEnd()}\nend", "| a", "| c | d |", ""))
    }

    @Test
    fun aDeletionOfSeveralCharactersStopsAtTheEndsOfTheCell() {
        val caret = table.indexOf('b') + 1
        val word = TextFieldValue(table.removeRange(2, caret), TextRange(2))
        assertEquals("| a | ^ |", shown(TableEditing.keepInCells(at(table, caret), word)!!).lines()[0])
        val forward = table.indexOf('a')
        val ahead = TextFieldValue(table.removeRange(forward, table.indexOf('b')), TextRange(forward))
        assertEquals("| ^ | b |", shown(TableEditing.keepInCells(at(table, forward), ahead)!!).lines()[0])
    }

    @Test
    fun aCellIsMergedWithTheNextOneAndSplitAgain() {
        val text = "| a | b | c |\n| --- | --- | --- |\n| one | two | three |\n"
        val merged = TableEditing.apply(at(text, text.indexOf("one") + 1), Action.MERGE_RIGHT)!!
        assertEquals("| a | b | c |\n| --- | --- | --- |\n| one two | < | three |", merged.text.trimEnd('\n'))
        // Tab goes past the cell merged
        val next = TableEditing.nextCell(merged, backwards = false)!!
        assertEquals(merged.text.indexOf("three") + 5, next.selection.start)
        val split = TableEditing.apply(at(merged.text, merged.text.indexOf("one") + 1), Action.SPLIT)!!
        assertEquals("| a | b | c |\n| --- | --- | --- |\n| one two |   | three |", split.text.trimEnd('\n'))
    }

    @Test
    fun theWidthsOfTheColumnsAreSetByHandAndKept() {
        val wider = TableEditing.apply(at(table, table.indexOf("b") + 1), Action.WIDER)!!
        assertEquals("| a | b |\n| ------ | -------- |\n| c | d |", wider.text.trimEnd('\n'))
        // Another change of the table keeps them
        val row = TableEditing.apply(at(wider.text, wider.text.indexOf("c") + 1), Action.ADD_ROW_BELOW)!!
        assertEquals("| ------ | -------- |", row.text.lines()[1])
        val aligned = TableEditing.apply(at(row.text, row.text.indexOf("d") + 1), Action.ALIGN_END)!!
        assertEquals("| ------ | -------: |", aligned.text.lines()[1])
        // Alike again: as wide as their content
        val back = TableEditing.apply(at(wider.text, wider.text.indexOf("b") + 1), Action.NARROWER)!!
        assertEquals("| --- | --- |", back.text.lines()[1])
    }
}
