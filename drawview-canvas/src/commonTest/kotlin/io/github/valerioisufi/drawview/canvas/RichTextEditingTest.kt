package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.drawview.canvas.text.RichTextEditing
import io.github.valerioisufi.drawview.model.text.ListNumbering
import io.github.valerioisufi.drawview.model.text.NumberingStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The formatting of a text being written, as changes of its notation.
 */
class RichTextEditingTest {

    @Test
    fun wrapsTheSelectionWithDelimiters() {
        val original = TextFieldValue(text = "Hello World", selection = TextRange(6, 11))
        val wrapped = RichTextEditing.toggle(original, RichTextEditing.Format.BOLD)
        assertEquals("Hello **World**", wrapped.text)
        assertEquals(TextRange(8, 13), wrapped.selection)
    }

    @Test
    fun unwrapsWhenTheSelectionIsAlreadyWrapped() {
        val original = TextFieldValue(text = "Hello **World**", selection = TextRange(8, 13))
        val unwrapped = RichTextEditing.toggle(original, RichTextEditing.Format.BOLD)
        assertEquals("Hello World", unwrapped.text)
    }

    @Test
    fun insertsDelimitersAtTheCursor() {
        val original = TextFieldValue(text = "E = mc", selection = TextRange(6))
        val result = RichTextEditing.toggle(original, RichTextEditing.Format.FORMULA)
        assertEquals("E = mc$$", result.text)
        assertEquals(TextRange(7), result.selection)
    }

    @Test
    fun aHeadingStartsItsLineWhereverTheCaretIs() {
        val original = TextFieldValue(text = "Intro\nTitle here\nEnd", selection = TextRange(12))
        val heading = RichTextEditing.toggleHeading(original)
        assertEquals("Intro\n# Title here\nEnd", heading.text)
        assertEquals(TextRange(14), heading.selection, "the caret stays on its character")

        val plain = RichTextEditing.toggleHeading(heading)
        assertEquals(original, plain)

        val subtitle = RichTextEditing.toggleHeading(TextFieldValue("##  Sub", TextRange(1)))
        assertEquals("Sub", subtitle.text)
        assertEquals(TextRange(0), subtitle.selection)
    }

    @Test
    fun aFormatWrapsTheSelectionInItsDelimiters() {
        val value = TextFieldValue("area pi r^2", TextRange(5, 11))
        assertEquals("area \$pi r^2\$", RichTextEditing.toggle(value, RichTextEditing.Format.FORMULA).text)
        assertEquals("area ~~pi r^2~~", RichTextEditing.toggle(value, RichTextEditing.Format.STRIKETHROUGH).text)
    }

    @Test
    fun toggleBulletListOnSingleLineAddsAndRemovesBullet() {
        val plain = TextFieldValue("Hello World", TextRange(6))
        val bullet = RichTextEditing.toggleBulletList(plain)
        assertEquals("- Hello World", bullet.text)
        assertEquals(TextRange(8), bullet.selection, "the caret shifts with the added prefix")

        val reverted = RichTextEditing.toggleBulletList(bullet)
        assertEquals("Hello World", reverted.text)
        assertEquals(TextRange(6), reverted.selection, "the caret shifts back when prefix is removed")
    }

    @Test
    fun toggleNumberedListOnSingleLineAddsAndRemovesNumber() {
        val plain = TextFieldValue("First item", TextRange(2))
        val numbered = RichTextEditing.toggleNumberedList(plain)
        assertEquals("1. First item", numbered.text)
        assertEquals(TextRange(5), numbered.selection)

        val reverted = RichTextEditing.toggleNumberedList(numbered)
        assertEquals("First item", reverted.text)
        assertEquals(TextRange(2), reverted.selection)
    }

    @Test
    fun toggleListConvertsBetweenBulletAndNumbered() {
        val bullet = TextFieldValue("- Item", TextRange(3))
        val numbered = RichTextEditing.toggleNumberedList(bullet)
        assertEquals("1. Item", numbered.text)

        val backToBullet = RichTextEditing.toggleBulletList(numbered)
        assertEquals("- Item", backToBullet.text)
    }

