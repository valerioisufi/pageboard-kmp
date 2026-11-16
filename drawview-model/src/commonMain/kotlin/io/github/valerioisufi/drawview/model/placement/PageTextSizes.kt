package io.github.valerioisufi.drawview.model.placement

import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.PdfTextLayout
import io.github.valerioisufi.drawview.model.StrokeTool
import io.github.valerioisufi.drawview.model.geometry.fitPdfPage
import io.github.valerioisufi.drawview.model.text.TextSizes
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The sizes of the characters on a page, in typographic points of the page, each `null` when the
 * page has too little of it to tell (see `docs/plans/placement-text-size.md`).
 *
 * @param typed the size of most of the characters typed on the page.
 * @param slide the size of the body of the text of its PDF page, as the page shows it.
 * @param handwriting the size of a font as large as the handwriting of the page.
 */
public data class PageTextSizes(
    public val typed: Float? = null,
    public val slide: Float? = null,
    public val handwriting: Float? = null
) {
    public companion object {
        /** The least characters typed that tell their size. */
        public const val MIN_TYPED_CHARS: Int = 20

        /** The least characters of a PDF page that tell the size of its body. */
        public const val MIN_SLIDE_CHARS: Int = 40

        /** The least strokes of writing that tell the size of the handwriting. */
        public const val MIN_STROKES: Int = 10

        /** The height of a stroke of writing for a font of one point (a lowercase letter with its stem). */
        public const val HANDWRITING_HEIGHT_PER_POINT: Float = 0.7f

        /** The sizes of [page], the text of its PDF page read from [pdfText] when there is one. */
        public fun of(page: DrawPage, pdfText: PdfTextLayout? = null): PageTextSizes =
            PageTextSizes(typedSize(page.texts), pdfText?.let { slideSize(page, it) }, handwritingSize(page))

        /** The size of most of the characters typed in [document], or `null` when it has too few. */
        public fun ofDocument(document: DrawDocument): Float? = typedSize(document.pages.flatMap { it.texts })

        /** The size covering the most characters of [texts] (a large heading does not outweigh the paragraphs). */
        private fun typedSize(texts: List<DrawText>): Float? {
            val chars = mutableMapOf<Float, Int>()
            for (text in texts) {
                val count = text.text.count { !it.isWhitespace() }
                if (count > 0) chars[text.fontSize] = (chars[text.fontSize] ?: 0) + count
            }
            if (chars.values.sum() < MIN_TYPED_CHARS) return null
            return chars.entries.sortedWith(compareByDescending<Map.Entry<Float, Int>> { it.value }.thenBy { it.key }).first().key
        }

        /**
         * The most frequent height of the boxes of the characters of [layout], in points of [page]
         * (a slide shown smaller than its PDF has smaller characters), to half a point.
         */
        private fun slideSize(page: DrawPage, layout: PdfTextLayout): Float? {
            if (!layout.hasText || layout.widthPt <= 0f) return null
            val fit = fitPdfPage(layout.widthPt.toDouble(), layout.heightPt.toDouble(), page.rect())
            val mmPerPdfPoint = fit.width / layout.widthPt
            val heights = mutableMapOf<Int, Int>()
            var count = 0
            for (index in 0 until layout.charCount) {
                if (!layout.hasBox(index)) continue
                val box = layout.boxOf(index)
                val points = box.height * mmPerPdfPoint / TextSizes.PT_TO_MM
                val halves = (points * 2f).roundToInt()
                if (halves <= 0) continue
                heights[halves] = (heights[halves] ?: 0) + 1
                count++
            }
            if (count < MIN_SLIDE_CHARS) return null
            return heights.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).first().key / 2f
        }

        /** The size of a font as large as the median height of the strokes of writing of [page]. */
        private fun handwritingSize(page: DrawPage): Float? {
            val heights = page.strokes
                .filter { it.brushConfig.tool != StrokeTool.HIGHLIGHTER && it.brushConfig.tool != StrokeTool.ERASER && it.brushConfig.tool != StrokeTool.LASSO }
                .map { it.bounds.height }
                .filter { it > 0f }
                .sorted()
            if (heights.size < MIN_STROKES) return null
            val median = heights[heights.size / 2]
            return median / TextSizes.PT_TO_MM / HANDWRITING_HEIGHT_PER_POINT
        }
    }
}

/**
 * The size a note of the application takes on a page (see `docs/plans/placement-text-size.md`): like the
 * student's typed texts there, else a little smaller than the body of the slide, else as large as
 * the handwriting, else the size of a new text; always a preset of [TextSizes] between [MIN] and
 * [MAX].
 */
public object TextSizePolicy {
    /** The smallest size of a note: smaller, it is hard to read. */
    public const val MIN: Float = 8f

    /** The largest size of a note: larger, it takes the room of the notes of the student. */
    public const val MAX: Float = 24f

    /** The share of the body of a slide a note on it takes: it annotates the slide without competing with it. */
    public const val SLIDE_SHARE: Float = 0.85f

    /** The size of a note on a page of [sizes], [default] the size of a new text. */
    public fun forNote(sizes: PageTextSizes, default: Float): Float =
        nearestPreset(sizes.typed ?: sizes.slide?.times(SLIDE_SHARE) ?: sizes.handwriting ?: default)

    /** One preset smaller than [size], never under [MIN] (a margin note made to fit beside its anchor). */
    public fun smaller(size: Float): Float = TextSizes.smaller(size).coerceAtLeast(MIN)

    /** The preset of [TextSizes] nearest to [size], between [MIN] and [MAX]. */
    public fun nearestPreset(size: Float): Float =
        TextSizes.PRESETS.filter { it in MIN..MAX }.minBy { abs(it - size) }
}
