package io.github.valerioisufi.drawview.core.change

import io.github.valerioisufi.drawview.core.geometry.AffineTransform
import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.DrawElement
import io.github.valerioisufi.drawview.core.model.DrawImage
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.DrawResource
import io.github.valerioisufi.drawview.core.model.DrawStroke
import io.github.valerioisufi.drawview.core.model.DrawText
import io.github.valerioisufi.drawview.core.model.DrawTextMarkup
import io.github.valerioisufi.drawview.core.model.PageBackground
import io.github.valerioisufi.drawview.core.model.TextMarkupKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * A change of a document, as plain values: every change of a document is one of these, submitted
 * to its [DocumentSession] (by the editor, by undo / redo or, later, by another device).
 *
 * A change refers to the pages and the elements by identifier and knows how to apply itself
 * ([applyTo]), producing the change that undoes it. The changes created from the editor keep a
 * reference to the model objects they hold ([Transient]), so that they are applied without
 * modeling the strokes again; the serialized form has the values only.
 */
@Serializable
sealed interface DocumentChange {
    /**
     * Applies the change to the document of [context], recording what it did in its effects.
     *
     * @return the change undoing it, or `null` when it changed nothing (e.g. its elements are
     *   gone).
     */
    fun applyTo(context: ChangeContext): DocumentChange?
}

/**
 * The document a change is applied to, and the effects it records.
 */
class ChangeContext internal constructor(val document: DrawDocument, val effects: ChangeEffects)

/**
 * Adds [elements] to the page [pageId] (the ones already there are skipped).
 *
 * @param positions position of every element among the elements of its kind, to put back
 *   elements removed in the order of [elements] (they are inserted in the reverse order); `null`:
 *   at the end, in order.
 */
@Serializable
@SerialName("addElements")
data class AddElements(
    val pageId: String,
    val elements: List<ElementData>,
    val positions: List<Int>? = null
) : DocumentChange {
    @Transient
    private var local: Map<String, DrawElement> = emptyMap()

    override fun applyTo(context: ChangeContext): DocumentChange? {
        val page = context.document.pageById(pageId) ?: return null
        val added = mutableListOf<String>()
        val order = if (positions != null) elements.indices.reversed() else elements.indices
        for (i in order) {
            val data = elements[i]
            if (page.find(data.id) != null) continue
            val element = local[data.id] ?: data.toElement()
            page.add(element, positions?.getOrNull(i) ?: -1)
            context.effects.saved(page, element)
            added.add(data.id)
        }
        if (added.isEmpty()) return null
        return RemoveElements(pageId, added)
    }

    companion object {
        /** Adds the model [elements] to [page] (the objects themselves are added). */
        fun of(page: DrawPage, elements: List<DrawElement>, positions: List<Int>? = null) =
            AddElements(page.id, elements.map { it.toData() }, positions).also { change ->
                change.local = elements.associateBy { it.id }
            }
    }
}

/**
 * Removes the elements [ids] from the page [pageId], in this order.
 */
@Serializable
@SerialName("removeElements")
data class RemoveElements(val pageId: String, val ids: List<String>) : DocumentChange {
    override fun applyTo(context: ChangeContext): DocumentChange? {
        val page = context.document.pageById(pageId) ?: return null
        val removed = mutableListOf<DrawElement>()
        val positions = mutableListOf<Int>()
        for (id in ids) {
            val element = page.find(id) ?: continue
            val bounds = page.boundsOf(element)
            val position = page.remove(element)
            if (position < 0) continue
            removed.add(element)
            positions.add(position)
            context.effects.deleted(page, element, bounds)
        }
        if (removed.isEmpty()) return null
        return AddElements.of(page, removed, positions)
    }

    companion object {
        fun of(page: DrawPage, elements: List<DrawElement>) = RemoveElements(page.id, elements.map { it.id })
    }
}

/**
 * Where an element is placed on its page (absolute).
 */
@Serializable
sealed interface Placement {
    val id: String
}

/** A stroke, by the transformation of the geometry it was drawn with. */
@Serializable
@SerialName("stroke")
data class StrokePlacement(override val id: String, val transform: AffineTransform) : Placement

/**
 * A text block or an image, by its (rotated) box; [fontSize] for a text (`null`: unchanged).
 */
@Serializable
@SerialName("box")
data class BoxPlacement(
    override val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float,
    val fontSize: Float? = null
) : Placement

/** Where [element] is placed now, or `null` for an element that is not placed freely. */
fun placementOf(element: DrawElement): Placement? = when (element) {
    is DrawStroke -> StrokePlacement(element.id, element.transform)
    is DrawText -> BoxPlacement(element.id, element.x, element.y, element.width, element.height, element.rotation, element.fontSize)
    is DrawImage -> BoxPlacement(element.id, element.x, element.y, element.width, element.height, element.rotation)
    else -> null
}

