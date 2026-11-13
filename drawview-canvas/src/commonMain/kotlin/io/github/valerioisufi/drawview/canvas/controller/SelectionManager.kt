package io.github.valerioisufi.drawview.canvas.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import io.github.valerioisufi.drawview.canvas.FrameStyle
import io.github.valerioisufi.drawview.canvas.geometry.toCompose
import io.github.valerioisufi.drawview.canvas.render.HiddenElements
import io.github.valerioisufi.drawview.canvas.tool.LassoMode
import io.github.valerioisufi.drawview.model.DrawCustomElement
import io.github.valerioisufi.drawview.model.DrawElement
import io.github.valerioisufi.drawview.model.DrawImage
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawStroke
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.SelectionActivity
import io.github.valerioisufi.drawview.model.TextAlignment
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.BoxPlacement
import io.github.valerioisufi.drawview.model.change.TextPlacement
import io.github.valerioisufi.drawview.model.change.ChangeEffects
import io.github.valerioisufi.drawview.model.change.CompositeChange
import io.github.valerioisufi.drawview.model.change.CopiedElements
import io.github.valerioisufi.drawview.model.change.PlaceElements
import io.github.valerioisufi.drawview.model.change.Placement
import io.github.valerioisufi.drawview.model.change.RemoveElements
import io.github.valerioisufi.drawview.model.change.ReorderElements
import io.github.valerioisufi.drawview.model.change.StackMove
import io.github.valerioisufi.drawview.model.change.StrokePlacement
import io.github.valerioisufi.drawview.model.change.UpdateTexts
import io.github.valerioisufi.drawview.model.change.copyElements
import io.github.valerioisufi.drawview.model.change.pasteInto
import io.github.valerioisufi.drawview.model.change.placementOf
import io.github.valerioisufi.drawview.model.change.toData
import io.github.valerioisufi.drawview.model.geometry.AffineTransform
import io.github.valerioisufi.drawview.model.geometry.HitTesting
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import io.github.valerioisufi.drawview.model.geometry.Rect
import io.github.valerioisufi.drawview.model.text.TextSizes
import kotlin.math.max
import kotlin.math.min

/**
 * A group of elements selected with the lasso on a single page.
 *
 * While the user drags the selection, [transform] accumulates the live transformation in the
 * millimeter space of the source page; it is applied to the elements by
 * [SelectionManager.applyTransformation].
 */
public class SelectionGroup(
    public val strokes: MutableList<DrawStroke> = mutableListOf(),
    public val texts: MutableList<DrawText> = mutableListOf(),
    public val images: MutableList<DrawImage> = mutableListOf(),
    internal var boundingBox: Rect = Rect.Zero,
    public var pageIndex: Int = -1,
    /** The elements of the types of the applications, placed by their box like the images. */
    public val customs: MutableList<DrawCustomElement> = mutableListOf()
) {
    /** Live transformation (page millimeters of the source page). */
    public val transform: Matrix2D = Matrix2D()

    /** Rotation of the selection frame (single rotated text/image). */
    internal var baseRotation: Float = 0f

    public fun isEmpty(): Boolean = images.isEmpty() && strokes.isEmpty() && texts.isEmpty() && customs.isEmpty()

    public val isSingleText: Boolean get() = texts.size == 1 && images.isEmpty() && strokes.isEmpty() && customs.isEmpty()
    internal val isSingleImage: Boolean get() = images.size == 1 && texts.isEmpty() && strokes.isEmpty() && customs.isEmpty()
    internal val isSingleCustom: Boolean get() = customs.size == 1 && images.isEmpty() && texts.isEmpty() && strokes.isEmpty()

    /**
     * AABB in millimeters of the whole selection, accounting for [baseRotation].
     */
    internal fun getEnclosingBoundingBoxMm(): Rect {
        if (baseRotation == 0f) return boundingBox
        val m = Matrix2D().postRotate(baseRotation, boundingBox.center.x, boundingBox.center.y)
        return m.mapRect(boundingBox)
    }

    /** Placement of the elements when the live transformation started. */
    internal var originalPlacements: Map<String, Placement>? = null

    internal fun captureOriginalStates() {
        originalPlacements = elements().mapNotNull { placementOf(it) }.associateBy { it.id }
    }

    public fun elements(): List<DrawElement> = strokes + texts + images + customs

    /**
     * Whether the elements are lifted over the page while they are moved, scaled or rotated: left
     * out of the tiles and drawn live on top. Else the tiles draw them at their level in the
     * stack, under the frame (see [SelectionManager.lift]).
     */
    public var isLifted: Boolean = false
        internal set

    /** Leaves the elements of the selection out of the tiles of [hidden] while [dragging]. */
    internal fun setDragging(hidden: HiddenElements, dragging: Boolean) {
        hidden.setDragged(elements().map { it.id }, dragging)
    }
}

