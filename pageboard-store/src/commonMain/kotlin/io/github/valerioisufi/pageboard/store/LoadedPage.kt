package io.github.valerioisufi.pageboard.store

import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.PageElement
import io.github.valerioisufi.pageboard.model.ImageElement
import io.github.valerioisufi.pageboard.model.PdfElement
import io.github.valerioisufi.pageboard.model.DocumentResource
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.TextMarkupElement
import io.github.valerioisufi.pageboard.model.PageContentSnapshot
import io.github.valerioisufi.pageboard.platform.Sha256

/**
 * A page of a document read alone to be drawn ([DocumentStore.loadPage]): its elements shown, its
 * format and its background, with the resources of the document (the files of its images and PDF
 * pages). A value, never saved back.
 */
public class LoadedPage(
    public val id: String,
    public val content: PageContentSnapshot,
    public val resources: List<DocumentResource>
) {
    internal companion object {
        /** The page at [index] of [document] (among its pages shown), or `null`. */
        fun of(document: Document, index: Int): LoadedPage? = document.pages.getOrNull(index)?.let { page ->
            LoadedPage(page.id, page.snapshotContent(), document.resources.toList())
        }
    }
}

/**
 * The fingerprint of the page at [index] of this document (see [DocumentStore.pageFingerprint]):
 * the hash of its identifier, format and background, of the identifier and the stamps of every
 * element shown on it, and of the files of the resources they show.
 */
internal fun Document.pageFingerprint(index: Int): String? {
    val page = pages.getOrNull(index) ?: return null
    val text = StringBuilder()
    text.append(page.id).append('|').append(page.widthMm).append('|').append(page.heightMm).append('|').append(page.background).append('\n')
    val resourceIds = mutableSetOf<String>()
    for (element in page.elements().sortedBy { it.id }) {
        text.append(element.id).append('|').append(element.added).append('|').append(element.placed).append('|')
            .append(element.ordered).append('|').append(element.updated).append('|').append(element.layerVersion)
        if (element is TextElement) text.append('|').append(element.contentVersions)
        text.append('\n')
        resourceIdOf(element)?.let { resourceIds.add(it) }
    }
    for (layer in page.layers.sortedBy { it.id }) {
        text.append("L|").append(layer.id).append('|').append(layer.orderKey).append('|')
            .append(layer.visible).append('|').append(layer.updated).append('\n')
    }
    for (id in resourceIds.sorted()) {
        val resource = resources.firstOrNull { it.id == id }
        text.append(id).append('|').append(resource?.path).append('|').append(resource?.hash).append('\n')
    }
    return Sha256.of(text.toString().encodeToByteArray())
}

private fun resourceIdOf(element: PageElement): String? = when (element) {
    is ImageElement -> element.resourceId
    is PdfElement -> element.resourceId
    is TextMarkupElement -> element.resourceId
    else -> null
}
