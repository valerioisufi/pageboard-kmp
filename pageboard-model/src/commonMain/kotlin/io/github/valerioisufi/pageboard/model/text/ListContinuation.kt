package io.github.valerioisufi.pageboard.model.text

import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.TextElement

/**
 * A numbered list that goes on from the list of another text, not of its chain (the "continue
 * numbering" of Word, see `docs/plans/checklists-and-lists.md`): the first numbered list of the
 * first level of a text whose [TextElement.continueNumberingFrom] names another text of its
 * document counts on after the last number of the last such list of that text. The source of the
 * text keeps its numbers as written; only the labels follow.
 *
 * The name comes from any device: a text named that is not in the document, or that goes back to
 * the text by its own continuation (a cycle), continues nothing.
 */
public object ListContinuation {

    /** The texts followed at most to find where a list starts: deeper than any document. */
    private const val MOST_TEXTS = 64

    /** The largest start of a list continued: far beyond any list, before the numbers overflow. */
    private const val MOST_START = 1_000_000

    /**
     * The number the first numbered list of [text] starts from, going on from the text it names
     * ([byId] finds a text of its document by its id), or `null` when it goes on from none.
     */
    public fun startOf(text: TextElement, byId: (String) -> TextElement?): Int? =
        startOf(text.continueNumberingFrom, text.id, byId, HashSet())

    /** The number the first numbered list of the text [id] starts from, going on [from] the text it names; `null` for none. */
    public fun startOf(from: String?, id: String, byId: (String) -> TextElement?): Int? = startOf(from, id, byId, HashSet())

    private fun startOf(from: String?, self: String, byId: (String) -> TextElement?, seen: MutableSet<String>): Int? {
        if (from == null || inCycle(from, self, byId)) return null
        return startAfter(from, self, byId, seen)
    }

    private fun startAfter(from: String, self: String, byId: (String) -> TextElement?, seen: MutableSet<String>): Int? {
        if (!seen.add(self) || seen.size > MOST_TEXTS) return null
        val previous = byId(from) ?: return null
        // The text named may go on from another one itself
        val previousStart = previous.continueNumberingFrom?.let { startAfter(it, previous.id, byId, seen) }
        val last = lastNumberOf(previous.text, previousStart) ?: return null
        return (last + 1).coerceIn(1, MOST_START)
    }

    /** Whether the texts named one after the other from [from] come back to one of them, [self] included: a cycle, whose texts continue nothing. */
    private fun inCycle(from: String, self: String, byId: (String) -> TextElement?): Boolean {
        val seen = hashSetOf(self)
        var next: String? = from
        while (next != null) {
            if (!seen.add(next) || seen.size > MOST_TEXTS) return true
            next = byId(next)?.continueNumberingFrom
        }
        return false
    }

    /**
     * The last number of the last numbered list of the first level of [text], its first list
     * counting from [firstListStart]; `null` when it has none.
     */
    public fun lastNumberOf(text: String, firstListStart: Int? = null): Int? {
        val items = ListNumbering.items(text, RichTextParser.parse(text), firstListStart)
        return items.lastOrNull { it.depth == 0 && it.span.ordered }?.ordinal
    }

    /**
     * Sets [TextElement.continuedListStart] of every text of [document]: the texts whose start
     * changed, with their page, to draw again.
     */
    public fun apply(document: Document): List<Pair<Page, TextElement>> {
        val continuing = document.pages.flatMap { page -> page.texts.filter { it.continueNumberingFrom != null || it.continuedListStart != null }.map { page to it } }
        if (continuing.isEmpty()) return emptyList()
        val byId = HashMap<String, TextElement>()
        for (page in document.pages) for (text in page.texts) byId[text.id] = text
        val changed = ArrayList<Pair<Page, TextElement>>()
        for ((page, text) in continuing) {
            val start = startOf(text) { byId[it] }
            if (start != text.continuedListStart) {
                text.continuedListStart = start
                text.markDirty()
                changed += page to text
            }
        }
        return changed
    }

    /**
     * The text a list of the text [id] at [y] on [page] of [document] would go on from: the nearest one before it
     * in the order of reading (above it on its page, then on the pages before) with a numbered list,
     * not of its chain; `null` when there is none.
     */
    public fun previousListText(document: Document, page: Page, id: String, y: Float): TextElement? {
        val pageIndex = document.pages.indexOf(page)
        if (pageIndex < 0) return null
        val chain = TextChains.of(document).chainOf(id).map { it.id }.toSet()
        fun hasList(candidate: TextElement) = candidate.id != id && candidate.id !in chain && lastNumberOf(candidate.text) != null
        val above = page.texts.filter { it.y < y && hasList(it) }.maxWithOrNull(compareBy<TextElement> { it.y }.thenBy { it.x })
        if (above != null) return above
        for (index in pageIndex - 1 downTo 0) {
            val last = document.pages[index].texts.filter(::hasList).maxWithOrNull(compareBy<TextElement> { it.y }.thenBy { it.x })
            if (last != null) return last
        }
        return null
    }
}
