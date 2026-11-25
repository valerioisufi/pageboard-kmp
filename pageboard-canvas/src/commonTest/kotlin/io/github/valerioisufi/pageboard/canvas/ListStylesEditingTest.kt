package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.pageboard.canvas.text.RichTextEditing
import io.github.valerioisufi.pageboard.model.text.ListNumbering.Bullet
import io.github.valerioisufi.pageboard.model.text.ListNumbering.Numbering
import io.github.valerioisufi.pageboard.model.text.NumberingStyle
import kotlin.test.Test
import kotlin.test.assertEquals

/** Choosing the numbers by level, the parentheses and the bullets, and writing on in them (`docs/plans/checklists-and-lists.md`). */
class ListStylesEditingTest {

    private fun value(text: String, caret: Int = text.length) = TextFieldValue(text, TextRange(caret))

    @Test
    fun theNumbersByLevelAreChosenForAListAndTheListsInIt() {
        val text = "1. a\n  1. b\n  2. c\n    1. d\n2. e"
        val made = RichTextEditing.setNumbering(value(text, 0), NumberingStyle.OUTLINE, 1)
        assertEquals("1.  a\n  1.1.  b\n  1.2.  c\n    1.2.1.  d\n2.  e", made.text)
    }

    @Test
    fun enterGoesOnInTheStyleOfTheItem() {
        assertEquals("  1.2.  b\n  1.3.  ", RichTextEditing.handleEnter(value("  1.2.  b"))!!.text)
        assertEquals("(b)  x\n(c)  ", RichTextEditing.handleEnter(value("(b)  x"))!!.text)
        assertEquals("–  x\n–  ", RichTextEditing.handleEnter(value("–  x"))!!.text)
    }

    @Test
    fun aBulletIsChosenForTheWholeListAndBackToItsLevel() {
        val text = "- a\n- b\n  - c"
        val dashed = RichTextEditing.setBullet(value(text, 0), Bullet.DASH)
        assertEquals("–  a\n–  b\n  - c", dashed.text)
        assertEquals(Bullet.DASH, RichTextEditing.bulletOf(dashed))
        assertEquals("- a\n- b\n  - c", RichTextEditing.setBullet(dashed, null).text)
        // A line not in a list becomes one
        assertEquals("▪  milk", RichTextEditing.setBullet(value("milk"), Bullet.SQUARE).text)
    }

    @Test
    fun theParenthesesAreAStyleOfTheNumbering() {
        val made = RichTextEditing.setNumbering(value("1. a\n2. b", 0), NumberingStyle(Numbering.LOWER_ALPHA, ')', enclosed = true), 1)
        assertEquals("(a)  a\n(b)  b", made.text)
    }
}
