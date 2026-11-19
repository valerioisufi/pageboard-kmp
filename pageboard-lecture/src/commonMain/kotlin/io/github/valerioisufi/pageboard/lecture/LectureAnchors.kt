package io.github.valerioisufi.pageboard.lecture

import io.github.valerioisufi.pageboard.lecture.handwriting.groupStrokes
import io.github.valerioisufi.pageboard.model.PageElement
import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.PdfTextLayout
import io.github.valerioisufi.pageboard.model.geometry.Rect
import io.github.valerioisufi.pageboard.model.geometry.fitPdfPage
import kotlin.math.abs

/**
 * What a note of the lecture is about on its page, found from the time the content of the page was
 * written (`PageElement.added`, the clock of the device that wrote it): the block of writing the
 * student was adding when the note was said, else the last block written during the lecture.
 * A preference for the placement of the note, never a constraint: the clock of another device may
 * be off, and a lecture imported has no writing of its time.
 */
public object LectureAnchors {

    /** How long before the moment a note was said the writing about it may have started. */
    public const val BEFORE_MS: Long = 60_000

    /** How long after the moment a note was said the writing about it may have gone on. */
    public const val AFTER_MS: Long = 30_000

    /**
     * The box of the writing of [page] about a note said at [saidAt] (wall clock) during a lecture
     * from [lectureStart] until [lectureEnd], or `null` when the page has none of that time.
     */
    public fun anchorOf(page: Page, saidAt: Long, lectureStart: Long, lectureEnd: Long): Rect? {
        val written = page.strokes + page.texts
        return blockNearest(page, written.filter { it.added.time in (saidAt - BEFORE_MS)..(saidAt + AFTER_MS) }, saidAt)
            ?: blockNearest(page, written.filter { it.added.time in lectureStart..lectureEnd }, lectureEnd)
    }

    /**
     * The box of the words of [page] that [quote] (a few words quoted by the model) is about: a text
     * typed on the page, a block of [handwriting] (its box and its text as read), or the run of
     * words of the text of its PDF page ([pdfText], drawn on the page as [fitPdfPage] places it)
     * sharing the most words with the quote; `null` when none shares [MIN_SHARED_WORDS] of them.
     */
    public fun quoteAnchor(page: Page, quote: String, handwriting: List<Pair<Rect, String>> = emptyList(), pdfText: PdfTextLayout? = null): Rect? {
        val wanted = wordsOf(quote)
        if (wanted.isEmpty()) return null
        var best: Rect? = null
        var bestShare = MIN_SHARED_WORDS - 1e-6
        fun consider(box: Rect?, words: List<String>) {
            if (box == null || box.isEmpty) return
            val share = shareOf(wanted, words)
            if (share > bestShare + 1e-6) {
                best = box
                bestShare = share
            }
        }
        for (text in page.texts) consider(page.boundsOf(text), wordsOf(text.text))
        for ((box, text) in handwriting) consider(box, wordsOf(text))
        if (pdfText != null && pdfText.hasText) {
            pdfMatch(pdfText, wanted)?.let { (range, share) ->
                if (share > bestShare + 1e-6) {
                    val fit = fitPdfPage(pdfText.widthPt.toDouble(), pdfText.heightPt.toDouble(), page.rect())
                    val scale = fit.width / pdfText.widthPt
                    best = pdfText.lineRects(range.first, range.last + 1)
                        .map { Rect(fit.left + it.left * scale, fit.top + it.top * scale, fit.left + it.right * scale, fit.top + it.bottom * scale) }
                        .reduceOrNull { a, b -> a.union(b) }
                    bestShare = share
                }
            }
        }
        return best
    }

    /** The share of the words of [wanted] found in [words]. */
    private fun shareOf(wanted: List<String>, words: List<String>): Double {
        if (words.isEmpty()) return 0.0
        val available = words.groupingBy { it }.eachCount().toMutableMap()
        var found = 0
        for (word in wanted) {
            val left = available[word] ?: 0
            if (left > 0) {
                found++
                available[word] = left - 1
            }
        }
        return found.toDouble() / wanted.size
    }

    /** The characters of the run of words of [layout] as long as [wanted] sharing the most words with it, and that share. */
    private fun pdfMatch(layout: PdfTextLayout, wanted: List<String>): Pair<IntRange, Double>? {
        // The words of the PDF with the range of their characters
        val words = mutableListOf<Pair<String, IntRange>>()
        val word = StringBuilder()
        var start = -1
        for (index in 0..layout.charCount) {
            val codePoint = if (index < layout.charCount) layout.codePointAt(index) else ' '.code
            val letter = codePoint.toChar().isLetterOrDigit() && codePoint <= 0xFFFF
            if (letter) {
                if (start < 0) start = index
                word.append(codePoint.toChar().lowercaseChar())
            } else if (start >= 0) {
                words += word.toString() to (start until index)
                word.clear()
                start = -1
            }
        }
        if (words.isEmpty()) return null
        val size = wanted.size.coerceAtMost(words.size)
        var best: Pair<IntRange, Double>? = null
        for (from in 0..words.size - size) {
            val window = words.subList(from, from + size)
            val share = shareOf(wanted, window.map { it.first })
            if (best == null || share > best.second + 1e-6) best = (window.first().second.first..window.last().second.last) to share
        }
        return best
    }

    /** The words of [text], in lower case, without the signs. */
    private fun wordsOf(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** The least share of the words of a quote a text must hold to be its anchor. */
    public const val MIN_SHARED_WORDS: Double = 0.6

    /** The box of the block of [elements] holding the one written the nearest to [time]. */
    private fun blockNearest(page: Page, elements: List<PageElement>, time: Long): Rect? {
        val placed = elements.mapNotNull { element -> page.boundsOf(element)?.takeIf { !it.isEmpty }?.let { element to it } }
        if (placed.isEmpty()) return null
        val nearest = placed.indices.minBy { abs(placed[it].first.added.time - time) }
        val block = groupStrokes(placed.map { it.second }).first { nearest in it }
        return block.map { placed[it].second }.reduce { a, b -> a.union(b) }
    }
}