    @Test
    fun toggleListsOnMultilineSelection() {
        val lines = "Apple\nBanana\nCherry"
        val value = TextFieldValue(lines, TextRange(0, lines.length))
        val bulleted = RichTextEditing.toggleBulletList(value)
        assertEquals("- Apple\n- Banana\n- Cherry", bulleted.text)

        val numbered = RichTextEditing.toggleNumberedList(bulleted)
        assertEquals("1. Apple\n2. Banana\n3. Cherry", numbered.text)

        val plain = RichTextEditing.toggleNumberedList(numbered)
        assertEquals("Apple\nBanana\nCherry", plain.text)
    }

    @Test
    fun toggleListPreservesIndentation() {
        val indented = TextFieldValue("  Subitem", TextRange(4))
        val bulleted = RichTextEditing.toggleBulletList(indented)
        assertEquals("  - Subitem", bulleted.text)
        assertEquals(TextRange(6), bulleted.selection)

        val plain = RichTextEditing.toggleBulletList(bulleted)
        assertEquals("  Subitem", plain.text)
        assertEquals(TextRange(4), plain.selection)
    }

    @Test
    fun handleEnterContinuesBulletList() {
        val dash = TextFieldValue("- Item 1", TextRange(8))
        val continuedDash = RichTextEditing.handleEnter(dash)
        assertEquals("- Item 1\n- ", continuedDash?.text)
        assertEquals(TextRange(11), continuedDash?.selection)

        val star = TextFieldValue("  * Subitem", TextRange(11))
        val continuedStar = RichTextEditing.handleEnter(star)
        assertEquals("  * Subitem\n  * ", continuedStar?.text)
        assertEquals(TextRange(16), continuedStar?.selection)

        val plus = TextFieldValue("+ Plus", TextRange(6))
        val continuedPlus = RichTextEditing.handleEnter(plus)
        assertEquals("+ Plus\n+ ", continuedPlus?.text)
        assertEquals(TextRange(9), continuedPlus?.selection)
    }

    @Test
    fun handleEnterContinuesNumberedList() {
        val single = TextFieldValue("1. First", TextRange(8))
        val continued = RichTextEditing.handleEnter(single)
        assertEquals("1. First\n2. ", continued?.text)
        assertEquals(TextRange(12), continued?.selection)

        val nine = TextFieldValue("9. Item 9", TextRange(9))
        val continuedNine = RichTextEditing.handleEnter(nine)
        assertEquals("9. Item 9\n10. ", continuedNine?.text)
        assertEquals(TextRange(14), continuedNine?.selection)

        val indented = TextFieldValue("  3. Nested", TextRange(11))
        val continuedIndented = RichTextEditing.handleEnter(indented)
        assertEquals("  3. Nested\n  4. ", continuedIndented?.text)
        assertEquals(TextRange(17), continuedIndented?.selection)
    }

    @Test
    fun handleEnterSplitsItemWithContentAfterCursor() {
        val item = TextFieldValue("- Hello world", TextRange(7))
        val split = RichTextEditing.handleEnter(item)
        assertEquals("- Hello\n- world", split?.text)
        assertEquals(TextRange(10), split?.selection)
    }

    @Test
    fun handleEnterExitsListOnEmptyItem() {
        val emptyBullet = TextFieldValue("- ", TextRange(2))
        val exitBullet = RichTextEditing.handleEnter(emptyBullet)
        assertEquals("", exitBullet?.text)
        assertEquals(TextRange(0), exitBullet?.selection)

        val inMiddle = TextFieldValue("Line 1\n- \nLine 3", TextRange(9))
        val exitMiddle = RichTextEditing.handleEnter(inMiddle)
        assertEquals("Line 1\n\nLine 3", exitMiddle?.text)
        assertEquals(TextRange(7), exitMiddle?.selection)

        val emptyNumbered = TextFieldValue("  1. ", TextRange(5))
        val exitNumbered = RichTextEditing.handleEnter(emptyNumbered)
        assertEquals("", exitNumbered?.text)
        assertEquals(TextRange(0), exitNumbered?.selection)
    }

