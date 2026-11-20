package io.github.valerioisufi.pageboard.store

import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.change.StrokeData
import io.github.valerioisufi.pageboard.model.change.toData

/**
 * The strokes of a document as they were drawn, without modeling them: their samples, brush and
 * placement, page by page. A value read to recognize the handwriting ([DocumentStore.loadInk]),
 * never saved back.
 */
public class DocumentInk(
    public val id: String,
    /** The pages shown, in their order. */
    public val pages: List<PageInk>
) {
    public companion object {
        /** The ink of [document], loaded whole. */
        public fun of(document: Document): DocumentInk = DocumentInk(
            id = document.id,
            pages = document.pages.map { page -> PageInk(page.id, page.strokes.map { it.toData() }) }
        )
    }
}

/** The strokes shown on a page, in their order (see [DocumentInk]). */
public class PageInk(
    public val id: String,
    public val strokes: List<StrokeData>
)
