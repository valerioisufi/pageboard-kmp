package io.github.valerioisufi.drawview.search

import io.github.valerioisufi.drawview.model.text.RichTextParser
import io.github.valerioisufi.drawview.model.text.RichTextParser.Kind

/**
 * A word of the notes and how many times it was written: [word] in the form written most often
 * ("perché" rather than "Perché" when the word mostly comes in the middle of a sentence).
 */
public class WordCount(public val word: String, public val count: Int) {
    override fun toString(): String = "$word×$count"
}

/**
 * The words of some texts with how many times each was written (`docs/writing-suggestions.md`):
 * the vocabulary from which the end of a word being written is suggested.
 *
 * A word is a run of letters and digits with at least one letter and [MIN_LENGTH] characters
 * (`dell'algoritmo` gives `dell` and `algoritmo`, `e-mail` gives `mail`). The notation is not counted: the
 * formulas (`$…$`), the code and the addresses of the links. The words are counted ignoring their
 * case, each with the form written most often.
 *
 * Not safe for use from several threads while it changes: it is built, then only read.
 */
public class WordCounts {
    /** The forms of each word, under its lower case, with their counts. */
    private val forms = HashMap<String, MutableMap<String, Int>>()

    /** The words in lower case, sorted, for [withPrefix]: `null` once a word was added. */
    private var sortedKeys: List<String>? = null

    /** How many different words were counted. */
    public val size: Int get() = forms.size

    /** Counts the words of [text], written in the notation of the notes. */
    public fun add(text: String) {
        for (word in wordsOf(text)) addWord(word, 1)
    }

    /** Counts the words of [other] too, as if their texts were added here. */
    public fun addAll(other: WordCounts) {
        for ((key, otherForms) in other.forms) {
            val mine = forms.getOrPut(key) { HashMap() }
            for ((form, count) in otherForms) mine[form] = (mine[form] ?: 0) + count
        }
        if (other.forms.isNotEmpty()) sortedKeys = null
    }

    /** How many times [word] was written, whatever its case. */
    public fun count(word: String): Int = forms[word.lowercase()]?.values?.sum() ?: 0

    /** The words that start with [prefix], ignoring the case, [prefix] itself included when it is a word. */
    public fun withPrefix(prefix: String): List<WordCount> {
        if (prefix.isEmpty()) return emptyList()
        val key = prefix.lowercase()
        val keys = sortedKeys ?: forms.keys.sorted().also { sortedKeys = it }
        var index = keys.binarySearch(key).let { if (it < 0) -it - 1 else it }
        val result = mutableListOf<WordCount>()
        while (index < keys.size && keys[index].startsWith(key)) {
            val wordForms = forms.getValue(keys[index])
            result += WordCount(wordForms.bestForm(), wordForms.values.sum())
            index++
        }
        return result
    }

    private fun addWord(word: String, count: Int) {
        val wordForms = forms.getOrPut(word.lowercase()) { sortedKeys = null; HashMap() }
        wordForms[word] = (wordForms[word] ?: 0) + count
    }

    /** The form written most often; between equals, the one in lower case first, then the first in order. */
    private fun Map<String, Int>.bestForm(): String =
        entries.maxWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key == it.key.lowercase() }.thenByDescending { it.key }).key

    public companion object {
        /** The fewest characters of a word counted. */
        public const val MIN_LENGTH: Int = 3

        /** The words of [text] in their order, as they are written, without those of the notation. */
        public fun wordsOf(text: String): List<String> {
            if (text.isEmpty()) return emptyList()
            val skipped = notationRanges(text)
            val words = mutableListOf<String>()
            var start = -1
            var hasLetter = false
            var skip = 0
            fun close(end: Int) {
                if (start >= 0 && hasLetter && end - start >= MIN_LENGTH) words += text.substring(start, end)
                start = -1
                hasLetter = false
            }
            for (i in text.indices) {
                while (skip < skipped.size && skipped[skip].last < i) skip++
                val inNotation = skip < skipped.size && i in skipped[skip]
                val char = text[i]
                if (!inNotation && char.isLetterOrDigit()) {
                    if (start < 0) start = i
                    if (char.isLetter()) hasLetter = true
                } else {
                    close(i)
                }
            }
            close(text.length)
            return words
        }

        /** The parts of [text] that are notation, not words: formulas, code, addresses; sorted, not overlapping. */
        private fun notationRanges(text: String): List<IntRange> {
            if ('$' !in text && '`' !in text && '(' !in text) return emptyList()
            val ranges = RichTextParser.parse(text).mapNotNull { span ->
                when (span.kind) {
                    Kind.FORMULA, Kind.CODE, Kind.CODE_BLOCK -> span.start until span.end
                    Kind.LINK -> span.contentEnd until span.end
                    else -> null
                }
            }.sortedBy { it.first }
            // A span inside another (a formula in a heading of a block) is in it already
            val merged = mutableListOf<IntRange>()
            for (range in ranges) {
                val last = merged.lastOrNull()
                if (last != null && range.first <= last.last + 1) {
                    merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
                } else {
                    merged += range
                }
            }
            return merged
        }
    }
}
