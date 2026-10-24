package io.github.valerioisufi.drawview.model.change

import io.github.valerioisufi.drawview.model.DrawCustomElement
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawElement
import io.github.valerioisufi.drawview.model.DrawImage
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawResource
import io.github.valerioisufi.drawview.model.DrawStroke
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.DrawTextMarkup
import io.github.valerioisufi.drawview.model.ElementOrder
import io.github.valerioisufi.drawview.model.FractionalIndex
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.Stamp
import io.github.valerioisufi.drawview.model.StrokeModeller
import io.github.valerioisufi.drawview.model.TextMarkupKind
import io.github.valerioisufi.drawview.model.Versions
import io.github.valerioisufi.drawview.model.geometry.AffineTransform
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
public sealed interface DocumentChange {
    /**
     * Applies the change to the document of [context], recording what it did in its effects.
     *
     * @return the change undoing it, or `null` when it changed nothing (e.g. its elements are
     *   gone, or changed by a more recent change).
     */
    public fun applyTo(context: ChangeContext): DocumentChange?

    /**
     * Does now the slow work of applying it that needs no document (e.g. modeling the strokes it
     * adds again from their inputs, with [modeller]: the one of the document), e.g. on another
     * thread for a change received: it is applied sooner then.
     */
    public fun prepare(modeller: StrokeModeller) {}

    /**
     * The stamps it carries from the document it was made from (none by default): the orders of
     * its elements, the versions of the contents of its texts, what a removal of a page saw. Its
     * author had seen them all: none is after the stamp of its operation (see
     * [DocumentSession.admits]).
     */
    public fun stamps(): Sequence<Stamp> = emptySequence()

    /**
     * The orders it gives its elements (none by default): each one must be well formed and not
     * after its stamp (see [DocumentSession.admits]), so that no element goes above the next one
     * put on top.
     */
    public fun orders(): Sequence<ElementOrder> = emptySequence()
}

/**
 * The stamps [this] carries (see [DocumentChange.stamps]): the stamp of an order written before
 * the one stack and the versions of a text.
 */
private fun ElementData.stamps(): Sequence<Stamp> = sequence {
    order?.legacyStamp?.let { yield(it) }
    if (this@stamps is TextData) yieldAll(versions.stamps.values)
}

/**
 * The document a change is applied to, the stamp of its operation and the effects it records;
 * [received]: the change was made on another device.
 */
public class ChangeContext internal constructor(
    internal val document: DrawDocument,
    internal val effects: ChangeEffects,
    internal val stamp: Stamp,
    internal val received: Boolean = false
)

/**
 * Adds [elements] to the page [pageId] (an element without order gets the order of this change: on
 * top). An element already there (e.g. removed before, and put back by undo) is added again with
 * the placement, the order and the content of this change, unless more recent ones.
 *
 * [lostVersions]: the copies of contents of texts lost to others (see [UpdateTexts]), made by the
 * devices as they merge their changes; they keep no removed page (see [DrawPage.isHidden]).
 */
@Serializable
@SerialName("addElements")
public data class AddElements(val pageId: String, val elements: List<ElementData>, val lostVersions: Boolean = false) : DocumentChange {
    @Transient
    private var local: Map<String, DrawElement> = emptyMap()

    /** Builds its elements now (the strokes are modeled again from their inputs). */
    override fun prepare(modeller: StrokeModeller) {
        if (local.isEmpty()) local = elements.associate { it.id to it.toElement(modeller) }
    }

    override fun stamps(): Sequence<Stamp> = elements.asSequence().flatMap { it.stamps() }

    override fun orders(): Sequence<ElementOrder> = elements.asSequence().mapNotNull { it.order }

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
            val order = data.order ?: ElementOrder.onTop(stamp, i)
            val found = document.findAnyElement(data.id, page)
            if (found == null) {
                val element = local[data.id] ?: data.toElement(document.strokeModeller)
                element.order = order
                element.added = stamp
                element.removed = null
                element.placed = stamp
                element.ordered = stamp
                element.updated = stamp
                if (element is DrawText && data is TextData) element.contentVersions = data.versions + stamp
                page.add(element)
                context.effects.saved(page, element)
                shown.add(data.id)
                continue
            }

