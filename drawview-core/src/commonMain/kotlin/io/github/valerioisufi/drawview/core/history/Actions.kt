package io.github.valerioisufi.drawview.core.history

import androidx.compose.ui.geometry.Rect
import io.github.valerioisufi.drawview.core.model.BrushConfig
import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.DrawImage
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.DrawStroke
import io.github.valerioisufi.drawview.core.model.DrawText
import io.github.valerioisufi.drawview.core.model.PageBackground
import io.github.valerioisufi.drawview.core.model.StrokeMesh

/**
 * Reference to the page an action applies to. Actions created from the UI reference the page
 * itself (robust to page reordering); the index based form is kept for headless callers.
 */
sealed interface PageRef {
    fun resolve(document: DrawDocument?): DrawPage?

    class ByIndex(val index: Int) : PageRef {
        override fun resolve(document: DrawDocument?): DrawPage? = document?.pages?.getOrNull(index)
    }

    class ByPage(val page: DrawPage) : PageRef {
        override fun resolve(document: DrawDocument?): DrawPage? =
            if (document == null || document.pages.contains(page)) page else null
    }
}

internal fun unionBounds(rects: List<Rect>): Rect? {
    if (rects.isEmpty()) return null
    var l = Float.POSITIVE_INFINITY
    var t = Float.POSITIVE_INFINITY
    var r = Float.NEGATIVE_INFINITY
    var b = Float.NEGATIVE_INFINITY
    for (rect in rects) {
        if (rect.left < l) l = rect.left
        if (rect.top < t) t = rect.top
        if (rect.right > r) r = rect.right
        if (rect.bottom > b) b = rect.bottom
    }
    return Rect(l, t, r, b)
}

/**
 * Action representing addition of new strokes, possibly spanning several pages.
 */
class AddStrokesAction(
    private val groups: List<Pair<PageRef, List<DrawStroke>>>
) : DrawAction {
    constructor(pageIndex: Int, strokes: List<DrawStroke>) : this(listOf(PageRef.ByIndex(pageIndex) to strokes))
    constructor(pageIndex: Int, stroke: DrawStroke) : this(pageIndex, listOf(stroke))
    constructor(page: DrawPage, strokes: List<DrawStroke>) : this(listOf(PageRef.ByPage(page) to strokes))

    val strokes: List<DrawStroke> get() = groups.flatMap { it.second }

    override suspend fun undo(context: DrawingActionContext) {
        for ((ref, strokes) in groups) {
            val page = ref.resolve(context.document) ?: continue
            page.strokes.removeAll(strokes)
            page.markContentChanged()
            context.persist { deleteStrokes(strokes) }
            context.invalidatePageContent(page, unionBounds(strokes.map { it.bounds }))
        }
    }

    override suspend fun redo(context: DrawingActionContext) {
        for ((ref, strokes) in groups) {
            val page = ref.resolve(context.document) ?: continue
            page.strokes.addAll(strokes.filter { it !in page.strokes })
            page.markContentChanged()
            context.persist { saveNewStrokes(page, strokes) }
            context.invalidatePageContent(page, unionBounds(strokes.map { it.bounds }))
        }
    }
}

/**
 * Action representing erasure / removal of strokes from a page. Undo restores the strokes at
 * their original stacking position.
 *
 * @param originalPositions index of each stroke in the page list at the moment of its removal
 * (strokes listed in removal order): re-inserting them in reverse order restores the exact
 * original order.
 */
class EraseStrokesAction(
    private val pageRef: PageRef,
    val strokes: List<DrawStroke>,
    private val originalPositions: List<Int>? = null
) : DrawAction {
    constructor(pageIndex: Int, strokes: List<DrawStroke>) : this(PageRef.ByIndex(pageIndex), strokes)
    constructor(pageIndex: Int, stroke: DrawStroke) : this(pageIndex, listOf(stroke))
    constructor(page: DrawPage, strokes: List<DrawStroke>, originalPositions: List<Int>) :
        this(PageRef.ByPage(page), strokes, originalPositions)

    override suspend fun undo(context: DrawingActionContext) {
        val page = pageRef.resolve(context.document) ?: return
        val positions = originalPositions
        if (positions != null && positions.size == strokes.size) {
            for (i in strokes.indices.reversed()) {
                val stroke = strokes[i]
                if (stroke !in page.strokes) page.strokes.add(positions[i].coerceIn(0, page.strokes.size), stroke)
            }
        } else {
            page.strokes.addAll(strokes.filter { it !in page.strokes })
        }
        page.markContentChanged()
        context.persist { saveNewStrokes(page, strokes) }
        context.invalidatePageContent(page, unionBounds(strokes.map { it.bounds }))
    }

    override suspend fun redo(context: DrawingActionContext) {
        val page = pageRef.resolve(context.document) ?: return
        page.strokes.removeAll(strokes)
        page.markContentChanged()
        context.persist { deleteStrokes(strokes) }
        context.invalidatePageContent(page, unionBounds(strokes.map { it.bounds }))
    }
}