    @Test
    fun handleEnterReturnsNullOnNonListItems() {
        val plain = TextFieldValue("Just plain text", TextRange(15))
        assertEquals(null, RichTextEditing.handleEnter(plain))

        val heading = TextFieldValue("# Heading", TextRange(9))
        assertEquals(null, RichTextEditing.handleEnter(heading))

        val notAList = TextFieldValue("-Not a list", TextRange(11))
        assertEquals(null, RichTextEditing.handleEnter(notAList))
    }

    @Test
    fun indentAddsTwoSpacesToLines() {
        val single = TextFieldValue("Item", TextRange(4))
        val indented = RichTextEditing.indent(single)
        assertEquals("  Item", indented.text)
        assertEquals(TextRange(6), indented.selection)

        val multi = TextFieldValue("A\nB", TextRange(0, 3))
        val multiIndented = RichTextEditing.indent(multi)
        assertEquals("  A\n  B", multiIndented.text)
        assertEquals(TextRange(2, 7), multiIndented.selection)
    }

    @Test
    fun outdentRemovesUpToTwoSpacesFromLines() {
        val twoSpaces = TextFieldValue("  - Item", TextRange(8))
        val outdented = RichTextEditing.outdent(twoSpaces)
        assertEquals("- Item", outdented.text)
        assertEquals(TextRange(6), outdented.selection)

        val oneSpace = TextFieldValue(" - Item", TextRange(7))
        assertEquals("- Item", RichTextEditing.outdent(oneSpace).text)

        val zeroSpaces = TextFieldValue("- Item", TextRange(6))
        assertEquals("- Item", RichTextEditing.outdent(zeroSpaces).text)

        val multi = TextFieldValue("  A\n    B", TextRange(2, 9))
        val multiOutdented = RichTextEditing.outdent(multi)
        assertEquals("A\n  B", multiOutdented.text)
        assertEquals(TextRange(0, 5), multiOutdented.selection)
    }

    @Test
    fun handleSmartNewlineDetectsSingleNewlineInsertion() {
        val oldVal = TextFieldValue("- Item", TextRange(6))
        val newVal = TextFieldValue("- Item\n", TextRange(7))
        val smart = RichTextEditing.handleSmartNewline(oldVal, newVal)
        assertEquals("- Item\n- ", smart?.text)
        assertEquals(TextRange(9), smart?.selection)

        val emptyOld = TextFieldValue("- ", TextRange(2))
        val emptyNew = TextFieldValue("- \n", TextRange(3))
        val smartExit = RichTextEditing.handleSmartNewline(emptyOld, emptyNew)
        assertEquals("", smartExit?.text)
        assertEquals(TextRange(0), smartExit?.selection)

        val charInput = TextFieldValue("- Itemx", TextRange(7))
        assertEquals(null, RichTextEditing.handleSmartNewline(oldVal, charInput))
    }

    private fun at(text: String, caret: Int) = TextFieldValue(text, TextRange(caret))

    @Test
    fun enterInABlockOfCodeKeepsTheIndentationAndContinuesNoList() {
        val text = "```\n    - item\n```"
        val caret = text.indexOf("item") + 4
        val result = RichTextEditing.handleEnter(at(text, caret))!!
        assertEquals("```\n    - item\n    \n```", result.text)
        assertEquals(TextRange(caret + 5), result.selection)

        // From the keyboard of a phone too
        val typed = TextFieldValue(text.substring(0, caret) + "\n" + text.substring(caret), TextRange(caret + 1))
        assertEquals(result.text, RichTextEditing.handleSmartNewline(at(text, caret), typed)!!.text)
    }

    @Test
    fun enterAfterAFenceNotClosedClosesIt() {
        val alone = "Intro\n```kotlin"
        val closed = RichTextEditing.handleEnter(at(alone, alone.length))!!
        assertEquals("Intro\n```kotlin\n\n```", closed.text)
        assertEquals(TextRange(alone.length + 1), closed.selection, "the caret in the block")

        val before = "~~~~\nold text"
        assertEquals("~~~~\n\n~~~~\nold text", RichTextEditing.handleEnter(at(before, 4))!!.text, "the text after is out of the block")
        assertEquals(null, RichTextEditing.handleEnter(at("```\na\n```", 3)), "a block closed: a plain line break")
    }

