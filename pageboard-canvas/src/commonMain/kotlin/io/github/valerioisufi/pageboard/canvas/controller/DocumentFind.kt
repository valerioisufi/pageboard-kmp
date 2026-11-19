package io.github.valerioisufi.pageboard.canvas.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.valerioisufi.pageboard.canvas.render.tile.TextBlockLayout
import io.github.valerioisufi.pageboard.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.PdfTextLayout
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import io.github.valerioisufi.pageboard.model.geometry.Rect
import io.github.valerioisufi.pageboard.model.pdfPointsToPageMm
import io.github.valerioisufi.pageboard.model.change.ChangeObserver
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Finding a text in the document open (see `docs/plans/find-in-document.md`): the words of its texts as
 * they are shown (the notation of Markdown hidden, as the adapter of the texts lays them out) and
 * the text of its PDF pages, without telling capitals and accents apart. The places found are
 * drawn on the pages (see `FindOverlay`), the current one shown on screen.
 *
 * The search runs on the thread of the controller, a page at a time; a new query, or [refresh]
 * after a change of the document, starts it again.
 */
public class DocumentFind internal constructor(private val controller: PageboardController) {

    /** What a place found is in: a text of the page or the text of a PDF page. */
    public enum class Source { TEXT, PDF }

    /**
     * A place found: on the page [pageId] (at [pageIndex] when it was found), in the element
     * [elementId] ([source]), from the character [start] to [end] of what that element shows; its
     * [rects] in millimeters of the page, a line each.
     */
    public class Match(
        public val pageId: String,
        public val pageIndex: Int,
        public val elementId: String,
        public val source: Source,
        public val start: Int,
        public val end: Int,
        public val rects: List<Rect>
    ) {
        /** The rectangle around every line of the place. */
        public val bounds: Rect
            get() = Rect(rects.minOf { it.left }, rects.minOf { it.top }, rects.maxOf { it.right }, rects.maxOf { it.bottom })
    }

    /** Whether the bar of the search is open. */
    public var isOpen: Boolean by mutableStateOf(false)
        private set

    /** What is looked for. */
    public var query: String by mutableStateOf("")
        private set

    /** The places found so far, in the order of the pages, then of the elements, then of the text. */
    public var matches: List<Match> by mutableStateOf(emptyList())
        private set

    /** The index in [matches] of the place shown, -1 for none. */
    public var current: Int by mutableIntStateOf(-1)
        private set

    /** Whether the pages are still being read. */
    public var isSearching: Boolean by mutableStateOf(false)
        private set

    private var job: Job? = null
    private var pending: Job? = null

    /** A change of the document while the bar is open: the places looked for again, the view kept. */
    private val observer: ChangeObserver = ChangeObserver {
        if (!isOpen || query.isBlank()) return@ChangeObserver
        pending?.cancel()
        pending = controller.coroutineScope.launch {
            delay(REFRESH_DELAY_MS)
            refresh(jump = false)
        }
    }

    /** Opens the bar of the search, with what it held last. */
    public fun open() {
        if (!isOpen) controller.session.addObserver(observer)
        isOpen = true
        if (query.isNotEmpty()) refresh()
    }

    /** Closes the bar and takes the places found off the pages. */
    public fun close() {
        if (isOpen) controller.session.removeObserver(observer)
        isOpen = false
        pending?.cancel()
        job?.cancel()
        matches = emptyList()
        current = -1
        isSearching = false
        controller.requestRedraw()
    }

    /** Looks for [text] (empty: nothing), the first place found at or after the page on screen shown. */
    public fun search(text: String) {
        query = text
        refresh()
    }