/**
 * Elements put back in the tiles after they were lifted (see [SelectionGroup.isLifted]): drawn
 * over the page until the tiles of [boundsMm] draw them again, so that they never disappear for a
 * frame.
 */
internal class SettlingElements(val pageIndex: Int, val elements: List<DrawElement>, val boundsMm: Rect)

/**
 * Lasso selection, clipboard and transformation of the selected elements.
 */
public class SelectionManager(private val controller: DrawViewController) {

    private var selectionState by mutableStateOf<SelectionGroup?>(null)

    public var currentSelection: SelectionGroup?
        get() = selectionState
        private set(value) {
            selectionState = value
            reportSelection()
        }

    /** Incremented on every live change of the selection (drives the overlay redraw). */
    public var selectionVersion: Int by mutableIntStateOf(0)
        private set

    public var lassoMode: LassoMode by mutableStateOf(LassoMode.ALL)

    /** The elements put back in the tiles, drawn until the tiles hold them again (read when drawing). */
    private var settling: SettlingElements? = null

    /** Whether elements were copied, in this document or another one (see [EditorClipboard]). */
    public fun canPaste(): Boolean = controller.clipboard.content is CopiedElements

    internal fun notifyChanged() {
        selectionVersion++
        reportSelection()
        controller.requestRedraw()
    }

    /** Tells the others working on the document what is selected, and how it is being moved. */
    private fun reportSelection() {
        val selection = selectionState
        val page = selection?.let { controller.document.pages.getOrNull(it.pageIndex) }
        if (selection == null || page == null || selection.isEmpty()) {
            controller.presence.reportSelection(null)
            return
        }
        controller.presence.reportSelection(SelectionActivity(page.id, selection.elements().map { it.id }, AffineTransform.of(selection.transform)))
    }

    /**
     * Selects the elements of `page` intersecting the closed [lasso] polygon (page millimeters),
     * except the texts other devices are writing (see [CollaboratorPresence.writerOf]).
     */
    internal fun selectWithLasso(pageIndex: Int, lasso: FloatArray) {
        clearSelection()
        val page = controller.document.pages.getOrNull(pageIndex) ?: return
        if (lasso.size < 6) return
        val lassoBox = HitTesting.polygonBounds(lasso)

        val selection = SelectionGroup(pageIndex = pageIndex)
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        fun include(r: Rect) {
            left = min(left, r.left); top = min(top, r.top)
            right = max(right, r.right); bottom = max(bottom, r.bottom)
        }

        if (lassoMode != LassoMode.IMAGES_ONLY) {
            for (stroke in page.strokes) {
                val b = stroke.bounds
                if (b.left >= lassoBox.right || lassoBox.left >= b.right || b.top >= lassoBox.bottom || lassoBox.top >= b.bottom) continue
                if (HitTesting.meshIntersectsPolygon(stroke.mesh, lasso, lassoBox)) {
                    selection.strokes.add(stroke)
                    include(b)
                }
            }
            for (text in page.texts) {
                if (isItemInsideLasso(text.centerX, text.centerY, text.getRotatedBounds(), lasso, lassoBox)) {
                    controller.presence.writerOf(text)?.let { writer ->
                        controller.presence.refuseEdit(writer)
                        continue
                    }
                    selection.texts.add(text)
                    include(text.getRotatedBounds())
                }
            }
        }
        for (image in page.images) {
            if (isItemInsideLasso(image.centerX, image.centerY, image.getRotatedBounds(), lasso, lassoBox)) {
                selection.images.add(image)
                include(image.getRotatedBounds())
            }
        }
        for (element in page.customElements) {
            if (isItemInsideLasso(element.centerX, element.centerY, element.getRotatedBounds(), lasso, lassoBox)) {
                selection.customs.add(element)
                include(element.getRotatedBounds())
            }
        }

        if (selection.isEmpty()) return
        when {
            selection.isSingleText -> {
                val t = selection.texts[0]
                selection.boundingBox = t.frame()
                selection.baseRotation = t.rotation
            }
            selection.isSingleImage -> {
                val i = selection.images[0]
                selection.boundingBox = Rect(i.x, i.y, i.x + i.width, i.y + i.height)
                selection.baseRotation = i.rotation
            }
            selection.isSingleCustom -> {
                val e = selection.customs[0]
                selection.boundingBox = Rect(e.x, e.y, e.x + e.width, e.y + e.height)
                selection.baseRotation = e.rotation
            }
            else -> selection.boundingBox = Rect(left, top, right, bottom)
        }
        setSelection(selection)
    }

