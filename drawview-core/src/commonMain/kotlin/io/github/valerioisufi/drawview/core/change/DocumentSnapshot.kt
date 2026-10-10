package io.github.valerioisufi.drawview.core.change

import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.DrawElement
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.DrawResource
import io.github.valerioisufi.drawview.model.KotlinStrokeModeller
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.Stamp
import io.github.valerioisufi.drawview.model.StrokeModeller
import io.github.valerioisufi.drawview.model.Versions
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
    val titleVersion: Stamp = Stamp.ZERO,
    /** The pages shown, in their order. */
    val pages: List<PageSnapshot> = emptyList(),
    val removedPages: List<PageSnapshot> = emptyList(),
    val resources: List<ResourceData> = emptyList()
) {
    /**
     * A new document with this state, its strokes modeled by [modeller]; its resources have no
     * file ([DrawResource.path] empty) until they are received.
     */
    fun toDocument(modeller: StrokeModeller = KotlinStrokeModeller): DrawDocument {
        val document = DrawDocument(
            id = id,
            title = title,
            defaultWidthMm = defaultWidthMm,
            defaultHeightMm = defaultHeightMm,
            defaultBackground = defaultBackground,
            addInitialPage = false,
            strokeModeller = modeller
        )
        document.defaultsVersion = defaultsVersion
        document.titleVersion = titleVersion
        for (resource in resources) document.resources.add(DrawResource(resource.id, resource.type, "", resource.hash))
        pages.forEach { document.pages.add(it.toPage(document)) }
        removedPages.forEach { snapshot -> snapshot.toPage(document).let { document.removedPages[it.id] = it } }
        document.reindexPages()
        return document
    }
}

/**
 * A page as its data (see [PageData]), its removed elements (hidden, see
 * [DrawPage.removedElements]) and the stamps of its last changes and of its elements'; the
 * versions of its content and what of it its removal saw (see [DrawPage.isHidden]).
 */
@Serializable
data class PageSnapshot(
    val data: PageData,
    val added: Stamp = Stamp.ZERO,
    val removed: Stamp? = null,
    val orderVersion: Stamp = Stamp.ZERO,
    val formatVersion: Stamp = Stamp.ZERO,
    val removedElements: List<ElementData> = emptyList(),
    val versions: Map<String, ElementVersions> = emptyMap(),
    val contentVersions: Versions = Versions.NONE,
    val removalSaw: Versions? = null
) {
    /** A new page of [document] with this state. */
    fun toPage(document: DrawDocument): DrawPage {
        val page = data.toPage(document)
        page.added = added
        page.removed = removed
        page.orderVersion = orderVersion
        page.formatVersion = formatVersion
        page.contentVersions = contentVersions
        page.removalSaw = removalSaw
        removedElements.forEach { page.removedElements.add(it.toElement(document.strokeModeller)) }
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
    titleVersion = titleVersion,
    pages = pages.map { it.snapshot() },
    removedPages = removedPages.values.sortedBy { it.id }.map { it.snapshot() },
    resources = resources.map { ResourceData(it.id, it.type, it.hash) }
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
    versions = (elements() + removedElements).associate { it.id to ElementVersions(it.added, it.removed, it.placed, it.updated) },
    contentVersions = contentVersions,
    removalSaw = removalSaw
)