    /**
     * Looks for [query] again: the first place at or after the page on screen shown when [jump];
     * else (the document changed) the place shown before kept, the view where it is.
     */
    public fun refresh(jump: Boolean = true) {
        job?.cancel()
        val wanted = fold(query.trim())
        val before = matches.getOrNull(current)
        if (jump) matches = emptyList()
        current = -1
        if (wanted.isEmpty()) {
            isSearching = false
            controller.requestRedraw()
            return
        }
        isSearching = true
        val from = controller.viewport.mostVisiblePageIndex() ?: 0
        job = controller.coroutineScope.launch {
            val found = ArrayList<Match>()
            val pages = controller.document.pages.toList()
            for ((index, page) in pages.withIndex()) {
                found += matchesOn(page, index, wanted)
                // The first place after the page on screen as soon as it is found
                if (jump && current < 0 && index >= from && found.isNotEmpty()) {
                    matches = found.toList()
                    show(found.indexOfFirst { it.pageIndex >= from }.takeIf { it >= 0 } ?: 0)
                }
                yield()
            }
            matches = found
            if (!jump) {
                // The same place, or the first one after where it was
                current = if (before == null || found.isEmpty()) -1 else found.indexOfFirst {
                    it.pageIndex > before.pageIndex || (it.pageIndex == before.pageIndex && (it.elementId != before.elementId || it.start >= before.start))
                }.takeIf { it >= 0 } ?: found.lastIndex
            } else if (current < 0 && found.isNotEmpty()) {
                show(0)
            }
            if (current >= found.size) current = found.lastIndex
            isSearching = false
            controller.requestRedraw()
        }
    }

    /** Shows the next place found, after the last one the first. */
    public fun next() {
        if (matches.isEmpty()) return
        show(if (current + 1 > matches.lastIndex) 0 else current + 1)
    }

    /** Shows the place found before, before the first one the last. */
    public fun previous() {
        if (matches.isEmpty()) return
        show(if (current - 1 < 0) matches.lastIndex else current - 1)
    }

    /** Shows the place found at [index] in [matches], its page centered on it. */
    public fun show(index: Int) {
        val match = matches.getOrNull(index) ?: return
        current = index
        controller.pageManager.jumpToPage(match.pageId, match.pageIndex + 1, match.bounds)
        controller.requestRedraw()
    }

    /** The places of [wanted] (folded) on [page], at [pageIndex]: its texts, then its PDF pages. */
    private fun matchesOn(page: Page, pageIndex: Int, wanted: String): List<Match> {
        val found = ArrayList<Match>()
        val adapter = controller.tileManager.tileRenderer.textAdapter
        if (adapter != null) {
            for (text in page.texts) {
                val layout = try {
                    adapter.layout(text, TextTileAdapter.NO_PAPER)
                } catch (_: Throwable) {
                    null
                } ?: continue
                found += textMatches(page, pageIndex, text, layout, wanted)
            }
        }
        for (pdf in page.pdfs) {
            val layout = controller.textMarkupManager.layoutOf(pdf) ?: continue
            if (!layout.hasText) continue
            found += pdfMatches(page, pageIndex, pdf.id, layout, wanted)
        }
        return found
    }

    private fun textMatches(page: Page, pageIndex: Int, text: TextElement, layout: TextBlockLayout, wanted: String): List<Match> {
        val shown = ShownWords.of(layout)
        val blockToPage = Matrix2D().postRotate(text.rotation).postTranslate(text.x, text.y)
        return occurrences(fold(shown.text), wanted).map { (start, end) ->
            Match(page.id, pageIndex, text.id, Source.TEXT, start, end, shown.rects(start, end).map { blockToPage.mapRect(it) })
        }
    }

    private fun pdfMatches(page: Page, pageIndex: Int, pdfId: String, layout: PdfTextLayout, wanted: String): List<Match> {
        val chars = StringBuilder()
        // The index of the character of the layout each character of the text stands for
        val indices = ArrayList<Int>()
        for (index in 0 until layout.charCount) {
            val code = layout.codePointAt(index)
            val char = if (code in 0..0xFFFF) code.toChar() else ' '
            chars.append(char)
            indices += index
        }
        val pointsToMm = pdfPointsToPageMm(layout.widthPt, layout.heightPt, page.widthMm, page.heightMm)
        return occurrences(fold(chars.toString()), wanted).mapNotNull { (start, end) ->
            val rects = layout.lineRects(indices[start], indices[end - 1] + 1).map { pointsToMm.mapRect(it) }
            if (rects.isEmpty()) null else Match(page.id, pageIndex, pdfId, Source.PDF, start, end, rects)
        }
    }

