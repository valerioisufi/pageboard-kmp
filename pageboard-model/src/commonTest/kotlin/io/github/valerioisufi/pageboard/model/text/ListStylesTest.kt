package io.github.valerioisufi.pageboard.model.text

import io.github.valerioisufi.pageboard.model.text.ListNumbering.Bullet
import io.github.valerioisufi.pageboard.model.text.ListNumbering.Numbering
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The numbers by level (`1.2.`), the numbers between parentheses (`(a)`) and the bullets chosen
 * (`docs/plans/checklists-and-lists.md`): their markers, their labels, and the notes written before
 * them left as they were.
 */
class ListStylesTest {

    private fun items(text: String) = ListNumbering.items(text, RichTextParser.parse(text))

    private fun labels(text: String) = items(text).map { it.label }

    @Test
    fun theMarkersOfTheNewStylesAreReadAndWrittenBack() {
        assertEquals(ListMarker.Read(3, NumberingStyle(Numbering.LOWER_ALPHA, ')', enclosed = true), 5), ListMarker.at("(c)  x"))
        assertEquals(ListMarker.Read(4, NumberingStyle(Numbering.LOWER_ROMAN, ')', enclosed = true), 6), ListMarker.at("(iv)  x"))
        assertEquals(ListMarker.Read(2, NumberingStyle(Numbering.DECIMAL, ')', enclosed = true), 5), ListMarker.at("(2)  x"))
        assertEquals(ListMarker.Read(3, NumberingStyle.OUTLINE, 8, listOf(1, 2)), ListMarker.at("1.2.3.  x"))
        assertEquals("(c)  ", ListMarker.write(NumberingStyle(Numbering.LOWER_ALPHA, ')', enclosed = true), 3))
        assertEquals("1.2.3.  ", ListMarker.write(NumberingStyle.OUTLINE, 3, listOf(1, 2)))
        assertTrue(ListMarker.canStart(NumberingStyle.OUTLINE, 4))
        assertTrue(ListMarker.canStart(NumberingStyle(Numbering.UPPER_ALPHA, ')', enclosed = true), 2))
        assertEquals(Bullet.DASH, ListMarker.bulletAt("–  x"))
        assertEquals("▪  ", ListMarker.writeBullet(Bullet.SQUARE))
        assertEquals("- ", ListMarker.writeBullet(null))
    }

    @Test
    fun whatWasTextStaysText() {
        // One space after them: as in the notes written before these styles
        for (text in listOf("(a) uno", "(1) due", "1.2. Introduzione", "1.2 Titolo", "• punto", "– trattino", "(a)x", "1.2.x")) {
            assertTrue(items(text).isEmpty(), text)
        }
        assertNull(ListMarker.bulletAt("• x"))
        assertNull(ListMarker.at("1.2. x"))
    }

    @Test
    fun theNumbersByLevelFollowTheNumbersTheyAreIn() {
        val text = "1.  a\n  1.1.  b\n  1.2.  c\n    1.2.1.  d\n2.  e\n  2.1.  f"
        assertEquals(listOf("1.", "1.1.", "1.2.", "1.2.1.", "2.", "2.1."), labels(text))
    }

    @Test
    fun theNumbersBetweenParenthesesCountInTheirLetters() {
        assertEquals(listOf("(a)", "(b)", "(c)"), labels("(a)  x\n(b)  y\n(c)  z"))
        assertEquals(listOf("(1)", "(2)"), labels("(1)  x\n(2)  y"))
        // Another style starts another list
        val mixed = items("(a)  x\na.  y")
        assertTrue(mixed[0].list != mixed[1].list)
    }

    @Test
    fun aBulletChosenIsTheBulletOfItsList() {
        assertEquals(listOf("–", "–", "◦"), labels("–  a\n–  b\n  - nested"))
        assertEquals(listOf(Bullet.DASH, Bullet.DASH, null), items("–  a\n–  b\n  - nested").map { it.chosenBullet })
        // With none chosen, the three bullets of the levels, as before
        assertEquals(listOf("•", "◦", "▪", "•"), labels("- a\n  - b\n    - c\n      - d"))
        // A bullet chosen other than the one of the list starts another
        val two = items("•  a\n▪  b")
        assertTrue(two[0].list != two[1].list)
    }

    @Test
    fun theNumbersOfTheParentsWrittenFollowThemAfterAnEdit() {
        // The first item taken away: the second becomes 1, its nested items 1.1 and 1.2
        val old = "1.  a\n2.  b\n  2.1.  c\n  2.2.  d"
        val new = "2.  b\n  2.1.  c\n  2.2.  d"
        val after = ListRenumbering.after(old, new)?.text
        assertEquals("1.  b\n  1.1.  c\n  1.2.  d", after)
        assertEquals(listOf("1.", "1.1.", "1.2."), labels(after!!))
    }
}
