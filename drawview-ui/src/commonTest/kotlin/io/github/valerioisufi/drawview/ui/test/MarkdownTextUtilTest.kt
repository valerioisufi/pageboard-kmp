package io.github.valerioisufi.drawview.ui.test

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import io.github.valerioisufi.drawview.ui.editor.MarkdownTextUtil
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
