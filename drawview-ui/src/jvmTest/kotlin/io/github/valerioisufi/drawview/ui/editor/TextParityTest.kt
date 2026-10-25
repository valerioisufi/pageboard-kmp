package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.model.text.code.BuiltInCodeHighlighter
import io.github.valerioisufi.drawview.ui.render.CodeBlockLayout
import java.io.File
import kotlin.math.abs
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The parity of the editor and the page (docs/text-parity.md): a text laid out by the field of the
 * editor, nothing revealed by the caret, has the lines of the text the canvas lays out: the same
 * number, tops, baselines, bottoms, and left and right ends.
 */
class TextParityTest {

    private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f, 1f), LayoutDirection.Ltr, cacheSize = 0)
    private val fonts = File("src/commonMain/composeResources/font")
    private val text = FontFamily(Font(File(fonts, "noto_sans_regular.ttf")))
    private val mono = FontFamily(Font(File(fonts, "noto_sans_mono_regular.ttf")))
    private val fontPx = 20f
    private val widthPx = 320

    /** A formula as MicroTeX would render it: taller when it has a fraction. */
    private fun render(latex: String, sizePx: Float): MarkdownTextUtil.RenderedFormula<Unit> {
        val tall = "\\frac" in latex
        return MarkdownTextUtil.RenderedFormula(Unit, latex.length * sizePx * 0.4f, sizePx * if (tall) 2.4f else 1.2f, if (tall) 0.55f else 0.75f)
    }

    private fun prefixWidth(prefix: String) =
        measurer.measure(AnnotatedString(prefix), TextStyle(fontSize = fontPx.sp, fontFamily = text, letterSpacing = 0.sp), softWrap = false, skipCache = true)
            .getHorizontalPosition(prefix.length, usePrimaryDirection = true)

    private fun codeBlock(source: io.github.valerioisufi.drawview.ui.render.CodeBlockSource) =
        CodeBlockLayout.layout(measurer, mono, source, fontPx, Color.Black, BuiltInCodeHighlighter, 0xFFFFFFFF.toInt())

    /** The text as the canvas lays it out (see `ComposeTextTileAdapter.prepare`). */
    private fun canvas(source: String, align: TextAlign): TextLayoutResult {
        val style = TextStyle(fontSize = fontPx.sp, fontFamily = text, letterSpacing = 0.sp, textAlign = align)
        val ascent = measurer.measure(AnnotatedString("Hg"), style, skipCache = true).firstBaseline
        val shown = MarkdownTextUtil.toCanvasText(
            text = source,
            baseColor = Color.Black,
            fontSizePx = fontPx,
            fontAscentPx = ascent,
            lineWidthPx = widthPx.toFloat(),
            monospace = mono,
            layoutCodeBlock = ::codeBlock,
            listPrefixWidth = ::prefixWidth,
            renderFormula = ::render
        )
        val placeholders = shown.placeholders.map { AnnotatedString.Range(Placeholder(it.widthPx.sp, it.heightPx.sp, it.verticalAlign), it.start, it.end) }
        return measurer.measure(shown.text, style, placeholders = placeholders, constraints = Constraints.fixedWidth(widthPx), skipCache = true)
    }

    /** The text as the field of the editor lays it out, the caret revealing nothing (see `TextBoxEditor`). */
    private fun editor(source: String, align: TextAlign): TextLayoutResult {
        val shown = MarkdownTextUtil.toEditorText(
            text = source,
            selection = TextRange(Int.MAX_VALUE / 2),
            defaultColor = Color.Black,
            fontSizePx = fontPx,
            density = 1f,
            lineWidthPx = widthPx.toFloat(),
            room = room(),
            textFont = text,
            monospace = mono,
            highlighter = BuiltInCodeHighlighter,
            layoutCodeBlock = ::codeBlock,
            listPrefixWidth = ::prefixWidth,
            renderFormula = ::render
        )
        val style = TextStyle(fontSize = fontPx.sp, fontFamily = text, letterSpacing = 0.sp, textAlign = align)
        return measurer.measure(shown.text, style, constraints = Constraints.fixedWidth(widthPx), skipCache = true)
    }

    private fun room(): MarkdownTextUtil.FontRoom {
        val size = 1000f
        val line = measurer.measure(AnnotatedString(MarkdownTextUtil.OBJECT_REPLACEMENT), TextStyle(fontSize = size.sp, fontFamily = text, letterSpacing = 0.sp))
        val ascent = line.firstBaseline
        return MarkdownTextUtil.FontRoom(ascent / size, (line.getLineBottom(0) - ascent) / size, line.getBoundingBox(0).width / size)
    }

    private class Line(val top: Float, val baseline: Float, val bottom: Float, val left: Float, val right: Float) {
        override fun toString() = "top=$top baseline=$baseline bottom=$bottom left=$left right=$right"
    }

    private fun TextLayoutResult.lines() = (0 until lineCount).map { Line(getLineTop(it), getLineBaseline(it), getLineBottom(it), getLineLeft(it), getLineRight(it)) }

    private fun assertSameLines(source: String, align: TextAlign = TextAlign.Start) {
        val page = canvas(source, align).lines()
        val field = editor(source, align).lines()
        val report = "page:\n${page.joinToString("\n")}\neditor:\n${field.joinToString("\n")}"
        assertEquals(page.size, field.size, "the number of lines of \"$source\"\n$report")
        for ((i, pair) in page.zip(field).withIndex()) {
            val (p, e) = pair
            val same = listOf(p.top to e.top, p.baseline to e.baseline, p.bottom to e.bottom, p.left to e.left, p.right to e.right).all { (a, b) -> abs(a - b) <= TOLERANCE }
            if (!same) fail("line $i of \"$source\" differs\n$report")
        }
    }

    @Test
    fun plainText() = assertSameLines("A plain text long enough to wrap over the width of its box, twice or more.\nA second line.")

    @Test
    fun alignedText() {
        assertSameLines("Centered text that wraps over the width of its box.", TextAlign.Center)
        assertSameLines("Text at the end that wraps over the width of its box.", TextAlign.End)
    }

    @Test
    fun emphasisAndEscapes() = assertSameLines("Some **bold**, _italic_, ~~struck~~, `code` and \\* escaped characters in a text that wraps.")

    @Test
    fun headings() = assertSameLines("# Title\n## Subtitle\nText under them.")

    @Ignore // P2: phase 2
    @Test
    fun inlineFormulas() = assertSameLines("The area \$\\frac{a}{b}\$ of the circle, then \$x^2\$ and more words after it.")

    @Ignore // P2: phase 2
    @Test
    fun displayFormulaOnItsLine() = assertSameLines("Before\n\$\$\\frac{x^2}{2}\$\$\nAfter")

    @Ignore // P3: phase 2
    @Test
    fun blockOfCode() = assertSameLines("Code:\n```kotlin\nfun f() = 1\n\tval g = 2\n```\nafter")

    @Ignore // P4: phase 3
    @Test
    fun lists() = assertSameLines("- an item long enough to wrap onto a second line of its box\n  - a nested item\n1. a numbered item\n2. another")

    @Ignore // P4: phase 3
    @Test
    fun blockOfCodeInAListItem() = assertSameLines("- an item\n  ```\n  x = 1\n  ```\nafter")

    private companion object {
        const val TOLERANCE = 0.5f
    }
}