    private fun isItemInsideLasso(cx: Float, cy: Float, bounds: Rect, lasso: FloatArray, lassoBox: Rect): Boolean {
        if (HitTesting.pointInPolygon(cx, cy, lasso)) return true
        return bounds.left >= lassoBox.left && bounds.right <= lassoBox.right &&
                bounds.top >= lassoBox.top && bounds.bottom <= lassoBox.bottom
    }

    /**
     * Selects a single image (long press on an image).
     */
    internal fun selectImage(pageIndex: Int, image: DrawImage) {
        clearSelection()
        controller.document.pages.getOrNull(pageIndex) ?: return
        val selection = SelectionGroup(
            images = mutableListOf(image),
            boundingBox = Rect(image.x, image.y, image.x + image.width, image.y + image.height),
            pageIndex = pageIndex
        ).apply { baseRotation = image.rotation }
        setSelection(selection)
    }

    /**
     * Selects a single element of a type of the application (a tap on it).
     */
    internal fun selectCustomElement(pageIndex: Int, element: DrawCustomElement) {
        clearSelection()
        controller.document.pages.getOrNull(pageIndex) ?: return
        val selection = SelectionGroup(
            customs = mutableListOf(element),
            boundingBox = Rect(element.x, element.y, element.x + element.width, element.y + element.height),
            pageIndex = pageIndex
        ).apply { baseRotation = element.rotation }
        setSelection(selection)
    }

    /**
     * Selects the elements [elements] of types of the application of the page [pageIndex] (one
     * of them as [selectCustomElement], turned with it).
     */
    internal fun selectCustomElements(pageIndex: Int, elements: List<DrawCustomElement>) {
        if (elements.size == 1) return selectCustomElement(pageIndex, elements[0])
        clearSelection()
        if (elements.isEmpty()) return
        controller.document.pages.getOrNull(pageIndex) ?: return
        val bounds = elements.map { it.getRotatedBounds() }.reduce { a, b -> a.union(b) }
        setSelection(SelectionGroup(customs = elements.toMutableList(), boundingBox = bounds, pageIndex = pageIndex))
    }

    /**
     * Selects a single text block (e.g. a text added by the application, so that it can be moved).
     */
    public fun selectText(pageIndex: Int, text: DrawText) {
        controller.presence.writerOf(text)?.let { writer ->
            controller.presence.refuseEdit(writer)
            return
        }
        clearSelection()
        controller.document.pages.getOrNull(pageIndex) ?: return
        val selection = SelectionGroup(
            texts = mutableListOf(text),
            boundingBox = text.frame(),
            pageIndex = pageIndex
        ).apply { baseRotation = text.rotation }
        setSelection(selection)
    }

    /** Drops a selection that changes made on another device made stale. */
    internal fun onChange(effects: ChangeEffects) {
        val selection = currentSelection ?: return
        val touched = selection.elements().any { it in effects.deletedElements || it in effects.savedElements }
        if (touched) clearSelection()
    }

    /** Selects [selection]: the tiles keep drawing its elements at their level. */
    private fun setSelection(selection: SelectionGroup) {
        controller.textMarkupManager.clearSelection()
        currentSelection = selection
        notifyChanged()
    }