    @Test
    fun tabInABlockOfCodeGoesToTheNextColumnOfFour() {
        val text = "```\nab\n\tx\n```"
        val ab = RichTextEditing.indent(at(text, text.indexOf("ab") + 2))
        assertEquals("```\nab  \n\tx\n```", ab.text)
        assertEquals(TextRange(text.indexOf("ab") + 4), ab.selection)
        val x = RichTextEditing.indent(at(text, text.indexOf("x") + 1))
        assertEquals("```\nab\n\tx   \n```", x.text, "after a tab, four columns then one")

        val lines = RichTextEditing.indent(TextFieldValue(text, TextRange(text.indexOf("ab"), text.indexOf("x"))))
        assertEquals("```\n    ab\n    \tx\n```", lines.text, "the lines selected by four spaces")
        val back = RichTextEditing.outdent(TextFieldValue("```\n      ab\n\tx\n```", TextRange(4, 16)))
        assertEquals("```\n  ab\nx\n```", back.text, "up to four spaces or a tab")

        assertEquals("- a\n  - item", RichTextEditing.indent(at("- a\n- item", 4)).text, "a list still by two")
    }

    @Test
    fun theFormatsDoNotApplyInABlockOfCode() {
        val text = "```\nval a = 1\n```"
        val inside = TextFieldValue(text, TextRange(text.indexOf("a ="), text.indexOf("a =") + 1))
        assertEquals(text, RichTextEditing.toggle(inside, RichTextEditing.Format.BOLD).text)
        assertEquals(text, RichTextEditing.toggleHeading(inside).text)
        assertEquals(text, RichTextEditing.toggleBulletList(inside).text)
        assertTrue(RichTextEditing.inCodeBlock(inside))
        assertFalse(RichTextEditing.inCodeBlock(at(text + "\nafter", text.length + 3)))
    }

    @Test
    fun theLinesOfTheSelectionGoInABlockAndComeOut() {
        val text = "Intro\nx = 1\ny = 2\nEnd"
        val selection = TextRange(text.indexOf("= 1"), text.indexOf("= 2"))
        val fenced = RichTextEditing.toggleCodeBlock(TextFieldValue(text, selection))
        assertEquals("Intro\n```\nx = 1\ny = 2\n```\nEnd", fenced.text, "whole lines")
        assertEquals(TextRange(selection.start + 4, selection.end + 4), fenced.selection)

        val unfenced = RichTextEditing.toggleCodeBlock(fenced)
        assertEquals(text, unfenced.text)
        assertEquals(selection, unfenced.selection)

        val empty = RichTextEditing.toggleCodeBlock(at("", 0))
        assertEquals("```\n\n```", empty.text)
        assertEquals(TextRange(4), empty.selection, "the caret on the line of the code")

        assertEquals("````\na ``` b\n````", RichTextEditing.toggleCodeBlock(at("a ``` b", 0)).text, "a fence longer than the backticks of the code")
        assertEquals("```\na\nb\n```", RichTextEditing.toggle(TextFieldValue("a\nb", TextRange(0, 3)), RichTextEditing.Format.CODE).text, "code over several lines")
    }

    @Test
    fun theLineNumbersOfABlockAreAskedAndTakenOff() {
        val text = "```kotlin\nval a = 1\n```\nafter"
        val caret = text.indexOf("a = 1")
        val numbered = RichTextEditing.toggleLineNumbers(at(text, caret))
        assertEquals("```kotlin showLineNumbers\nval a = 1\n```\nafter", numbered.text)
        assertEquals(TextRange(caret + 16), numbered.selection, "the caret on the same character")
        val plain = RichTextEditing.toggleLineNumbers(numbered)
        assertEquals(text, plain.text)
        assertEquals(TextRange(caret), plain.selection)

        assertEquals("```showLineNumbers\nx\n```", RichTextEditing.toggleLineNumbers(at("```\nx\n```", 4)).text, "without a language")
        assertEquals("``` {.python}\nx\n```", RichTextEditing.toggleLineNumbers(at("``` {.python .numberLines}\nx\n```", 30)).text, "the attributes of Pandoc")
        assertEquals("plain text", RichTextEditing.toggleLineNumbers(at("plain text", 3)).text, "nothing outside a block")
    }

