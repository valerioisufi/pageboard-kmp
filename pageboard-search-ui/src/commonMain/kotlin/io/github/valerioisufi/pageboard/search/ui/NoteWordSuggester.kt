package io.github.valerioisufi.pageboard.search.ui

import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.search.NoteVocabulary
import io.github.valerioisufi.pageboard.search.WordCounts
import io.github.valerioisufi.pageboard.ui.extension.EditorContext
import io.github.valerioisufi.pageboard.ui.extension.TextBeingWritten
import io.github.valerioisufi.pageboard.ui.extension.TextSuggester
import io.github.valerioisufi.pageboard.ui.extension.TextSuggestion
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Suggests the end of the word being written from the words of the notes
 * (`docs/plans/writing-suggestions.md`): of the document being written, of its slides, handwriting and
 * lectures, of the library ([vocabulary], `null` before the search starts), each with its weight.
 * A word is suggested only when the notes make it clearly the one being written.
 */
internal class NoteWordSuggester(
    private val vocabulary: () -> NoteVocabulary?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : TextSuggester {

    override suspend fun suggest(context: EditorContext, writing: TextBeingWritten): TextSuggestion? {
        val prefix = prefixAt(writing.source, writing.caret) ?: return null
        // The texts are read here, on the main thread, and counted on another
        val texts = typedTexts(context.document.document, writing, prefix)
        val words = vocabulary()
        val indexed = words?.document(context.documentId)
        val library = words?.library()
        return withContext(dispatcher) {
            val typed = WordCounts().apply { texts.forEach(::add) }
            complete(prefix, listOf(DOCUMENT_WEIGHT to typed, INDEXED_WEIGHT to indexed, LIBRARY_WEIGHT to library))
        }?.let(::TextSuggestion)
    }

    /** The typed texts of [document], the one being written as [writing] says without the word being written. */
    private fun typedTexts(document: Document, writing: TextBeingWritten, prefix: String): List<String> = buildList {
        for (page in document.pages) {
            for (text in page.texts) if (text.removed == null && text.id != writing.textId) add(text.text)
        }
        add(writing.source.substring(0, writing.caret - prefix.length) + writing.source.substring(writing.caret))
    }

    internal companion object {
        /** The fewest letters typed before a word is suggested. */
        const val MIN_PREFIX = 3

        /** The fewest letters a suggestion adds. */
        const val MIN_ADDED = 2

        /** The least share of the score of the words of the prefix that the word suggested has. */
        const val MIN_SHARE = 0.5

        /** The fewest times the word suggested was written in the notes. */
        const val MIN_COUNT = 2

        const val DOCUMENT_WEIGHT = 3
        const val INDEXED_WEIGHT = 2
        const val LIBRARY_WEIGHT = 1

        /**
         * The word being written before [caret] in [source], `null` when it is shorter than
         * [MIN_PREFIX], goes on after the caret, or is in a formula, in code, in the address of a
         * link or a command of LaTeX.
         */
        fun prefixAt(source: String, caret: Int): String? {
            if (caret <= 0 || caret > source.length) return null
            if (caret < source.length && source[caret].isLetterOrDigit()) return null
            var start = caret
            while (start > 0 && source[start - 1].isLetterOrDigit()) start--
            val prefix = source.substring(start, caret)
            if (prefix.length < MIN_PREFIX || !prefix.first().isLetter()) return null
            if (start > 0 && source[start - 1] == '\\') return null
            val line = source.substring(source.lastIndexOf('\n', start - 1) + 1, start)
            if (unescapedCount(line, '$') % 2 == 1 || unescapedCount(line, '`') % 2 == 1) return null
            val link = line.lastIndexOf("](")
            if (link >= 0 && line.indexOf(')', link) < 0) return null
            return prefix
        }

        private fun unescapedCount(line: String, char: Char): Int =
            line.indices.count { line[it] == char && (it == 0 || line[it - 1] != '\\') }

        /**
         * The rest of the word that [prefix] begins, from the words of [layers] (a weight and
         * their counts, `null` for counts not built yet), when one is clearly the best; `null`
         * otherwise. The letters typed keep their case: all in capitals, the rest is too.
         */
        fun complete(prefix: String, layers: List<Pair<Int, WordCounts?>>): String? {
            class Candidate(val word: String) {
                var score = 0
                var count = 0
            }
            val candidates = LinkedHashMap<String, Candidate>()
            for ((weight, counts) in layers) {
                for (match in counts?.withPrefix(prefix).orEmpty()) {
                    // The form of the layer that comes first: the one the student writes now
                    val candidate = candidates.getOrPut(match.word.lowercase()) { Candidate(match.word) }
                    candidate.score += weight * match.count
                    candidate.count += match.count
                }
            }
            val total = candidates.values.sumOf { it.score }
            val best = candidates.values.maxByOrNull { it.score } ?: return null
            if (best.word.length - prefix.length < MIN_ADDED) return null
            if (best.count < MIN_COUNT || best.score < MIN_SHARE * total) return null
            // Another word as good is no clear winner
            if (candidates.values.any { it !== best && it.score == best.score }) return null
            val rest = best.word.substring(prefix.length)
            return if (prefix.length > 1 && prefix.all { !it.isLetter() || it.isUpperCase() }) rest.uppercase() else rest
        }
    }
}