/**
 * Action representing the creation of a text block.
 */
class AddTextAction(
    private val page: DrawPage,
    private val text: DrawText
) : DrawAction {
    override suspend fun undo(context: DrawingActionContext) {
        context.clearSelection()
        page.texts.remove(text)
        page.markContentChanged()
        context.persist { deleteText(text) }
        context.invalidatePageContent(page, text.getRotatedBounds())
    }

    override suspend fun redo(context: DrawingActionContext) {
        if (text !in page.texts) page.texts.add(text)
        page.markContentChanged()
        context.persist { saveText(page, text) }
        context.invalidatePageContent(page, text.getRotatedBounds())
    }
}

/**
 * Action representing the edit of the content/format of an existing text block.
 */
class EditTextAction(
    private val page: DrawPage,
    private val text: DrawText,
    private val before: DrawText,
    private val after: DrawText
) : DrawAction {
    private suspend fun apply(context: DrawingActionContext, state: DrawText) {
        context.clearSelection()
        val oldBounds = text.getRotatedBounds()
        text.copyFrom(state)
        page.markContentChanged()
        context.persist { saveText(page, text) }
        context.invalidatePageContent(page, unionBounds(listOf(oldBounds, text.getRotatedBounds())))
    }

    override suspend fun undo(context: DrawingActionContext) = apply(context, before)
    override suspend fun redo(context: DrawingActionContext) = apply(context, after)
}

/**
 * Action representing the import of an image.
 */
class AddImageAction(
    private val page: DrawPage,
    private val image: DrawImage
) : DrawAction {
    override suspend fun undo(context: DrawingActionContext) {
        context.clearSelection()
        page.images.remove(image)
        page.markContentChanged()
        context.persist { deleteImage(image) }
        context.invalidatePageContent(page, image.getRotatedBounds())
    }

    override suspend fun redo(context: DrawingActionContext) {
        if (image !in page.images) page.images.add(image)
        page.markContentChanged()
        context.persist { saveImage(page, image) }
        context.invalidatePageContent(page, image.getRotatedBounds())
    }
}

/**
 * Action representing the deletion of a group of elements (lasso delete / cut).
 */
class DeleteElementsAction(
    private val page: DrawPage,
    private val strokes: List<DrawStroke>,
    private val texts: List<DrawText>,
    private val images: List<DrawImage>
) : DrawAction {
    private fun bounds(): Rect? = unionBounds(
        strokes.map { it.bounds } + texts.map { it.getRotatedBounds() } + images.map { it.getRotatedBounds() }
    )

    override suspend fun undo(context: DrawingActionContext) {
        context.clearSelection()
        strokes.forEach { it.isDragging = false }
        texts.forEach { it.isDragging = false }
        images.forEach { it.isDragging = false }
        page.strokes.addAll(strokes.filter { it !in page.strokes })
        page.texts.addAll(texts.filter { it !in page.texts })
        page.images.addAll(images.filter { it !in page.images })
        page.markContentChanged()
        context.persist {
            saveNewStrokes(page, strokes)
            texts.forEach { saveText(page, it) }
            images.forEach { saveImage(page, it) }
        }
        bounds()?.let { context.invalidatePageContent(page, it) }
    }

    override suspend fun redo(context: DrawingActionContext) {
        context.clearSelection()
        page.strokes.removeAll(strokes)
        page.texts.removeAll(texts)
        page.images.removeAll(images)
        page.markContentChanged()
        context.persist {
            deleteStrokes(strokes)
            texts.forEach { deleteText(it) }
            images.forEach { deleteImage(it) }
        }
        bounds()?.let { context.invalidatePageContent(page, it) }
    }
}

/**
 * Geometric state of a stroke (used by [TransformSelectionAction]).
 */
class StrokeState(val mesh: StrokeMesh, val brushConfig: BrushConfig, val bounds: Rect) {
    companion object {
        fun of(stroke: DrawStroke) = StrokeState(stroke.mesh, stroke.brushConfig, stroke.bounds)
    }
}

/**
 * Geometric state of a rotatable item: x, y, width, height, rotation (and font size for texts).
 */
