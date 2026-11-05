package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.canvas.render.tile.FormulaImage
import io.github.valerioisufi.drawview.canvas.render.tile.FormulaRenderer
import io.github.valerioisufi.drawview.canvas.render.tile.TextFace
import io.github.valerioisufi.drawview.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.TextAlignment
import io.github.valerioisufi.drawview.model.text.TextSizes.PT_TO_MM
import io.github.valerioisufi.drawview.model.text.code.BuiltInCodeHighlighter
import io.github.valerioisufi.drawview.ui.render.CodeBlockLayout
import io.github.valerioisufi.drawview.ui.render.ComposeTextTileAdapter
import io.github.valerioisufi.drawview.ui.render.FormulaImages
import io.github.valerioisufi.drawview.ui.render.codeChips
import io.github.valerioisufi.drawview.ui.render.toTextAlign
import io.github.valerioisufi.drawview.ui.theme.NotesFonts
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The parity of the editor and the page (docs/text-parity.md): a text laid out by the field of the
 * editor (as `TextBoxEditor` presents it), nothing revealed by the caret, has the lines of the text
 * the canvas lays out (`ComposeTextTileAdapter.layout`, what the tiles and the export draw): the
 * same tops, baselines, bottoms and starts of its lines, the same places of its formulas, of its
 * blocks of code and of the backgrounds of its code. Both at 20 pixels per millimeter, the
 * resolution of the canvas.
 */
class TextParityTest {

