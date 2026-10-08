package io.github.valerioisufi.drawview.ui.test

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import io.github.valerioisufi.drawview.ui.editor.MarkdownTextUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkdownTextUtilTest {

    @Test
    fun liveStringKeepsTheOffsets() {
        val text = "This is **bold** and *italic* and `code`"
        val annotated = MarkdownTextUtil.toLiveAnnotatedString(text, TextRange(0), Color.Black)
        assertEquals(text, annotated.text)
        assertTrue(annotated.spanStyles.isNotEmpty())
    }

    @Test
    fun canvasTextRemovesTheDelimiters() {
        val canvas = MarkdownTextUtil.toCanvasText<Unit>(
            text = "# Title\n**bold** _it_ ~~del~~ `x` \$a^2\$",
            baseColor = Color.Black,
            fontSizePx = 20f,
            fontAscentPx = 18f
        ) { _, _ -> null }
        // Without a LaTeX engine the formula is kept as (red) source
        assertEquals("Title\nbold it del x \$a^2\$", canvas.text.text)
        assertTrue(canvas.placeholders.isEmpty())
    }

    @Test
    fun canvasTextPlacesTheFormulas() {
        val canvas = MarkdownTextUtil.toCanvasText(
            text = "area \$\\pi r^2\$ ok",
            baseColor = Color.Black,
            fontSizePx = 20f,
            fontAscentPx = 18f
        ) { _, size -> MarkdownTextUtil.RenderedFormula("img", 40f, size, 0.75f) }
        assertEquals(1, canvas.formulas.size)
        // Body (above the baseline) + descent placeholders
        assertEquals(2, canvas.placeholders.size)
        val body = canvas.placeholders[canvas.formulas[0].placeholderIndex]
        assertEquals(PlaceholderVerticalAlign.AboveBaseline, body.verticalAlign)
        assertEquals(40f, body.widthPx)
        assertTrue(canvas.text.text.startsWith("area "))
        assertTrue(canvas.text.text.endsWith(" ok"))
    }

    @Test
    fun headingsDropEveryHash() {
        val canvas = MarkdownTextUtil.toCanvasText<Unit>(
            text = "## Subtitle",
            baseColor = Color.Black,
            fontSizePx = 20f,
            fontAscentPx = 18f
        ) { _, _ -> null }
        assertEquals("Subtitle", canvas.text.text)
    }

    @Test
    fun canvasTextNestsTheFormattingAndDropsTheEscapes() {
        val canvas = MarkdownTextUtil.toCanvasText<Unit>(
            text = "**bold *both* too** costs \\\$5 and \\*not italic\\*",
            baseColor = Color.Black,
            fontSizePx = 20f,
            fontAscentPx = 18f
        ) { _, _ -> null }
        assertEquals("bold both too costs \$5 and *not italic*", canvas.text.text)
        val both = canvas.text.text.indexOf("both")
        val styles = canvas.text.spanStyles.filter { both >= it.start && both < it.end }.map { it.item }
        assertTrue(styles.any { it.fontWeight == FontWeight.Bold } && styles.any { it.fontStyle == FontStyle.Italic }, "bold and italic")
    }

    @Test
    fun aFormulaAloneOnItsLinesTakesTheLine() {
        val canvas = MarkdownTextUtil.toCanvasText(
            text = "Before\n  \$\$\\frac{1}{2}\$\$  \nAfter \$\$x\$\$",
            baseColor = Color.Black,
            fontSizePx = 20f,
            fontAscentPx = 18f,
            lineWidthPx = 300f
        ) { latex, size -> MarkdownTextUtil.RenderedFormula(latex, 40f, size, 0.75f) }
        assertEquals("Before\n\uFFFC\nAfter \uFFFC\u2060\uFFFC", canvas.text.text, "the spaces around it go")
        val block = canvas.formulas[0]
        assertTrue(block.block)
        assertEquals("\\displaystyle \\frac{1}{2}", block.latex)
        val line = canvas.placeholders[block.placeholderIndex]
        assertEquals(PlaceholderVerticalAlign.Center, line.verticalAlign)
        assertEquals(300f, line.widthPx, 0.5f)
        // In a line: display style, on the baseline
        assertTrue(!canvas.formulas[1].block)
        assertEquals(PlaceholderVerticalAlign.AboveBaseline, canvas.placeholders[canvas.formulas[1].placeholderIndex].verticalAlign)
    }

    @Test
    fun aBlockFormulaWiderThanTheLineIsMadeSmaller() {
        val canvas = MarkdownTextUtil.toCanvasText(
            text = "\$\$x\$\$",
            baseColor = Color.Black,
            fontSizePx = 20f,
            fontAscentPx = 18f,
            lineWidthPx = 100f
        ) { _, _ -> MarkdownTextUtil.RenderedFormula(Unit, 400f, 80f, 0.75f) }
        assertEquals(100f, canvas.formulas.single().widthPx, 0.01f)
        assertEquals(20f, canvas.formulas.single().heightPx, 0.01f)
    }

    /** A font whose lines go 0.8 above and 0.2 below the baseline, the formula character 0.5 wide (per pixel of size). */
    private val room = MarkdownTextUtil.FontRoom(ascent = 0.8f, descent = 0.2f, advance = 0.5f)

    private fun editorText(text: String, caret: Int, valid: Boolean = true) = MarkdownTextUtil.toEditorText(
        text = text,
        selection = TextRange(caret),
        defaultColor = Color.Black,
        fontSizePx = 20f,
        density = 2f,
        lineWidthPx = 300f,
        room = room,
        textFont = FontFamily.SansSerif
    ) { latex, _ -> if (valid) MarkdownTextUtil.RenderedFormula(latex, 40f, 30f, 0.8f) else null }

    @Test
    fun aFormulaAwayFromTheCaretStandsAsOneCharacterOfItsSize() {
        val shown = editorText("a \$x+1\$ b", caret = 0)
        assertEquals("a \uFFFC b", shown.text.text)
        val formula = shown.formulas.single()
        assertEquals(2, formula.offset)
        assertEquals(40f, formula.widthPx)
        // Its room: 24 px above the baseline (0.8 of 30) need a size of 30 px; 40 px wide
        val style = shown.text.spanStyles.last { it.start == 2 }.item
        assertEquals(15f, style.fontSize.value, 0.01f)
        assertEquals((40f - 30f * 0.5f) / 2f, style.letterSpacing.value, 0.01f)
        assertEquals(Color.Transparent, style.color)
        assertNull(shown.formulaAtCaret)

        val mapping = shown.offsetMapping
        assertEquals(listOf(0, 1, 2, 2, 2, 2, 2, 3, 4, 5), (0..9).map(mapping::originalToTransformed))
        assertEquals(listOf(0, 1, 2, 7, 8, 9), (0..5).map(mapping::transformedToOriginal))
    }

    @Test
    fun theFormulaUnderTheCaretShowsItsSource() {
        val shown = editorText("a \$x+1\$ b", caret = 4)
        assertEquals("a \$x+1\$ b", shown.text.text)
        assertTrue(shown.formulas.isEmpty())
        assertEquals(2, shown.formulaAtCaret?.start)
        assertEquals(4, shown.offsetMapping.originalToTransformed(4))
    }

    @Test
    fun aFormulaThatCannotBeReadIsRedSource() {
        val shown = editorText("a \$x+1\$ b", caret = 0, valid = false)
        assertEquals("a \$x+1\$ b", shown.text.text)
        assertTrue(shown.text.spanStyles.any { it.start == 2 && it.end == 7 && it.item.fontStyle == FontStyle.Italic && it.item.color != Color.Transparent })
    }

    @Test
    fun aBlockFormulaTakesItsLine() {
        val shown = editorText("Before\n\$\$x\$\$\nAfter", caret = 0)
        assertEquals("Before\n\uFFFC\nAfter", shown.text.text)
        val formula = shown.formulas.single()
        assertTrue(formula.block)
        val style = shown.text.spanStyles.last { it.start == 7 }.item
        // Centered: 30 px of a line 1.0 per pixel of size; the whole line wide
        assertEquals(15f, style.fontSize.value, 0.01f)
        assertEquals((300f * 0.999f - 30f * 0.5f) / 2f, style.letterSpacing.value, 0.01f)
    }
}