    /** Drops the selection (its elements put back in the tiles if they were lifted). */
    public fun clearSelection() {
        val selection = currentSelection ?: return
        settle(selection)
        currentSelection = null
        notifyChanged()
    }

    /**
     * Lifts the elements of [selection] over the page for a gesture: left out of the tiles (drawn
     * again without them) and drawn live on top.
     */
    private fun lift(selection: SelectionGroup) {
        if (selection.isLifted) return
        selection.isLifted = true
        selection.setDragging(controller.hiddenElements, true)
        controller.document.pages.getOrNull(selection.pageIndex)?.let { controller.invalidatePageContent(it, selection.getEnclosingBoundingBoxMm()) }
    }

    /**
     * Puts the elements of [selection] back in the tiles at their level, drawn over the page until
     * the tiles hold them again (see [settlingElements]).
     */
    private fun settle(selection: SelectionGroup) {
        if (!selection.isLifted) return
        selection.isLifted = false
        selection.setDragging(controller.hiddenElements, false)
        val page = controller.document.pages.getOrNull(selection.pageIndex) ?: return
        val bounds = selection.getEnclosingBoundingBoxMm()
        settling = SettlingElements(selection.pageIndex, selection.elements(), bounds)
        controller.invalidatePageContent(page, bounds)
    }

    /**
     * The elements put back in the tiles that the tiles do not draw yet, drawn over the page
     * meanwhile; `null` once the tiles hold them.
     */
    internal fun settlingElements(): SettlingElements? {
        val current = settling ?: return null
        val page = controller.document.pages.getOrNull(current.pageIndex)
        if (page == null || controller.tileManager.isRegionCurrent(page, current.boundsMm)) {
            settling = null
            return null
        }
        return current
    }

    // ==========================================================
    // Stack
    // ==========================================================

    /** The change moving the selection by [move] in the stack of its page, or `null` when it changes nothing. */
    private fun arrangement(move: StackMove): ReorderElements? {
        val selection = currentSelection ?: return null
        val page = controller.document.pages.getOrNull(selection.pageIndex) ?: return null
        return ReorderElements.of(page, selection.elements().map { it.id }, move)
    }

    /** Whether [arrange] would change something: the selection is not above (or under) all it overlaps. */
    public fun canArrange(move: StackMove): Boolean {
        // Read by the composition: follows the changes of the selection
        selectionVersion
        return arrangement(move) != null
    }

    /**
     * Moves the selection in the stack of its page (see [StackMove]): it stays selected, at its
     * new level; one step of the history.
     */
    public fun arrange(move: StackMove) {
        val change = arrangement(move) ?: return
        controller.session.submit(change)
        notifyChanged()
    }

    // ==========================================================
    // Live transformation (driven by the canvas gestures)
    // ==========================================================

    /** The live transformation when the move of the gesture started (see [moveBy]). */
    private var moveStart: Matrix2D? = null

    /** The move of the gesture so far, in page millimeters, before snapping. */
    private var moveOffset = Offset.Zero

    public fun beginTransformation() {
        val selection = currentSelection ?: return
        lift(selection)
        selection.captureOriginalStates()
        moveStart = Matrix2D(selection.transform)
        moveOffset = Offset.Zero
    }

    /**
     * Moves the selection by ([dxMm], [dyMm]) more in the source page space during a gesture,
     * snapped to what is around it (see [SnapManager]) unless [free].
     */
    public fun moveBy(dxMm: Float, dyMm: Float, free: Boolean = false) {
        val selection = currentSelection ?: return
        lift(selection)
        val start = moveStart ?: Matrix2D(selection.transform).also { moveStart = it }
        moveOffset += Offset(dxMm, dyMm)
        val moved = Matrix2D(start).postTranslate(moveOffset.x, moveOffset.y)
        val frame = Matrix2D().postRotate(selection.baseRotation, selection.boundingBox.center.x, selection.boundingBox.center.y).postConcat(moved)
        val box = frame.mapRect(selection.boundingBox)
        // The first baseline of a single text, not turned, on the rows of the paper
        val text = selection.texts.singleOrNull()?.takeIf { selection.isSingleText && it.rotation == 0f && moved.rotationDegrees == 0f }
        val baseline = text?.let { controller.textLineMetrics(it.fontSize * moved.scaleFactor)?.let { line -> box.top + line.baseline } }
        val snap = controller.snapManager.snapBox(selection.pageIndex, box, selection.elements(), baseline, free)
        selection.transform.set(moved.postTranslate(snap.x, snap.y))
        notifyChanged()
    }