    private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f, 1f), LayoutDirection.Ltr, cacheSize = 0)
    private val fontFiles = File("src/commonMain/composeResources/font")
    private fun font(name: String) = FontFamily(Font(File(fontFiles, "$name.ttf")))
    private val fonts = NotesFonts(font("noto_sans_regular"), font("noto_sans_mono_regular"), font("strut_above"), font("strut_below"), font("notes_symbols"))

    /** Formulas as MicroTeX would render them: taller when they have a fraction. */
    private val renderer = object : FormulaRenderer {
        override val isReady: StateFlow<Boolean> = MutableStateFlow(true)
        override suspend fun prepare(textFonts: Map<TextFace, ByteArray>) = Unit
        override fun sanitize(latex: String) = latex
        override fun render(latex: String, fontSizePx: Float, colorArgb: Int): FormulaImage {
            val tall = "\\frac" in latex
            val width = (latex.length * fontSizePx * 0.4f).roundToInt().coerceAtLeast(1)
            val height = (fontSizePx * if (tall) 2.4f else 1.2f).roundToInt().coerceAtLeast(1)
            return FormulaImage(ImageBitmap(width, height), if (tall) 0.55f else 0.75f)
        }
    }
    private val adapter = ComposeTextTileAdapter(createFontFamilyResolver(), fonts, renderer)

    private fun text(content: String, alignment: TextAlignment) = DrawText().apply {
        text = content
        this.alignment = alignment
        width = WIDTH_MM
        height = 10f
        fontSize = FONT_PT
    }

    /** What is drawn of a text, in pixels at the resolution of the canvas. */
    private class Drawn(val lines: List<Line>, val formulas: List<Rect>, val boxes: List<Rect>)

    private data class Line(val top: Float, val baseline: Float, val bottom: Float, val left: Float)

    /** [text] as the canvas draws it. */
    private fun page(text: DrawText): Drawn {
        val layout = adapter.layout(text, TextTileAdapter.NO_PAPER) ?: fail("nothing laid out")
        val boxes = layout.boxes.map { Rect(it.left, it.top, it.left + it.width, it.top + it.height).px() }
        val blocks = boxes.filter { it.width > WIDTH_MM * PX_PER_MM / 2f }
        // The words of the text, not those of its blocks of code (drawn by the block in the editor too)
        val words = layout.words.filter { word -> blocks.none { word.baseline * PX_PER_MM in it.top..it.bottom } && word.text.isNotBlank() }
        val lines = words.groupBy { it.baseline }.map { (baseline, line) ->
            Line(line.first().lineTop * PX_PER_MM, baseline * PX_PER_MM, line.first().lineBottom * PX_PER_MM, line.minOf { it.left } * PX_PER_MM)
        }.sortedBy { it.baseline }
        val formulas = layout.formulas.map { Rect(it.left, it.top, it.left + it.width, it.top + it.height).px() }
        return Drawn(lines, formulas, boxes)
    }

    private fun Rect.px() = Rect(left * PX_PER_MM, top * PX_PER_MM, right * PX_PER_MM, bottom * PX_PER_MM)

    /**
     * [text] laid out by the field of the editor with the caret at [caret] (`null`: revealing
     * nothing), at the zoom where the screen is the canvas (see `TextBoxEditor`).
     */
    private fun field(text: DrawText, caret: Int? = null): Pair<TextLayoutResult, PresentedText<ImageBitmap>> {
        val fontSizePx = text.fontSize * PT_TO_MM * PX_PER_MM
        val widthPx = (text.width * PX_PER_MM).toInt()
        val images = FormulaImages(renderer, maxSize = 32)
        val presentation = Presentation(
            fontSizePx = fontSizePx,
            color = Color.Black,
            struts = fonts.struts(measurer),
            lineWidthPx = widthPx.toFloat(),
            monospace = fonts.monospace,
            symbols = fonts.symbols,
            highlighter = BuiltInCodeHighlighter,
            layoutCodeBlock = { source -> CodeBlockLayout.layout(measurer, fonts.monospace, source, fontSizePx, Color.Black, BuiltInCodeHighlighter, TextTileAdapter.NO_PAPER, fonts.text) },
            measurePrefix = { prefix ->
                measurer.measure(AnnotatedString(prefix), TextStyle(fontSize = fontSizePx.sp, fontFamily = fonts.text, letterSpacing = 0.sp), softWrap = false, skipCache = true)
                    .getHorizontalPosition(prefix.length, usePrimaryDirection = true)
            }
        ) { latex, sizePx -> images.formula(latex, sizePx, text.color) }
        val shown = RichTextPresenter.present(text.text, presentation, reveal = TextRange(caret ?: (text.text.length + 1)))
        val style = TextStyle(fontSize = fontSizePx.sp, fontFamily = fonts.text, letterSpacing = 0.sp, lineHeight = TextUnit.Unspecified, textAlign = text.alignment.toTextAlign())
        return measurer.measure(shown.text, style, constraints = Constraints.fixedWidth(widthPx), skipCache = true) to shown
    }

    /** [text] as the field of the editor shows it, revealing nothing. */
    private fun editor(text: DrawText): Drawn {
        val (layout, shown) = field(text)
        val chars = shown.text.text
        val lines = (0 until layout.lineCount).mapNotNull { line ->
            val first = (layout.getLineStart(line) until layout.getLineEnd(line)).firstOrNull { !RichTextPresenter.isInvisible(chars[it]) && !chars[it].isWhitespace() }
                ?: return@mapNotNull null
            Line(layout.getLineTop(line), layout.getLineBaseline(line), layout.getLineBottom(line), layout.getBoundingBox(first).left)
        }
        val blocks = shown.codeBlocks.map { block ->
            val origin = layout.codeBlockOrigin(block)
            Rect(origin.x, origin.y, origin.x + block.layout.widthPx, origin.y + block.layout.heightPx)
        }
        val chips = (layout.codeChips(shown.inlineCode) + layout.quoteBars(shown.quotes, layout.layoutInput.style.fontSize.value)).map { it.rect }
        return Drawn(lines, shown.formulas.map { layout.formulaRect(it) }, chips + blocks)
    }

    private fun assertSame(source: String, alignment: TextAlignment = TextAlignment.START) {
        val text = text(source, alignment)
        val page = page(text)
        val field = editor(text)
        val report = "\"$source\"\npage: ${page.lines.joinToString("\n  ", "\n  ")}\n  ${page.formulas} ${page.boxes}" +
            "\neditor: ${field.lines.joinToString("\n  ", "\n  ")}\n  ${field.formulas} ${field.boxes}"
        assertEquals(page.lines.size, field.lines.size, "the lines of $report")
        for ((p, e) in page.lines.zip(field.lines)) {
            if (!near(p.top, e.top) || !near(p.baseline, e.baseline) || !near(p.bottom, e.bottom) || !near(p.left, e.left)) fail("a line differs: $p, $e in $report")
        }
        assertSameRects(page.formulas, field.formulas, "the formulas of $report")
        assertSameRects(page.boxes.sortedBy { it.top }, field.boxes.sortedBy { it.top }, "the backgrounds of $report")
    }

    private fun assertSameRects(page: List<Rect>, field: List<Rect>, message: String) {
        assertEquals(page.size, field.size, message)
        for ((p, e) in page.zip(field)) {
            if (!near(p.left, e.left) || !near(p.top, e.top) || !near(p.right, e.right) || !near(p.bottom, e.bottom)) fail("$p, $e: $message")
        }
    }

    private fun near(a: Float, b: Float) = abs(a - b) <= TOLERANCE_PX

    @Test
    fun plainText() = assertSame("A plain text long enough to wrap over the width of its box, twice or more.\nA second line.\n\nAfter an empty one.")

    @Test
    fun alignedText() {
        assertSame("Centered text that wraps over the width of its box.", TextAlignment.CENTER)
        assertSame("Text at the end that wraps over the width of its box.", TextAlignment.END)
    }

    @Test
    fun emphasisAndEscapes() {
        val source = "Some **bold**, _italic_, ~~struck~~, [linked](https://example.com), `code` and \\* escaped characters in a text that wraps."
        assertSame(source)
        val layout = adapter.layout(text(source, TextAlignment.START), TextTileAdapter.NO_PAPER) ?: fail("nothing laid out")
        assertTrue(layout.words.any { "linked" in it.text && it.underline })
    }

    @Test
    fun headings() = assertSame("# Title\n## Subtitle with `code`\nText under them.")

    @Test
    fun inlineFormulas() = assertSame("The area \$\\frac{a}{b}\$ of the circle, then \$x^2\$ and more words after it.")

    @Test
    fun displayFormulaOnItsLine() = assertSame("Before\n\$\$\\frac{x^2}{2}\$\$\nAfter")

    @Test
    fun blockOfCode() = assertSame("Code:\n```kotlin\nfun f() = 1\n\tval g = 2\n```\nafter")

    @Test
    fun blockQuotes() = assertSame("Before\n> **A quote** long enough to wrap over the width of its box, twice or more, with `code`.\n> * an item long enough to wrap onto a second line of its box\n>   its line\n>   * nested\n>\n> 1. one\n\nAfter")

    @Test
    fun theLevelsOfTheHeadings() = assertSame("# 1\n## 2\n### 3\n#### 4\n##### 5\n###### 6\nText")

    @Test
    fun numberingStyles() = assertSame("a.  letters\nb.  more\n\nIV)  Roman\nV)  more\n  1.  decimal")

    @Test
    fun symbols() = assertSame("An arrow → and another ↔ with ≤ and ✔ long enough to wrap over the width of its box, twice or more.")

    @Test
    fun lists() = assertSame("- an item long enough to wrap onto a second line of its box\n  - a nested item\n1. a numbered item\n2. another")

    @Test
    fun blockOfCodeInAListItem() = assertSame("- an item\n  ```\n  x = 1\n  ```\nafter")

    /**
     * The tops of the lines of [source] laid out by the editor with the caret at [caret], from the
     * line of the offset [from] of the source: the same as with the caret revealing nothing.
     */
    private fun assertNoVerticalMove(source: String, caret: Int, from: Int) {
        val text = text(source, TextAlignment.START)
        fun tops(caret: Int?): List<Float> {
            val (layout, shown) = field(text, caret)
            val first = layout.getLineForOffset(shown.mapping.originalToTransformed(from))
            return (first until layout.lineCount).map { layout.getLineTop(it) }
        }
        val still = tops(null)
        val revealed = tops(caret)
        assertEquals(still.size, revealed.size, "the lines from offset $from of \"$source\", caret at $caret: $still, $revealed")
        for ((a, b) in still.zip(revealed)) if (abs(a - b) > LINE_ROUNDING_PX) fail("the lines from offset $from of \"$source\" move with the caret at $caret: $still, $revealed")
    }

    @Test
    fun theSourceOfAFormulaKeepsItsRoom() {
        val source = "The area \$\\frac{a}{b}\$ of it\nnext line"
        assertNoVerticalMove(source, caret = source.indexOf("frac"), from = 0)
        val block = "Before\n\$\$\\frac{x^2}{2}\$\$\nAfter"
        assertNoVerticalMove(block, caret = block.indexOf("frac"), from = block.indexOf("After"))
    }

    @Test
    fun theCodeOfABlockIsWrittenAsInTheBlock() {
        val source = "Code:\n```kotlin\nfun f() = 1\n\tval g = 2\n```\nafter"
        val caret = source.indexOf("f()")
        assertNoVerticalMove(source, caret, from = source.indexOf("after"))
        // Its code on the lines of the block, the fences hidden, the rest of the block drawn around it
        val text = text(source, TextAlignment.START)
        val (still, stillShown) = field(text)
        val (layout, shown) = field(text, caret)
        assertTrue("```" !in shown.text.text)
        val block = stillShown.codeBlocks.single()
        val top = still.codeBlockOrigin(block).y
        val written = shown.writtenBlocks.single()
        val chrome = layout.getLineTop(layout.getLineForOffset(written.offset))
        assertEquals(top, chrome, LINE_ROUNDING_PX, "drawn from the top of the block")
        // The code on the baselines of the code of the block drawn around it
        val fn = layout.getLineForOffset(shown.mapping.originalToTransformed(caret))
        assertEquals(chrome + block.layout.codeTopPx + block.layout.code.getLineBaseline(0), layout.getLineBaseline(fn), LINE_ROUNDING_PX, "the first line of code on its baseline in the block")
        val v = layout.getLineForOffset(shown.mapping.originalToTransformed(source.indexOf("val")))
        assertEquals(chrome + block.layout.codeTopPx + block.layout.code.getLineBaseline(1), layout.getLineBaseline(v), LINE_ROUNDING_PX, "the second one too")
        val codeLeft = block.layout.codeLeftPx
        assertEquals(codeLeft + 4 * block.layout.advancePx, layout.getHorizontalPosition(shown.mapping.originalToTransformed(source.indexOf("val")), true), TOLERANCE_PX, "after its tab, at the column of the block")
    }

    @Test
    fun aHeadingNeverShowsItsHashes() {
        val source = "## Title\nnext"
        val text = text(source, TextAlignment.START)
        for (caret in listOf(0, 2, source.indexOf("tle"))) {
            assertEquals("Title", field(text, caret = caret).second.text.text.substringBefore('\u200B'), "the caret at $caret")
        }
        assertNoVerticalMove(source, caret = 2, from = 0)
    }

    @Test
    fun theDelimitersNearTheCaretKeepTheHeightOfTheirLine() {
        val source = "Some **bold** words\nnext"
        assertNoVerticalMove(source, caret = source.indexOf("old"), from = 0)
    }

    private companion object {
        /** The resolution the canvas lays its texts out at (`ComposeTextTileAdapter`). */
        const val PX_PER_MM = 20f
        const val FONT_PT = 12f
        const val WIDTH_MM = 60f

        const val TOLERANCE_PX = 0.5f

        /** The lines of Skia are whole pixels: a line rebuilt of other parts may round the other way. */
        const val LINE_ROUNDING_PX = 1f
    }
}
