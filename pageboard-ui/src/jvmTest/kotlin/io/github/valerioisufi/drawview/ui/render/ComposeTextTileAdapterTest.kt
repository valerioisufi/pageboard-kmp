package io.github.valerioisufi.drawview.ui.render

import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.canvas.render.tile.TextFace
import io.github.valerioisufi.drawview.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.drawview.canvas.render.tile.TextWord
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.TextAlignment
import io.github.valerioisufi.drawview.model.text.TextSizes
import io.github.valerioisufi.drawview.model.text.code.CodeHighlighter
import io.github.valerioisufi.drawview.model.text.code.CodeToken
import io.github.valerioisufi.drawview.model.text.code.CodeTokenKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The texts as the canvas lays them out: aligned in the width of their box, measured, with their
 * blocks of code and their code in the line.
 */
class ComposeTextTileAdapterTest {

    private val adapter = ComposeTextTileAdapter(createFontFamilyResolver())

    private fun text(content: String, alignment: TextAlignment = TextAlignment.START, width: Float = 100f) = DrawText().apply {
        text = content
        this.alignment = alignment
        this.width = width
        height = 10f
        fontSize = 16f
    }

    /** Left and right of the words of [text] in millimeters of its box. */
    private fun extent(text: DrawText): Pair<Float, Float> {
        val words = assertNotNull(adapter.layout(text, TextTileAdapter.NO_PAPER)).words
        return words.minOf { it.left } to words.maxOf { it.left + it.width }
    }

    @Test
    fun aShortLineIsAlignedInTheWidthOfItsBox() {
        val (startLeft, startRight) = extent(text("Short", alignment = TextAlignment.START))
        assertEquals(0f, startLeft, 0.5f)

        val (centerLeft, centerRight) = extent(text("Short", alignment = TextAlignment.CENTER))
        val lineWidth = startRight - startLeft
        assertEquals((100f - lineWidth) / 2f, centerLeft, 0.5f)
        assertEquals(50f, (centerLeft + centerRight) / 2f, 0.5f)

        val (_, endRight) = extent(text("Short", alignment = TextAlignment.END))
        assertEquals(100f, endRight, 0.5f)
    }

    @Test
    fun aTextIsMeasuredOnTheWidthOfItsBox() {
        val line = assertNotNull(adapter.lineMetrics(16f))
        assertTrue(line.height > 16f * TextSizes.PT_TO_MM, "a line is higher than the size of its font")
        assertTrue(line.baseline in 0f..line.height)

        val oneLine = assertNotNull(adapter.measure(text("Short")))
        assertEquals(line.height, oneLine.height, 0.1f)
        assertTrue(oneLine.width < 30f, "the width of its line, not of its box")

        val long = text("word ".repeat(40).trim(), width = 40f)
        val wrapped = assertNotNull(adapter.measure(long))
        assertTrue(wrapped.height > line.height * 5, "the lines wrap on the width")
        assertTrue(wrapped.width <= 40f + 0.1f)
        long.width = 80f
        assertTrue(assertNotNull(adapter.measure(long)).height < wrapped.height)
    }

    @Test
    fun listItemsRenderWithBulletsAndHangingIndent() {
        val bulletText = text("- First bullet item\n- Second bullet item")
        val layout = assertNotNull(adapter.layout(bulletText, TextTileAdapter.NO_PAPER))
        val firstWord = layout.words.first()
        assertEquals("•", firstWord.text.trim())
        assertTrue(layout.words[1].left - (firstWord.left + firstWord.width) > 0f, "a gap between the bullet and the text")

        val longItem = text("- " + "word ".repeat(30).trim(), width = 40f)
        val longLayout = assertNotNull(adapter.layout(longItem, TextTileAdapter.NO_PAPER))
        assertTrue(longLayout.words.size > 5)
        val laterLineWords = longLayout.words.filter { it.baseline > longLayout.words[0].baseline }
        assertTrue(laterLineWords.isNotEmpty())
        assertTrue(laterLineWords.first().left > 0f, "Hanging indent indents wrapped lines")
    }

