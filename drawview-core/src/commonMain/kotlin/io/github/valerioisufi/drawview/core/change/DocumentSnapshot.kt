package io.github.valerioisufi.drawview.core.change

import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.DrawElement
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.DrawResource
import io.github.valerioisufi.drawview.core.model.PageBackground
import io.github.valerioisufi.drawview.core.model.Stamp
import kotlinx.serialization.Serializable

/**
 * The whole state of a document as plain values: its content, the removed pages and elements
 * (hidden) and the stamps of every piece (see [DocumentChange]).
 *
 * The operations already applied to a document change nothing in its snapshot (every piece has
 * the stamp of its last change): a device receiving a document gets its snapshot, then its
 * operations.
 */
@Serializable
data class DocumentSnapshot(
    val id: String,
    val title: String,
    val defaultWidthMm: Float,
    val defaultHeightMm: Float,
    val defaultBackground: PageBackground,
    val defaultsVersion: Stamp = Stamp.ZERO,
    /** The pages shown, in their order. */
    val pages: List<PageSnapshot> = emptyList(),
    val removedPages: List<PageSnapshot> = emptyList(),
    val resources: List<ResourceData> = emptyList()
) {
    /**
     * A new document with this state; its resources have no file ([DrawResource.path] empty)
     * until they are received.
     */
    fun toDocument(): DrawDocument {
        val document = DrawDocument(
            id = id,
            title = title,
            defaultWidthMm = defaultWidthMm,
            defaultHeightMm = defaultHeightMm,
            defaultBackground = defaultBackground,
            addInitialPage = false
        )
        document.defaultsVersion = defaultsVersion
        for (resource in resources) document.resources.add(DrawResource(resource.id, resource.type, ""))
        pages.forEach { document.pages.add(it.toPage(document)) }
        removedPages.forEach { snapshot -> snapshot.toPage(document).let { document.removedPages[it.id] = it } }
        document.reindexPages()
        return document
    }
}

/**
 * A page as its data (see [PageData]), its removed elements (hidden, see
 * [DrawPage.removedElements]) and the stamps of its last changes and of its elements'.
 */
@Serializable
data class PageSnapshot(
    val data: PageData,
    val added: Stamp = Stamp.ZERO,
    val removed: Stamp? = null,
    val orderVersion: Stamp = Stamp.ZERO,
    val formatVersion: Stamp = Stamp.ZERO,
    val removedElements: List<ElementData> = emptyList(),
    val versions: Map<String, ElementVersions> = emptyMap()
) {
    /** A new page of [document] with this state. */
    fun toPage(document: DrawDocument): DrawPage {
        val page = data.toPage(document)
        page.added = added
        page.removed = removed
        page.orderVersion = orderVersion
        page.formatVersion = formatVersion
        removedElements.forEach { page.removedElements.add(it.toElement()) }
        for (element in page.elements() + page.removedElements) versions[element.id]?.applyTo(element)
        return page
    }
}

/** The stamps of the last changes of an element (see [DrawElement]). */
@Serializable
data class ElementVersions(val added: Stamp, val removed: Stamp? = null, val placed: Stamp, val updated: Stamp) {
    fun applyTo(element: DrawElement) {
        element.added = added
        element.removed = removed
        element.placed = placed
        element.updated = updated
    }
}

/** The state of this document (see [DocumentSnapshot]). */
fun DrawDocument.snapshot(): DocumentSnapshot = DocumentSnapshot(
    id = id,
    title = title,
    defaultWidthMm = defaultWidthMm,
    defaultHeightMm = defaultHeightMm,
    defaultBackground = defaultBackground,
    defaultsVersion = defaultsVersion,
    pages = pages.map { it.snapshot() },
    removedPages = removedPages.values.sortedBy { it.id }.map { it.snapshot() },
    resources = resources.map { ResourceData(it.id, it.type) }
)

/** The state of this page (see [PageSnapshot]). */
fun DrawPage.snapshot(): PageSnapshot = PageSnapshot(
    data = toData(),
    added = added,
    removed = removed,
    orderVersion = orderVersion,
    formatVersion = formatVersion,
    // In a canonical order: the same state has the same snapshot
    removedElements = removedElements.sortedBy { it.id }.map { it.toData() },
    versions = (elements() + removedElements).associate { it.id to ElementVersions(it.added, it.removed, it.placed, it.updated) }
)