    /** Translates the selection by ([dxMm], [dyMm]) in the source page space. */
    internal fun translateBy(dxMm: Float, dyMm: Float) {
        val selection = currentSelection ?: return
        lift(selection)
        selection.transform.postTranslate(dxMm, dyMm)
        notifyChanged()
    }

    /**
     * Scales the selection by [factor] around its (transformed) center; never its texts beyond
     * the sizes of the texts (see [TextSizes]).
     */
    internal fun scaleBy(factor: Float) {
        val selection = currentSelection ?: return
        lift(selection)
        if (!(factor > 0f) || !factor.isFinite()) return
        var applied = factor
        if (selection.texts.isNotEmpty()) {
            val current = selection.transform.scaleFactor.takeIf { it > 0f } ?: return
            val least = selection.texts.maxOf { TextSizes.MIN / it.fontSize }
            val most = selection.texts.minOf { TextSizes.MAX / it.fontSize }
            applied = (current * factor).coerceIn(least, most.coerceAtLeast(least)) / current
        }
        val c = selection.transform.mapPoint(selection.boundingBox.center.x, selection.boundingBox.center.y)
        selection.transform.postScale(applied, applied, c.x, c.y)
        notifyChanged()
    }

    /** Rotates the selection by [degrees] around its (transformed) center. */
    public fun rotateBy(degrees: Float) {
        val selection = currentSelection ?: return
        lift(selection)
        val c = selection.transform.mapPoint(selection.boundingBox.center.x, selection.boundingBox.center.y)
        selection.transform.postRotate(degrees, c.x, c.y)
        notifyChanged()
    }

    /**
     * Resizes the wrapping width of a single selected text by dragging one of its side handles.
     *
     * @param touchMm finger position in the source page space.
     */
    internal fun resizeText(touchMm: Offset, isRightEdge: Boolean, free: Boolean = false) {
        val selection = currentSelection ?: return
        val text = selection.texts.singleOrNull() ?: return
        lift(selection)
        val inverse = Matrix2D()
        if (!selection.transform.invert(inverse)) return
        val point = inverse.mapPoint(touchMm.x, touchMm.y)
        // A box set by hand from now on: as wide as it was, from its corner
        if (text.autoWidth) {
            val boxX = text.boxX
            val boxY = text.boxY
            text.width = text.boxWidth
            text.x = boxX
            text.y = boxY
            text.autoWidth = false
        }
        // Along the lines of the text, from its corner (its rotation undone)
        val x = text.toLocal(point.x, point.y).x
        // The handle is on the frame, its padding away from the text
        val padding = FrameStyle.PADDING_DP * controller.viewport.density / max(controller.viewport.pixelsPerMm(selection.pageIndex), 0.01f) / max(selection.transform.scaleFactor, 0.01f)
        var edge = if (isRightEdge) x - padding else x + padding
        // An edge of a text not turned nor moved snaps to what is around it
        if (selection.baseRotation == 0f && selection.transform.isIdentity) {
            edge += controller.snapManager.snapEdge(selection.pageIndex, text.x + edge, text.y, text.y + text.height, selection.elements(), free)
        }
        val minWidth = 20f
        if (isRightEdge) {
            text.width = max(minWidth, edge)
        } else if (text.width - edge >= minWidth) {
            // The corner moves along the lines, the right edge stays
            val corner = DrawText.cornerFor(text.centerX, text.centerY, text.width, text.height, text.rotation)
            val moved = Matrix2D().postRotate(text.rotation).mapPoint(edge, 0f)
            text.x = corner.x + moved.x
            text.y = corner.y + moved.y
            text.width -= edge
        }
        // The lines wrap again on the new width
        controller.measureText(text)?.let { text.height = it.height }
        text.markDirty()
        selection.boundingBox = text.frame()
        notifyChanged()
    }

