package io.github.valerioisufi.drawview.ui.test

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.drawview.ui.editor.MarkdownTextUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownTextUtilTest {

    @Test
    fun wrapsTheSelectionWithDelimiters() {
        val original = TextFieldValue(text = "Hello World", selection = TextRange(6, 11))
        val wrapped = MarkdownTextUtil.toggleDelimiters(original, "**", "**")
        assertEquals("Hello **World**", wrapped.text)
        assertEquals(TextRange(8, 13), wrapped.selection)
    }

    @Test
    fun unwrapsWhenTheSelectionIsAlreadyWrapped() {
        val original = TextFieldValue(text = "Hello **World**", selection = TextRange(8, 13))
        val unwrapped = MarkdownTextUtil.toggleDelimiters(original, "**", "**")
        assertEquals("Hello World", unwrapped.text)
    }

    @Test
    fun insertsDelimitersAtTheCursor() {
        val original = TextFieldValue(text = "E = mc", selection = TextRange(6))
        val result = MarkdownTextUtil.toggleDelimiters(original, "$", "$")
        assertEquals("E = mc$$", result.text)
        assertEquals(TextRange(7), result.selection)
    }

    @Test
    fun insertsMathTemplates() {
        val original = TextFieldValue(text = "x = ", selection = TextRange(4))
        val result = MarkdownTextUtil.insertTemplate(original, "\\frac{}{}", "\\frac{".length)
        assertEquals("x = \\frac{}{}", result.text)
        assertEquals(TextRange(10), result.selection)
    }

    @Test
    fun liveStringKeepsTheOffsets() {
        val text = "This is **bold** and *italic* and `code`"
        val annotated = MarkdownTextUtil.toLiveAnnotatedString(text, TextRange(0), true, Color.Black)
        assertEquals(text, annotated.text)
        assertTrue(annotated.spanStyles.isNotEmpty())
    }

    @Test
    fun canvasTextRemovesTheDelimiters() {
        val canvas = MarkdownTextUtil.toCanvasText<Unit>(
            text = "# Title\n**bold** _it_ ~~del~~ `x` \$a^2\$",
            isMarkdownEnabled = true,
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
            isMarkdownEnabled = true,
            baseColor = Color.Black,
            fontSizePx = 20f,
            fontAscentPx = 18f
        ) { _, size -> MarkdownTextUtil.RenderedFormula("img", 40f, size, 0.75f) }
        assertEquals(1, canvas.formulas.size)
        // Body (above the baseline) + descent placeholders
        assertEquals(2, canvas.placeholders.size)
        val body = canvas.placeholders[canvas.formulas[0].placeholderIndex]
        assertTrue(body.aboveBaseline)
        assertEquals(40f, body.widthPx)
        assertTrue(canvas.text.text.startsWith("area "))
        assertTrue(canvas.text.text.endsWith(" ok"))
    }

    @Test
    fun headingsDropEveryHash() {
        val canvas = MarkdownTextUtil.toCanvasText<Unit>(
            text = "## Subtitle",
            isMarkdownEnabled = true,
            baseColor = Color.Black,
            fontSizePx = 20f,
            fontAscentPx = 18f
        ) { _, _ -> null }
        assertEquals("Subtitle", canvas.text.text)
    }

    @Test
    fun aHeadingStartsItsLineWhereverTheCaretIs() {
        val original = TextFieldValue(text = "Intro\nTitle here\nEnd", selection = TextRange(12))
        val heading = MarkdownTextUtil.toggleHeading(original)
        assertEquals("Intro\n# Title here\nEnd", heading.text)
        assertEquals(TextRange(14), heading.selection, "the caret stays on its character")

        val plain = MarkdownTextUtil.toggleHeading(heading)
        assertEquals(original, plain)

        val subtitle = MarkdownTextUtil.toggleHeading(TextFieldValue("##  Sub", TextRange(1)))
        assertEquals("Sub", subtitle.text)
        assertEquals(TextRange(0), subtitle.selection)
    }
}
