package io.github.valerioisufi.pageboard

import io.github.valerioisufi.pageboard.ui.extension.EditorExtension
import io.github.valerioisufi.pageboard.ui.extension.TextSuggestion

/** A word of a text: letters and digits. */
private val WORD = Regex("""[\p{L}\p{N}]+""")

/**
 * Suggests the end of the word being written from the words of the texts of the document, the
 * most frequent first (see `docs/plans/text-box-hook.md`): what an extension of the text box does, without
 * a model.
 */
fun wordSuggestionsExtension(): EditorExtension = EditorExtension {
    textSuggestions { context, writing ->
        val prefix = writing.source.substring(0, writing.caret).takeLastWhile { it.isLetterOrDigit() }
        if (prefix.length < 2) return@textSuggestions null
        val words = context.document.document.pages.asSequence()
            .flatMap { page -> page.texts.asSequence().filter { it.removed == null && it.id != writing.textId } }
            .flatMap { text -> WORD.findAll(text.text).map { it.value } }
        val word = words.filter { it.length > prefix.length && it.startsWith(prefix) }
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key
        word?.let { TextSuggestion(it.substring(prefix.length)) }
    }
}