            val (from, element) = found
            val removed = element.removed
            val before = element.toData()
            var touched = false
            // Its presence: since the last addition, unless removed after it
            if (element.added < stamp) {
                element.added = stamp
                touched = true
            }
            if (removed != null && removed < stamp) {
                from.show(element)
                shown.add(data.id)
                touched = true
            }
            // Its placement, its order and its content: the ones of this change, unless more recent ones
            var holder = from
            var valuesChanged = false
            if (element.placed < stamp) {
                context.place(from, element, data.placement(), page)
                holder = page
                valuesChanged = true
            }
            if (context.reorder(holder, element, order)) valuesChanged = true
            val contentChanged = when {
                element is DrawText && data is TextData -> context.writeText(holder, element, data)
                element.updated < stamp -> {
                    writeContent(element, data)
                    element.updated = stamp
                    true
                }
                else -> false
            }
            if (contentChanged) valuesChanged = true
            if (!touched && !valuesChanged) continue
            if (removed == null && valuesChanged) previous.getOrPut(from) { mutableListOf() }.add(before)
            context.effects.saved(holder, element)
            changed = true
        }
        val inverses = buildList {
            if (shown.isNotEmpty()) add(RemoveElements(pageId, shown))
            previous.forEach { (page, values) -> add(AddElements(page.id, values)) }
        }
        val inverse = when {
            inverses.isEmpty() -> if (changed) NoChange else null
            inverses.size == 1 -> inverses[0]
            else -> CompositeChange(inverses)
        }
        if (!lostVersions) context.contentChanged(page, inverse)
        return inverse
    }

    public companion object {
        /** Adds the model [elements] to [page] (the objects themselves are added). */
        public fun of(page: DrawPage, elements: List<DrawElement>): AddElements =
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
public data class RemoveElements(val pageId: String, val ids: List<String>) : DocumentChange {
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

    public companion object {
        public fun of(page: DrawPage, elements: List<DrawElement>): RemoveElements = RemoveElements(page.id, elements.map { it.id })
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
 * Notes that the change of this context changed the content of [page] (when it is an operation:
 * [inverse] not `null`, or received): a removal of the page that had not seen it hides it no more
 * (see [DrawPage.isHidden]).
 */
private fun ChangeContext.contentChanged(page: DrawPage, inverse: DocumentChange?) {
    if (inverse == null && !received) return
    val versions = page.contentVersions + stamp
    if (versions == page.contentVersions) return
    page.contentVersions = versions
    effects.pageVersioned(page)
    showAsRemoved(page)
}

/** Shows or hides [page] as its removal (if any) tells (see [DrawPage.isHidden]). */
private fun ChangeContext.showAsRemoved(page: DrawPage) {
    val shown = document.isShown(page)
    if (page.isHidden && shown) {
        document.removeShownPage(page)
        document.removedPages[page.id] = page
        effects.pageRemoved(page)
    } else if (!page.isHidden && !shown) {
        document.removedPages.remove(page.id)
        document.insertPage(page)
        effects.pageInserted(page)
    }
}

/** How far under a text the copy of a content it lost goes (millimeters, see [writeText]). */
private const val LOST_VERSION_GAP_MM = 4f

/**
 * Writes the content of [data] (the text and its format) into [text] of [page], as the change of
 * this context made it from the content [TextData.versions]. Of two contents made apart (neither
 * includes the other: two devices changed the text without seeing each other's change) the most
 * recent wins and the other one is kept as a copy under the text (see
 * [ChangeEffects.lostVersions]), whatever the order the devices see them in.
 *
 * @return whether the content of [text] changed.
 */
private fun ChangeContext.writeText(page: DrawPage, text: DrawText, data: TextData): Boolean {
    val incoming = data.versions + stamp
    val current = text.contentVersions
    // Included already
    if (current.covers(incoming)) return false
    val newer = stamp > text.updated
    if (!(newer && incoming.covers(current))) {
        val kept = text.toData()
        if (!data.hasContentOf(kept)) {
            // Made apart: the one lost is kept, as the same copy on every device
            val (lost, lostAt) = if (newer) kept to text.updated else data.copy(versions = incoming) to stamp
            effects.versionLost(page, lost.copy(id = "${text.id}~$lostAt", y = lost.y + lost.height + LOST_VERSION_GAP_MM, order = null))
        }
    }
    if (!newer) return false
    writeContent(text, data)
    text.contentVersions = incoming
    text.updated = stamp
    return true
}

/**
 * Writes [placement] (if any) into [element] of the page [from], and moves it to the page [to]:
 * the placement of the change of this context. Its order stays (it is apart from the placement,
 * see [DrawElement.ordered]): on another page it takes its place in the stack by its order, and a
 * change putting it on top there goes with the move (see [ReorderElements.onTop]).
 */
private fun ChangeContext.place(from: DrawPage, element: DrawElement, placement: Placement?, to: DrawPage) {
    val shown = element.removed == null
    if (shown) effects.dirty(from, from.boundsOf(element))
    placement?.applyTo(element)
    if (from !== to) {
        if (shown) from.remove(element) else from.removedElements.remove(element)
        if (shown) to.add(element) else to.removedElements.add(element)
    }
    element.placed = stamp
}

/**
 * Writes [order] into [element] of [page], the order of the change of this context, unless its
 * order was changed by a more recent change; whether it did (the same order written again counts:
 * the change is the most recent one now).
 */
private fun ChangeContext.reorder(page: DrawPage, element: DrawElement, order: ElementOrder): Boolean {
    if (element.ordered >= stamp) return false
    if (order != element.order) {
        if (element.removed == null) {
            page.remove(element)
            element.order = order
            page.add(element)
        } else {
            element.order = order
        }
    }
    element.ordered = stamp
    return true
}

/**
 * Where an element is placed (absolute). [order] is no longer read: the order of an element is
 * changed apart from its placement (see [ReorderElements]); the changes made before the order had
 * its own register carry it.
 */
@Serializable
public sealed interface Placement {
    public val id: String
    public val order: ElementOrder?

    /** Writes this placement into [element] (of the matching kind). */
    public fun applyTo(element: DrawElement)
}

/** A stroke, by the transformation of the geometry it was drawn with. */
@Serializable
@SerialName("stroke")
public data class StrokePlacement(
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
public data class BoxPlacement(
    override val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float,
    val fontSize: Float? = null,
    override val order: ElementOrder? = null,
    /** Whether the width of a text follows its text (see `DrawText.autoWidth`). */
    val autoWidth: Boolean? = null
) : Placement {
    override fun applyTo(element: DrawElement) {
        when (element) {
            is DrawText -> {
                element.x = x; element.y = y
                element.width = width; element.height = height
                element.rotation = rotation
                fontSize?.let { element.fontSize = it }
                autoWidth?.let { element.autoWidth = it }
                element.markDirty()
            }
            is DrawImage -> {
                element.x = x; element.y = y
                element.width = width; element.height = height
                element.rotation = rotation
            }
            is DrawCustomElement -> {
                element.x = x; element.y = y
                element.width = width; element.height = height
                element.rotation = rotation
            }
            else -> Unit
        }
    }
}

/** Where [element] is placed now (with its order), or `null` for an element not placed freely. */
public fun placementOf(element: DrawElement): Placement? = when (element) {
    is DrawStroke -> StrokePlacement(element.id, element.transform)
    is DrawText -> BoxPlacement(element.id, element.x, element.y, element.width, element.height, element.rotation, element.fontSize, autoWidth = element.autoWidth)
    is DrawImage -> BoxPlacement(element.id, element.x, element.y, element.width, element.height, element.rotation)
    is DrawCustomElement -> BoxPlacement(element.id, element.x, element.y, element.width, element.height, element.rotation)
    else -> null
}

/**
 * Places elements (of the page [pageId], or of the page another device moved them to) on the page
 * [toPageId]: moves, scales or rotates them, their orders as they are (see [place]).
 */
@Serializable
@SerialName("placeElements")
public data class PlaceElements(
    val pageId: String,
    val placements: List<Placement>,
    val toPageId: String = pageId
) : DocumentChange {
    override fun stamps(): Sequence<Stamp> = placements.asSequence().mapNotNull { it.order?.legacyStamp }

    override fun orders(): Sequence<ElementOrder> = placements.asSequence().mapNotNull { it.order }

    override fun applyTo(context: ChangeContext): DocumentChange? {
        val document = context.document
        val hint = document.anyPage(pageId)
        val to = document.anyPage(toPageId) ?: return null
        // The elements to place, with their page, their new and their current placement
        val targets = placements.mapNotNull { placement ->
            val (from, element) = document.findAnyElement(placement.id, hint) ?: return@mapNotNull null
            if (element.placed >= context.stamp) return@mapNotNull null
            val current = placementOf(element) ?: return@mapNotNull null
            if ((current is StrokePlacement) != (placement is StrokePlacement)) return@mapNotNull null
            Target(from, element, placement, current)
        }
        if (targets.isEmpty()) {
            context.contentChanged(to, null)
            return null
        }

        for ((from, element, placement) in targets) {
            context.place(from, element, placement, to)
            context.effects.saved(to, element)
        }
        // Back to the page each one came from
        val inverses = targets.groupBy { it.from }.map { (from, moved) -> PlaceElements(toPageId, moved.map { it.current }, from.id) }
        return (inverses.singleOrNull() ?: CompositeChange(inverses)).also { context.contentChanged(to, it) }
    }

    private data class Target(
        val from: DrawPage,
        val element: DrawElement,
        val placement: Placement,
        val current: Placement
    )
}

/** An element and its new order (`null`: on top, the order of the change; see [ReorderElements]). */
@Serializable
public data class ElementOrdering(val id: String, val order: ElementOrder? = null)

/**
 * Changes the order of elements (of the page [pageId], or of the page another device moved them
 * to) in the stack of their page: each one takes its order (see [ElementOrdering]) unless its
 * order was changed by a more recent change. Undone by their previous orders.
 */
@Serializable
@SerialName("reorderElements")
public data class ReorderElements(val pageId: String, val orders: List<ElementOrdering>) : DocumentChange {
    override fun stamps(): Sequence<Stamp> = orders.asSequence().mapNotNull { it.order?.legacyStamp }

    override fun orders(): Sequence<ElementOrder> = orders.asSequence().mapNotNull { it.order }

    override fun applyTo(context: ChangeContext): DocumentChange? {
        val document = context.document
        val hint = document.anyPage(pageId)
        val previous = LinkedHashMap<DrawPage, MutableList<ElementOrdering>>()
        for ((i, entry) in orders.withIndex()) {
            val (page, element) = document.findAnyElement(entry.id, hint) ?: continue
            val before = element.order
            if (!context.reorder(page, element, entry.order ?: ElementOrder.onTop(context.stamp, i))) continue
            previous.getOrPut(page) { mutableListOf() }.add(ElementOrdering(element.id, before))
            context.effects.saved(page, element)
        }
        val inverses = previous.map { (page, old) -> ReorderElements(page.id, old) }
        val inverse = if (inverses.size <= 1) inverses.singleOrNull() else CompositeChange(inverses)
        // The page of the change, whatever the devices found (the same versions on every device)
        hint?.let { context.contentChanged(it, inverse) }
        return inverse
    }

    public companion object {
        /**
         * The change moving the elements [ids] of [page] in its stack by [move] (see [StackMove]),
         * as a block keeping their order among themselves: the fewest elements take a new order.
         * `null` when nothing would change (e.g. already above everything they overlap).
         */
        public fun of(page: DrawPage, ids: Collection<String>, move: StackMove): ReorderElements? =
            StackMoves.of(page, ids.toSet(), move)?.let { ReorderElements(page.id, it) }

        /**
         * Puts the elements [ids] (in this order) on top of the stack of the page [pageId]: e.g.
         * with the move of elements to that page, in one change.
         */
        public fun onTop(pageId: String, ids: List<String>): ReorderElements = ReorderElements(pageId, ids.map { ElementOrdering(it) })
    }
}

/**
 * Replaces text blocks (of the page [pageId], or of the page another device moved them to): their
 * content and their placement on the page [pageId], their orders as they are (see [place]).
 */
@Serializable
@SerialName("updateTexts")
public data class UpdateTexts(val pageId: String, val texts: List<TextData>) : DocumentChange {
    override fun stamps(): Sequence<Stamp> = texts.asSequence().flatMap { it.stamps() }

    override fun orders(): Sequence<ElementOrder> = texts.asSequence().mapNotNull { it.order }

    override fun applyTo(context: ChangeContext): DocumentChange? {
        val document = context.document
        val page = document.anyPage(pageId) ?: return null
        val previous = LinkedHashMap<DrawPage, MutableList<TextData>>()
        for (data in texts) {
            val (from, element) = document.findAnyElement(data.id, page) ?: continue
            val text = element as? DrawText ?: continue
            val before = text.toData()
            var holder = from
            var changed = false
            if (text.placed < context.stamp) {
                context.place(from, text, data.placement(), page)
                holder = page
                changed = true
            }
            // Its content: the most recent one, the other one kept as a copy when made apart
            if (context.writeText(holder, text, data)) changed = true
            if (!changed) continue
            previous.getOrPut(from) { mutableListOf() }.add(before)
            context.effects.saved(holder, text)
        }
        val inverses = previous.map { (page, old) -> UpdateTexts(page.id, old) }
        val inverse = if (inverses.size <= 1) inverses.singleOrNull() else CompositeChange(inverses)
        context.contentChanged(page, inverse)
        return inverse
    }
}

/** The kind and the color of a mark of the text of a PDF. */
@Serializable
public data class MarkupStyle(val id: String, val kind: TextMarkupKind, val color: Int)

/**
 * Changes the kind and the color of marks of the text of the PDFs of the page [pageId].
 */
@Serializable
@SerialName("restyleMarkups")
public data class RestyleMarkups(val pageId: String, val styles: List<MarkupStyle>) : DocumentChange {
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
        val inverse = if (previous.isEmpty()) null else RestyleMarkups(pageId, previous)
        hint?.let { context.contentChanged(it, inverse) }
        return inverse
    }
}

/**
 * Replaces the value of the element [id] of a kind added by an application (see
 * [DrawCustomElement]; of the page [pageId], or of the page another device moved it to) with
 * [payload] of format [version]: the most recent value wins on every device.
 */
@Serializable
@SerialName("updateContent")
public class UpdateElementContent(public val pageId: String, public val id: String, public val version: Int, public val payload: ByteArray) : DocumentChange {
    override fun applyTo(context: ChangeContext): DocumentChange? {
        val hint = context.document.anyPage(pageId)
        val (page, element) = context.document.findAnyElement(id, hint) ?: return null
        val custom = element as? DrawCustomElement ?: return null
        if (custom.updated >= context.stamp) return null
        val previous = UpdateElementContent(page.id, id, custom.version, custom.payload)
        custom.version = version
        custom.payload = payload
        custom.updated = context.stamp
        context.effects.saved(page, custom)
        context.contentChanged(page, previous)
        return previous
    }

    override fun equals(other: Any?): Boolean =
        other is UpdateElementContent && pageId == other.pageId && id == other.id && version == other.version && payload.contentEquals(other.payload)

    override fun hashCode(): Int = id.hashCode() * 31 + payload.contentHashCode()

    override fun toString(): String = "UpdateElementContent(pageId=$pageId, id=$id, version=$version, ${payload.size} bytes)"
}

/**
 * Inserts [page], where its order key puts it: a new page with its content, or a removed page put
 * back as it is (its content kept receiving the changes).
 */
@Serializable
@SerialName("addPage")
public data class AddPage(val page: PageData) : DocumentChange {
    @Transient
    private var local: DrawPage? = null

    override fun stamps(): Sequence<Stamp> = page.elements.asSequence().flatMap { it.stamps() }

    override fun orders(): Sequence<ElementOrder> = page.elements.asSequence().mapNotNull { it.order }

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
            // Its removal is over (shown already when it had not seen all of its content)
            existing.removed = null
            existing.removalSaw = null
            if (document.isShown(existing)) context.effects.pageSaved(existing) else context.showAsRemoved(existing)
            return RemovePage(page.id)
        }

        val newPage = local?.takeIf { it.id == page.id } ?: page.toPage(document)
        newPage.orderKey = page.orderKey
        newPage.added = context.stamp
        newPage.orderVersion = context.stamp
        newPage.formatVersion = context.stamp
        for ((i, element) in newPage.elements().withIndex()) {
            if (element.order == ElementOrder.UNASSIGNED) element.order = ElementOrder.onTop(context.stamp, i)
            element.added = context.stamp
            element.placed = context.stamp
            element.ordered = context.stamp
            element.updated = context.stamp
            if (element is DrawText) element.contentVersions += context.stamp
        }
        document.insertPage(newPage)
        context.effects.pageInserted(newPage)
        return RemovePage(page.id)
    }

    public companion object {
        /** Inserts the model [page] (the object itself) where its order key puts it. */
        public fun of(page: DrawPage): AddPage = AddPage(page.toData()).also { it.local = page }
    }
}

/**
 * Removes the page [pageId] with its content (unless added again by a more recent change): it is
 * kept, hidden, and can be put back. The editor never removes the last page of a document.
 *
 * Only the content it [saw] is removed: when another device added to the page meanwhile, the page
 * stays (see [DrawPage.isHidden]). Made on this device, it saw the content of the page as it is
 * when applied (written into [saw] then); `null` in a change received: all of it.
 */
@Serializable
@SerialName("removePage")
public data class RemovePage(val pageId: String, var saw: Versions? = null) : DocumentChange {
    override fun stamps(): Sequence<Stamp> = saw?.stamps?.values?.asSequence() ?: emptySequence()

    override fun applyTo(context: ChangeContext): DocumentChange? {
        val document = context.document
        val page = document.anyPage(pageId) ?: return null
        // Put back after it, or removed again after it (on another device too)
        if (page.added >= context.stamp) return null
        val removed = page.removed
        if (removed != null && removed >= context.stamp) return null
        if (saw == null && !context.received) saw = page.contentVersions
        val shown = document.isShown(page)
        page.removed = context.stamp
        page.removalSaw = saw
        context.showAsRemoved(page)
        if (shown && !document.isShown(page)) return AddPage.of(page)
        context.effects.pageSaved(page)
        return NoChange
    }
}

/** A page and its new position among the pages (see [DrawPage.orderKey]). */
@Serializable
public data class PageMove(val pageId: String, val orderKey: String)

/**
 * Moves pages: every page gets its new order key.
 */
@Serializable
@SerialName("movePages")
public data class MovePages(val moves: List<PageMove>) : DocumentChange {
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

    public companion object {
        /**
         * The moves putting the pages of [document] in the order [pageIds] (the fewest pages
         * move), or `null` when they are in that order already.
         */
        public fun toOrder(document: DrawDocument, pageIds: List<String>): MovePages? {
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
public data class SetPageFormat(
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
public data class SetDocumentDefaults(
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

/** Renames the document: the most recent title wins on every device. */
@Serializable
@SerialName("renameDocument")
public data class RenameDocument(val title: String) : DocumentChange {
    override fun applyTo(context: ChangeContext): DocumentChange? {
        val document = context.document
        if (document.titleVersion >= context.stamp) return null
        val previous = RenameDocument(document.title)
        document.title = title
        document.titleVersion = context.stamp
        context.effects.documentRenamed()
        return previous
    }
}

/**
 * Adds a file to the document. The files are never removed by undo (their content is kept with
 * the document): the change undoing it is [NoChange].
 */
@Serializable
@SerialName("addResource")
public data class AddResource(val resource: ResourceData) : DocumentChange {
    /** The file on this device (empty for the file of another device, until it is received). */
    @Transient
    private var path: String = ""

    override fun applyTo(context: ChangeContext): DocumentChange? {
        val document = context.document
        if (document.findResource(resource.id) != null) return null
        val added = DrawResource(resource.id, resource.type, path, resource.hash)
        document.addResource(added)
        context.effects.resourceAdded(added)
        return NoChange
    }

    public companion object {
        /** Adds the file [path] of this device (its content of hash [hash]) as the resource [id]. */
        public fun of(id: String, type: DrawResource.ResourceType, path: String, hash: String = ""): AddResource =
            AddResource(ResourceData(id, type, hash)).also { it.path = path }
    }
}

/** A change that changes nothing (the change undoing [AddResource]). */
@Serializable
@SerialName("none")
internal data object NoChange : DocumentChange {
    override fun applyTo(context: ChangeContext): DocumentChange = NoChange
}

/**
 * Several changes applied as one, in order (undone in the reverse order).
 */
@Serializable
@SerialName("composite")
public data class CompositeChange(val changes: List<DocumentChange>) : DocumentChange {
    override fun prepare(modeller: StrokeModeller): Unit = changes.forEach { it.prepare(modeller) }

    override fun stamps(): Sequence<Stamp> = changes.asSequence().flatMap { it.stamps() }

    override fun orders(): Sequence<ElementOrder> = changes.asSequence().flatMap { it.orders() }

    override fun applyTo(context: ChangeContext): DocumentChange? {
        val inverses = changes.mapNotNull { it.applyTo(context) }
        if (inverses.isEmpty()) return null
        return CompositeChange(inverses.asReversed().toList())
    }
}
