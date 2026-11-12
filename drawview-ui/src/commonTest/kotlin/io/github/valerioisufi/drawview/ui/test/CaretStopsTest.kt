package io.github.valerioisufi.drawview.ui.test

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.drawview.ui.editor.CaretStops
import io.github.valerioisufi.drawview.ui.editor.FontRoom
import io.github.valerioisufi.drawview.ui.editor.MarkdownTextUtil
import io.github.valerioisufi.drawview.ui.editor.Presentation
import io.github.valerioisufi.drawview.ui.editor.RichTextPresenter
import io.github.valerioisufi.drawview.ui.editor.SourceMapping
import io.github.valerioisufi.drawview.ui.editor.Struts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Where the caret of the text being written stands, the delimiters not shown (docs/text-fidelity.md):
 * every move moves it on the screen, typing goes on in the format on its left, never in a marker.
 */
class CaretStopsTest {

    private val struts = Struts(null, null, FontFamily.SansSerif, FontRoom(ascent = 0.8f, descent = 0.2f, advance = 0.5f))

    private fun mapping(text: String): SourceMapping = RichTextPresenter.present(
        text,
        Presentation(fontSizePx = 20f, color = Color.Black, struts = struts, lineWidthPx = 300f) { _, _ -> MarkdownTextUtil.RenderedFormula("x", 40f, 30f, 0.8f) }
    ).mapping

    /** The selection [to] the field gave after [from], as the caret stops make it. */
    private fun move(text: String, from: TextRange, to: TextRange): TextRange =
        CaretStops.moved(TextFieldValue(text, from), TextFieldValue(text, to), mapping(text))?.selection ?: to

    /** The selection [to] a pointer gave after [from], as the caret stops make it. */
    private fun point(text: String, from: TextRange, to: TextRange): TextRange =
        CaretStops.moved(TextFieldValue(text, from), TextFieldValue(text, to), mapping(text), pointed = true)?.selection ?: to

    private fun right(text: String, caret: Int) = move(text, TextRange(caret), TextRange(caret + 1)).start
    private fun left(text: String, caret: Int) = move(text, TextRange(caret), TextRange(caret - 1)).start

    @Test
    fun theArrowsCrossTheDelimitersInOnePress() {
        val text = "a **bold** c"
        // Before b: after the opening delimiters takes no press of its own
        assertEquals(text.indexOf("old"), right(text, text.indexOf("**")))
        // After d: inside the bold, at the first press
        assertEquals(text.indexOf("d") + 1, right(text, text.indexOf("d")))
        // Leaving the d to the right goes past the closing delimiters
        assertEquals(text.indexOf(" c") + 1, right(text, text.indexOf("d") + 1))
        // Back from c: inside the bold, after d
        assertEquals(text.indexOf("d") + 1, left(text, text.indexOf("c")))
    }

    @Test
    fun aCaretPutAtTheEdgeOfAFormatGoesOnInTheFormatOnItsLeft() {
        val text = "a **bold** c"
        // Clicked before b: the character on its left is a space, outside the bold
        assertEquals(TextRange(text.indexOf("**")), move(text, TextRange(0), TextRange(text.indexOf("b"))))
        // Clicked after d: inside the bold
        assertEquals(TextRange(text.indexOf("d") + 1), move(text, TextRange(0), TextRange(text.indexOf(" c"))))
    }

    @Test
    fun aSelectionHoldsWhatItShowsAndNoDelimiter() {
        val text = "a **bold** c"
        // A word selected (as a double click selects it): its letters only
        assertEquals(TextRange(text.indexOf("b"), text.indexOf("d") + 1), move(text, TextRange(0), TextRange(text.indexOf("**"), text.indexOf(" c"))))
        // Shift and the right arrow from before b: b
        assertEquals(TextRange(text.indexOf("b"), text.indexOf("o")), move(text, TextRange(text.indexOf("**")), TextRange(text.indexOf("**"), text.indexOf("**") + 1)))
    }

    @Test
    fun aPointerSelectsFromWhereItIsAndIsNotMovedOn() {
        val text = "a **bold** c"
        val opening = text.indexOf("**")
        val b = text.indexOf("b")
        val d = text.indexOf("d")
        val after = text.indexOf(" c")
        // A drag from before b, where its press put the caret, to after b: b, and not the caret after it
        assertEquals(TextRange(b, b + 1), point(text, TextRange(opening), TextRange(b, b + 1)))
        // A drag back from after d, the caret inside the bold: d, without its delimiters and the space
        assertEquals(TextRange(d + 1, d), point(text, TextRange(d + 1), TextRange(after, d)))
        // A handle held at the end of the bold stays there, whichever offset shown there it is given
        assertEquals(TextRange(b, d + 1), point(text, TextRange(b, d + 1), TextRange(b, after)))
        assertEquals(TextRange(b, d + 1), point(text, TextRange(b, d + 1), TextRange(opening, d + 1)))
        // A click where the caret is shown leaves it there, in the format on its left
        assertEquals(TextRange(d + 1), point(text, TextRange(after), TextRange(d + 1)))
        assertEquals(TextRange(opening), point(text, TextRange(b), TextRange(opening)))
        // A pointer through the notation still selects what it shows only
        assertEquals(TextRange(b, d + 1), point(text, TextRange(0), TextRange(opening, after)))
    }

    @Test
    fun theCaretNeverStandsInAMarker() {
        // A heading: after its #
        assertEquals(TextRange(2), move("# Title", TextRange(5), TextRange(0)))
        assertEquals(2, left("# Title", 2), "at the start of the text nothing is before")
        // A list item: after its marker; going left from its text, the end of the line before
        assertEquals(TextRange("a\n- ".length), move("a\n- item", TextRange(0), TextRange(2)))
        assertEquals(1, left("a\n- item", "a\n- ".length))
        // An escape: before it, not between it and what it escapes
        val escaped = "a \\*b"
        assertEquals(TextRange(2), move(escaped, TextRange(0), TextRange(3)))
        assertEquals(4, right(escaped, 2))
    }

    @Test
    fun theObjectsKeepTheirOwnRules() {
        val text = "a \$x+1\$ b"
        assertNull(CaretStops.moved(TextFieldValue(text, TextRange(0)), TextFieldValue(text, TextRange(4)), mapping(text)), "inside a formula")
        // The text changed: not a move
        assertNull(CaretStops.moved(TextFieldValue("ab", TextRange(1)), TextFieldValue("a b", TextRange(2)), mapping("ab")))
    }
}
