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
import io.github.valerioisufi.drawview.core.model.ElementOrder
import io.github.valerioisufi.drawview.core.model.PageBackground
import io.github.valerioisufi.drawview.core.model.Stamp
import io.github.valerioisufi.drawview.core.model.TextMarkupKind
import io.github.valerioisufi.drawview.core.util.FractionalIndex
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * A change of a document, as plain values: every change of a document is one of these, submitted
 * to its [DocumentSession] (by the editor, by undo / redo or by another device).
 *
 * A change refers to the pages and the elements by identifier and knows how to apply itself
 * ([applyTo]), producing the change that undoes it. The changes created from the editor keep a
 * reference to the model objects they hold ([Transient]), so that they are applied without
 * modeling the strokes again; the serialized form has the values only.
 *
 * Every change is applied with the stamp of its operation ([ChangeContext.stamp]): a piece of
 * the document is changed only by a change more recent than its last one (last-writer-wins), so
 * that the devices applying the same changes in different orders end up with the same document.
 * The pieces are the presence and the values of every element, the presence, position and format
 * of every page, the defaults of the document. Nothing is destroyed: the removed pages and
 * elements are hidden (see [DrawDocument.removedPages], [DrawPage.removedElements]) and keep
 * receiving the changes, so that what is put back is the same on every device.
 */
@Serializable
sealed interface DocumentChange {
    /**
     * Applies the change to the document of [context], recording what it did in its effects.
     *
     * @return the change undoing it, or `null` when it changed nothing (e.g. its elements are
     *   gone, or changed by a more recent change).
     */
    fun applyTo(context: ChangeContext): DocumentChange?
}

/**
 * The document a change is applied to, the stamp of its operation and the effects it records.
 */
class ChangeContext internal constructor(val document: DrawDocument, val effects: ChangeEffects, val stamp: Stamp)

/**
 * Adds [elements] to the page [pageId] (an element without order gets the order of this change: on
 * top). An element already there (e.g. removed before, and put back by undo) is added again with
 * the placement and the content of this change, unless more recent ones.
 */
@Serializable
@SerialName("addElements")
data class AddElements(val pageId: String, val elements: List<ElementData>) : DocumentChange {
    @Transient
    private var local: Map<String, DrawElement> = emptyMap()

    /**
     * Builds its elements now instead of when it is applied (the strokes are modeled again from
     * their inputs: slow), e.g. on another thread for a change received.
     */
    fun prepare() {
        if (local.isEmpty()) local = elements.associate { it.id to it.toElement() }
    }

    override fun applyTo(context: ChangeContext): DocumentChange? {
        val document = context.document
        val stamp = context.stamp
        val page = document.anyPage(pageId) ?: return null
        // The elements made present (undone by removing them), the previous values of the
        // elements present already (undone by writing them again)
        val shown = mutableListOf<String>()
        val previous = LinkedHashMap<DrawPage, MutableList<ElementData>>()
        var changed = false
        for ((i, data) in elements.withIndex()) {
            val order = data.order ?: ElementOrder(stamp, i)
            val found = document.findAnyElement(data.id, page)
            if (found == null) {
                val element = local[data.id] ?: data.toElement()
                element.order = order
                element.added = stamp
                element.removed = null
                element.placed = stamp
                element.updated = stamp
                page.add(element)
                context.effects.saved(page, element)
                shown.add(data.id)
                continue
            }

            val (from, element) = found
            if (element.added >= stamp && element.placed >= stamp && element.updated >= stamp) continue
            val removed = element.removed
            val before = element.toData()
            // Its presence: since the last addition, unless removed after it
            if (element.added < stamp) element.added = stamp
            if (removed != null && removed < stamp) {
                from.show(element)
                shown.add(data.id)
            }
            // Its placement and its content: the ones of this change, unless more recent ones
            var holder = from
            var valuesChanged = false
            if (element.placed < stamp) {
                context.place(from, element, data.placement(), page, order)
                holder = page
                valuesChanged = true
            }
            if (element.updated < stamp) {
                writeContent(element, data)
                element.updated = stamp
                valuesChanged = true
            }
            if (removed == null && valuesChanged) previous.getOrPut(from) { mutableListOf() }.add(before)
            context.effects.saved(holder, element)
            changed = true
        }
        val inverses = buildList {
            if (shown.isNotEmpty()) add(RemoveElements(pageId, shown))
            previous.forEach { (page, values) -> add(AddElements(page.id, values)) }
        }
        return when {
            inverses.isEmpty() -> if (changed) NoChange else null
            inverses.size == 1 -> inverses[0]
            else -> CompositeChange(inverses)
        }
    }

