package io.github.valerioisufi.pageboard.model.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The numbers of the source of the lists after an edit. */
class ListRenumberingTest {

    private val list = "1. a\n2. b\n3. c"

    private fun after(old: String, new: String) = ListRenumbering.after(old, new)?.text ?: new

    private fun labels(text: String) = ListNumbering.items(text, RichTextParser.parse(text)).map { it.label }

    @Test
    fun anItemTakenAwayMovesTheNumbersAfterIt() {
        assertEquals("1. a\n2. c", after(list, "1. a\n3. c"))
        assertEquals("1. a\n2. b", after(list, "1. a\n2. b"))
    }

    @Test
    fun theStartStaysWhenTheFirstItemGoes() {
        assertEquals("1. b\n2. c", after(list, "2. b\n3. c"))
        assertEquals(listOf("1.", "2."), labels(after(list, "2. b\n3. c")))
        assertEquals("5. b\n6. c", after("5. a\n6. b\n7. c", "6. b\n7. c"), "a list from 5 still starts from 5")
        assertEquals("10. b\n11. c", after("10. a\n11. b\n12. c", "11. b\n12. c"), "markers sharing their first digit")
        assertEquals("a\n1. b\n2. c", after(list, "a\n2. b\n3. c"), "the first marker taken away")
    }

    @Test
    fun aListCutByTextGoesOn() {
        assertEquals("1. a\nb\n2. c", after(list, "1. a\nb\n3. c"))
        assertEquals("1. a\n- b\n2. c", after(list, "1. a\n- b\n3. c"), "an item turned to a bullet")
    }

    @Test
    fun anItemMovedToALevelStartsFromOne() {
        assertEquals("1. a\n2. b\n  1. c", after(list, "1. a\n2. b\n  3. c"))
        assertEquals(listOf("1.", "2.", "a."), labels(after(list, "1. a\n2. b\n  3. c")))
        assertEquals("1. a\n  1. b\n2. c", after(list, "1. a\n  2. b\n3. c"))
        assertEquals("1. a\n2. b\n3. c", after("1. a\n  1. b\n2. c", "1. a\n1. b\n2. c"), "and back")
    }

    @Test
    fun anItemAddedMovesTheNumbersAfterIt() {
        assertEquals("1. a\n2. x\n3. b\n4. c", after(list, "1. a\n2. x\n2. b\n3. c"))
        assertEquals("1. x\n2. a\n3. b\n4. c", after(list, "1. x\n1. a\n2. b\n3. c"))
    }

    @Test
    fun aNewListKeepsTheNumberWrittenInIt() {
        assertNull(ListRenumbering.after("x", "5. x"))
        assertEquals("text\n\n3. x\n4. y", after("text", "text\n\n3. x\n9. y"))
    }

    @Test
    fun aListNotTouchedKeepsItsMarkers() {
        val untouched = "1. a\n1. b\n\nother\n\n1. x\n2. y"
        assertNull(ListRenumbering.after(untouched, untouched.replace("other", "others")))
        assertNull(ListRenumbering.after("1. a\n1. b", "1. a\n1. bc"), "typing in an item")
        assertEquals("1. a\n1. b\n\nother\n\n1. y", after(untouched, "1. a\n1. b\n\nother\n\n2. y"), "the other list stays")
    }

    @Test
    fun theListsOfEachLevelAreCountedOnTheirOwn() {
        val nested = "1. a\n  1. x\n  2. y\n2. b\n3. c"
        assertEquals("1. a\n  1. y\n2. b\n3. c", after(nested, "1. a\n  2. y\n2. b\n3. c"))
        assertEquals("1. a\n  1. x\n  2. y\n2. c", after(nested, "1. a\n  1. x\n  2. y\n3. c"))
    }

    @Test
    fun aNumberedListInBulletsIsCounted() {
        assertEquals("- a\n  1. y\n  2. z\n- b", after("- a\n  1. x\n  2. y\n  3. z\n- b", "- a\n  2. y\n  3. z\n- b"))
    }

    @Test
    fun theLinesOfAnItemFollowItsMarker() {
        val nine = (1..9).joinToString("\n") { "$it. i" }
        assertEquals("$nine\n10. j\n    more", after("$nine\n10. j", "$nine\n9. j\n   more"), "a marker growing")
        val ten = (1..9).joinToString("\n") { "$it. i" } + "\n10. j\n    more"
        val shorter = (1..8).joinToString("\n") { "$it. i" } + "\n9. j\n   more"
        assertEquals(shorter, after(ten, ten.removePrefix("1. i\n")), "a marker getting shorter")
        val code = (1..9).joinToString("\n") { "$it. i" } + "\n9. j\n   ```c\n   x\n   ```"
        val moved = after((1..9).joinToString("\n") { "$it. i" } + "\n10. j", code)
        assertEquals("10. j\n    ```c\n    x\n    ```", moved.substringAfter("9. i\n"), "a block of code under the item")
    }

    @Test
    fun theOffsetsFollowTheMarkers() {
        val old = (1..10).joinToString("\n") { "$it. i" }
        val new = old.replaceFirst("1. i\n", "")
        val result = ListRenumbering.after(old, new)!!
        val caret = new.indexOf("10. i") + 4
        assertEquals(result.text.lastIndexOf("9. i") + 3, result.map(caret))
    }

    @Test
    fun bulletsAndCodeAreLeftAlone() {
        assertNull(ListRenumbering.after("- a\n- b", "- b"))
        assertNull(ListRenumbering.after("```\n1. a\n2. b\n```", "```\n2. b\n```"))
    }

    @Test
    fun undoingAnEditBringsTheNumbersBack() {
        assertNull(ListRenumbering.after("1. b\n2. c", list))
        assertNull(ListRenumbering.after("5. b", "5. a\n6. b"))
    }

    @Test
    fun aListKeepsItsStyle() {
        val letters = "a.  x\nb.  y\nc.  z"
        assertEquals("a.  y\nb.  z", after(letters, "b.  y\nc.  z"), "the first item goes")
        assertEquals("a.  x\nb.  z", after(letters, "a.  x\nc.  z"))
        assertEquals("a.  x\nb.  y\nc.  n\nd.  z", after(letters, "a.  x\nb.  y\nc.  n\nc.  z"))
        assertEquals("i)  y\nii)  z", after("i)  x\nii)  y\niii)  z", "ii)  y\niii)  z"))
    }

    @Test
    fun anItemMovedTakesTheNumberingOfItsLevel() {
        assertEquals("A.  x\n  1. y", after("A.  x\nB.  y", "A.  x\n  B.  y"))
        assertEquals("A.  x\nB.  y", after("A.  x\n  1. y", "A.  x\n1. y"), "back in the list, in its style")
    }

    @Test
    fun aMarkerInAStyleMovesTheLinesOfItsItem() {
        val written = ListRenumbering.withMarkers("1. x\n   more", RichTextParser.parse("1. x\n   more").filter { it.kind == RichTextParser.Kind.LIST_ITEM }) { ListMarker.write(NumberingStyle(ListNumbering.Numbering.LOWER_ALPHA), it + 1) }
        assertEquals("a.  x\n    more", written.text)
    }
}
