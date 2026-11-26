package io.github.valerioisufi.pageboard.lecture.ui

import io.github.valerioisufi.pageboard.lecture.LectureAssistant
import io.github.valerioisufi.pageboard.search.WordCounts
import io.github.valerioisufi.pageboard.search.WritingWords
import io.github.valerioisufi.pageboard.ui.extension.EditorContext
import io.github.valerioisufi.pageboard.ui.extension.TextBeingWritten
import io.github.valerioisufi.pageboard.ui.extension.TextSuggester
import io.github.valerioisufi.pageboard.ui.extension.TextSuggestion
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The end of the word being written, from the transcript of the lecture of the document being
 * recorded (see `docs/plans/writing-suggestions-more.md`): a word heard once is enough, it was just
 * said. Nothing when no lecture of the document is recorded.
 */
internal class LectureWordSuggester(
    private val heard: (documentId: String) -> List<String>?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : TextSuggester {

    /** The suggester of the lecture [assistant] is recording. */
    constructor(assistant: LectureAssistant) : this({ documentId ->
        assistant.activeRecorder?.lecture?.takeIf { it.documentId == documentId }?.transcript?.map { it.text }
    })

    override suspend fun suggest(context: EditorContext, writing: TextBeingWritten): TextSuggestion? {
        val prefix = WritingWords.prefixAt(writing.source, writing.caret, MIN_PREFIX) ?: return null
        // The transcript read here, on the main thread, and counted on another
        val segments = heard(context.documentId)?.takeIf { it.isNotEmpty() } ?: return null
        return withContext(dispatcher) {
            val counts = WordCounts().apply { segments.forEach(::add) }
            complete(prefix, counts)
        }?.let(::TextSuggestion)
    }

    internal companion object {
        /** The fewest letters typed, and added, as for the words of the notes. */
        const val MIN_PREFIX = 3
        const val MIN_ADDED = 2

        /** The rest of the word heard most often that [prefix] begins; `null` when two are heard as often. */
        fun complete(prefix: String, counts: WordCounts): String? {
            val matches = counts.withPrefix(prefix).filter { it.word.length - prefix.length >= MIN_ADDED }
            val best = matches.maxByOrNull { it.count } ?: return null
            if (matches.any { it !== best && it.count == best.count }) return null
            return WritingWords.restOf(best.word, prefix)
        }
    }
}