    companion object {
        /** Adds the model [elements] to [page] (the objects themselves are added). */
        fun of(page: DrawPage, elements: List<DrawElement>) =
            AddElements(page.id, elements.map { it.toData() }).also { change ->
                change.local = elements.associateBy { it.id }
            }
    }
}

/**
 * Removes the elements [ids] (of the page [pageId], or of the page another device moved them to)
 * unless added again by a more recent change: they are kept, hidden (see
 * [DrawPage.removedElements]).
 */
@Serializable
@SerialName("removeElements")
data class RemoveElements(val pageId: String, val ids: List<String>) : DocumentChange {
    override fun applyTo(context: ChangeContext): DocumentChange? {
        val document = context.document
        val hint = document.anyPage(pageId)
        val hidden = LinkedHashMap<DrawPage, MutableList<DrawElement>>()
        var changed = false
        for (id in ids) {
            val (page, element) = document.findAnyElement(id, hint) ?: continue
            val removed = element.removed
            if (removed != null) {
                // Removed on another device too: removed since the last of the two
                if (removed < context.stamp) {
                    element.removed = context.stamp
                    context.effects.saved(page, element)
                    changed = true
                }
                continue
            }
            if (element.added >= context.stamp) continue
            val bounds = page.boundsOf(element)
            page.hide(element)
            element.removed = context.stamp
            hidden.getOrPut(page) { mutableListOf() }.add(element)
            context.effects.deleted(page, element, bounds)
        }
        // Put back with their order: where they were
        val inverses = hidden.map { (page, elements) -> AddElements.of(page, elements) }
        return when {
            inverses.isEmpty() -> if (changed) NoChange else null
            inverses.size == 1 -> inverses[0]
            else -> CompositeChange(inverses)
        }
    }

    companion object {
        fun of(page: DrawPage, elements: List<DrawElement>) = RemoveElements(page.id, elements.map { it.id })
    }
}

/** The element [id], present or removed, and the page holding it (looked for in [hint] first). */
private fun DrawDocument.findAnyElement(id: String, hint: DrawPage?): Pair<DrawPage, DrawElement>? =
    findElement(id, hint) ?: findRemovedElement(id)

/** Hides the present [element] of this page (see [DrawPage.removedElements]). */
private fun DrawPage.hide(element: DrawElement) {
    remove(element)
    removedElements.add(element)
}

/** Shows the hidden [element] of this page again. */
private fun DrawPage.show(element: DrawElement) {
    removedElements.remove(element)
    element.removed = null
    add(element)
}

/**
 * Writes [placement] (if any) into [element] of the page [from], and moves it to the page [to] with
 * [order]: the placement of the change of this context.
 */
private fun ChangeContext.place(from: DrawPage, element: DrawElement, placement: Placement?, to: DrawPage, order: ElementOrder) {
    val shown = element.removed == null
    if (shown) effects.dirty(from, from.boundsOf(element))
    placement?.applyTo(element)
    if (from !== to || order != element.order) {
        if (shown) from.remove(element) else from.removedElements.remove(element)
        element.order = order
        if (shown) to.add(element) else to.removedElements.add(element)
    }
    element.placed = stamp
}

/**
 * Where an element is placed (absolute), and its stacking [order] on its page (`null`: on top,
 * the order of the change).
 */
@Serializable
sealed interface Placement {
    val id: String
    val order: ElementOrder?

    /** Writes this placement into [element] (of the matching kind). */
    fun applyTo(element: DrawElement)
}

/** A stroke, by the transformation of the geometry it was drawn with. */
@Serializable
@SerialName("stroke")
data class StrokePlacement(
    override val id: String,
    val transform: AffineTransform,
    override val order: ElementOrder? = null
) : Placement {
    override fun applyTo(element: DrawElement) {
        if (element is DrawStroke) element.place(transform)
    }
}

/**
 * A text block or an image, by its (rotated) box; [fontSize] for a text (`null` for an image).
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
    val fontSize: Float? = null,
    override val order: ElementOrder? = null
) : Placement {
    override fun applyTo(element: DrawElement) {
        when (element) {
            is DrawText -> {
                element.x = x; element.y = y
                element.width = width; element.height = height
                element.rotation = rotation
                fontSize?.let { element.fontSize = it }
                element.markDirty()
            }
            is DrawImage -> {
                element.x = x; element.y = y
                element.width = width; element.height = height
                element.rotation = rotation
            }
            else -> Unit
        }
    }
}

/** Where [element] is placed now (with its order), or `null` for an element not placed freely. */
fun placementOf(element: DrawElement): Placement? = when (element) {
    is DrawStroke -> StrokePlacement(element.id, element.transform, element.order)
    is DrawText -> BoxPlacement(element.id, element.x, element.y, element.width, element.height, element.rotation, element.fontSize, element.order)
    is DrawImage -> BoxPlacement(element.id, element.x, element.y, element.width, element.height, element.rotation, order = element.order)
    else -> null
}

