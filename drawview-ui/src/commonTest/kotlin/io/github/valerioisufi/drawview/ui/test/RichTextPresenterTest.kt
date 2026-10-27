package io.github.valerioisufi.drawview.ui.test

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.ui.editor.FontRoom
import io.github.valerioisufi.drawview.ui.editor.MarkdownTextUtil
import io.github.valerioisufi.drawview.ui.editor.Presentation
import io.github.valerioisufi.drawview.ui.editor.PresentedText
import io.github.valerioisufi.drawview.ui.editor.RichTextPresenter
import io.github.valerioisufi.drawview.ui.editor.Struts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The presentation of the texts, the same for the canvas and the editor: what is shown of the
 * source, the offsets both ways, the objects and their struts, the paragraphs, what the caret
 * reveals.
 */
class RichTextPresenterTest {

    /** A font whose lines go 0.8 above and 0.2 below the baseline, its object character 0.5 wide (per pixel of size). */
    private val room = FontRoom(ascent = 0.8f, descent = 0.2f, advance = 0.5f)
    private val approximated = Struts(null, null, FontFamily.SansSerif, room)
    private val exact = Struts(FontFamily.Serif, FontFamily.Cursive, FontFamily.SansSerif, room)

    private fun present(
        text: String,
        reveal: TextRange? = null,
        struts: Struts = approximated,
        lineWidthPx: Float? = 300f,
        density: Float = 1f,
        formula: MarkdownTextUtil.RenderedFormula<String>? = MarkdownTextUtil.RenderedFormula("x", 40f, 30f, 0.8f)
    ): PresentedText<String> = RichTextPresenter.present(
        text,
        Presentation(fontSizePx = 20f, color = Color.Black, struts = struts, density = density, lineWidthPx = lineWidthPx) { _, _ -> formula },
        reveal
    )

    private val PresentedText<*>.shown get() = text.text

    @Test
    fun theDelimitersAndTheEscapesAreHidden() {
        val presented = present("**bold *both* too** costs \\\$5 and \\*not italic\\*")
        assertEquals("bold both too costs \$5 and *not italic*", presented.shown)
        val both = presented.shown.indexOf("both")
        val styles = presented.text.spanStyles.filter { both >= it.start && both < it.end }.map { it.item }
        assertTrue(styles.any { it.fontWeight == FontWeight.Bold } && styles.any { it.fontStyle == FontStyle.Italic }, "bold and italic")
        assertEquals("Subtitle", present("## Subtitle").shown, "every hash")
    }

    @Test
    fun everyLineIsAParagraphEndedByAZeroWidthSpace() {
        val presented = present("one\n\ntwo\n")
        assertEquals("one​​two\n", presented.shown, "the last line break stays one, for the empty line after it")
        assertEquals(listOf(0 until 4, 4 until 5, 5 until 9), presented.text.paragraphStyles.map { it.start until it.end })
        // One character for one: the caret before a line break at the end of its line, after it at the start of the next
        assertEquals((0..9).toList(), (0..9).map(presented.mapping::originalToTransformed))
    }

    @Test
    fun aFormulaIsAnObjectOfThreeCharacters() {
        val presented = present("a \$x+1\$ b")
        assertEquals("a ￼⁠￼ b", presented.shown)
        val formula = presented.formulas.single()
        assertEquals(2, formula.offset)
        assertEquals(40f, formula.widthPx)
        assertNull(presented.formulaAtCaret)
        val mapping = presented.mapping
        assertEquals(listOf(0, 1, 2, 2, 2, 2, 2, 5, 6, 7), (0..9).map(mapping::originalToTransformed))
        assertEquals(listOf(0, 1, 2, 7, 7, 7, 8, 9), (0..7).map(mapping::transformedToOriginal), "a tap after the middle of a formula goes after it")
    }

