package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawElement
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawPdf
import io.github.valerioisufi.drawview.model.DrawResource
import io.github.valerioisufi.drawview.model.FractionalIndex
import io.github.valerioisufi.drawview.model.change.AddPage
import io.github.valerioisufi.drawview.model.change.AddResource
import io.github.valerioisufi.drawview.model.change.CopiedContent
import io.github.valerioisufi.drawview.model.change.CopiedElements
import io.github.valerioisufi.drawview.model.change.CopiedPages
import io.github.valerioisufi.drawview.model.change.DocumentChange
import io.github.valerioisufi.drawview.model.change.copyElements
import io.github.valerioisufi.drawview.model.change.copyPages
import io.github.valerioisufi.drawview.model.change.pasteInto
import io.github.valerioisufi.drawview.model.geometry.Rect
import io.github.valerioisufi.drawview.model.newElementId
import io.github.valerioisufi.drawview.store.OpenDocuments
import kotlinx.coroutines.flow.first

/**
 * The items of a proposal that bring in what is elsewhere (Creator, Low Coupling): elements and pages
 * copied from a document, as the editor pastes them ([CopiedContent], `docs/plans/cross-document-paste.md`),
 * and the pages of a PDF file. The copy is taken from its document when the items are applied, checked
 * against what the model read; the ids of what is pasted are new.
 */
internal object CopyItems {
    /** The document [item] copies from, or `null` for another item. */
    fun sourceOf(item: ProposalItem): String? = when (item) {
        is ProposalItem.CopyElements -> item.fromDocument
        is ProposalItem.CopyPages -> item.fromDocument
        else -> null
    }

    /**
     * What [items] copy, taken from their documents as they are now, by index of an item: an item whose
     * document is not [allowed], or whose elements or pages are gone or changed since they were read, has none.
     */
    suspend fun copiesOf(documents: OpenDocuments, items: List<ProposalItem>, allowed: (String) -> Boolean): Map<Int, CopiedContent> {
        val copies = mutableMapOf<Int, CopiedContent>()
        items.forEachIndexed { index, item ->
            val source = sourceOf(item)?.takeIf(allowed) ?: return@forEachIndexed
            if (documents.store.documents.first().none { it.id == source }) return@forEachIndexed
            val open = documents.open(source) ?: return@forEachIndexed
            try {
                open.edit { session -> copyOf(session.document, item) }?.let { copies[index] = it }
            } finally {
                documents.close(open)
            }
        }
        return copies
    }

    /** What [item] copies from [source], or `null` when it is gone or changed since it was read. */
    fun copyOf(source: DrawDocument, item: ProposalItem): CopiedContent? = when (item) {
        is ProposalItem.CopyElements -> {
            val page = source.pageById(item.fromPage)
            if (page == null || ElementItems.isStale(page, item.elements)) null
            else {
                val elements = ElementItems.elementsOf(page, item.elements)!!
                source.copyElements(page, elements, boundsOf(page, elements) ?: Rect(0f, 0f, 0f, 0f))
            }
        }
        is ProposalItem.CopyPages -> {
            val pages = item.pages.map { id -> source.pageById(id) ?: return null }
            source.copyPages(pages)
        }
        else -> null
    }

    /** The box holding [elements] of [page], or `null` when none has one (a PDF page alone). */
    fun boundsOf(page: DrawPage, elements: List<DrawElement>): Rect? =
        elements.mapNotNull { page.boundsOf(it) }.reduceOrNull { all, next -> all.union(next) }

    /** The change pasting [copy] as [item] asks, into [document] as it is now; `null` when nothing would be pasted. */
    fun changeOf(document: DrawDocument, item: ProposalItem, copy: CopiedContent): DocumentChange? = when (item) {
        is ProposalItem.CopyElements -> (copy as? CopiedElements)?.let { elements ->
            document.pageById(item.page)?.let { page -> elements.pasteInto(document, page, item.dx, item.dy).change }
        }
        is ProposalItem.CopyPages -> (copy as? CopiedPages)?.let { pages ->
            val after = item.after?.let { document.pageById(it) } ?: document.pages.last()
            pages.pasteInto(document, document.pages.indexOf(after) + 1).change
        }
        else -> null
    }

    /** The changes of [item], the pages of its PDF file after the page it names, on the background of the document. */
    fun importChanges(document: DrawDocument, item: ProposalItem.ImportPdf): List<DocumentChange> {
        val after = item.after?.let { document.pageById(it) } ?: document.pages.last()
        val next = document.pages.getOrNull(document.pages.indexOf(after) + 1)
        val resourceId = newElementId()
        var key = after.orderKey
        val pages = item.pageSizes.mapIndexed { pdfIndex, (widthMm, heightMm) ->
            key = FractionalIndex.between(key, next?.orderKey)
            DrawPage(widthMm = widthMm, heightMm = heightMm, background = document.defaultBackground, orderKey = key).apply {
                add(DrawPdf(pdfPageIndex = pdfIndex).apply { this.resourceId = resourceId })
            }
        }
        return listOf(AddResource.of(resourceId, DrawResource.ResourceType.PDF, item.path, item.hash)) + pages.map { AddPage.of(it) }
    }
}