class ItemState(val values: FloatArray) {
    companion object {
        fun of(text: DrawText) = ItemState(floatArrayOf(text.x, text.y, text.width, text.height, text.rotation, text.fontSize))
        fun of(image: DrawImage) = ItemState(floatArrayOf(image.x, image.y, image.width, image.height, image.rotation))
    }
}

/**
 * Action representing a lasso transformation (move, scale, rotate, move to another page).
 */
class TransformSelectionAction(
    private val oldPage: DrawPage,
    private val newPage: DrawPage,
    private val strokes: List<DrawStroke>,
    private val texts: List<DrawText>,
    private val images: List<DrawImage>,
    private val oldStrokeStates: List<StrokeState>,
    private val newStrokeStates: List<StrokeState>,
    private val oldTextStates: List<ItemState>,
    private val newTextStates: List<ItemState>,
    private val oldImageStates: List<ItemState>,
    private val newImageStates: List<ItemState>
) : DrawAction {

    private suspend fun apply(
        context: DrawingActionContext,
        from: DrawPage,
        to: DrawPage,
        strokeStates: List<StrokeState>,
        textStates: List<ItemState>,
        imageStates: List<ItemState>
    ) {
        context.clearSelection()
        val dirtyFrom = unionBounds(
            strokes.map { it.bounds } + texts.map { it.getRotatedBounds() } + images.map { it.getRotatedBounds() }
        )

        if (from !== to) {
            from.strokes.removeAll(strokes)
            from.texts.removeAll(texts)
            from.images.removeAll(images)
            to.strokes.addAll(strokes.filter { it !in to.strokes })
            to.texts.addAll(texts.filter { it !in to.texts })
            to.images.addAll(images.filter { it !in to.images })
        }

        strokes.forEachIndexed { i, stroke ->
            val state = strokeStates[i]
            stroke.mesh = state.mesh
            stroke.brushConfig = state.brushConfig
            stroke.bounds = state.bounds
            stroke.isDragging = false
        }
        texts.forEachIndexed { i, text ->
            val v = textStates[i].values
            text.x = v[0]; text.y = v[1]; text.width = v[2]; text.height = v[3]; text.rotation = v[4]; text.fontSize = v[5]
            text.isDragging = false
            text.markDirty()
        }
        images.forEachIndexed { i, image ->
            val v = imageStates[i].values
            image.x = v[0]; image.y = v[1]; image.width = v[2]; image.height = v[3]; image.rotation = v[4]
            image.isDragging = false
        }
        from.markContentChanged()
        to.markContentChanged()

        context.persist {
            if (from !== to) {
                // Moving to another page changes the parent row: re-insert the strokes
                deleteStrokes(strokes)
                saveNewStrokes(to, strokes)
            } else {
                updateStrokes(to, strokes)
            }
            texts.forEach { saveText(to, it) }
            images.forEach { saveImage(to, it) }
        }

        val dirtyTo = unionBounds(
            strokes.map { it.bounds } + texts.map { it.getRotatedBounds() } + images.map { it.getRotatedBounds() }
        )
        if (from !== to) {
            dirtyFrom?.let { context.invalidatePageContent(from, it) }
            dirtyTo?.let { context.invalidatePageContent(to, it) }
        } else {
            unionBounds(listOfNotNull(dirtyFrom, dirtyTo))?.let { context.invalidatePageContent(to, it) }
        }
    }

    override suspend fun undo(context: DrawingActionContext) =
        apply(context, newPage, oldPage, oldStrokeStates, oldTextStates, oldImageStates)

    override suspend fun redo(context: DrawingActionContext) =
        apply(context, oldPage, newPage, newStrokeStates, newTextStates, newImageStates)
}

/**
 * Action representing a change of color and/or font size of a group of texts.
 */
class ChangeTextFormattingAction(
    private val page: DrawPage,
    private val texts: List<DrawText>,
    private val oldColors: List<Int>,
    private val newColors: List<Int>,
    private val oldFontSizes: List<Float>,
    private val newFontSizes: List<Float>
) : DrawAction {
    private fun apply(context: DrawingActionContext, colors: List<Int>, sizes: List<Float>) {
        texts.forEachIndexed { i, text ->
            text.color = colors[i]
            text.fontSize = sizes[i]
            text.markDirty()
        }
        page.markContentChanged()
        context.persist { texts.forEach { saveText(page, it) } }
        unionBounds(texts.map { it.getRotatedBounds() })?.let { context.invalidatePageContent(page, it) }
    }

    override suspend fun undo(context: DrawingActionContext) = apply(context, oldColors, oldFontSizes)
    override suspend fun redo(context: DrawingActionContext) = apply(context, newColors, newFontSizes)
}

