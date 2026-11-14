package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.drawview.canvas.text.RichTextEditing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Writing checklists and choosing the level of a heading (see `docs/checklists.md`). */
class ChecklistEditingTest {

    private fun value(text: String, caret: Int = text.length) = TextFieldValue(text, TextRange(caret))

    @Test
    fun linesBecomeAChecklistAndBackToText() {
        val text = "milk\n- eggs\n1. bread\n# shop"
        val made = RichTextEditing.toggleChecklist(TextFieldValue(text, TextRange(0, text.length)))
        assertEquals("- [ ] milk\n- [ ] eggs\n- [ ] bread\n- [ ] shop", made.text)
        assertTrue(RichTextEditing.inChecklist(made))
        val back = RichTextEditing.toggleChecklist(made)
        assertEquals("milk\neggs\nbread\nshop", back.text)
        assertFalse(RichTextEditing.inChecklist(back))
    }

    @Test
    fun theCaretFollowsTheBoxAdded() {
        val made = RichTextEditing.toggleChecklist(value("milk", 2))
        assertEquals("- [ ] milk", made.text)
        assertEquals(TextRange(8), made.selection)
    }

    @Test
    fun anItemIsCheckedAndUnchecked() {
        val text = "- [ ] a\n- [x] b"
        val checked = RichTextEditing.toggleChecked(value(text, 3))
        assertEquals("- [x] a\n- [x] b", checked.text)
        assertEquals(TextRange(3), checked.selection)
        assertEquals("- [ ] a\n- [x] b", RichTextEditing.toggleChecked(checked).text)
        // A selection with one not checked: all checked; all checked: all unchecked
        val all = RichTextEditing.toggleChecked(TextFieldValue(text, TextRange(0, text.length)))
        assertEquals("- [x] a\n- [x] b", all.text)
        assertEquals("- [ ] a\n- [ ] b", RichTextEditing.toggleChecked(all).text)
        // Out of a checklist: nothing
        assertEquals("- a", RichTextEditing.toggleChecked(value("- a")).text)
    }

    @Test
    fun aTapOnABoxTogglesThatItem() {
        val text = "- [ ] a\n- [x] b"
        assertEquals("- [ ] a\n- [ ] b", RichTextEditing.toggleCheckedAt(value(text, 0), 8).text)
        assertEquals("- [x] a\n- [x] b", RichTextEditing.toggleCheckedAt(value(text, 0), 0).text)
        assertEquals(text, RichTextEditing.toggleCheckedAt(value(text, 0), 3).text, "not the start of an item")
    }

    @Test
    fun enterGoesOnWithAnEmptyBoxAndLeavesOnAnEmptyItem() {
        val next = RichTextEditing.handleEnter(value("- [x] milk"))!!
        assertEquals("- [x] milk\n- [ ] ", next.text)
        assertEquals(TextRange(next.text.length), next.selection)
        val out = RichTextEditing.handleEnter(next)!!
        assertEquals("- [x] milk\n", out.text)
        // Nested: one level out
        val nested = RichTextEditing.handleEnter(value("- [ ] a\n  - [ ] "))!!
        assertEquals("- [ ] a\n- ", nested.text)
    }

    @Test
    fun theLevelOfAHeadingIsChosen() {
        val line = value("Title", 2)
        assertEquals(0, RichTextEditing.headingLevelOf(line))
        val two = RichTextEditing.setHeading(line, 2)
        assertEquals("## Title", two.text)
        assertEquals(TextRange(5), two.selection)
        assertEquals(2, RichTextEditing.headingLevelOf(two))
        val one = RichTextEditing.setHeading(two, 1)
        assertEquals("# Title", one.text)
        assertEquals("Title", RichTextEditing.setHeading(one, 0).text)
        // In a quote: nothing
        assertEquals("> q", RichTextEditing.setHeading(value("> q"), 2).text)
    }

    @Test
    fun aNumberedItemKeepsBracketsAsText() {
        assertNull(RichTextEditing.handleEnter(value("[ ] text")))
    }
}
