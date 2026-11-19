package io.github.valerioisufi.drawview.model.change

import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawElement
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawStroke
import io.github.valerioisufi.drawview.model.ElementOrder
import io.github.valerioisufi.drawview.model.FractionalIndex
import io.github.valerioisufi.drawview.model.Versions
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import io.github.valerioisufi.drawview.model.geometry.Rect
import io.github.valerioisufi.drawview.model.newElementId
import io.github.valerioisufi.drawview.model.remodelingBrush
import io.github.valerioisufi.drawview.platform.PlatformFiles

/*
 * The changes pasting what was copied (see CopiedContent) into a document: new identifiers, the
 * resources of the document of the same content or new resources on the same files, the
 * positions, the orders (see `docs/plans/cross-document-paste.md`).
 */

/** Whether the file [path] is on this device (an empty path: a file not received yet). */
public fun isFileHere(path: String): Boolean = path.isNotEmpty() && PlatformFiles.exists(path)

/**
 * Elements pasted: the [change] adding them with their resources (`null` when nothing is pasted),
 * the [elements] it adds (the model objects), the box they fill and the rotation of their frame;
 * [leftOut]: the elements not pasted, their file not on this device.
 */
public class PastedElements(
    public val change: DocumentChange?,
    public val elements: List<DrawElement>,
    public val bounds: Rect,
    public val baseRotation: Float,
    public val leftOut: Int
)

/**
 * Pages pasted: the [change] adding them with their resources, the [pages] it adds (the model
 * objects), in their order; [leftOut]: the elements not pasted, their file not on this device.
 */
public class PastedPages(
    public val change: DocumentChange?,
    public val pages: List<DrawPage>,
    public val leftOut: Int
)

/**
 * Pastes these elements on [page] of [document], moved by [dx], [dy] (page millimeters), on top
 * of its stack in their order. An element whose file is not here ([fileIsHere]) is left out.
 */
public fun CopiedElements.pasteInto(
    document: DrawDocument,
    page: DrawPage,
    dx: Float,
    dy: Float,
    fileIsHere: (String) -> Boolean = ::isFileHere
): PastedElements {
    val resources = ResourceMapping(document, this.resources, fileIsHere)
    val move = Matrix2D().postTranslate(dx, dy)
    val models = elements.mapNotNull { data -> resources.pasted(data, dx, dy, move)?.let { modelOf(data, it, document) } }
    val change = if (models.isEmpty()) null else resources.with(AddElements.of(page, models))
    return PastedElements(change, models, bounds.translate(dx, dy), baseRotation, elements.size - models.size)
}

/**
 * Pastes these pages into [document] at [index] (the pages from [index] on after them), with all
 * their content: new pages, of their size and background. An element whose file is not here
 * ([fileIsHere]) is left out, its page kept.
 */
public fun CopiedPages.pasteInto(
    document: DrawDocument,
    index: Int,
    fileIsHere: (String) -> Boolean = ::isFileHere
): PastedPages {
    val resources = ResourceMapping(document, this.resources, fileIsHere)
    val keys = orderKeys(document, index.coerceIn(0, document.pages.size), this.pages.size)
    var leftOut = 0
    val pages = this.pages.mapIndexed { i, data ->
        val elements = data.elements.mapNotNull { source -> resources.pasted(source, 0f, 0f, null)?.let { source to it } }
        leftOut += data.elements.size - elements.size
        // Their stack as it was, under what is put on top later; the PDF pages and their marks
        // outside of the stack
        val orders = ElementOrder.spread(elements.count { it.second.isStacked }).iterator()
        DrawPage(
            widthMm = data.widthMm,
            heightMm = data.heightMm,
            background = data.background ?: document.defaultBackground,
            id = newElementId(),
            orderKey = keys[i]
        ).apply {
            hasCustomBackground = data.background != null
            for ((source, copy) in elements) add(modelOf(source, if (copy.isStacked) copy.withOrder(orders.next()) else copy, document))
        }
    }
    val change = if (pages.isEmpty()) null else resources.with(CompositeChange(pages.map { AddPage.of(it) }))
    return PastedPages(change, pages, leftOut)
}

