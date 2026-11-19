package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawPdf
import io.github.valerioisufi.drawview.model.DrawTextMarkup
import io.github.valerioisufi.drawview.model.PdfTextLayout
import io.github.valerioisufi.drawview.model.TextMarkupKind
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.DocumentChange
import io.github.valerioisufi.drawview.model.change.MarkupStyle
import io.github.valerioisufi.drawview.model.change.RestyleMarkups
import io.github.valerioisufi.drawview.model.geometry.Rect

/**
 * The items of a proposal on the highlights, underlines and strikeouts of the text of a PDF page
 * (Information Expert): a passage a model quotes, found in the text of the page as the editor reads it
 * ([AgentEngines.pdfLayout]), turned into a [DrawTextMarkup] as the editor makes one when the user
 * selects that passage. A model never gives the indices of the text layer (see
 * `docs/plans/agent-coverage.md`).
 */
internal object MarkupItems {
    val KINDS: Map<String, TextMarkupKind> = TextMarkupKind.entries.associateBy { it.name.lowercase() }

    const val MAX_QUOTE_LENGTH: Int = 2000

    /** The yellow of a highlight, seen through. */
    const val HIGHLIGHT_COLOR: Int = 0x80FFEB3B.toInt()

    /** A passage found: the PDF page it is on, its layout, its characters from [start] until [end]. */
    class Passage(val pdf: DrawPdf, val layout: PdfTextLayout, val start: Int, val end: Int) {
        val text: String get() = layout.text(start, end)
        val rects: List<Rect> get() = layout.lineRects(start, end)
    }

    /**
     * The passage [quote] of the text of the PDF pages of [page], its [occurrence]-th one (from 1), or
     * `null`. Spaces and line breaks count as one space, on either side; the letters must be the same.
     */
    fun find(document: DrawDocument, page: DrawPage, quote: String, occurrence: Int, engines: AgentEngines): Passage? {
        val wanted = collapse(quote)
        if (wanted.isEmpty()) return null
        var left = occurrence
        for (pdf in page.pdfs.filter { it.removed == null }) {
            val layout = engines.pdfLayout(document, pdf)?.takeIf { it.hasText } ?: continue
            val (text, indices) = flatten(layout)
            var from = text.indexOf(wanted)
            while (from >= 0) {
                if (--left == 0) return Passage(pdf, layout, indices[from], indices[from + wanted.length - 1] + 1)
                from = text.indexOf(wanted, from + 1)
            }
        }
        return null
    }

    /** The text of [layout] with its spaces collapsed, and the character of the layout each of its characters comes from. */
    private fun flatten(layout: PdfTextLayout): Pair<String, IntArray> {
        val text = StringBuilder()
        val indices = ArrayList<Int>()
        for (index in 0 until layout.charCount) {
            for (char in layout.text(index, index + 1)) {
                if (char.isWhitespace()) {
                    if (text.isNotEmpty() && text.last() != ' ') {
                        text.append(' ')
                        indices += index
                    }
                } else {
                    text.append(char)
                    indices += index
                }
            }
        }
        return text.toString() to indices.toIntArray()
    }

    private fun collapse(text: String): String = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /** The mark [item] makes of [passage], on [page]. */
    fun markOf(item: ProposalItem.AddMarkup, passage: Passage): DrawTextMarkup {
        val kind = KINDS.getValue(item.kind)
        val color = item.color?.let { ElementItems.parseColor(it) } ?: if (kind == TextMarkupKind.HIGHLIGHT) HIGHLIGHT_COLOR else AGENT_TEXT_COLOR
        return DrawTextMarkup(
            resourceId = passage.pdf.resourceId,
            pdfPageIndex = passage.pdf.pdfPageIndex,
            pdfWidthPt = passage.layout.widthPt,
            pdfHeightPt = passage.layout.heightPt,
            startIndex = passage.start,
            endIndex = passage.end,
            text = passage.text,
            rects = passage.rects,
            kind = kind,
            color = color
        )
    }

    /** The box of [passage] on [page], in millimeters. */
    fun boxOf(page: DrawPage, passage: Passage): Rect {
        val mark = DrawTextMarkup(
            resourceId = passage.pdf.resourceId, pdfPageIndex = passage.pdf.pdfPageIndex, pdfWidthPt = passage.layout.widthPt,
            pdfHeightPt = passage.layout.heightPt, startIndex = passage.start, endIndex = passage.end, text = "", rects = passage.rects,
            kind = TextMarkupKind.HIGHLIGHT, color = 0
        )
        val matrix = mark.pointsToPageMm(page)
        return passage.rects.map { matrix.mapRect(it) }.reduce { all, next -> all.union(next) }
    }

    /** The marks [refs] of [page], or `null` when one is not a mark there. */
    fun marksOf(page: DrawPage, refs: List<ElementRef>): List<DrawTextMarkup>? {
        val shown = page.markups.filter { it.removed == null }.associateBy { it.id }
        return refs.map { shown[it.id] ?: return null }
    }

    /** The changes of [item], applied to [document] as it is now (not stale). */
    fun changesOf(document: DrawDocument, item: ProposalItem, engines: AgentEngines): List<DocumentChange> = when (item) {
        is ProposalItem.AddMarkup -> {
            val page = document.pageById(item.page)!!
            val passage = find(document, page, item.quote, item.occurrence, engines)!!
            listOf(AddElements.of(page, listOf(markOf(item, passage))))
        }
        is ProposalItem.RestyleMarkups -> {
            val page = document.pageById(item.page)!!
            val marks = marksOf(page, item.markups)!!
            val styles = marks.map { mark ->
                MarkupStyle(mark.id, item.kind?.let { KINDS.getValue(it) } ?: mark.kind, item.color?.let { ElementItems.parseColor(it) } ?: mark.color)
            }
            listOf(RestyleMarkups(page.id, styles))
        }
        else -> emptyList()
    }
}