    @Test
    fun theCaretStaysOutOfTheMarkersOfTheListItems() {
        val text = "intro\n  - item"
        val content = text.indexOf("item")
        fun moved(from: Int, to: Int) = RichTextEditing.keepCaretOutOfMarkers(TextFieldValue(text, TextRange(from)), TextFieldValue(text, TextRange(to)))?.selection
        assertEquals(TextRange(text.indexOf('\n')), moved(content, content - 1), "backwards from the text: to the end of the line before")
        assertEquals(TextRange(content), moved(0, text.indexOf('-')), "anywhere else in the marker: to the text")
        assertEquals(TextRange(content), moved(text.length, text.indexOf('\n') + 1), "the start of its line is in the marker")
        assertEquals(null, moved(0, content), "already out")
        val first = "- a"
        assertEquals(TextRange(2), RichTextEditing.keepCaretOutOfMarkers(TextFieldValue(first, TextRange(2)), TextFieldValue(first, TextRange(1)))?.selection, "no line before: stays at the text")
    }

    @Test
    fun backspaceAtTheStartOfAnItemTakesItsMarkerAway() {
        val text = "intro\n  1. item"
        val content = text.indexOf("item")
        val deleted = TextFieldValue(text.removeRange(content - 1, content), TextRange(content - 1))
        val result = RichTextEditing.handleConcealedDeletion(TextFieldValue(text, TextRange(content)), deleted)
        assertEquals(TextFieldValue("intro\nitem", TextRange(6)), result)
        // Any other deletion is a deletion
        val elsewhere = TextFieldValue(text.removeRange(content, content + 1), TextRange(content))
        assertEquals(null, RichTextEditing.handleConcealedDeletion(TextFieldValue(text, TextRange(content + 1)), elsewhere))
    }

    @Test
    fun backspaceAtTheStartOfTheCodeOfABlockShowsItsFence() {
        val text = "```kotlin\nval x = 1\n```"
        val content = text.indexOf("val")
        val deleted = TextFieldValue(text.removeRange(content - 1, content), TextRange(content - 1))
        assertEquals(TextFieldValue(text, TextRange(content - 1)), RichTextEditing.handleConcealedDeletion(TextFieldValue(text, TextRange(content)), deleted))
    }

    @Test
    fun shiftEnterBreaksTheLineInsideTheItem() {
        val broken = RichTextEditing.lineBreakInItem(at("- first item", 7))!!
        assertEquals("- first\n  item", broken.text, "the next line at the text of the item, the space dropped")
        assertEquals(TextRange(10), broken.selection)
        assertEquals("1. one\n   ", RichTextEditing.lineBreakInItem(at("1. one", 6))!!.text, "at the text of a numbered item")
        assertEquals("- a\n  b\n  ", RichTextEditing.lineBreakInItem(at("- a\n  b", 7))!!.text, "again on a continuation line")
        assertEquals(null, RichTextEditing.lineBreakInItem(at("plain", 2)), "a plain line break out of the lists")
    }

    @Test
    fun enterOnALineBreakOfAnItemStartsTheNextItem() {
        val next = RichTextEditing.handleEnter(at("- a\n  more", 10))!!
        assertEquals("- a\n  more\n- ", next.text)
        assertEquals(TextRange(13), next.selection)
        assertEquals("1. a\n   b\n2. c", RichTextEditing.handleEnter(at("1. a\n   b c", 9))!!.text, "the text after the caret in the next item")
        assertEquals("- a\n- b", RichTextEditing.handleEnter(at("- a\n  b", 6))!!.text, "at the start of the line: the line becomes the item")
    }