    @Test
    fun withoutTheStrutFontsTheRoomIsTheLargerOfBothNeeds() {
        // 24 px above the baseline need a size of 30 px, 6 px below one of 30 px; 40 px wide
        val style = present("a \$x\$ b").text.spanStyles.last { it.start == 2 }.item
        assertEquals(30f, style.fontSize.value, 0.01f)
        assertEquals(40f - 30f * 0.5f, style.letterSpacing.value, 0.01f)
        assertEquals(Color.Transparent, style.color)
    }

    @Test
    fun withTheStrutFontsTheRoomIsExact() {
        val presented = present("a \$x\$ b", struts = exact, density = 2f)
        val above = presented.text.spanStyles.last { it.start == 2 }.item
        val below = presented.text.spanStyles.last { it.start == 4 }.item
        // 24 px above, 6 px below, in sp of a screen of 2 pixels per sp
        assertEquals(FontFamily.Serif, above.fontFamily)
        assertEquals(12f, above.fontSize.value, 0.01f)
        assertEquals((40f - 24f) / 2f, above.letterSpacing.value, 0.01f, "as wide as the formula")
        assertEquals(FontFamily.Cursive, below.fontFamily)
        assertEquals(3f, below.fontSize.value, 0.01f)
        assertEquals(-3f, below.letterSpacing.value, 0.01f, "no width")
    }

    @Test
    fun aFormulaAloneOnItsLinesTakesTheLine() {
        val presented = present("Before\n  \$\$\\frac{1}{2}\$\$  \nAfter \$\$x\$\$")
        assertEquals("Before​￼⁠￼​After ￼⁠￼", presented.shown, "the spaces around it go")
        val (block, inLine) = presented.formulas
        assertTrue(block.block)
        assertEquals("\\displaystyle \\frac{1}{2}", block.latex)
        val style = presented.text.spanStyles.last { it.start == block.offset }.item
        assertEquals(300f * 0.999f - style.fontSize.value * 0.5f, style.letterSpacing.value, 0.01f, "the whole line wide")
        assertTrue(!inLine.block)
    }

    @Test
    fun aBlockFormulaWiderThanTheLineIsMadeSmaller() {
        val presented = present("\$\$x\$\$", lineWidthPx = 100f, formula = MarkdownTextUtil.RenderedFormula("x", 400f, 80f, 0.75f))
        assertEquals(100f, presented.formulas.single().widthPx, 0.01f)
        assertEquals(20f, presented.formulas.single().heightPx, 0.01f)
    }

    @Test
    fun theFormulaUnderTheCaretShowsItsSource() {
        val presented = present("a \$x+1\$ b", reveal = TextRange(4))
        assertEquals("a \uFFFC\u2060\uFFFC\$x+1\$ b", presented.shown, "after the struts keeping its room")
        assertTrue(presented.formulas.isEmpty())
        assertEquals(2, presented.formulaAtCaret?.start)
        assertEquals(7, presented.mapping.originalToTransformed(4))
    }

    @Test
    fun aFormulaThatCannotBeReadIsRedSource() {
        val presented = present("a \$x+1\$ b", formula = null)
        assertEquals("a \$x+1\$ b", presented.shown)
        assertTrue(presented.text.spanStyles.any { it.start == 2 && it.end == 7 && it.item.fontStyle == FontStyle.Italic && it.item.color != Color.Transparent })
    }

    @Test
    fun theCodeInTheLineIsToldInTheOffsetsShown() {
        val presented = present("Run `make`\n# The `main` file")
        assertEquals("Run make​The main file", presented.shown)
        val (make, main) = presented.inlineCode
        assertEquals("make", presented.shown.substring(make.start, make.end))
        assertEquals(20f, make.fontSizePx)
        assertEquals("main", presented.shown.substring(main.start, main.end))
        assertEquals(28f, main.fontSizePx, 0.01f, "the size of its heading")
    }

