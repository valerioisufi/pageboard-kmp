package io.github.valerioisufi.pageboard.handwriting.ui

import io.github.valerioisufi.pageboard.canvas.port.EditorDocument
import io.github.valerioisufi.pageboard.handwriting.HandwritingReading
import io.github.valerioisufi.pageboard.handwriting.InkReading
import io.github.valerioisufi.pageboard.handwriting.InkSample
import io.github.valerioisufi.pageboard.model.StrokeElement
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.RemoveElements
import io.github.valerioisufi.pageboard.model.change.toData
import io.github.valerioisufi.pageboard.model.ink.isHandwriting
import io.github.valerioisufi.pageboard.model.ink.unionOf
import io.github.valerioisufi.pageboard.model.placement.PageTextSizes
import io.github.valerioisufi.pageboard.model.placement.TextSizePolicy
import io.github.valerioisufi.pageboard.model.text.TextSizes

/**
 * Handwriting converted into a text box (Controller): the strokes read with the readers of the
 * device ([reading], in [language]), and the text box that replaces them, at their place, as wide
 * as they are, its letters as high as the handwritten ones, in their color. The replacement is one
 * change to undo, made of the changes of today (`RemoveElements`, `AddElements`): nothing new
 * travels to the other devices.
 */
public class HandwritingConversion(
    public val reading: HandwritingReading,
    private val language: () -> String
) {
    /** Whether the device reads the language of the notes now. */
    public suspend fun readsText(): Boolean = reading.readsText(language())

    /** The reading of [strokes] (the strokes of writing among them), `null` when nothing is read. */
    public suspend fun read(strokes: List<StrokeElement>): InkReading? {
        val ink = InkSample.of(strokes)
        if (ink.isEmpty) return null
        return reading.readText(ink, language())
    }

    /** The text box holding [text] in the place of [strokes]. */
    public fun textBox(strokes: List<StrokeElement>, text: String): TextElement {
        val writing = strokes.filter { it.brushConfig.tool.isHandwriting }.ifEmpty { strokes }
        // The samples where the pen went, not the ink around them
        val strokeBounds = writing.map { stroke -> InkSample.of(listOf(stroke)).bounds ?: stroke.bounds }
        val bounds = unionOf(strokeBounds)
        return TextElement().apply {
            this.text = text
            x = bounds.left
            y = bounds.top
            width = bounds.width.coerceAtLeast(MIN_WIDTH_MM)
            fontSize = sizeOf(strokeBounds.map { it.height })
            color = colorOf(writing)
            height = TextSizes.estimatedHeightMm(text, fontSize)
        }
    }

    /**
     * Replaces [strokes] of the page [pageId] with [box] in [document], as one change to undo; the
     * box, or `null` when the document cannot be changed.
     */
    public fun replace(document: EditorDocument, pageId: String, strokes: List<StrokeElement>, box: TextElement): TextElement? {
        if (document.isReadOnly) return null
        document.group {
            document.submit(RemoveElements(pageId, strokes.map { it.id }))
            document.submit(AddElements(pageId, listOf(box.toData())))
        }
        return box
    }

    private companion object {
        const val MIN_WIDTH_MM = 20f

        /** The size of a font as large as the median of [strokeHeights] (millimeters), within the sizes of the notes. */
        fun sizeOf(strokeHeights: List<Float>): Float {
            val heights = strokeHeights.filter { it > 0f }.sorted()
            if (heights.isEmpty()) return DEFAULT_SIZE
            val size = heights[heights.size / 2] / TextSizes.PT_TO_MM / PageTextSizes.HANDWRITING_HEIGHT_PER_POINT
            return TextSizes.PRESETS.minByOrNull { kotlin.math.abs(it - size) }!!.coerceIn(TextSizePolicy.MIN, TextSizePolicy.MAX)
        }

        /** The color most of [strokes] are written in. */
        fun colorOf(strokes: List<StrokeElement>): Int =
            strokes.groupingBy { it.brushConfig.color }.eachCount().maxByOrNull { it.value }?.key ?: BLACK

        const val DEFAULT_SIZE = 12f
        const val BLACK = 0xFF000000.toInt()
    }
}
