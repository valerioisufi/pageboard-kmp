package io.github.valerioisufi.pageboard.lecture.ui

import io.github.valerioisufi.pageboard.lecture.LectureAssistant
import io.github.valerioisufi.pageboard.ui.extension.EditorContext
import io.github.valerioisufi.pageboard.ui.extension.TextBeingWritten
import io.github.valerioisufi.pageboard.ui.extension.TextSuggester
import io.github.valerioisufi.pageboard.ui.extension.TextSuggestion

/**
 * The next words of the text being written, from the model of the device (see
 * `docs/plans/writing-suggestions-more.md`): asked after a word ended (a space after it), out of a
 * formula and of code, with the paragraph before the caret.
 */
internal class ModelWordsSuggester(private val nextWords: suspend (paragraph: String) -> String?) : TextSuggester {

    /** The suggester of the model of [assistant] (see [LectureAssistant.nextWords]). */
    constructor(assistant: LectureAssistant) : this({ paragraph -> assistant.nextWords(paragraph) })

    override suspend fun suggest(context: EditorContext, writing: TextBeingWritten): TextSuggestion? {
        val paragraph = paragraphBefore(writing.source, writing.caret) ?: return null
        return nextWords(paragraph)?.let(::TextSuggestion)
    }

    internal companion object {
        /**
         * The paragraph before [caret] of [source] when a word just ended there (a space after a
         * letter, a digit or a sign), out of a formula and of code; `null` otherwise.
         */
        fun paragraphBefore(source: String, caret: Int): String? {
            if (caret < 2 || caret > source.length || source[caret - 1] != ' ' || source[caret - 2].isWhitespace()) return null
            if (caret < source.length && source[caret] != '\n') return null
            val start = source.lastIndexOf("\n\n", caret - 1).let { if (it < 0) 0 else it + 2 }
            val line = source.substring(source.lastIndexOf('\n', caret - 1) + 1, caret)
            if (line.count { it == '$' } % 2 == 1 || line.count { it == '`' } % 2 == 1) return null
            return source.substring(start, caret).trimEnd().takeIf { it.isNotEmpty() }
        }
    }
}