    @Test
    fun theFillOfABoxIsItsFirstBackgroundAroundIt() {
        val filled = text("Filled", width = 40f).apply { background = 0xFFD7E8FA.toInt() }
        val layout = assertNotNull(adapter.layout(filled, TextTileAdapter.NO_PAPER))
        val fill = layout.boxes.first()
        val outset = io.github.valerioisufi.drawview.model.text.TextBackground.outsetMm(filled.fontSize)
        assertEquals(0xFFD7E8FA.toInt(), fill.color)
        assertEquals(-outset, fill.left, 0.01f)
        assertEquals(-outset, fill.top, 0.01f)
        assertEquals(40f + 2 * outset, fill.width, 0.05f)
        assertEquals(layout.height + 2 * outset, fill.height, 0.05f)
        assertTrue(assertNotNull(adapter.layout(text("Plain"), TextTileAdapter.NO_PAPER)).boxes.isEmpty(), "no fill, no background")
    }

    @Test
    fun theEmptyLineAfterATableIsALineOfTheText() {
        fun height(content: String) = assertNotNull(adapter.measure(text(content, width = 120f))).height
        val table = "| Day | Topic |\n| --- | --- |\n| 1 | Limits |"
        assertEquals(height(table) + height("x"), height(table + "\n"), 0.01f, "one line more, not the room of the table again")
        assertEquals(height(table + "\nafter"), height(table + "\n"), 0.01f)
    }

    /** The words of [text] that are not spaces, with their text trimmed. */
    private fun words(text: DrawText) = assertNotNull(adapter.layout(text, TextTileAdapter.NO_PAPER)).words.filter { it.text.isNotBlank() }

    private fun List<TextWord>.word(text: String) = first { it.text.trim() == text }

    @Test
    fun aBlockOfCodeIsInTheMonospaceAndTheColorsOfItsLanguageOnItsBackground() {
        val layout = assertNotNull(adapter.layout(text("Code:\n```kotlin\nfun f() = 1\n```\nafter"), TextTileAdapter.NO_PAPER))
        val keyword = layout.words.first { it.text.trim() == "fun" }
        assertEquals(TextFace.MONO, keyword.face)
        assertEquals(0xFFCF222E.toInt(), keyword.color, "a keyword in its color")
        assertEquals(TextFace.MONO, layout.words.first { it.text.trim() == "kotlin" }.face, "the name of the language")
        val box = layout.boxes.single()
        assertEquals(0f, box.left, 0.01f)
        assertEquals(100f, box.width, 0.5f, "the width of the box")
        assertTrue(keyword.left > box.left && keyword.baseline in box.top..box.top + box.height)
        assertTrue(box.color ushr 24 in 1..0x40, "a faint background")
        val after = layout.words.first { it.text.trim() == "after" }
        assertTrue(after.lineTop >= box.top + box.height - 0.1f, "the text goes on under the block")
        assertTrue(layout.words.first { it.text.trim() == "Code:" }.lineBottom <= box.top + 0.1f)
    }

    @Test
    fun theBackgroundsOfTheCodeAreOpaqueOnTheirPaper() {
        val text = text("```\nx = 1\n```\nand `y`")
        val white = assertNotNull(adapter.layout(text, 0xFFFFFFFF.toInt())).boxes
        assertEquals(2, white.size)
        // The color of the text at 7 % over white: opaque, the lines of the paper hidden
        for (box in white) assertEquals(0xFFEDEDED.toInt(), box.color, Integer.toHexString(box.color))
        val yellow = assertNotNull(adapter.layout(text, 0xFFFFF4C2.toInt())).boxes.first()
        assertEquals(0xFF, yellow.color ushr 24)
        assertTrue((yellow.color and 0xFF) < 0xC2, "the paper shows its tone through the color of the text")
        assertTrue(assertNotNull(adapter.layout(text, TextTileAdapter.NO_PAPER)).boxes.all { it.color ushr 24 < 0x40 }, "translucent without a paper")
    }

    @Test
    fun aBlockWithoutALanguageKnownIsInOneColor() {
        val words = words(text("```\nfun f() = 1\n```")) + words(text("```brainfuck\nfun f() = 1\n```"))
        assertTrue(words.filter { it.text.trim() != "brainfuck" }.all { it.color == 0xFF000000.toInt() }, "${words.map { it.text to it.color }}")
    }

    @Test
    fun aLongLineWrapsUnderItsIndentation() {
        val words = words(text("```\n    " + "word ".repeat(30).trim() + "\n```", width = 80f))
        val lineStarts = words.groupBy { it.baseline }.values.map { line -> line.minOf { it.left } }
        assertTrue(lineStarts.size > 2, "the line wraps")
        for (start in lineStarts) assertEquals(lineStarts.first(), start, 0.2f)
        assertTrue(lineStarts.first() > 5f, "after the padding and the indentation")
    }