    /**
     * Applies the live transformation to the selected elements, moving them to the page under
     * the selection center when needed (one change of the document), and puts them back at their
     * level in the tiles.
     */
    public fun applyTransformation() {
        val selection = currentSelection ?: return
        try {
            transform(selection)
        } finally {
            settle(selection)
        }
    }

    private fun transform(selection: SelectionGroup) {
        val doc = controller.document
        val oldPage = doc.pages.getOrNull(selection.pageIndex) ?: return
        val original = selection.originalPlacements ?: return
        selection.originalPlacements = null
        moveStart = null
        controller.snapManager.clearGuides()

        val transform = Matrix2D(selection.transform)
        val textResized = selection.isSingleText && original[selection.texts[0].id] != placementOf(selection.texts[0])
        if (transform.isIdentity && !textResized) return

        // Target page: the one under the transformed selection center
        val center = transform.mapPoint(selection.boundingBox.center.x, selection.boundingBox.center.y)
        val oldMmToWorld = controller.viewport.pageMmToWorld(selection.pageIndex) ?: return
        val worldCenter = oldMmToWorld.mapPoint(center.x, center.y)
        val targetIndex = controller.viewport.findPageIndexAtWorld(worldCenter.toCompose(), nearest = true)
        val newPage = doc.pages.getOrNull(targetIndex) ?: oldPage

        val finalTransform = Matrix2D(transform)
        if (newPage !== oldPage) {
            val newMmToWorld = controller.viewport.pageMmToWorld(targetIndex) ?: return
            val worldToNewMm = Matrix2D()
            if (!newMmToWorld.invert(worldToNewMm)) return
            finalTransform.postConcat(oldMmToWorld).postConcat(worldToNewMm)
        }

        // --- The new placements (on another page, on top of its elements) ---
        val scale = finalTransform.scaleFactor
        val angle = finalTransform.rotationDegrees
        val placements = mutableListOf<Placement>()
        for (stroke in selection.strokes) {
            placements.add(StrokePlacement(stroke.id, stroke.transform.then(finalTransform)))
        }
        for (text in selection.texts) {
            // Its box scaled and turned around the middle of the selection: its corner follows
            val c = finalTransform.mapPoint(text.centerX, text.centerY)
            val width = text.width * scale
            val rotation = (text.rotation + angle) % 360f
            val corner = DrawText.cornerFor(c.x, c.y, text.boxWidth * scale, text.height * scale, rotation, text.boxLeft * scale)
            placements.add(
                TextPlacement(
                    text.id, corner.x, corner.y, width, rotation, text.fontSize * scale,
                    // A width set by its handles no longer follows the text
                    autoWidth = text.autoWidth && !textResized
                )
            )
        }
        for (item in selection.images + selection.customs) {
            val c = finalTransform.mapPoint(item.centerX, item.centerY)
            val width = item.width * scale
            val height = item.height * scale
            placements.add(BoxPlacement(item.id, c.x - width / 2f, c.y - height / 2f, width, height, (item.rotation + angle) % 360f))
        }
        // A text resized live goes back to where the change starts from (its undo)
        if (textResized) {
            val text = selection.texts[0]
            (original[text.id] as? TextPlacement)?.let {
                text.x = it.x; text.y = it.y; text.width = it.width; text.autoWidth = it.autoWidth
            }
        }
        val move = PlaceElements(oldPage.id, placements, newPage.id)
        // On another page: on top of its elements, in their order
        val ids = selection.elements().sortedWith(DrawPage.stackOrder).map { it.id }
        controller.session.submit(if (newPage === oldPage) move else CompositeChange(listOf(move, ReorderElements.onTop(newPage.id, ids))))
        selection.pageIndex = doc.pages.indexOf(newPage)
        fitFrame(selection)
        selection.transform.reset()
        notifyChanged()
    }

