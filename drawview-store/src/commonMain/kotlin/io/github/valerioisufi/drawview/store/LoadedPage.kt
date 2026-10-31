package io.github.valerioisufi.drawview.store

import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawElement
import io.github.valerioisufi.drawview.model.DrawImage
import io.github.valerioisufi.drawview.model.DrawPdf
import io.github.valerioisufi.drawview.model.DrawResource
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.DrawTextMarkup
import io.github.valerioisufi.drawview.model.PageContentSnapshot
import io.github.valerioisufi.drawview.platform.Sha256

/**
 * A page of a document read alone to be drawn ([DocumentStore.loadPage]): its elements shown, its
 * format and its background, with the resources of the document (the files of its images and PDF
 * pages). A value, never saved back.
 */
public class LoadedPage(
    public val id: String,
    public val content: PageContentSnapshot,
    public val resources: List<DrawResource>
) {
    internal companion object {
        /** The page at [index] of [document] (among its pages shown), or `null`. */
        fun of(document: DrawDocument, index: Int): LoadedPage? = document.pages.getOrNull(index)?.let { page ->
            LoadedPage(page.id, page.snapshotContent(), document.resources.toList())
        }
    }
}

/**
 * The fingerprint of the page at [index] of this document (see [DocumentStore.pageFingerprint]):
 * the hash of its identifier, format and background, of the identifier and the stamps of every
 * element shown on it, and of the files of the resources they show.
 */
internal fun DrawDocument.pageFingerprint(index: Int): String? {
    val page = pages.getOrNull(index) ?: return null
    val text = StringBuilder()
    text.append(page.id).append('|').append(page.widthMm).append('|').append(page.heightMm).append('|').append(page.background).append('\n')
    val resourceIds = mutableSetOf<String>()
    for (element in page.elements().sortedBy { it.id }) {
        text.append(element.id).append('|').append(element.added).append('|').append(element.placed).append('|')
            .append(element.ordered).append('|').append(element.updated)
        if (element is DrawText) text.append('|').append(element.contentVersions)
        text.append('\n')
        resourceIdOf(element)?.let { resourceIds.add(it) }
    }
    for (id in resourceIds.sorted()) {
        val resource = resources.firstOrNull { it.id == id }
        text.append(id).append('|').append(resource?.path).append('|').append(resource?.hash).append('\n')
    }
    return Sha256.of(text.toString().encodeToByteArray())
}

private fun resourceIdOf(element: DrawElement): String? = when (element) {
    is DrawImage -> element.resourceId
    is DrawPdf -> element.resourceId
    is DrawTextMarkup -> element.resourceId
    else -> null
}