    @Test
    fun backspaceAtTheStartOfALineBreakJoinsItBack() {
        val old = at("- a\n  b", 6)
        val joined = RichTextEditing.handleConcealedDeletion(old, TextFieldValue("- a\n b", TextRange(5)))!!
        assertEquals("- ab", joined.text)
        assertEquals(TextRange(3), joined.selection)
    }

    @Test
    fun theCaretStaysOutOfTheIndentOfALineBreak() {
        val text = "- a\n  b"
        fun moved(from: Int, to: Int) = RichTextEditing.keepCaretOutOfMarkers(TextFieldValue(text, TextRange(from)), TextFieldValue(text, TextRange(to)))?.selection
        assertEquals(TextRange(6), moved(0, 4), "to the text of the line")
        assertEquals(TextRange(3), moved(6, 5), "backwards to the end of the line before")
        assertEquals(null, moved(0, 6))
    }

    @Test
    fun theListsKeepTheLineBreaksOfTheirItems() {
        // Bullets to numbers: the line under the item at the text of the numbered item
        val numbered = RichTextEditing.toggleNumberedList(TextFieldValue("- a\n  b\n- c", TextRange(0, 10)))
        assertEquals("1. a\n   b\n2. c", numbered.text)
        assertEquals("1. a\n   b\n2. c", RichTextEditing.toggleNumberedList(at("- a\n  b\n- c", 2)).text, "the level of the caret with its lines")
        // Out of the list: the line at the indent of its item
        assertEquals("a\nb\nc", RichTextEditing.toggleNumberedList(TextFieldValue(numbered.text, TextRange(0, numbered.text.length))).text)
        // Tab moves the item with its lines
        assertEquals("- x\n  - a\n    b", RichTextEditing.indent(at("- x\n- a\n  b", 6)).text)
        assertEquals("- a\n  b", RichTextEditing.outdent(at("  - a\n    b", 4)).text)
    }

    private fun level(text: String, offset: Int) = TextFieldValue(text, TextRange(offset))

    @Test
    fun theNumberedButtonTurnsTheWholeLevel() {
        val text = "- a\n  - b\n  - c\n- d"
        assertEquals("- a\n  1. b\n  2. c\n- d", RichTextEditing.toggleNumberedList(level(text, text.indexOf("b"))).text)
        assertEquals("1. a\n  - b\n  - c\n2. d", RichTextEditing.toggleNumberedList(level(text, text.indexOf("d"))).text)
        assertEquals("- x\n- y", RichTextEditing.toggleBulletList(level("1. x\n2. y", 3)).text, "and back to bullets")
        val selected = TextFieldValue(text, TextRange(text.indexOf("b"), text.indexOf("b") + 1))
        assertEquals("- a\n  1. b\n  - c\n- d", RichTextEditing.toggleNumberedList(selected).text, "a selection: its lines only")
    }

    @Test
    fun aSelectionOverTheLevelsCountsEachOnItsOwn() {
        val text = "- a\n  - b\n  - c\n- d"
        assertEquals("1. a\n  1. b\n  2. c\n2. d", RichTextEditing.toggleNumberedList(TextFieldValue(text, TextRange(0, text.length))).text)
    }

    @Test
    fun theLinesOfTheItemsFollowWhenALevelTurns() {
        val text = "- a\n  more\n- b"
        assertEquals("1. a\n   more\n2. b", RichTextEditing.toggleNumberedList(level(text, 2)).text)
    }

    @Test
    fun tabNestsAnItemUnderTheSiblingBeforeIt() {
        assertEquals("- a", RichTextEditing.indent(level("- a", 2)).text, "the first item has none")
        assertEquals("- a\n  - b", RichTextEditing.indent(level("- a\n- b", 6)).text)
        assertEquals("- a\n  - b\n    - c", RichTextEditing.indent(level("- a\n- b\n  - c", 6)).text, "what is nested under it follows")
        assertEquals("- a\n  - b\n  - c", RichTextEditing.indent(level("- a\n  - b\n- c", 12)).text, "past a nested item")
        assertEquals("- a\n\nb\n\n- c", RichTextEditing.indent(level("- a\n\nb\n\n- c", 11)).text, "text before it: another list")
    }

