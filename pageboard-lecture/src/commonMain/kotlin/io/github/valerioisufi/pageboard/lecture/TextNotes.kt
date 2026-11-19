package io.github.valerioisufi.pageboard.lecture

import kotlin.random.Random

/**
 * What turning a text into notes would cost (see `docs/plans/text-import.md`), shown before the request
 * is sent.
 *
 * @param provider who answers it (Gemini is billed, a local model is free).
 * @param model the model of the task of the review.
 * @param chars the characters of the request (the notebook and the text).
 * @param tokens about the tokens of the request (a token is about 4 characters).
 * @param tooLong the text is longer than a request takes ([TextNotes.MAX_TEXT_CHARS]).
 * @param missingApiKey Gemini is chosen without a key.
 */
public class TextNotesEstimate(
    public val provider: ModelProvider,
    public val model: String,
    public val chars: Int,
    public val tokens: Int,
    public val tooLong: Boolean,
    public val missingApiKey: Boolean
)

/**
 * The notes a model made of a text: to place as the notes of a lecture (not suggestions of a
 * lecture), and how much of the text they hold.
 *
 * @param coveredShare the share of the words of the text found in the notes (what the model left
 *   out can be placed as text).
 */
public class TextNotes(public val notes: List<NoteSuggestion>, public val coveredShare: Double) {
    public companion object {
        /** The longest text turned into notes in one request. */
        public const val MAX_TEXT_CHARS: Int = 40_000

        /** The characters of a token, about, to estimate a request. */
        public const val CHARS_PER_TOKEN: Int = 4

        /** The notes of [answer], on the pages of [pageIds] (by index), with the share of the words of [text] they hold. */
        internal fun of(answer: List<AnalysisNote>, pageIds: List<String>, text: String): TextNotes {
            val notes = answer.filter { it.content.isNotBlank() }.map { note ->
                val pageIndex = note.page?.let { it - 1 }?.takeIf { it in pageIds.indices }
                NoteSuggestion(
                    id = "text_" + Random.nextLong().toULong().toString(16),
                    kind = NoteKind.parse(note.kind),
                    title = note.title.trim(),
                    content = note.content.trim(),
                    pageIndex = pageIndex,
                    pageId = pageIndex?.let { pageIds[it] }.orEmpty(),
                    importance = note.importance.coerceIn(1, 3),
                    anchor = note.anchor.trim().take(MAX_ANCHOR_CHARS)
                )
            }
            return TextNotes(notes, coverage(text, notes))
        }

        /** The share of the distinct words of [text] (of four letters or more) found in [notes]. */
        internal fun coverage(text: String, notes: List<NoteSuggestion>): Double {
            val wanted = wordsOf(text)
            if (wanted.isEmpty()) return 1.0
            val found = notes.flatMapTo(mutableSetOf()) { wordsOf(it.title + " " + it.content) }
            return wanted.count { it in found }.toDouble() / wanted.size
        }

        private fun wordsOf(text: String): Set<String> =
            text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filterTo(mutableSetOf()) { it.length >= 4 }

        private const val MAX_ANCHOR_CHARS = 120
    }
}