/**
 * Places elements (of the page [pageId], or of the page another device moved them to) on the page
 * [toPageId]: moves, scales or rotates them, with their order (see [Placement.order]).
 */
@Serializable
@SerialName("placeElements")
data class PlaceElements(
    val pageId: String,
    val placements: List<Placement>,
    val toPageId: String = pageId
) : DocumentChange {
    override fun applyTo(context: ChangeContext): DocumentChange? {
        val document = context.document
        val hint = document.anyPage(pageId)
        val to = document.anyPage(toPageId) ?: return null
        // The elements to place, with their page, their new and their current placement (and
        // their position in this change: their order on another page, the same on every device)
        val targets = placements.withIndex().mapNotNull { (i, placement) ->
            val (from, element) = document.findAnyElement(placement.id, hint) ?: return@mapNotNull null
            if (element.placed >= context.stamp) return@mapNotNull null
            val current = placementOf(element) ?: return@mapNotNull null
            if ((current is StrokePlacement) != (placement is StrokePlacement)) return@mapNotNull null
            Target(from, element, placement, current, i)
        }
        if (targets.isEmpty()) return null

        for ((from, element, placement, _, i) in targets) {
            context.place(from, element, placement, to, placement.order ?: ElementOrder(context.stamp, i))
            context.effects.saved(to, element)
        }
        // Back to the page each one came from
        val inverses = targets.groupBy { it.from }.map { (from, moved) -> PlaceElements(toPageId, moved.map { it.current }, from.id) }
        return inverses.singleOrNull() ?: CompositeChange(inverses)
    }

    private data class Target(
        val from: DrawPage,
        val element: DrawElement,
        val placement: Placement,
        val current: Placement,
        val index: Int
    )
}

/**
 * Replaces text blocks (of the page [pageId], or of the page another device moved them to): their
 * content and their placement on the page [pageId].
 */
@Serializable
@SerialName("updateTexts")
data class UpdateTexts(val pageId: String, val texts: List<TextData>) : DocumentChange {
    override fun applyTo(context: ChangeContext): DocumentChange? {
        val document = context.document
        val page = document.anyPage(pageId) ?: return null
        val previous = LinkedHashMap<DrawPage, MutableList<TextData>>()
        for ((i, data) in texts.withIndex()) {
            val (from, element) = document.findAnyElement(data.id, page) ?: continue
            val text = element as? DrawText ?: continue
            if (text.placed >= context.stamp && text.updated >= context.stamp) continue
            previous.getOrPut(from) { mutableListOf() }.add(text.toData())
            var holder = from
            if (text.placed < context.stamp) {
                context.place(from, text, data.placement(), page, data.order ?: ElementOrder(context.stamp, i))
                holder = page
            }
            if (text.updated < context.stamp) {
                writeContent(text, data)
                text.updated = context.stamp
            }
            context.effects.saved(holder, text)
        }
        val inverses = previous.map { (page, old) -> UpdateTexts(page.id, old) }
        return if (inverses.size <= 1) inverses.singleOrNull() else CompositeChange(inverses)
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
        val hint = context.document.anyPage(pageId)
        val previous = mutableListOf<MarkupStyle>()
        for (style in styles) {
            val (page, element) = context.document.findAnyElement(style.id, hint) ?: continue
            val markup = element as? DrawTextMarkup ?: continue
            if (markup.updated >= context.stamp) continue
            previous.add(MarkupStyle(markup.id, markup.kind, markup.color))
            markup.kind = style.kind
            markup.color = style.color
            markup.updated = context.stamp
            context.effects.saved(page, markup)
        }
        if (previous.isEmpty()) return null
        return RestyleMarkups(pageId, previous)
    }
}

/**
 * Inserts [page], where its order key puts it: a new page with its content, or a removed page put
 * back as it is (its content kept receiving the changes).
 */
@Serializable
@SerialName("addPage")
data class AddPage(val page: PageData) : DocumentChange {
    @Transient
    private var local: DrawPage? = null