/**
 * The model element of [copy], pasted from [source]: a stroke with the mesh it was copied with
 * (see [CopiedContent.strokeMeshes]), not modeled again; the others made from their values.
 */
private fun CopiedContent.modelOf(source: ElementData, copy: ElementData, document: DrawDocument): DrawElement {
    val mesh = strokeMeshes[source.id]
    if (copy !is StrokeData || mesh == null) return copy.toElement(document.strokeModeller)
    return DrawStroke(id = copy.id, mesh = mesh, brushConfig = remodelingBrush(copy.tool, copy.size, copy.color), transform = copy.transform)
        .also { stroke -> copy.order?.let { stroke.order = it } }
}

/** [count] increasing order keys for pages inserted at [index] of [document] (see [DrawDocument.orderKeyAt]). */
private fun orderKeys(document: DrawDocument, index: Int, count: Int): List<String> {
    val before = document.pages.getOrNull(index - 1)?.orderKey
    // Pages of equal keys: after all of them
    val after = document.pages.drop(index).firstOrNull { before == null || it.orderKey > before }?.orderKey
    val keys = ArrayList<String>(count)
    var previous = before
    repeat(count) {
        val key = FractionalIndex.between(previous, after)
        keys.add(key)
        previous = key
    }
    return keys
}

/** Whether the element of [this] is in the stack of its page (the PDF pages and their marks are not). */
private val ElementData.isStacked: Boolean
    get() = this is StrokeData || this is TextData || this is ImageData || this is CustomElementData

private fun ElementData.withOrder(order: ElementOrder): ElementData = when (this) {
    is StrokeData -> copy(order = order)
    is TextData -> copy(order = order)
    is ImageData -> copy(order = order)
    is CustomElementData -> copy(order = order)
    is PdfData -> copy(order = order)
    is TextMarkupData -> copy(order = order)
}

/**
 * The resources of [document] for the ones of what was copied: the same resource (pasted in its
 * own document), else the one of the same content, else a new resource on the same file when it
 * is here; none when it is not.
 */
private class ResourceMapping(
    private val document: DrawDocument,
    private val copied: List<CopiedResource>,
    private val fileIsHere: (String) -> Boolean
) {
    private val ids = HashMap<String, String?>()
    private val added = ArrayList<AddResource>()

    fun idFor(sourceId: String): String? {
        if (sourceId in ids) return ids[sourceId]
        val resource = copied.firstOrNull { it.resource.id == sourceId }
        val id = when {
            // Not a resource of its document (an image of no file): as it was
            resource == null -> sourceId
            document.findResource(sourceId) != null -> sourceId
            else -> {
                val data = resource.resource
                document.resources.firstOrNull { data.hash.isNotEmpty() && it.hash == data.hash }?.id
                    ?: if (fileIsHere(resource.path)) {
                        newElementId().also { added.add(AddResource.of(it, data.type, resource.path, data.hash)) }
                    } else {
                        null
                    }
            }
        }
        ids[sourceId] = id
        return id
    }

    /**
     * [data] pasted: a new identifier, moved by [dx], [dy] ([move] for a stroke, `null`: not
     * moved), no order (put on top by the change), its texts' content new; `null` when its file is
     * not here.
     */
    fun pasted(data: ElementData, dx: Float, dy: Float, move: Matrix2D?): ElementData? {
        val id = newElementId()
        return when (data) {
            is StrokeData -> data.copy(id = id, transform = if (move == null) data.transform else data.transform.then(move), order = null)
            is TextData -> data.copy(id = id, x = data.x + dx, y = data.y + dy, order = null, versions = Versions.NONE)
            is CustomElementData -> data.copy(id = id, x = data.x + dx, y = data.y + dy, order = null)
            is ImageData -> idFor(data.resourceId)?.let { data.copy(id = id, resourceId = it, x = data.x + dx, y = data.y + dy, order = null) }
            is PdfData -> idFor(data.resourceId)?.let { data.copy(id = id, resourceId = it, order = null) }
            is TextMarkupData -> idFor(data.resourceId)?.let { data.copy(id = id, resourceId = it, order = null) }
        }
    }

    /** [change] after the resources it needs (one change: one step to undo). */
    fun with(change: DocumentChange): DocumentChange = if (added.isEmpty()) change else CompositeChange(added + change)
}
