package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.valerioisufi.drawview.model.text.code.BuiltInCodeHighlighter
import io.github.valerioisufi.drawview.ui.render.CodeBlockLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The code in the field of the editor: a block away from the caret as one character drawn over,
 * its source under the caret in its colors, the code in the line, the caret on a character tapped
 * in a block.
 */
class EditorCodeTest {

    private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f, 1f), LayoutDirection.Ltr, cacheSize = 0)

    private fun layout(code: String, language: String = "kotlin", indent: Int = 0, widthPx: Float = 600f) =
        CodeBlockLayout.layout(measurer, FontFamily.Monospace, code, language, indent, 20f, widthPx, Color.Black, BuiltInCodeHighlighter)

    private fun shown(text: String, caret: Int) = MarkdownTextUtil.toEditorText<Unit>(
        text = text,
        selection = TextRange(caret),
        defaultColor = Color.Black,
        fontSizePx = 20f,
        density = 1f,
        lineWidthPx = 600f,
        room = MarkdownTextUtil.FontRoom(ascent = 0.9f, descent = 0.3f, advance = 1f),
        textFont = FontFamily.Default,
        monospace = FontFamily.Monospace,
        highlighter = BuiltInCodeHighlighter,
        layoutCodeBlock = { code, language, indent -> layout(code, language, indent) }
    ) { _, _ -> null }

    private val text = "Code:\n```kotlin\nfun f() = 1\n```\nafter"

    @Test
    fun aBlockAwayFromTheCaretIsOneCharacterAsHighAsTheBlock() {
        val shown = shown(text, caret = text.length)
        assertEquals("Code:\n${MarkdownTextUtil.OBJECT_REPLACEMENT}\nafter", shown.text.text)
        val block = shown.codeBlocks.single()
        assertEquals(6, block.offset)
        assertEquals(text.indexOf("fun"), block.codeStart)
        // The room of the character: its size gives its line the height of the block, its spacing the width of the line
        val room = shown.text.spanStyles.last { it.start == 6 }.item
        assertEquals(block.layout.heightPx / 1.2f, room.fontSize.value, 0.01f)
        assertEquals(600f * 0.999f - room.fontSize.value, room.letterSpacing.value, 0.01f)
        assertTrue(shown.codeSources.isEmpty())
        // The offsets of the block go to its character
        assertEquals(6, shown.offsetMapping.originalToTransformed(text.indexOf("fun")))
        assertEquals(text.indexOf("\nafter"), shown.offsetMapping.transformedToOriginal(7))
    }

    @Test
    fun theBlockOfTheCaretShowsItsSourceInItsColors() {
        val shown = shown(text, caret = text.indexOf("f()"))
        assertEquals(text, shown.text.text, "the source as it is written")
        assertTrue(shown.codeBlocks.isEmpty())
        assertEquals(text.indexOf("```") until text.indexOf("\nafter"), shown.codeSources.single())
        val keyword = text.indexOf("fun")
        assertTrue(shown.text.spanStyles.any { it.start == keyword && it.end == keyword + 3 && it.item.color == Color(0xFFCF222E) }, "the keyword in its color")
        val fence = shown.text.spanStyles.first { it.start == text.indexOf("```") }.item
        assertTrue(fence.color.alpha < 0.5f, "the fences faded")
    }

    @Test
    fun theCodeInTheLineIsToldInTheOffsetsShown() {
        val text = "\$x\$ and `run` it"
        val shown = MarkdownTextUtil.toEditorText(
            text = text,
            selection = TextRange(text.length),
            defaultColor = Color.Black,
            fontSizePx = 20f,
            density = 1f,
            lineWidthPx = 600f,
            room = MarkdownTextUtil.FontRoom(0.9f, 0.3f, 1f),
            textFont = FontFamily.Default
        ) { _, _ -> MarkdownTextUtil.RenderedFormula(Unit, 10f, 10f, 0.8f) }
        val code = shown.inlineCode.single()
        assertEquals("run", shown.text.text.substring(code.start, code.end), "after the formula standing as one character")
        assertEquals(20f, code.fontSizePx)
    }

    @Test
    fun aTapOnABlockGoesToTheCharacterTapped() {
        val code = "fun f() = 1\n\tval g = 2"
        val block = layout(code)
        val lines = block.code
        // On the "f" of the first line, then past the end of the first line, then on the "v" after the tab
        val f = lines.getBoundingBox(4)
        assertEquals(4, block.codeOffsetAt(Offset(block.paddingPx + f.center.x, block.codeTopPx + f.center.y)))
        val endOfFirst = Offset(block.paddingPx + lines.getLineRight(0) + 30f, block.codeTopPx + lines.getLineTop(0) + 2f)
        assertEquals(code.indexOf('\n'), block.codeOffsetAt(endOfFirst), "before the line break")
        val v = lines.getBoundingBox(lines.layoutInput.text.text.indexOf('v'))
        assertEquals(code.indexOf('v'), block.codeOffsetAt(Offset(block.paddingPx + v.left + 1f, block.codeTopPx + v.center.y)))
    }
}