    override fun applyTo(context: ChangeContext): DocumentChange? {
        val document = context.document
        val existing = document.anyPage(page.id)
        if (existing != null) {
            // Its presence: since the last addition, unless removed after it (a removed page is
            // always removed after its last addition)
            if (existing.added >= context.stamp) return null
            existing.added = context.stamp
            val removed = existing.removed
            if (removed == null || removed >= context.stamp) {
                context.effects.pageSaved(existing)
                return NoChange
            }
            document.removedPages.remove(page.id)
            existing.removed = null
            document.insertPage(existing)
            context.effects.pageInserted(existing)
            return RemovePage(page.id)
        }

        val newPage = local?.takeIf { it.id == page.id } ?: page.toPage(document)
        newPage.orderKey = page.orderKey
        newPage.added = context.stamp
        newPage.orderVersion = context.stamp
        newPage.formatVersion = context.stamp
        for ((i, element) in newPage.elements().withIndex()) {
            if (element.order == ElementOrder.UNASSIGNED) element.order = ElementOrder(context.stamp, i)
            element.added = context.stamp
            element.placed = context.stamp
            element.updated = context.stamp
        }
        document.insertPage(newPage)
        context.effects.pageInserted(newPage)
        return RemovePage(page.id)
    }

    companion object {
        /** Inserts the model [page] (the object itself) where its order key puts it. */
        fun of(page: DrawPage) = AddPage(page.toData()).also { it.local = page }
    }
}

/**
 * Removes the page [pageId] with its content (unless added again by a more recent change): it is
 * kept, hidden, and can be put back. The editor never removes the last page of a document.
 */
@Serializable
@SerialName("removePage")
data class RemovePage(val pageId: String) : DocumentChange {
    override fun applyTo(context: ChangeContext): DocumentChange? {
        val document = context.document
        val page = document.pageById(pageId)
        if (page == null) {
            // Removed on another device too: removed since the last of the two
            val hidden = document.removedPages[pageId] ?: return null
            if (hidden.removed!! >= context.stamp) return null
            hidden.removed = context.stamp
            context.effects.pageSaved(hidden)
            return NoChange
        }
        if (page.added >= context.stamp) return null
        document.pages.remove(page)
        document.reindexPages()
        page.removed = context.stamp
        document.removedPages[pageId] = page
        context.effects.pageRemoved(page)
        return AddPage.of(page)
    }
}

/** A page and its new position among the pages (see [DrawPage.orderKey]). */
@Serializable
data class PageMove(val pageId: String, val orderKey: String)

/**
 * Moves pages: every page gets its new order key.
 */
@Serializable
@SerialName("movePages")
data class MovePages(val moves: List<PageMove>) : DocumentChange {
    override fun applyTo(context: ChangeContext): DocumentChange? {
        val document = context.document
        val previous = mutableListOf<PageMove>()
        for (move in moves) {
            val page = document.anyPage(move.pageId) ?: continue
            if (page.orderVersion >= context.stamp) continue
            previous.add(PageMove(page.id, page.orderKey))
            page.orderKey = move.orderKey
            page.orderVersion = context.stamp
            if (document.isShown(page)) document.repositionPage(page)
            context.effects.pageSaved(page)
        }
        if (previous.isEmpty()) return null
        return MovePages(previous)
    }

    companion object {
        /**
         * The moves putting the pages of [document] in the order [pageIds] (the fewest pages
         * move), or `null` when they are in that order already.
         */
        fun toOrder(document: DrawDocument, pageIds: List<String>): MovePages? {
            val pages = pageIds.mapNotNull { document.pageById(it) }
            val keys = FractionalIndex.reorder(pages.map { it.orderKey })
            if (keys.isEmpty()) return null
            return MovePages(keys.map { (i, key) -> PageMove(pages[i].id, key) })
        }
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
        val page = document.anyPage(pageId) ?: return null
        if (page.formatVersion >= context.stamp) return null
        val previous = SetPageFormat(pageId, page.widthMm, page.heightMm, if (page.hasCustomBackground) page.background else null)
        page.widthMm = widthMm
        page.heightMm = heightMm
        page.background = background ?: document.defaultBackground
        page.hasCustomBackground = background != null
        page.formatVersion = context.stamp
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
        if (document.defaultsVersion >= context.stamp) return null
        val previous = SetDocumentDefaults(document.defaultWidthMm, document.defaultHeightMm, document.defaultBackground)
        document.defaultWidthMm = widthMm
        document.defaultHeightMm = heightMm
        document.defaultBackground = background
        document.defaultsVersion = context.stamp
        for (page in document.pages + document.removedPages.values) {
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
    /** The file on this device (empty for the file of another device, until it is received). */
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
