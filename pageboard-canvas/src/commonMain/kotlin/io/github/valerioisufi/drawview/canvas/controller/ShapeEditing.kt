package io.github.valerioisufi.drawview.canvas.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.valerioisufi.drawview.model.DrawCustomElement
import io.github.valerioisufi.drawview.model.change.BoxPlacement
import io.github.valerioisufi.drawview.model.change.CompositeChange
import io.github.valerioisufi.drawview.model.change.DocumentChange
import io.github.valerioisufi.drawview.model.change.PlaceElements
import io.github.valerioisufi.drawview.model.change.UpdateElementContent
import io.github.valerioisufi.drawview.model.encode
import io.github.valerioisufi.drawview.model.geometry.Point
import io.github.valerioisufi.drawview.model.shape.ShapeBox
import io.github.valerioisufi.drawview.model.shape.ShapeConstraints
import io.github.valerioisufi.drawview.model.shape.ShapeElement
import io.github.valerioisufi.drawview.model.shape.ShapeElementType
import io.github.valerioisufi.drawview.model.shape.ShapeGeometry
import io.github.valerioisufi.drawview.model.shape.ShapeHead
import io.github.valerioisufi.drawview.model.shape.ShapeKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

/** A handle of a shape alone in the selection (see [ShapeEditing]). */
internal sealed interface ShapeHandle {
    /** An end of a line: its start, or its end. */
    data class End(val start: Boolean) : ShapeHandle

    /** A corner of the box of a shape: 0 top left, 1 top right, 2 bottom right, 3 bottom left (before its rotation). */
    data class Corner(val index: Int) : ShapeHandle

    /** A side of the box of a shape: 0 top, 1 right, 2 bottom, 3 left (before its rotation). */
    data class Side(val index: Int) : ShapeHandle
}

/**
 * Edits the shapes selected ([selectedShapes]) as shapes: the ends of a line alone moved one by
 * one, the box of a shape alone resized from its corners and its sides (with the constraints, the
 * guides and the anchors of [ShapeInserter]); the style and the kind of all of them changed at
 * once, which becomes the style of the shapes tool. Each edit is one step of undo.
 */
public class ShapeEditing internal constructor(private val controller: DrawViewController) {

    /** The shape being edited by a handle, as it will be, `null` when none is. */
    public var handleDraft: ShapeDraft? by mutableStateOf(null)
        private set

    private var handle: ShapeHandle? = null
    private var element: DrawCustomElement? = null
    private var value: ShapeElement? = null
    private var original: ShapeBox? = null
    private var pageIndex = -1

    /**
     * The shapes selected, with their values, when the selection holds shapes only (and readable
     * ones); empty otherwise.
     */
    public val selectedShapes: List<Pair<DrawCustomElement, ShapeElement>>
        get() {
            // Read by the composition: follows the changes of the selection
            controller.selectionManager.selectionVersion
            val selection = controller.selectionManager.currentSelection ?: return emptyList()
            if (selection.strokes.isNotEmpty() || selection.texts.isNotEmpty() || selection.images.isNotEmpty()) return emptyList()
            val shapes = selection.customs.map { element ->
                if (element.type != ShapeElementType.id) return emptyList()
                val value = controller.engines.elements.valueOf(element) as? ShapeElement ?: return emptyList()
                element to value
            }
            return shapes
        }

    /** The shape alone in the selection, with its value, if it is one. */
    internal fun single(): Pair<DrawCustomElement, ShapeElement>? = selectedShapes.singleOrNull()

    /**
     * Changes every shape selected by [transform] (its style: the colors, the outline, the heads),
     * in one step of undo; the result becomes the style of the shapes tool.
     */
    public fun restyle(transform: (ShapeElement) -> ShapeElement) {
        val shapes = selectedShapes
        if (shapes.isEmpty()) return
        val changes = ArrayList<DocumentChange>()
        val pageIndex = controller.selectionManager.currentSelection?.pageIndex ?: return
        val page = controller.document.pages.getOrNull(pageIndex) ?: return
        var last: ShapeElement? = null
        val placements = ArrayList<BoxPlacement>()
        for ((element, value) in shapes) {
            val changed = try {
                transform(value)
            } catch (_: IllegalArgumentException) {
                continue
            }
            last = changed
            if (changed == value) continue
            changes += UpdateElementContent(page.id, element.id, ShapeElementType.version, ShapeElementType.encode(changed))
            boxFor(element, value, changed)?.let(placements::add)
        }
        last?.let { controller.shapeInserter.updateStyle(controller.shapeInserter.style.takingStyleOf(it)) }
        if (changes.isEmpty()) return
        if (placements.isNotEmpty()) changes += PlaceElements(page.id, placements)
        controller.session.submit(CompositeChange(changes)) ?: return
        controller.selectionManager.selectCustomElements(pageIndex, shapes.map { it.first })
    }

