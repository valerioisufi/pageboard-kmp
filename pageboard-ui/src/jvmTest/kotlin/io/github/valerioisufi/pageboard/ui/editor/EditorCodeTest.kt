package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.text.code.BuiltInCodeHighlighter
import io.github.valerioisufi.pageboard.ui.adaptive.ShortcutModifier
import io.github.valerioisufi.pageboard.ui.adaptive.platformConventions
import io.github.valerioisufi.pageboard.ui.render.CodeBlockLayout
import io.github.valerioisufi.pageboard.ui.render.CodeBlockSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * The code in the field of the editor: a block away from the caret as an object drawn over,
 * its source under the caret in its colors, the code in the line, the caret on a character tapped
 * in a block.
 */
class EditorCodeTest {

    private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f, 1f), LayoutDirection.Ltr, cacheSize = 0)

    private fun layout(code: String, language: String = "kotlin", indent: Int = 0, widthPx: Float = 600f, numbered: Boolean = false, firstLine: Int = 1) =
        CodeBlockLayout.layout(measurer, FontFamily.Monospace, CodeBlockSource(code, language, indent, numbered, widthPx, firstLine), 20f, Color.Black, BuiltInCodeHighlighter, 0xFFFFFFFF.toInt())

    private val struts = Struts(null, null, FontFamily.Default, FontRoom(ascent = 0.9f, descent = 0.3f, advance = 1f))

    private fun shown(text: String, caret: Int) = RichTextPresenter.present(
        text,
        Presentation<Unit>(
            fontSizePx = 20f,
            color = Color.Black,
            struts = struts,
            lineWidthPx = 600f,
            monospace = FontFamily.Monospace,
            highlighter = BuiltInCodeHighlighter,
            layoutCodeBlock = { source -> layout(source.code, source.language, source.indent, source.widthPx, source.numbered, source.firstLine) }
        ) { _, _ -> null },
        reveal = TextRange(caret)
    )

    private val text = "Code:\n```kotlin\nfun f() = 1\n```\nafter"

    @Test
    fun aBlockAwayFromTheCaretIsAnObjectAsHighAsTheBlock() {
        val shown = shown(text, caret = text.length)
        assertEquals("Code:\u200B\uFFFC\u2060\uFFFC\u200Bafter", shown.text.text)
        val block = shown.codeBlocks.single()
        assertEquals(6, block.offset)
        assertEquals(text.indexOf("fun"), block.codeStart)
        // Its room: the height of the block above the descent of the font (6 px), the width of the line
        val room = shown.text.spanStyles.last { it.start == 6 }.item
        assertEquals(maxOf((block.layout.heightPx - 6f) / 0.9f, 6f / 0.3f), room.fontSize.value, 0.01f)
        assertEquals(600f * 0.999f - room.fontSize.value, room.letterSpacing.value, 0.01f)
        // The offsets of the block go to its characters
        assertEquals(6, shown.mapping.originalToTransformed(text.indexOf("fun")))
        assertEquals(text.indexOf("\nafter"), shown.mapping.transformedToOriginal(7))
    }

    @Test
    fun theBlockWithTheCaretInItStaysABlockItsFenceInTheStrip() {
        val still = shown(text, caret = text.length)
        for (caret in listOf(text.indexOf("```") + 2, text.indexOf("f()"), text.indexOf("\n```\n") + 2)) {
            val shown = shown(text, caret)
            assertEquals(still.text.text, shown.text.text, "the caret at $caret: the block as on the page")
            assertEquals(1, shown.codeBlocks.size)
        }
        // On the opening fence: its line in the strip
        val onFence = shown(text, caret = text.indexOf("```") + 2).hiddenAtCaret!!
        assertEquals("```kotlin", text.substring(onFence.source.first, onFence.source.last + 1))
        // In the code: the block holds the caret, no strip
        val inCode = shown(text, caret = text.indexOf("f()"))
        assertTrue(inCode.codeBlocks.single().holds(text.indexOf("f()")))
        assertEquals(null, inCode.hiddenAtCaret)
    }

    @Test
    fun theCodeInTheLineIsToldInTheOffsetsShown() {
        val text = "\$x\$ and `run` it"
        val shown = RichTextPresenter.present(
            text,
            Presentation(fontSizePx = 20f, color = Color.Black, struts = struts, lineWidthPx = 600f) { _, _ -> MarkdownTextUtil.RenderedFormula(Unit, 10f, 10f, 0.8f) },
            reveal = TextRange(text.length)
        )
        val code = shown.inlineCode.single()
        assertEquals("run", shown.text.text.substring(code.start, code.end), "after the three characters of the formula")
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

    @Test
    fun aBlockShowsTheNumbersOfItsLinesInAColumn() {
        val code = "a\n\n" + "word ".repeat(40).trim() + "\nb"
        val plain = layout(code, widthPx = 400f)
        val block = layout(code, widthPx = 400f, numbered = true)
        assertEquals(listOf("1", "2", "3", "4"), block.numbers.map { it.layout.layoutInput.text.text }, "a number per line of the code, not per wrapped line")
        assertTrue(block.codeLeftPx > plain.codeLeftPx, "the code after the column")
        assertTrue(block.numbers.all { it.left + it.layout.size.width <= block.codeLeftPx }, "the numbers before the code")
        // Each number on the baseline of the first line of its line of code
        val lines = block.code
        val third = lines.getLineForOffset(lines.layoutInput.text.text.indexOf("word"))
        assertEquals(block.codeTopPx + lines.getLineBaseline(third), block.numbers[2].top + block.numbers[2].layout.firstBaseline, 0.5f)
        assertTrue(block.numbers[3].top > block.numbers[2].top + 2 * (lines.getLineBottom(0) - lines.getLineTop(0)), "the wrapped lines have no number")
        // A tap on the code, after the column
        val b = lines.getBoundingBox(lines.layoutInput.text.text.lastIndexOf('b'))
        assertEquals(code.lastIndexOf('b'), block.codeOffsetAt(Offset(block.codeLeftPx + b.center.x, block.codeTopPx + b.center.y)))
    }

    @Test
    fun aBlockOfAListItemIsDrawnAtTheTextOfItsItem() {
        val text = "- a\n  ```\n  x\n  ```\nafter"
        val block = shown(text, caret = text.length).codeBlocks.single()
        assertTrue(block.leftPx > 0f)
        assertEquals(600f - block.leftPx, block.layout.widthPx, 0.01f, "as wide as the rest of the line")
        // A tap on its code, after the left of the item
        val x = block.layout.code.getBoundingBox(block.layout.code.layoutInput.text.text.indexOf('x'))
        assertEquals(0, block.layout.codeOffsetAt(Offset(block.layout.codeLeftPx + x.center.x, block.layout.codeTopPx + x.center.y)) - 2, "the x after the 2 spaces taken off")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun typingADelimiterInTheFieldSurroundsTheSelectionAndIsUndoneInOneStep() = runDesktopComposeUiTest {
        val controller = PageboardController(document = Document(), coroutineScope = CoroutineScope(Dispatchers.Unconfined))
        val editor = controller.textEditorManager
        editor.startNewText(0, 10f, 10f)
        setContent {
            BasicTextField(value = editor.value, onValueChange = { editor.updateValue(it) })
        }
        onNode(hasSetTextAction()).performTextInput("Hello world")
        runOnIdle { editor.updateValue(editor.value.copy(selection = TextRange(6, 11))) }
        onNode(hasSetTextAction()).performTextInput("*")
        runOnIdle { assertEquals("Hello *world*", editor.value.text) }

        // Undone by the undo of the field
        val primary = if (platformConventions.shortcutModifier == ShortcutModifier.COMMAND) Key.MetaLeft else Key.CtrlLeft
        onNode(hasSetTextAction()).performKeyInput {
            keyDown(primary)
            pressKey(Key.Z)
            keyUp(primary)
        }
        runOnIdle { assertEquals("Hello world", editor.value.text) }
    }

    @Test
    fun theNumbersStartFromTheFirstLineOfTheBlock() {
        val block = layout("a\nb\nc", numbered = true, firstLine = 98)
        assertEquals(listOf("98", "99", "100"), block.numbers.map { it.layout.layoutInput.text.text })
        assertTrue(block.codeLeftPx > layout("a\nb\nc", numbered = true).codeLeftPx, "a column as wide as the last number")
        val shown = shown("Text\n```kotlin showLineNumbers startFrom=\"10\"\na\nb\n```", 0)
        assertEquals(listOf("10", "11"), shown.codeBlocks.single().layout.numbers.map { it.layout.layoutInput.text.text }, "read after the fence")
    }

    @Test
    fun aBlockWithNoLanguageIsColoredInTheOneItLooksLike() {
        val code = "def area(r):\n    if r < 0:\n        return None\n    return 3.14 * r * r"
        val guessed = layout(code, language = "")
        assertTrue(guessed.code.layoutInput.text.spanStyles.any { it.item.color != Color.Unspecified }, "colored")
        assertEquals(null, guessed.label, "no name written: none shown")
        assertTrue(layout("hello world", language = "").code.layoutInput.text.spanStyles.none { it.item.color != Color.Unspecified }, "like no language: one color")
    }

    @Test
    fun aSelectionGoesThroughTheCodeOfABlockByCharacters() {
        // "ab\n" then the block: its fence on 3..9, its code "xy" on 10..12, its closing fence on 13..16
        val text = "ab\n```kt\nxy\n```\ncd"
        val shown = shown(text, 0)
        val block = shown.codeBlocks.single()
        assertEquals(block.codeStart, CodeBlockCaret.extended(shown, 2, forward = true, length = text.length), "over the opening fence into the code")
        assertEquals(block.codeStart + 1, CodeBlockCaret.extended(shown, block.codeStart, forward = true, length = text.length), "a character of the code")
        assertEquals(block.end + 1, CodeBlockCaret.extended(shown, block.codeEnd, forward = true, length = text.length), "over the closing fence out of it")
        assertEquals(block.codeEnd, CodeBlockCaret.extended(shown, block.end + 1, forward = false, length = text.length), "back into the code")
        assertEquals(2, CodeBlockCaret.extended(shown, block.codeStart, forward = false, length = text.length), "back out of it")
        assertEquals(null, CodeBlockCaret.extended(shown, 0, forward = true, length = text.length), "far from a block: the field's own")
    }

    @Test
    fun aSelectionDrawnByAMouseIntoABlockTakesItsCharacters() {
        val text = "ab\n```kt\nxy\n```\ncd"
        val shown = shown(text, 0)
        val block = shown.codeBlocks.single()
        assertEquals(TextRange(1, block.codeStart + 1), CodeBlockCaret.drawn(shown, 1, block.codeStart + 1))
        assertEquals(TextRange(block.codeStart, 19), CodeBlockCaret.drawn(shown, block.codeStart, 19), "from the code out of it")
        assertEquals(null, CodeBlockCaret.drawn(shown, 0, 2), "in the text alone: the field's own")
    }
}