/**
 * Places elements of the page [pageId]: moves, scales or rotates them, moving them to the page
 * [toPageId] when it is another one.
 *
 * @param positions position of every element among the elements of its kind on [toPageId], to
 *   put back elements moved away from it (see [AddElements]); `null`: at the end, in order.
 */
@Serializable
@SerialName("placeElements")
data class PlaceElements(
    val pageId: String,
    val placements: List<Placement>,
    val toPageId: String = pageId,
    val positions: List<Int>? = null
) : DocumentChange {
    override fun applyTo(context: ChangeContext): DocumentChange? {
        val from = context.document.pageById(pageId) ?: return null
        val to = context.document.pageById(toPageId) ?: return null
        // The elements found, with their new and their current placement
        val targets = placements.mapNotNull { placement ->
            val element = from.find(placement.id) ?: return@mapNotNull null
            val current = placementOf(element) ?: return@mapNotNull null
            if ((current is StrokePlacement) != (placement is StrokePlacement)) return@mapNotNull null
            Triple(element, placement, current)
        }
        if (targets.isEmpty()) return null

        for ((element) in targets) context.effects.dirty(from, from.boundsOf(element))
        var removedAt: List<Int>? = null
        if (from !== to) {
            removedAt = targets.map { (element) -> from.remove(element) }
            val order = if (positions != null) targets.indices.reversed() else targets.indices
            for (i in order) to.add(targets[i].first, positions?.getOrNull(i) ?: -1)
        }
        for ((element, placement) in targets) {
            when {
                element is DrawStroke && placement is StrokePlacement -> element.place(placement.transform)
                element is DrawText && placement is BoxPlacement -> {
                    element.x = placement.x; element.y = placement.y
                    element.width = placement.width; element.height = placement.height
                    element.rotation = placement.rotation
                    placement.fontSize?.let { element.fontSize = it }
                    element.markDirty()
                }
                element is DrawImage && placement is BoxPlacement -> {
                    element.x = placement.x; element.y = placement.y
                    element.width = placement.width; element.height = placement.height
                    element.rotation = placement.rotation
                }
            }
            context.effects.saved(to, element)
        }
        return PlaceElements(toPageId, targets.map { it.third }, pageId, removedAt)
    }
}

/**
 * Replaces the content of text blocks of the page [pageId] (text, format and box).
 */
@Serializable
@SerialName("updateTexts")
data class UpdateTexts(val pageId: String, val texts: List<TextData>) : DocumentChange {
    override fun applyTo(context: ChangeContext): DocumentChange? {
        val page = context.document.pageById(pageId) ?: return null
        val previous = mutableListOf<TextData>()
        for (data in texts) {
            val text = page.find(data.id) as? DrawText ?: continue
            previous.add(text.toData())
            context.effects.dirty(page, text.getRotatedBounds())
            data.applyTo(text)
            context.effects.saved(page, text)
        }
        if (previous.isEmpty()) return null
        return UpdateTexts(pageId, previous)
    }
}

/** The kind and the color of a mark of the text of a PDF. */
@Serializable
data class MarkupStyle(val id: String, val kind: TextMarkupKind, val color: Int)

/**
 * Changes the kind and the color of marks of the text of the PDFs of the page [pageId].
 */
@Serializable
@SerialName("restyleMarkups")
data class RestyleMarkups(val pageId: String, val styles: List<MarkupStyle>) : DocumentChange {
    override fun applyTo(context: ChangeContext): DocumentChange? {
        val page = context.document.pageById(pageId) ?: return null
        val previous = mutableListOf<MarkupStyle>()
        for (style in styles) {
            val markup = page.find(style.id) as? DrawTextMarkup ?: continue
            previous.add(MarkupStyle(markup.id, markup.kind, markup.color))
            markup.kind = style.kind
            markup.color = style.color
            context.effects.saved(page, markup)
        }
        if (previous.isEmpty()) return null
        return RestyleMarkups(pageId, previous)
    }
}

/**
 * Inserts [page] (with its content) at [index] (clamped to the pages of the document).
 */
@Serializable
@SerialName("addPage")
data class AddPage(val page: PageData, val index: Int) : DocumentChange {
    @Transient
    private var local: DrawPage? = null

    override fun applyTo(context: ChangeContext): DocumentChange? {
        val document = context.document
        if (document.pageById(page.id) != null) return null
        val newPage = local?.takeIf { it.id == page.id } ?: page.toPage(document)
        val position = index.coerceIn(0, document.pages.size)
        document.pages.add(position, newPage)
        document.reindexPages()
        context.effects.pageInserted(newPage, position)
        return RemovePage(page.id)
    }