    @Test
    fun withoutALayoutABlockOfCodeIsCodeInTheLine() {
        val presented = present("a\n```kotlin\nval x = 1\n```\nb")
        assertEquals("a\u200Bval x = 1\u200Bb", presented.shown, "its fences hidden")
        assertTrue(presented.codeBlocks.isEmpty())
        assertTrue(presented.text.spanStyles.any { it.item.fontFamily == FontFamily.Monospace && presented.shown.substring(it.start, it.end) == "val x = 1" })
    }

    @Test
    fun theSizesOfTheStylesAreInSpWhereTheTextIsShown() {
        // 20 px on a screen of 2 pixels per sp: a heading of 1.4 times 10 sp
        val presented = present("# Title", density = 2f)
        assertEquals(14f, presented.text.spanStyles.first { it.item.fontSize.isSp }.item.fontSize.value, 0.01f)
    }

    @Test
    fun aListItemShowsItsBulletAndHangsItsLines() {
        val presented = present("- Item 1\n  * Item 2\n1. Numbered")
        assertEquals("• Item 1​  ◦ Item 2​1. Numbered", presented.shown)
        val (first, nested) = presented.text.paragraphStyles.map { it.item.textIndent!! }
        assertEquals(0.sp, first.firstLine)
        assertTrue(first.restLine.value > 0f)
        assertTrue(nested.restLine.value > first.restLine.value, "a nested item hangs further")
        // The marker is not a place of the caret: its offsets go to the text of the item, and back
        val mapping = presented.mapping
        assertEquals(listOf(2, 2, 2), (0..2).map(mapping::originalToTransformed))
        assertEquals(listOf(2, 2, 2), (0..2).map(mapping::transformedToOriginal))
    }

    @Test
    fun boldInAListItem() {
        val presented = present("- Item with **bold** text")
        assertEquals("• Item with bold text", presented.shown)
        val bold = presented.shown.indexOf("bold")
        assertTrue(presented.text.spanStyles.any { bold >= it.start && bold + 4 <= it.end && it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun theDelimitersNearTheCaretAreFadedAtTheSizeOfTheirText() {
        val presented = present("a **b** c", reveal = TextRange(4))
        assertEquals("a **b** c", presented.shown)
        val delimiter = presented.text.spanStyles.filter { it.start <= 2 && it.end >= 4 }.map { it.item }
        assertTrue(delimiter.any { it.color.alpha < 0.5f }, "faded")
        assertTrue(delimiter.none { it.fontSize.isSp }, "no size of their own")
    }

    @Test
    fun theSourceOfAFormulaUnderTheCaretKeepsItsRoom() {
        val presented = present("a \$x\$ b", reveal = TextRange(3), struts = exact)
        assertEquals("a \uFFFC\u2060\uFFFC\$x\$ b", presented.shown, "struts of no width before the source")
        val above = presented.text.spanStyles.last { it.start == 2 }.item
        assertEquals(24f, above.fontSize.value, 0.01f)
        assertEquals(-24f, above.letterSpacing.value, 0.01f)
        assertEquals(5, presented.mapping.originalToTransformed(2), "the caret before the source after the struts")
        assertEquals(2, presented.mapping.transformedToOriginal(3))
    }

    @Test
    fun aLinkUnderlinesItsTextAndHidesItsAddressAwayFromTheCaret() {
        val hidden = present("Read [the guide](https://example.com) now")
        assertEquals("Read the guide now", hidden.shown)
        val guide = hidden.shown.indexOf("the guide")
        assertTrue(hidden.text.spanStyles.any { guide >= it.start && guide + 9 <= it.end && it.item.textDecoration?.contains(TextDecoration.Underline) == true })

        val revealed = present("Read [the guide](https://example.com) now", reveal = TextRange(8))
        assertEquals("Read [the guide](https://example.com) now", revealed.shown)
        val address = revealed.shown.indexOf("](https://example.com)")
        assertTrue(revealed.text.spanStyles.any { address >= it.start && address + 22 <= it.end && it.item.color.alpha < 0.5f })
    }
}
