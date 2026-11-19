package io.github.valerioisufi.pageboard.store

import io.github.valerioisufi.pageboard.model.CustomElement
import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.PdfElement
import io.github.valerioisufi.pageboard.model.DocumentResource
import io.github.valerioisufi.pageboard.model.TextElement

/**
 * What can be read of a document without modeling its strokes: the texts, the elements of the
 * applications and the PDF pages of its pages shown, in their order, with its resources. A value
 * read to search the document ([DocumentStore.loadOutline]), never saved back: a document without
 * its strokes would lose them.
 */
public class DocumentOutline(
    public val id: String,
    /** The pages shown, in their order. */
    public val pages: List<PageOutline>,
    public val resources: List<DocumentResource>
) {
    /** The resource [resourceId] of the document, or `null`. */
    public fun findResource(resourceId: String): DocumentResource? = resources.firstOrNull { it.id == resourceId }

    public companion object {
        /** The outline of [document], loaded whole. */
        public fun of(document: Document): DocumentOutline = DocumentOutline(
            id = document.id,
            pages = document.pages.map { page ->
                PageOutline(page.id, page.texts.toList(), page.customElements.toList(), page.pdfs.toList())
            },
            resources = document.resources.toList()
        )
    }
}

/** The elements shown on a page that can be read (see [DocumentOutline]). */
public class PageOutline(
    public val id: String,
    public val texts: List<TextElement>,
    public val customElements: List<CustomElement>,
    public val pdfs: List<PdfElement>
)
