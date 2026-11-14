package io.github.valerioisufi.drawview.model.text

import io.github.valerioisufi.drawview.model.text.RichTextParser.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The items of a checklist: their box read, their content after it, their label (see `docs/checklists.md`). */
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
        for (text in listOf("- [x]y", "- [y] no", "- [] no", "- [  ] no", "1. [ ] numbered", "[ ] no marker")) {
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
}
