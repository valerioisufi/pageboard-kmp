package io.github.valerioisufi.drawview.search

import io.github.valerioisufi.drawview.model.geometry.Rect
import io.github.valerioisufi.drawview.store.DocumentSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Where a piece found by a search lives: a document as a whole, a page (and the box of the
 * element on it, in page millimeters, when known), or a minute of a lecture.
 */
public sealed interface SearchAnchor {
    /** Identifier of the document holding the piece. */
    public val documentId: String

    /** The document itself (its name and the path of its folders). */
    public data class Document(override val documentId: String) : SearchAnchor

    /**
     * A page of a document, and the area of the page when the piece is an element of it.
     *
     * @param pageId identifier of the page ([io.github.valerioisufi.drawview.model.DrawPage.id]).
     * @param bounds the box of the element on the page in millimeters, `null` for the whole page.
     * @param pageNumber the 1-based number of the page in the document.
     */
    public data class Page(
        override val documentId: String,
        public val pageId: String,
        public val bounds: Rect? = null,
        public val pageNumber: Int = 1
    ) : SearchAnchor

    /**
     * A lecture of a document, at [startMs] milliseconds from its beginning.
     */
    public data class Lecture(
        override val documentId: String,
        public val lectureId: String,
        public val startMs: Long = 0L
    ) : SearchAnchor
}

/** What kind of content a [SearchChunk] comes from. */
public enum class SearchChunkKind {
    /** The name of the document and the path of its folders. */
    TITLE,

    /** Typed text or an element of a type added by an application. */
    TEXT,

    /** The text layer of a PDF page. */
    PDF,

    /** Handwritten notes read as text. */
    HANDWRITING,

    /** Spoken transcript of a lecture. */
    LECTURE_TRANSCRIPT,

    /** Review or live suggestions of a lecture. */
    LECTURE_NOTES
}

/**
 * A searchable piece of a document, with its [anchor] and the [title] of its document.
 */
public data class SearchChunk(
    public val sourceId: String,
    public val kind: SearchChunkKind,
    public val anchor: SearchAnchor,
    public val title: String,
    public val text: String
)

/**
 * Produces the searchable pieces ([SearchChunk]) of the documents for one kind of content
 * (Polymorphism, Protected Variations): the documents and their texts ([DocumentSearchSource]),
 * the PDF pages, the lectures.
 */
public interface SearchSource {
    /** Identifier of this source in the index, stable across runs. */
    public val id: String

    /** Emits when the content of this source may have changed outside [DocumentSummary.modifiedAt]. */
    public val changes: Flow<Unit> get() = emptyFlow()

    /**
     * The version of [document] in this source, or `null` when the document has no content from
     * this source. When the version is the same as the one kept in the index, the document is not
     * read again.
     */
    public suspend fun version(document: DocumentSummary): String?

    /** The searchable pieces of [document] from this source. */
    public suspend fun chunks(document: DocumentSummary): List<SearchChunk>
}
