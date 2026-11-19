package io.github.valerioisufi.drawview.store

import io.github.valerioisufi.drawview.model.DrawCustomElement
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawPdf
import io.github.valerioisufi.drawview.model.DrawResource
import io.github.valerioisufi.drawview.model.DrawText

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
    public val resources: List<DrawResource>
) {
    /** The resource [resourceId] of the document, or `null`. */
    public fun findResource(resourceId: String): DrawResource? = resources.firstOrNull { it.id == resourceId }

    public companion object {
        /** The outline of [document], loaded whole. */
        public fun of(document: DrawDocument): DocumentOutline = DocumentOutline(
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
    public val texts: List<DrawText>,
    public val customElements: List<DrawCustomElement>,
    public val pdfs: List<DrawPdf>
)