    /**
     * Makes every shape selected a [kind] (a line, an arrow of [heads] heads), its style kept as
     * far as the kind has it, in the same place.
     */
    public fun changeKind(kind: ShapeKind, heads: Int = 0) {
        val head = controller.shapeInserter.style.head
        restyle { value ->
            val shape = value.withKind(kind)
            if (!kind.isLine) {
                shape
            } else {
                val kept = listOf(value.endHead, value.startHead).firstOrNull { it != ShapeHead.NONE } ?: head
                shape.copy(startHead = if (heads == 2) kept else ShapeHead.NONE, endHead = if (heads >= 1) kept else ShapeHead.NONE)
            }
        }
    }

    /**
     * The box of [element] once its value goes from [before] to [after], when it changes: a line
     * as high as its width and heads need, a line made a box as high as two thirds of its length,
     * a box made a line along its middle.
     */
    private fun boxFor(element: DrawCustomElement, before: ShapeElement, after: ShapeElement): BoxPlacement? {
        val height = when {
            after.kind.isLine -> ShapeGeometry.lineHeight(after)
            before.kind.isLine -> max(element.width * LINE_TO_BOX, ShapeInserter.STANDARD_SIDE_MM / 2f)
            else -> return null
        }
        if (abs(height - element.height) < 0.001f) return null
        val centerY = element.y + element.height / 2f
        return BoxPlacement(element.id, element.x, centerY - height / 2f, element.width, height, element.rotation)
    }

    // ==========================================================
    // Handles
    // ==========================================================

    /** Starts dragging [handle] of the shape alone in the selection. */
    internal fun beginHandle(handle: ShapeHandle) {
        val (element, value) = single() ?: return
        val selection = controller.selectionManager.currentSelection ?: return
        this.handle = handle
        this.element = element
        this.value = value
        this.pageIndex = selection.pageIndex
        original = ShapeBox(element.x, element.y, element.width, element.height, element.rotation)
        // Out of the tiles while it is dragged: its draft is drawn instead
        controller.selectionManager.beginTransformation()
    }

    /**
     * The handle dragged to [pointer] (page millimeters of the page of the shape): the shape
     * follows it, square or straight when [forced], around its center when [fromCenter], without
     * snapping when [free].
     */
    internal fun dragHandle(pointer: Point, forced: Boolean, fromCenter: Boolean, free: Boolean) {
        val handle = handle ?: return
        val element = element ?: return
        val value = value ?: return
        val box = original ?: return
        val snaps = controller.snapManager
        handleDraft = when (handle) {
            is ShapeHandle.End -> {
                val (start, end) = ShapeGeometry.lineEnds(box)
                val fixed = if (handle.start) end else start
                val anchor = if (forced) null else snaps.anchorFor(pageIndex, pointer, except = element.id, free = free)
                val line = if (anchor == null && (forced || !free)) ShapeConstraints.line(fixed, pointer, forced) else null
                val moved = anchor ?: line?.end ?: pointer
                val newBox = if (handle.start) ShapeGeometry.lineBox(moved, fixed, value) else ShapeGeometry.lineBox(fixed, moved, value)
                val nearby = if (free) emptyList() else snaps.anchorsNear(pageIndex, pointer, snaps.toMm(pageIndex, ShapeInserter.ANCHORS_SHOWN_DP), except = element.id)
                val from = if (handle.start) moved else fixed
                val to = if (handle.start) fixed else moved
                ShapeDraft(pageIndex, value, newBox, fixed, moved, (to - from).distance, 0f, readAngle(from, to), false, line?.snapped == true, anchor, nearby)
            }
            is ShapeHandle.Corner, is ShapeHandle.Side -> {
                val (newBox, regular) = resized(box, handle, pointer, forced, fromCenter, free, element)
                ShapeDraft(
                    pageIndex, value, newBox, box.center, pointer, newBox.width, newBox.height, 0f, regular, false, null, emptyList()
                )
            }
        }
    }

