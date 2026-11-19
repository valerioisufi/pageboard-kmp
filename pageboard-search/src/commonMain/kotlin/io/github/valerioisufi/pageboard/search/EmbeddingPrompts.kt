package io.github.valerioisufi.pageboard.search

import io.github.valerioisufi.pageboard.platform.Sha256

/**
 * The text of a chunk, of a search query and of a question as EmbeddingGemma 2 expects them
 * (Information Expert of its prompt format, verified in phase 0 of `docs/plans/search.md`).
 */
public object EmbeddingPrompts {
    /** Version of the prompt format stored in the index metadata. */
    public const val VERSION: Int = 1

    /** Default Matryoshka dimension of the stored vectors (phase 0 of `docs/plans/search.md`). */
    public const val DEFAULT_DIMENSIONS: Int = 512

    /** Formats a chunk of a document named [title] with [text] for its embedding. */
    public fun document(title: String, text: String): String {
        val cleanTitle = title.replace('\n', ' ').trim().ifEmpty { "none" }
        return "title: $cleanTitle | text: ${text.trim()}"
    }

    /** Formats a search [query] for its embedding. */
    public fun query(query: String): String = "task: search result | query: ${query.trim()}"

    /** Formats a [question] for "Ask your notes" (`NotesAnswerer`). */
    public fun question(question: String): String = "task: question answering | query: ${question.trim()}"

    /** The SHA-256 hash of a chunk's formatted text: unchanged chunks keep their vector. */
    public fun hash(title: String, text: String): String =
        Sha256.of(document(title, text).encodeToByteArray())
}