    internal companion object {
        /** How long the document stays unchanged before the places are looked for again. */
        const val REFRESH_DELAY_MS: Long = 400

        /**
         * [text] as it is compared: in lower case, the accents of the Latin letters taken off and
         * the spaces of any kind as one space. Each character stays one character, so that the
         * offsets are those of [text].
         */
        fun fold(text: String): String {
            val out = CharArray(text.length)
            for ((i, c) in text.withIndex()) {
                val lower = c.lowercaseChar()
                out[i] = when {
                    lower.isWhitespace() -> ' '
                    else -> FOLDED[lower] ?: lower
                }
            }
            return out.concatToString()
        }

        /** The ranges (start, end exclusive) of [wanted] in [text], not overlapping. */
        fun occurrences(text: String, wanted: String): List<Pair<Int, Int>> {
            if (wanted.isEmpty()) return emptyList()
            val found = ArrayList<Pair<Int, Int>>()
            var from = 0
            while (true) {
                val at = text.indexOf(wanted, from)
                if (at < 0) break
                found += at to at + wanted.length
                from = at + wanted.length
            }
            return found
        }

        /** The Latin letters with an accent and the letter without it. */
        private val FOLDED: Map<Char, Char> = buildMap {
            fun add(letters: String, base: Char) = letters.forEach { put(it, base) }
            add("àáâãäåāăą", 'a')
            add("çćĉċč", 'c')
            add("ďđ", 'd')
            add("èéêëēĕėęě", 'e')
            add("ĝğġģ", 'g')
            add("ĥħ", 'h')
            add("ìíîïĩīĭįı", 'i')
            add("ĵ", 'j')
            add("ķ", 'k')
            add("ĺļľŀł", 'l')
            add("ñńņňŉ", 'n')
            add("òóôõöøōŏő", 'o')
            add("ŕŗř", 'r')
            add("śŝşš", 's')
            add("ţťŧ", 't')
            add("ùúûüũūŭůűų", 'u')
            add("ŵ", 'w')
            add("ýÿŷ", 'y')
            add("źżž", 'z')
            // Typographic quotes and dashes as the ones of a keyboard
            add("‘’‚‛", '\'')
            add("“”„‟", '"')
            add("‐‑‒–—", '-')
        }
    }
}

/**
 * The words of a text block laid out ([TextBlockLayout]) as one text: each word with the spaces
 * after it, a space between two lines when the first ends with none; and where each character is
 * in the block, by the share of its word it is (the advances of the characters are not known).
 */
internal class ShownWords private constructor(val text: String, private val owners: IntArray, private val words: List<io.github.valerioisufi.pageboard.canvas.render.tile.TextWord>) {

    /** The rectangles, in millimeters of the block, of the characters from [start] to [end] (exclusive), one per line. */
    fun rects(start: Int, end: Int): List<Rect> {
        val rects = ArrayList<Rect>()
        var k = start
        while (k < end) {
            val owner = owners[k]
            if (owner < 0) {
                k++
                continue
            }
            val word = words[owner]
            val wordStart = firstOf(owner)
            val length = word.text.length.coerceAtLeast(1)
            var last = k
            while (last + 1 < end && owners[last + 1] == owner) last++
            val left = word.left + word.width * (k - wordStart) / length
            val right = word.left + word.width * (last + 1 - wordStart) / length
            val rect = Rect(left, word.lineTop, right, word.lineBottom)
            // On the same line as the one before: one rectangle
            val previous = rects.lastOrNull()
            if (previous != null && previous.top == rect.top && previous.bottom == rect.bottom) {
                rects[rects.lastIndex] = Rect(minOf(previous.left, rect.left), previous.top, maxOf(previous.right, rect.right), previous.bottom)
            } else {
                rects += rect
            }
            k = last + 1
        }
        return rects
    }

    private fun firstOf(owner: Int): Int {
        var k = owners.indexOf(owner)
        return k.coerceAtLeast(0)
    }

    companion object {
        fun of(layout: TextBlockLayout): ShownWords {
            val text = StringBuilder()
            val owners = ArrayList<Int>()
            var lineTop = Float.NaN
            for ((index, word) in layout.words.withIndex()) {
                if (!lineTop.isNaN() && word.lineTop != lineTop && text.isNotEmpty() && !text.last().isWhitespace()) {
                    text.append(' ')
                    owners += -1
                }
                lineTop = word.lineTop
                text.append(word.text)
                repeat(word.text.length) { owners += index }
            }
            return ShownWords(text.toString(), owners.toIntArray(), layout.words)
        }
    }
}