    companion object {
        /** Inserts the model [page] (the object itself) at [index]. */
        fun of(page: DrawPage, index: Int) = AddPage(page.toData(), index).also { it.local = page }
    }
}

/**
 * Removes the page [pageId] with its content (the last page of a document is never removed).
 */
@Serializable
@SerialName("removePage")
data class RemovePage(val pageId: String) : DocumentChange {
    override fun applyTo(context: ChangeContext): DocumentChange? {
        val document = context.document
        val page = document.pageById(pageId) ?: return null
        if (document.pages.size <= 1) return null
        val position = document.pages.indexOf(page)
        document.pages.removeAt(position)
        document.reindexPages()
        context.effects.pageRemoved(page, position)
        return AddPage.of(page, position)
    }
}

/**
 * Orders the pages as [pageIds] (the pages not listed follow, in their order).
 */
@Serializable
@SerialName("setPageOrder")
data class SetPageOrder(val pageIds: List<String>) : DocumentChange {
    override fun applyTo(context: ChangeContext): DocumentChange? {
        val document = context.document
        val previous = document.pages.toList()
        val listed = pageIds.mapNotNull { document.pageById(it) }.distinct()
        val order = listed + previous.filter { it !in listed }
        if (order == previous) return null
        document.pages.clear()
        document.pages.addAll(order)
        document.reindexPages()
        context.effects.pagesReordered()
        return SetPageOrder(previous.map { it.id })
    }
}

/**
 * Changes the format of the page [pageId] and its background (`null`: the default background of
 * the document).
 */
@Serializable
@SerialName("setPageFormat")
data class SetPageFormat(
    val pageId: String,
    val widthMm: Float,
    val heightMm: Float,
    val background: PageBackground?
) : DocumentChange {
    override fun applyTo(context: ChangeContext): DocumentChange? {
        val document = context.document
        val page = document.pageById(pageId) ?: return null
        val previous = SetPageFormat(pageId, page.widthMm, page.heightMm, if (page.hasCustomBackground) page.background else null)
        if (previous == this) return null
        page.widthMm = widthMm
        page.heightMm = heightMm
        page.background = background ?: document.defaultBackground
        page.hasCustomBackground = background != null
        context.effects.pageFormatted(page)
        return previous
    }
}

/**
 * Changes the default format of the new pages and the default background, which the pages
 * without a background of their own follow.
 */
@Serializable
@SerialName("setDocumentDefaults")
data class SetDocumentDefaults(
    val widthMm: Float,
    val heightMm: Float,
    val background: PageBackground
) : DocumentChange {
    override fun applyTo(context: ChangeContext): DocumentChange? {
        val document = context.document
        val previous = SetDocumentDefaults(document.defaultWidthMm, document.defaultHeightMm, document.defaultBackground)
        if (previous == this) return null
        document.defaultWidthMm = widthMm
        document.defaultHeightMm = heightMm
        document.defaultBackground = background
        for (page in document.pages) {
            if (page.hasCustomBackground || page.background == background) continue
            page.background = background
            context.effects.dirty(page, null)
        }
        context.effects.documentDefaultsChanged()
        return previous
    }
}

/**
 * Adds a file to the document. The files are never removed by undo (their content is kept with
 * the document): the change undoing it is [NoChange].
 */
@Serializable
@SerialName("addResource")
data class AddResource(val resource: ResourceData) : DocumentChange {
    /** The file on this device (where the content of a remote file will be stored, later). */
    @Transient
    private var path: String = ""

    override fun applyTo(context: ChangeContext): DocumentChange? {
        val document = context.document
        if (document.findResource(resource.id) != null) return null
        val added = DrawResource(resource.id, resource.type, path)
        document.resources.add(added)
        context.effects.resourceAdded(added)
        return NoChange
    }

    companion object {
        /** Adds the file [path] of this device as the resource [id]. */
        fun of(id: String, type: DrawResource.ResourceType, path: String) =
            AddResource(ResourceData(id, type)).also { it.path = path }
    }
}

/** A change that changes nothing (the change undoing [AddResource]). */
@Serializable
@SerialName("none")
data object NoChange : DocumentChange {
    override fun applyTo(context: ChangeContext): DocumentChange = NoChange
}

/**
 * Several changes applied as one, in order (undone in the reverse order).
 */
@Serializable
@SerialName("composite")
data class CompositeChange(val changes: List<DocumentChange>) : DocumentChange {
    override fun applyTo(context: ChangeContext): DocumentChange? {
        val inverses = changes.mapNotNull { it.applyTo(context) }
        if (inverses.isEmpty()) return null
        return CompositeChange(inverses.asReversed().toList())
    }
}