    /** The box of [box] with [handle] dragged to [pointer], and whether it was made square. */
    private fun resized(
        box: ShapeBox,
        handle: ShapeHandle,
        pointer: Point,
        forced: Boolean,
        fromCenter: Boolean,
        free: Boolean,
        element: DrawCustomElement
    ): Pair<ShapeBox, Boolean> {
        // The pointer in the box, snapped to the guides when the box is not turned
        val snapped = if (box.rotation == 0f && !free && handle is ShapeHandle.Corner) {
            controller.snapManager.snapPoint(pageIndex, pointer, listOf(element), free)
        } else {
            pointer
        }
        val local = box.toLocal(snapped)
        var left = 0f
        var top = 0f
        var right = box.width
        var bottom = box.height
        when (handle) {
            is ShapeHandle.Corner -> {
                if (handle.index == 0 || handle.index == 3) left = local.x else right = local.x
                if (handle.index == 0 || handle.index == 1) top = local.y else bottom = local.y
            }
            is ShapeHandle.Side -> when (handle.index) {
                0 -> top = local.y
                1 -> right = local.x
                2 -> bottom = local.y
                else -> left = local.x
            }
            is ShapeHandle.End -> Unit
        }
        if (fromCenter) {
            // The opposite side moves the other way
            val dl = left
            val dt = top
            val dr = right - box.width
            val db = bottom - box.height
            if (dl != 0f) right = box.width - dl
            if (dr != 0f) left = -dr
            if (dt != 0f) bottom = box.height - dt
            if (db != 0f) top = -db
        }
        var regular = false
        var width = abs(right - left)
        var height = abs(bottom - top)
        if (handle is ShapeHandle.Corner && box.width > 0f && box.height > 0f) {
            if (forced) {
                // The proportions of the box kept
                val scale = max(width / box.width, height / box.height)
                width = box.width * scale
                height = box.height * scale
            } else if (!free) {
                val longer = max(width, height)
                if (longer > 0f && abs(width - height) <= ShapeConstraints.SQUARE_TOLERANCE * longer) {
                    width = longer
                    height = longer
                    regular = true
                }
            }
            // The corner opposite to the one dragged stays (or the center)
            val anchorX = if (fromCenter) box.width / 2f else if (handle.index == 0 || handle.index == 3) box.width else 0f
            val anchorY = if (fromCenter) box.height / 2f else if (handle.index == 0 || handle.index == 1) box.height else 0f
            val towardX = if (local.x >= anchorX) 1f else -1f
            val towardY = if (local.y >= anchorY) 1f else -1f
            if (fromCenter) {
                left = anchorX - width / 2f; right = anchorX + width / 2f
                top = anchorY - height / 2f; bottom = anchorY + height / 2f
            } else {
                left = minOf(anchorX, anchorX + towardX * width); right = maxOf(anchorX, anchorX + towardX * width)
                top = minOf(anchorY, anchorY + towardY * height); bottom = maxOf(anchorY, anchorY + towardY * height)
            }
        }
        val l = minOf(left, right)
        val t = minOf(top, bottom)
        width = max(abs(right - left), ShapeInserter.MIN_SIZE_MM)
        height = max(abs(bottom - top), ShapeInserter.MIN_SIZE_MM)
        val center = box.toPage(Point(l + width / 2f, t + height / 2f))
        return ShapeBox(center.x - width / 2f, center.y - height / 2f, width, height, box.rotation) to regular
    }

    /** Places the shape where its handle was dragged (one step of undo); it stays selected. */
    internal fun endHandle() {
        val draft = handleDraft
        val element = element
        val pageIndex = pageIndex
        val original = original
        reset()
        val selections = controller.selectionManager
        if (draft == null || element == null) {
            selections.applyTransformation()
            return
        }
        val page = controller.document.pages.getOrNull(pageIndex)
        val box = draft.box
        if (page != null && box.width >= ShapeInserter.MIN_SIZE_MM) {
            // The lines joined to the shape follow its anchors, in the same step of undo
            val before = original ?: ShapeBox(element.x, element.y, element.width, element.height, element.rotation)
            val values = controller.engines.elements::valueOf
            val joined = ShapeConnectors.follow(page, element, { values(it) as? ShapeElement }, before, box)
            controller.session.submit(PlaceElements(page.id, listOf(BoxPlacement(element.id, box.x, box.y, box.width, box.height, box.rotation)) + joined))
        }
        selections.applyTransformation()
        selections.selectCustomElement(pageIndex, element)
    }

    /** Drops the drag of a handle: the shape stays as it was. */
    internal fun cancelHandle() {
        reset()
        controller.selectionManager.applyTransformation()
    }

    private fun reset() {
        handleDraft = null
        handle = null
        element = null
        value = null
        original = null
        controller.snapManager.clearGuides()
    }

    private fun readAngle(from: Point, to: Point): Float {
        val degrees = -atan2(to.y - from.y, to.x - from.x) * 180f / PI.toFloat()
        return if (degrees < 0f) degrees + 360f else degrees
    }

    private companion object {
        /** A line made a box: as high as this part of its length. */
        const val LINE_TO_BOX = 2f / 3f
    }
}