/**
 * Action representing adding a new page into the document.
 */
class AddPageAction(
    val page: DrawPage,
    val index: Int
) : DrawAction {
    override suspend fun undo(context: DrawingActionContext) {
        val doc = context.document ?: return
        context.clearSelection()
        val position = doc.pages.indexOf(page)
        if (position >= 0) {
            doc.pages.removeAt(position)
            doc.reindexPages()
            context.persist { softDeletePage(doc, page, position) }
            context.markLayoutNeedsUpdate()
        }
    }

    override suspend fun redo(context: DrawingActionContext) {
        val doc = context.document ?: return
        if (page in doc.pages) return
        val targetIndex = index.coerceIn(0, doc.pages.size)
        doc.pages.add(targetIndex, page)
        doc.reindexPages()
        context.persist {
            if (page.dbId != 0) restorePage(doc, page, targetIndex) else insertPage(doc, page)
        }
        context.markLayoutNeedsUpdate()
    }
}

/**
 * Action representing deleting a page from the document.
 */
class DeletePageAction(
    val page: DrawPage,
    val index: Int
) : DrawAction {
    override suspend fun undo(context: DrawingActionContext) {
        val doc = context.document ?: return
        if (page in doc.pages) return
        val targetIndex = index.coerceIn(0, doc.pages.size)
        doc.pages.add(targetIndex, page)
        doc.reindexPages()
        context.persist { restorePage(doc, page, targetIndex) }
        context.markLayoutNeedsUpdate()
    }

    override suspend fun redo(context: DrawingActionContext) {
        val doc = context.document ?: return
        context.clearSelection()
        val position = doc.pages.indexOf(page)
        if (position >= 0 && doc.pages.size > 1) {
            doc.pages.removeAt(position)
            doc.reindexPages()
            context.persist { softDeletePage(doc, page, position) }
            context.markLayoutNeedsUpdate()
        }
    }
}

/**
 * Action representing a reordering of the pages.
 */
class ReorderPagesAction(
    private val oldOrder: List<DrawPage>,
    private val newOrder: List<DrawPage>
) : DrawAction {
    private fun apply(context: DrawingActionContext, order: List<DrawPage>) {
        val doc = context.document ?: return
        context.clearSelection()
        doc.pages.clear()
        doc.pages.addAll(order)
        doc.reindexPages()
        val snapshot = order.toList()
        context.persist { updatePagesOrder(doc, snapshot) }
        context.markLayoutNeedsUpdate()
    }

    override suspend fun undo(context: DrawingActionContext) = apply(context, oldOrder)
    override suspend fun redo(context: DrawingActionContext) = apply(context, newOrder)
}

/**
 * Action representing a change of format and/or background of a page.
 */
class ChangePageTemplateAction(
    private val page: DrawPage,
    private val oldWidthMm: Float,
    private val oldHeightMm: Float,
    private val oldBackground: PageBackground,
    private val oldHasCustomBackground: Boolean,
    private val newWidthMm: Float,
    private val newHeightMm: Float,
    private val newBackground: PageBackground,
    private val newHasCustomBackground: Boolean
) : DrawAction {
    private fun apply(context: DrawingActionContext, w: Float, h: Float, bg: PageBackground, custom: Boolean) {
        val doc = context.document ?: return
        page.widthMm = w
        page.heightMm = h
        page.background = bg
        page.hasCustomBackground = custom
        page.markContentChanged()
        context.persist { updatePageFormat(doc, page) }
        context.markLayoutNeedsUpdate()
        context.invalidatePageContent(page, null)
    }

    override suspend fun undo(context: DrawingActionContext) =
        apply(context, oldWidthMm, oldHeightMm, oldBackground, oldHasCustomBackground)

    override suspend fun redo(context: DrawingActionContext) =
        apply(context, newWidthMm, newHeightMm, newBackground, newHasCustomBackground)
}

/**
 * Combines multiple actions into a single reversible atomic transaction.
 */
class BatchAction(
    val actions: List<DrawAction>
) : DrawAction {
    override suspend fun undo(context: DrawingActionContext) {
        // Undo in reverse order
        for (i in actions.indices.reversed()) {
            actions[i].undo(context)
        }
    }

    override suspend fun redo(context: DrawingActionContext) {
        // Redo in forward order
        for (action in actions) {
            action.redo(context)
        }
    }
}