    @Test
    fun shiftTabTakesAnItemToTheLevelOfItsParent() {
        assertEquals("- a\n- b", RichTextEditing.outdent(level("- a\n    - b", 10)).text, "however far it was written")
        assertEquals("- a\n- b\n  - c", RichTextEditing.outdent(level("- a\n  - b\n    - c", 8)).text, "what is nested under it follows")
    }

    @Test
    fun enterOnAnEmptyNestedItemGoesOneLevelOut() {
        val out = RichTextEditing.handleEnter(level("- a\n  - b\n  - ", 15))!!
        assertEquals("- a\n  - b\n- ", out.text)
        assertEquals(TextRange(12), out.selection)
        assertEquals("1. a\n2. ", RichTextEditing.handleEnter(level("1. a\n  - ", 9))!!.text, "of the kind of its parent")
        assertEquals("- a\n", RichTextEditing.handleEnter(level("- a\n- ", 6))!!.text, "out of the list from the first level")
    }

    @Test
    fun backspaceOnANestedMarkerGoesOneLevelOut() {
        val old = "- a\n  1. b"
        val caret = old.indexOf("b")
        val out = RichTextEditing.handleConcealedDeletion(level(old, caret), level(old.removeRange(caret - 1, caret), caret - 1))!!
        assertEquals("- a\n- b", out.text)
        assertEquals(TextRange(6), out.selection)
    }

    @Test
    fun enterContinuesAListInItsStyle() {
        assertEquals("a.  x\nb.  ", RichTextEditing.handleEnter(level("a.  x", 5))!!.text)
        assertEquals("I)  x\nII)  ", RichTextEditing.handleEnter(level("I)  x", 5))!!.text)
        assertEquals("a.  x\n", RichTextEditing.handleEnter(level("a.  x\nb.  ", 10))!!.text, "out of the list on an empty one")
    }

    @Test
    fun theButtonsReadAMarkerInAStyle() {
        assertTrue(RichTextEditing.inNumberedList(level("a.  x", 5)))
        assertEquals("x", RichTextEditing.toggleNumberedList(level("a.  x", 5)).text)
        assertEquals("- x\n- y", RichTextEditing.toggleBulletList(level("a.  x\nb.  y", 5)).text)
    }

    @Test
    fun aStyleIsSetOnTheWholeListAtTheCaret() {
        val letters = NumberingStyle(ListNumbering.Numbering.LOWER_ALPHA)
        val roman = NumberingStyle(ListNumbering.Numbering.UPPER_ROMAN, ')')
        assertEquals("a.  x\nb.  y", RichTextEditing.setNumbering(level("1. x\n2. y", 8), letters, 1).text)
        assertEquals("C.  x".lowercase(), RichTextEditing.setNumbering(level("x", 0), letters, 3).text, "a line made a list")
        assertEquals("a.  x\nb.  y", RichTextEditing.setNumbering(level("- x\n- y", 2), letters, 1).text, "a level of bullets")
        assertEquals("1. x\n2. y", RichTextEditing.setNumbering(level("a.  x\nb.  y", 5), null, 1).text, "back to the numbers by level")
        assertEquals("V)  x\nVI)  y", RichTextEditing.setNumbering(level("1. x\n2. y", 3), roman, 5).text)
        assertEquals("- a\n  a.  b\n- c", RichTextEditing.setNumbering(level("- a\n  1. b\n- c", 9), letters, 1).text, "its level only")
        assertEquals(TextRange(4), RichTextEditing.setNumbering(level("1. x", 3), letters, 1).selection, "the caret at its text")
    }

    @Test
    fun theStyleOfTheListAtTheCaretIsRead() {
        val letters = "c.  x\nd.  y"
        assertEquals(NumberingStyle(ListNumbering.Numbering.LOWER_ALPHA), RichTextEditing.numberingStyleOf(level(letters, 8)))
        assertEquals(3, RichTextEditing.numberingStartOf(level(letters, 8)))
        assertEquals(null, RichTextEditing.numberingStyleOf(level("1. x", 3)))
        assertEquals(1, RichTextEditing.numberingStartOf(level("plain", 2)))
    }
}