    /** The frame of [selection] around its elements as they are, with its rotation. */
    private fun fitFrame(selection: SelectionGroup) {
        when {
            selection.isSingleText -> {
                val t = selection.texts[0]
                selection.boundingBox = t.frame()
                selection.baseRotation = t.rotation
            }
            selection.isSingleImage -> {
                val i = selection.images[0]
                selection.boundingBox = Rect(i.x, i.y, i.x + i.width, i.y + i.height)
                selection.baseRotation = i.rotation
            }
            selection.isSingleCustom -> {
                val e = selection.customs[0]
                selection.boundingBox = Rect(e.x, e.y, e.x + e.width, e.y + e.height)
                selection.baseRotation = e.rotation
            }
            else -> {
                selection.baseRotation = 0f
                val rects = selection.images.map { it.getRotatedBounds() } +
                        selection.customs.map { it.getRotatedBounds() } +
                        selection.texts.map { it.getRotatedBounds() } +
                        selection.strokes.map { it.bounds }
                selection.boundingBox = Rect(
                    rects.minOf { it.left }, rects.minOf { it.top },
                    rects.maxOf { it.right }, rects.maxOf { it.bottom }
                )
            }
        }
    }

    // ==========================================================
    // Clipboard
    // ==========================================================

    public fun deleteSelection() {
        val selection = currentSelection ?: return
        val page = controller.document.pages.getOrNull(selection.pageIndex) ?: return
        currentSelection = null
        selection.isLifted = false
        selection.setDragging(controller.hiddenElements, false)
        controller.session.submit(RemoveElements.of(page, selection.elements()))
        notifyChanged()
    }

    /** Copies the selection into the clipboard of the application (see [EditorClipboard]). */
    public fun copySelection() {
        putInClipboard()
        clearSelection()
    }

    /** Copies the selection into the clipboard of the application, then deletes it. */
    public fun cutSelection() {
        putInClipboard()
        deleteSelection()
    }

    private fun putInClipboard() {
        val selection = currentSelection ?: return
        val page = controller.document.pages.getOrNull(selection.pageIndex) ?: return
        controller.clipboard.content = controller.document.copyElements(page, selection.elements(), selection.boundingBox, selection.baseRotation)
    }

    /**
     * Pastes the elements of the clipboard centered at [targetScreen], selecting them. Without a
     * point: on the page shown, 10 mm away from where they were in their own document, where they
     * were on their page in another one (kept inside the page). The elements of a file not on
     * this device are left out (see [DrawViewController.Message.PASTED_WITHOUT_MISSING_FILES]).
     */
    public fun pasteSelection(targetScreen: Offset? = null) {
        val copied = controller.clipboard.content as? CopiedElements ?: return
        val doc = controller.document
        val sameDocument = copied.sourceDocumentId == doc.id
        val targetIndex = targetScreen?.let { controller.viewport.findPageIndexAtScreen(it) }
            ?: controller.viewport.firstVisiblePageIndex()
            ?: doc.pages.indexOfFirst { sameDocument && it.id == copied.sourcePageId }.coerceAtLeast(0)
        val targetPage = doc.pages.getOrNull(targetIndex) ?: return
        clearSelection()

        val bounds = copied.bounds
        var offsetX = if (sameDocument) 10f else inside(bounds.left, bounds.right, targetPage.widthMm)
        var offsetY = if (sameDocument) 10f else inside(bounds.top, bounds.bottom, targetPage.heightMm)
        if (targetScreen != null) {
            val mm = controller.viewport.screenToPageMm(targetIndex, targetScreen)
            if (mm != null) {
                offsetX = mm.x - bounds.center.x
                offsetY = mm.y - bounds.center.y
            }
        }

        // On top of the page, in the order of what was copied
        val pasted = copied.pasteInto(doc, targetPage, offsetX, offsetY)
        if (pasted.leftOut > 0) controller.emitMessage(DrawViewController.Message.PASTED_WITHOUT_MISSING_FILES)
        controller.session.submit(pasted.change ?: return)

        val elements = pasted.elements
        val selection = SelectionGroup(
            strokes = elements.filterIsInstance<DrawStroke>().toMutableList(),
            texts = elements.filterIsInstance<DrawText>().toMutableList(),
            images = elements.filterIsInstance<DrawImage>().toMutableList(),
            boundingBox = pasted.bounds,
            pageIndex = targetIndex,
            customs = elements.filterIsInstance<DrawCustomElement>().toMutableList()
        ).apply { baseRotation = pasted.baseRotation }
        // Some left out: the frame around the others
        if (pasted.leftOut > 0) fitFrame(selection)
        setSelection(selection)
    }

