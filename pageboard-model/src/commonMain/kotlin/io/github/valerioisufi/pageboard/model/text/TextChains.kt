package io.github.valerioisufi.pageboard.model.text

import io.github.valerioisufi.pageboard.model.DrawDocument
import io.github.valerioisufi.pageboard.model.DrawText

/**
 * The chains of text boxes of a document: a text that goes on into another one on a following
 * page (`DrawText.next`), and that one into the next, and so on. Each box keeps its part of the
 * text; the whole text of a chain is its parts joined (see [joined]).
 *
 * A link comes from any device and is not trusted: a link to a text that is not in the document
 * (removed, or never there), to the first text of its own chain (a cycle) or to a text another
 * text links to already (the first one in the order of the pages keeps it) is read as no link.
 *
 * @param pageOf the index of the page of each text.
 */
public class TextChains private constructor(
    private val texts: Map<String, DrawText>,
    private val nextOf: Map<String, String>,
    private val previousOf: Map<String, String>,
    private val pageOf: Map<String, Int>
) {
    /** The text [id] goes on into, or `null`. */
    public fun next(id: String): DrawText? = nextOf[id]?.let { texts[it] }

    /** The text that goes on into [id], or `null`. */
    public fun previous(id: String): DrawText? = previousOf[id]?.let { texts[it] }

    /** The page index of the text [id], or -1. */
    public fun pageOf(id: String): Int = pageOf[id] ?: -1

    /** The chain of [id]: its texts from the first one, in their order; [id] alone when it is in none. */
    public fun chainOf(id: String): List<DrawText> {
        var first = id
        while (true) first = previousOf[first] ?: break
        val chain = mutableListOf<DrawText>()
        var current: String? = first
        while (current != null) {
            chain += texts[current] ?: break
            current = nextOf[current]
        }
        return chain
    }

    /** Whether [id] is in a chain of more than one text. */
    public fun isChained(id: String): Boolean = id in nextOf || id in previousOf

    public companion object {
        /** The chains of the texts of [document] (its pages in their order, the texts removed left out). */
        public fun of(document: DrawDocument): TextChains {
            val texts = LinkedHashMap<String, DrawText>()
            val pages = HashMap<String, Int>()
            for ((index, page) in document.pages.withIndex()) {
                for (text in page.texts) {
                    if (text.removed != null) continue
                    texts[text.id] = text
                    pages[text.id] = index
                }
            }
            val nextOf = HashMap<String, String>()
            val previousOf = HashMap<String, String>()
            for ((id, text) in texts) {
                val next = text.next ?: continue
                if (next == id || next !in texts || next in previousOf) continue
                // A cycle: the text it links to is the first of its own chain
                var first = id
                while (true) first = previousOf[first] ?: break
                if (first == next) continue
                nextOf[id] = next
                previousOf[next] = id
            }
            return TextChains(texts, nextOf, previousOf, pages)
        }

        /** The whole text of the parts of a chain, [texts] in their order (see `DrawText.continuesLine`). */
        public fun joined(texts: List<DrawText>): String = buildString {
            for ((index, text) in texts.withIndex()) {
                if (index > 0 && !text.continuesLine) append('\n')
                append(text.text)
            }
        }
    }
}
