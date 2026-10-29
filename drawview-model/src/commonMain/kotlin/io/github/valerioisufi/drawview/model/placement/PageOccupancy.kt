package io.github.valerioisufi.drawview.model.placement

import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawPdf
import io.github.valerioisufi.drawview.model.DrawTextMarkup
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.PdfTextLayout
import io.github.valerioisufi.drawview.model.geometry.Rect
import io.github.valerioisufi.drawview.model.geometry.fitPdfPage

/**
 * What a page holds, as the boxes its content covers in page millimeters, for the placement of
 * new content around it (see [PlacementPlanner]).
 *
 * @param widthMm width of the page.
 * @param heightMm height of the page.
 * @param boxes the areas taken: the elements of the page and the lines of the text of its PDF
 *   page, without margin.
 * @param lineSpacingMm the spacing of the lines of a ruled or squared page, which new content is
 *   aligned to (`0`: none).
 */
public class PageOccupancy(
    public val widthMm: Float,
    public val heightMm: Float,
    public val boxes: List<Rect>,
    public val lineSpacingMm: Float = 0f
) {

    /** The page rectangle. */
    public val pageRect: Rect get() = Rect(0f, 0f, widthMm, heightMm)

    /** This occupancy with [box] taken too (content placed meanwhile). */
    public operator fun plus(box: Rect): PageOccupancy = PageOccupancy(widthMm, heightMm, boxes + box, lineSpacingMm)

    public companion object {

        /**
         * The occupancy of [page]: every element shown but those of [excluded] (by identifier),
         * by its bounds; the PDF page under them by the lines of its text when [pdfText] gives
         * them, whole otherwise (a scanned page, or no PDF engine). The marks of the PDF text are
         * on the text, already taken.
         */
        public fun of(
            page: DrawPage,
            pdfText: (DrawPdf) -> PdfTextLayout? = { null },
            excluded: Set<String> = emptySet()
        ): PageOccupancy {
            val boxes = mutableListOf<Rect>()
            for (element in page.elements()) {
                if (element.id in excluded || element is DrawTextMarkup) continue
                if (element is DrawPdf) {
                    boxes += pdfBoxes(page, pdfText(element))
                    continue
                }
                page.boundsOf(element)?.takeIf { !it.isEmpty }?.let { boxes += it }
            }
            val spacing = when (val background = page.background) {
                is PageBackground.Ruled -> background.spacingMm
                is PageBackground.Grid -> background.spacingMm
                else -> 0f
            }
            return PageOccupancy(page.widthMm, page.heightMm, boxes, spacing)
        }

        /** The lines of the text of a PDF page drawn on [page], or the whole page without text. */
        private fun pdfBoxes(page: DrawPage, layout: PdfTextLayout?): List<Rect> {
            if (layout == null || !layout.hasText) return listOf(page.rect())
            val fit = fitPdfPage(layout.widthPt.toDouble(), layout.heightPt.toDouble(), page.rect())
            val scale = fit.width / layout.widthPt
            return layout.lineRects(0, layout.charCount).map {
                Rect(fit.left + it.left * scale, fit.top + it.top * scale, fit.left + it.right * scale, fit.top + it.bottom * scale)
            }
        }
    }
}
