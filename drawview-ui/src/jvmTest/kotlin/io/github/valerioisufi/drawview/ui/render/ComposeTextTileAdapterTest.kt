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
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.TextAlignment
import io.github.valerioisufi.drawview.model.text.TextSizes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The texts as the canvas lays them out: aligned in the width of their box, measured.
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
        val words = assertNotNull(adapter.layout(text)).words
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
        val layout = assertNotNull(adapter.layout(bulletText))
        val firstWord = layout.words.first()
        assertEquals("• ", firstWord.text)

        val longItem = text("- " + "word ".repeat(30).trim(), width = 40f)
        val longLayout = assertNotNull(adapter.layout(longItem))
        assertTrue(longLayout.words.size > 5)
        val laterLineWords = longLayout.words.filter { it.baseline > longLayout.words[0].baseline }
        assertTrue(laterLineWords.isNotEmpty())
        assertTrue(laterLineWords.first().left > 0f, "Hanging indent indents wrapped lines")
    }
}
