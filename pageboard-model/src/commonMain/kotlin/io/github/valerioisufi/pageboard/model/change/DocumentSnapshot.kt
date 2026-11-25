package io.github.valerioisufi.pageboard.model.change

import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.PageElement
import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.DocumentResource
import io.github.valerioisufi.pageboard.model.KotlinStrokeModeller
import io.github.valerioisufi.pageboard.model.PageBackground
import io.github.valerioisufi.pageboard.model.PageLayerData
import io.github.valerioisufi.pageboard.model.Stamp
import io.github.valerioisufi.pageboard.model.StrokeModeller
import io.github.valerioisufi.pageboard.model.Versions
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
public data class DocumentSnapshot(
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
     * file ([DocumentResource.path] empty) until they are received.
     */
    public fun toDocument(modeller: StrokeModeller = KotlinStrokeModeller): Document {
        val document = Document(
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
        for (resource in resources) document.addResource(DocumentResource(resource.id, resource.type, "", resource.hash))
        pages.forEach { document.appendPage(it.toPage(document)) }
        removedPages.forEach { snapshot -> snapshot.toPage(document).let { document.removedPages[it.id] = it } }
        document.reindexPages()
        return document
    }
}

/**
 * A page as its data (see [PageData]), its removed elements (hidden, see
 * [Page.removedElements]) and the stamps of its last changes and of its elements'; the
 * versions of its content and what of it its removal saw (see [Page.isHidden]).
 */
@Serializable
public data class PageSnapshot(
    val data: PageData,
    val added: Stamp = Stamp.ZERO,
    val removed: Stamp? = null,
    val orderVersion: Stamp = Stamp.ZERO,
    val formatVersion: Stamp = Stamp.ZERO,
    val removedElements: List<ElementData> = emptyList(),
    val versions: Map<String, ElementVersions> = emptyMap(),
    val contentVersions: Versions = Versions.NONE,
    val removalSaw: Versions? = null,
    val removedLayers: List<PageLayerData> = emptyList()
) {
    /** A new page of [document] with this state. */
    public fun toPage(document: Document): Page {
        val page = data.toPage(document)
        page.added = added
        page.removed = removed
        page.orderVersion = orderVersion
        page.formatVersion = formatVersion
        page.contentVersions = contentVersions
        page.removalSaw = removalSaw
        page.load(emptyList(), removedElements.map { it.toElement(document.strokeModeller) })
        page.loadLayers(removedLayers.map { it.toLayer() } + data.layers.map { it.toLayer() })
        for (element in page.elements() + page.removedElements) versions[element.id]?.applyTo(element)
        return page
    }
}

/**
 * The stamps of the last changes of an element (see [PageElement]); [ordered] `null` (a snapshot
 * made before the order had its own register): the stamp of its placement, which held the order.
 */
@Serializable
public data class ElementVersions(
    val added: Stamp,
    val removed: Stamp? = null,
    val placed: Stamp,
    val updated: Stamp,
    val ordered: Stamp? = null,
    val lockVersion: Stamp = Stamp.ZERO,
    val layerVersion: Stamp = Stamp.ZERO
) {
    public fun applyTo(element: PageElement) {
        element.added = added
        element.removed = removed
        element.placed = placed
        element.ordered = ordered ?: placed
        element.updated = updated
        element.lockVersion = lockVersion
        element.layerVersion = layerVersion
    }
}

/** The state of this document (see [DocumentSnapshot]). */
public fun Document.snapshot(): DocumentSnapshot = DocumentSnapshot(
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
public fun Page.snapshot(): PageSnapshot = PageSnapshot(
    data = toData(),
    added = added,
    removed = removed,
    orderVersion = orderVersion,
    formatVersion = formatVersion,
    // In a canonical order: the same state has the same snapshot
    removedElements = removedElements.sortedBy { it.id }.map { it.toData() },
    versions = (elements() + removedElements).associate {
        it.id to ElementVersions(it.added, it.removed, it.placed, it.updated, it.ordered, it.lockVersion, it.layerVersion)
    },
    contentVersions = contentVersions,
    removalSaw = removalSaw,
    removedLayers = removedLayers.values.sortedBy { it.id }.map { it.toData() }
)