    /** The shift keeping [start]..[end] inside 0..[length] (none when it fits; to 0 when larger). */
    private fun inside(start: Float, end: Float, length: Float): Float = when {
        end - start >= length || start < 0f -> -start
        end > length -> length - end
        else -> 0f
    }

    // ==========================================================
    // Text formatting of the selection
    // ==========================================================

    /**
     * The text of the texts selected, from the top of the page down, separated by an empty line
     * (their notation kept); `null` when no text is selected.
     */
    public val selectedPlainText: String?
        get() = currentSelection?.texts?.takeIf { it.isNotEmpty() }
            ?.sortedWith(compareBy<DrawText> { it.y }.thenBy { it.x })
            ?.joinToString("\n\n") { it.text }

    /** The texts selected, as a target of the formatting; `null` when no text is selected. */
    public val selectedTexts: TextFormatTarget?
        get() = currentSelection?.takeIf { it.texts.isNotEmpty() }?.let { SelectedTexts(it) }

    /** The texts of [selection], formatted together (the first one tells their style). */
    private inner class SelectedTexts(private val selection: SelectionGroup) : TextFormatTarget {
        // Read by the composition: follows the changes of the selected texts
        private val first get() = selection.texts.first().also { selectionVersion }
        override val fontSize: Float get() = first.fontSize
        override val color: Int get() = first.color
        override val alignment: TextAlignment get() = first.alignment
        override fun changeFontSize(size: Float) = updateSelectedTextsFontSize(size)
        override fun changeColor(argb: Int) = updateSelectedTextsColor(argb)
        override fun changeAlignment(newAlignment: TextAlignment) = updateSelectedTextsAlignment(newAlignment)
        override val takesBackground: Boolean get() = true
        override val background: Int? get() = first.background
        override fun changeBackground(argb: Int?) = updateSelectedTextsBackground(argb)
    }

    internal fun updateSelectedTextsBackground(background: Int?) {
        val selection = currentSelection ?: return
        if (selection.texts.isEmpty()) return
        val page = controller.document.pages.getOrNull(selection.pageIndex) ?: return
        controller.session.submit(UpdateTexts(page.id, selection.texts.map { it.toData().copy(background = background) }))
        notifyChanged()
    }

    internal fun updateSelectedTextsColor(color: Int) {
        val selection = currentSelection ?: return
        if (selection.texts.isEmpty()) return
        val page = controller.document.pages.getOrNull(selection.pageIndex) ?: return
        controller.session.submit(UpdateTexts(page.id, selection.texts.map { it.toData().copy(color = color) }))
        notifyChanged()
    }

    internal fun updateSelectedTextsFontSize(fontSize: Float) {
        val selection = currentSelection ?: return
        if (selection.texts.isEmpty()) return
        val page = controller.document.pages.getOrNull(selection.pageIndex) ?: return
        val clamped = TextSizes.clamp(fontSize)
        // The text box grows with the font, its lines wrapping again on the same width
        val texts = selection.texts.map { it.toData().copy(fontSize = clamped) }
        controller.session.submit(UpdateTexts(page.id, texts))
        if (selection.isSingleText) {
            val t = selection.texts[0]
            selection.boundingBox = t.frame()
        }
        notifyChanged()
    }

    internal fun updateSelectedTextsAlignment(alignment: TextAlignment) {
        val selection = currentSelection ?: return
        if (selection.texts.isEmpty()) return
        val page = controller.document.pages.getOrNull(selection.pageIndex) ?: return
        controller.session.submit(UpdateTexts(page.id, selection.texts.map { it.toData().copy(alignment = alignment) }))
        notifyChanged()
    }
}

/**
 * The frame of the selection of [this] text: its box before its rotation, placed so that turning
 * it around its middle (as the frame of a selection turns) puts it on the text.
 */
private fun DrawText.frame(): Rect = Rect(centerX - boxWidth / 2f, centerY - height / 2f, centerX + boxWidth / 2f, centerY + height / 2f)
