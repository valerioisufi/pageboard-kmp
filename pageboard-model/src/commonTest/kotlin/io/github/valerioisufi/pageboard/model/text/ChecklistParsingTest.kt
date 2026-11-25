package io.github.valerioisufi.pageboard.model.text

import io.github.valerioisufi.pageboard.model.text.RichTextParser.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The items of a checklist: their box read, their content after it, their label (see `docs/plans/checklists.md`). */
class ChecklistParsingTest {

    private fun items(text: String) = RichTextParser.parse(text).filter { it.kind == Kind.LIST_ITEM }

    @Test
    fun aBoxAfterTheMarkerOfABulletedItemIsReadAndLeftOutOfItsContent() {
        val text = "- [ ] buy milk\n- [x] call Anna\n* [X] done too\n- plain"
        val spans = items(text)
        assertEquals(listOf(false, true, true, null), spans.map { it.checked })
        assertEquals(listOf("buy milk", "call Anna", "done too", "plain"), spans.map { text.substring(it.contentStart, it.contentEnd) })
    }

    @Test
    fun anEmptyItemOfAChecklistHasItsBox() {
        val text = "- [ ]"
        val item = items(text).single()
        assertEquals(false, item.checked)
        assertEquals(text.length, item.contentStart)
    }

    @Test
    fun whatIsNotABoxStaysText() {
        for (text in listOf("- [x]y", "- [y] no", "- [] no", "- [  ] no", "1. [y] no", "[ ] no marker")) {
            val item = items(text).singleOrNull()
            assertNull(item?.checked, text)
        }
        assertEquals("[x]y", "- [x]y".let { t -> items(t).single().let { t.substring(it.contentStart, it.contentEnd) } })
    }

    @Test
    fun theLabelsOfAChecklistAreItsBoxes() {
        val text = "- [ ] a\n- [x] b\n  - nested\n- c"
        val items = ListNumbering.items(text, RichTextParser.parse(text))
        assertEquals(listOf("☐", "☑", "◦", "•"), items.map { it.label })
        assertEquals(listOf(false, true, null, null), items.map { it.checked })
        assertNull(items[0].bullet)
        assertEquals(1, items.map { it.list }.toSet().size - 1, "the boxes and the bullets of a level are one list, the nested item another")
    }

    @Test
    fun anItemOfAChecklistTakesItsContinuationLinesAtItsText() {
        val text = "- [ ] one\n      more\n- [x] two"
        val spans = items(text)
        assertEquals("one\n      more", text.substring(spans[0].contentStart, spans[0].contentEnd))
    }

    @Test
    fun theBoxesAreSymbolsOfTheFont() {
        assertEquals(true, TextSymbols.isAt("☐", 0))
        assertEquals(true, TextSymbols.isAt("☑", 0))
    }

    @Test
    fun aBoxAfterTheNumberOfAnItemMakesANumberedChecklist() {
        val text = "1. [ ] first\n2. [x] second\n3. plain"
        val spans = items(text)
        assertEquals(listOf(false, true, null), spans.map { it.checked })
        assertEquals(listOf(true, true, true), spans.map { it.ordered })
        assertEquals(listOf("first", "second", "plain"), spans.map { text.substring(it.contentStart, it.contentEnd) })
        val labels = ListNumbering.items(text, RichTextParser.parse(text)).map { it.label }
        assertEquals(listOf("1. ☐", "2. ☑", "3."), labels)
    }

    @Test
    fun theItemsDoneAreCountedInTheChecklistOfTheCaret() {
        val text = "- [x] a\n- [ ] b\n- [x] c\n\ntext\n\n1. [ ] d\n2. [ ] e"
        assertEquals(2 to 5, ListNumbering.checklistCounts(text))
        assertEquals(2 to 3, ListNumbering.checklistCountsAt(text, 0))
        assertEquals(0 to 2, ListNumbering.checklistCountsAt(text, text.length))
        // Out of a checklist: all of them
        assertEquals(2 to 5, ListNumbering.checklistCountsAt(text, text.indexOf("text")))
        assertEquals(0 to 0, ListNumbering.checklistCountsAt("- a\n- b", 0))
    }
}