    @Test
    fun theTabsAndTheEmptyLinesOfTheCodeAreKept() {
        val oneLine = words(text("```\na\nb\n```")).let { it.word("b").baseline - it.word("a").baseline }
        val words = words(text("```\n\tx\n\ny\n```"))
        val x = words.word("x")
        val y = words.word("y")
        assertEquals(2f * oneLine, y.baseline - x.baseline, 0.2f, "an empty line between them")
        assertEquals(4f * x.width, x.left - y.left, 0.2f, "a tab is four columns")
    }

    @Test
    fun aBlockIsAtTheStartOfACenteredText() {
        val words = words(text("Title\n```\nab\n```", alignment = TextAlignment.CENTER))
        assertTrue(words.word("Title").left > 30f)
        assertTrue(words.word("ab").left < 10f)
    }

    @Test
    fun aBlockCountsItsOwnWidthInTheMeasure() {
        val short = assertNotNull(adapter.measure(text("```\nab\n```", width = 150f))).width
        val long = assertNotNull(adapter.measure(text("```\n" + "x".repeat(40) + "\n```", width = 150f))).width
        assertTrue(short < 20f, "its longest line and its padding: $short")
        assertTrue(long > 100f && long < 150f, "$long")
    }

    @Test
    fun codeInTheLineIsOnABackgroundTakingNoRoom() {
        val plain = words(text("a code b"))
        val code = assertNotNull(adapter.layout(text("a `code` b"), TextTileAdapter.NO_PAPER))
        val word = code.words.first { it.text.trim() == "code" }
        assertEquals(TextFace.MONO, word.face)
        val box = code.boxes.single()
        assertTrue(box.left < word.left && box.left + box.width > word.left + word.width - 0.5f)
        assertTrue(word.baseline in box.top..box.top + box.height)
        assertEquals(plain.word("b").baseline, code.words.first { it.text.trim() == "b" }.baseline, 0.01f, "the same line")
    }

    @Test
    fun anEngineGivingWrongTokensOrFailingDrawsTheCodeInOneColor() {
        val wrong = ComposeTextTileAdapter(createFontFamilyResolver(), code = object : CodeHighlighter {
            override fun tokens(code: String, language: String) = listOf(
                CodeToken(CodeTokenKind.KEYWORD, 5, 2), CodeToken(CodeTokenKind.KEYWORD, -1, 100), CodeToken(CodeTokenKind.KEYWORD, 0, 3),
                CodeToken(CodeTokenKind.KEYWORD, 1, 2), CodeToken(CodeTokenKind.KEYWORD, 4, 1_000)
            )
        })
        val layout = assertNotNull(wrong.layout(text("```kotlin\nfun f\n```"), TextTileAdapter.NO_PAPER))
        assertEquals(0xFFCF222E.toInt(), layout.words.first { it.text.trim() == "fun" }.color, "the tokens in order and in the code are kept")
        val failing = ComposeTextTileAdapter(createFontFamilyResolver(), code = object : CodeHighlighter {
            override fun tokens(code: String, language: String): List<CodeToken> = throw IllegalStateException()
        })
        assertNotNull(failing.layout(text("```kotlin\nfun f\n```"), TextTileAdapter.NO_PAPER))
    }

    @Test
    fun theLineNumbersAreWordsOfTheLayout() {
        val words = words(text("```kotlin showLineNumbers\nval a = 1\nval b = 2\n```"))
        // The numbers, not the digits of the code
        val one = words.filter { it.text.trim() == "1" }.minBy { it.left }
        val two = words.filter { it.text.trim() == "2" }.minBy { it.left }
        assertEquals(TextFace.MONO, one.face)
        assertTrue(one.left < words.word("val").left, "before the code")
        assertTrue(two.baseline > one.baseline)
    }

    @Test
    fun aBlockOfAListItemIsAlignedWithTheTextOfItsItem() {
        val layout = assertNotNull(adapter.layout(text("- Compile:\n  ```c\n  int x;\n  ```\nafter"), TextTileAdapter.NO_PAPER))
        val item = layout.words.first { it.text.trim() == "Compile:" }
        val box = layout.boxes.single()
        assertEquals(item.left, box.left, 0.5f, "at the text of the item")
        assertEquals(100f, box.left + box.width, 0.5f, "to the end of the line")
        assertTrue(layout.words.first { it.text.trim() == "int" }.left > box.left)
        val top = assertNotNull(adapter.layout(text("Compile:\n```c\nint x;\n```"), TextTileAdapter.NO_PAPER)).boxes.single()
        assertEquals(0f, top.left, 0.01f, "out of a list, at the left of the box")
    }
}
