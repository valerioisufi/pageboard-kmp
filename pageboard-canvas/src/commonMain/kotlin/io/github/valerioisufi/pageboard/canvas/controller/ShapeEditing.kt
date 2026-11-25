package io.github.valerioisufi.pageboard.canvas.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.valerioisufi.pageboard.model.CustomElement
import io.github.valerioisufi.pageboard.model.StrokeElement
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.BoxPlacement
import io.github.valerioisufi.pageboard.model.change.CompositeChange
import io.github.valerioisufi.pageboard.model.change.CustomElementData
import io.github.valerioisufi.pageboard.model.change.DocumentChange
import io.github.valerioisufi.pageboard.model.change.PlaceElements
import io.github.valerioisufi.pageboard.model.change.RemoveElements
import io.github.valerioisufi.pageboard.model.change.UpdateElementContent
import io.github.valerioisufi.pageboard.model.encode
import io.github.valerioisufi.pageboard.model.geometry.Point
import io.github.valerioisufi.pageboard.model.newElementId
import io.github.valerioisufi.pageboard.model.shape.Shape
import io.github.valerioisufi.pageboard.model.shape.ShapeAnchorRef
import io.github.valerioisufi.pageboard.model.shape.ShapeBox
import io.github.valerioisufi.pageboard.model.shape.ShapeConstraints
import io.github.valerioisufi.pageboard.model.shape.ShapeConverter
import io.github.valerioisufi.pageboard.model.shape.ShapeElement
import io.github.valerioisufi.pageboard.model.shape.ShapeElementType
import io.github.valerioisufi.pageboard.model.shape.ShapeGeometry
import io.github.valerioisufi.pageboard.model.shape.ShapeGeometryParams
import io.github.valerioisufi.pageboard.model.shape.ShapeHead
import io.github.valerioisufi.pageboard.model.shape.ShapeKind
import io.github.valerioisufi.pageboard.model.shape.ShapeRecognizer
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

    /** An existing vertex of a [ShapeKind.POLYLINE] or [ShapeKind.FREE_POLYGON]. */
    data class Vertex(val index: Int) : ShapeHandle

    /** An edge midpoint handle that inserts a new vertex after [afterIndex]. */
    data class AddVertex(val afterIndex: Int) : ShapeHandle
}

/**
 * Edits the shapes selected ([selectedShapes]) as shapes: the ends of a line alone moved one by
 * one, the box of a shape alone resized from its corners and its sides (with the constraints, the
 * guides and the anchors of [ShapeInserter]); the style and the kind of all of them changed at
 * once, which becomes the style of the shapes tool. Each edit is one step of undo.
 */
public class ShapeEditing internal constructor(private val controller: PageboardController) {

    /** The shape being edited by a handle, as it will be, `null` when none is. */
    public var handleDraft: ShapeDraft? by mutableStateOf(null)
        private set

    /** Live previews of connected lines following a shape whose handle is being dragged. */
    internal var connectorDrafts: List<Pair<ShapeElement, ShapeBox>> by mutableStateOf(emptyList())
        private set

    private var handle: ShapeHandle? = null
    private var element: CustomElement? = null
    private var value: ShapeElement? = null
    private var original: ShapeBox? = null
    private var pageIndex = -1
    private var draggedAnchorHit: SnapManager.AnchorHit? = null
    private var workingVertices: MutableList<Point>? = null
    private var activeVertexIndex: Int = -1

    /**
     * The shapes selected, with their values, when the selection holds shapes only (and readable
     * ones); empty otherwise.
     */
    public val selectedShapes: List<Pair<CustomElement, ShapeElement>>
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
    internal fun single(): Pair<CustomElement, ShapeElement>? = selectedShapes.singleOrNull()

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
    private fun boxFor(element: CustomElement, before: ShapeElement, after: ShapeElement): BoxPlacement? {
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

    /** Whether the current selection is a single hand-drawn stroke recognized as a geometric shape. */
    public val canConvertSelectedStroke: Boolean
        get() {
            controller.selectionManager.selectionVersion
            return recognizedSelectedStroke() != null
        }

    private fun recognizedSelectedStroke(): Triple<Int, StrokeElement, Shape>? {
        val selection = controller.selectionManager.currentSelection ?: return null
        if (selection.strokes.size != 1 || selection.texts.isNotEmpty() || selection.images.isNotEmpty() || selection.customs.isNotEmpty()) {
            return null
        }
        val stroke = selection.strokes[0]
        val points = stroke.mesh.points.map { Point(it.x, it.y) }
        if (points.size < 2) return null
        val pxPerMm = controller.viewport.pixelsPerMm(selection.pageIndex)
        val tolerance = if (pxPerMm > 0.01f) ShapeSnapper.TOLERANCE_DP * controller.viewport.density / pxPerMm else 2.5f
        val shape = ShapeRecognizer.recognize(points, tolerance) ?: return null
        return Triple(selection.pageIndex, stroke, shape)
    }

    /**
     * Converts the single selected hand-drawn stroke into an editable [ShapeElement] at the same
     * position in the page stack, in one step of undo, and selects the new shape.
     */
    public fun convertSelectedStrokeToShape(): CustomElement? {
        val (pageIdx, stroke, recognized) = recognizedSelectedStroke() ?: return null
        val page = controller.document.pages.getOrNull(pageIdx) ?: return null
        val (shapeValue, box) = ShapeConverter.toShapeElement(recognized, stroke.brushConfig)
        val id = newElementId()
        val data = CustomElementData(
            id = id,
            type = ShapeElementType.id,
            version = ShapeElementType.version,
            payload = ShapeElementType.encode(shapeValue),
            x = box.x,
            y = box.y,
            width = box.width.coerceAtLeast(ShapeInserter.MIN_SIZE_MM),
            height = box.height.coerceAtLeast(ShapeInserter.MIN_SIZE_MM),
            rotation = box.rotation,
            order = stroke.order
        )
        controller.selectionManager.clearSelection()
        val change = CompositeChange(listOf(RemoveElements(page.id, listOf(stroke.id)), AddElements(page.id, listOf(data))))
        controller.session.submit(change) ?: return null
        val added = page.customElements.firstOrNull { it.id == id } ?: return null
        controller.selectionManager.selectCustomElement(pageIdx, added)
        return added
    }

    /** Updates the text [label] of all currently selected shapes in one step of undo. */
    public fun updateLabel(label: String?) {
        val normalized = label?.trim()?.takeIf { it.isNotEmpty() }?.take(ShapeElement.MAX_LABEL_LENGTH)
        restyle { it.copy(label = normalized) }
    }

    /** Updates the geometry parameters of selected geometry shapes (`AXES`, `GRID`, `ARC`, `ANGLE`) in one undo step. */
    public fun updateGeometry(transform: (ShapeGeometryParams) -> ShapeGeometryParams) {
        restyle { value ->
            if (!value.kind.isGeometryTool) value
            else value.copy(geometry = transform(value.geometry ?: ShapeElement.defaultGeometryParams(value.kind) ?: ShapeGeometryParams()))
        }
    }

    /** Deletes the vertex at [index] from the single selected polyline/free polygon if above its minimum vertex count. */
    public fun deleteVertex(index: Int): Boolean {
        val (element, value) = single() ?: return false
        if (!value.kind.isVertexEditable) return false
        val selection = controller.selectionManager.currentSelection ?: return false
        val page = controller.document.pages.getOrNull(selection.pageIndex) ?: return false
        val before = ShapeBox(element.x, element.y, element.width, element.height, element.rotation)
        val verts = ShapeGeometry.verticesInPage(before, value).toMutableList()
        val minCount = if (value.kind == ShapeKind.FREE_POLYGON) 3 else 2
        if (verts.size <= minCount || index !in verts.indices) return false
        verts.removeAt(index)
        val (newBox, normVerts) = ShapeGeometry.boxAndVerticesFromPage(verts, value.widthMm, ShapeInserter.MIN_SIZE_MM)
        val updated = value.copy(vertices = normVerts)
        val values = controller.engines.elements::valueOf
        val joined = ShapeConnectors.follow(page, element, { values(it) as? ShapeElement }, before, newBox)
        val changes = listOf(
            UpdateElementContent(page.id, element.id, ShapeElementType.version, ShapeElementType.encode(updated)),
            PlaceElements(page.id, listOf(BoxPlacement(element.id, newBox.x, newBox.y, newBox.width, newBox.height, newBox.rotation)) + joined)
        )
        controller.session.submit(CompositeChange(changes)) ?: return false
        controller.selectionManager.selectCustomElement(selection.pageIndex, element)
        return true
    }

    /** Starts dragging [handle] of the shape alone in the selection. */
    internal fun beginHandle(handle: ShapeHandle) {
        val (element, value) = single() ?: return
        val selection = controller.selectionManager.currentSelection ?: return
        this.handle = handle
        this.element = element
        this.value = value
        this.pageIndex = selection.pageIndex
        this.draggedAnchorHit = null
        this.workingVertices = null
        this.activeVertexIndex = -1
        val box = ShapeBox(element.x, element.y, element.width, element.height, element.rotation)
        original = box
        when (handle) {
            is ShapeHandle.Vertex -> {
                val verts = ShapeGeometry.verticesInPage(box, value).toMutableList()
                workingVertices = verts
                activeVertexIndex = handle.index.coerceIn(0, (verts.size - 1).coerceAtLeast(0))
            }
            is ShapeHandle.AddVertex -> {
                val verts = ShapeGeometry.verticesInPage(box, value).toMutableList()
                if (verts.size < ShapeElement.MAX_FREE_VERTICES && handle.afterIndex in verts.indices) {
                    val mid = (verts[handle.afterIndex] + verts[(handle.afterIndex + 1) % verts.size]) * 0.5f
                    verts.add(handle.afterIndex + 1, mid)
                    workingVertices = verts
                    activeVertexIndex = handle.afterIndex + 1
                }
            }
            else -> {}
        }
        // Out of the tiles while it is dragged: its draft is drawn instead
        controller.selectionManager.beginTransformation()
        if (!value.kind.isLine) {
            val page = controller.document.pages.getOrNull(pageIndex)
            if (page != null) {
                val values = controller.engines.elements::valueOf
                val initial = ShapeConnectors.previewFollowResize(page, element, { values(it) as? ShapeElement }, box, box)
                connectorDrafts = initial.map { it.value to it.box }
            }
        }
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
                val hit = if (forced) null else snaps.anchorHitFor(pageIndex, pointer, except = element.id, free = free)
                draggedAnchorHit = hit
                val anchor = hit?.point
                val line = if (anchor == null && (forced || !free)) ShapeConstraints.line(fixed, pointer, forced) else null
                val moved = anchor ?: line?.end ?: pointer
                val newBox = if (handle.start) ShapeGeometry.lineBox(moved, fixed, value) else ShapeGeometry.lineBox(fixed, moved, value)
                val nearby = if (free) emptyList() else snaps.anchorsNear(pageIndex, pointer, snaps.toMm(pageIndex, ShapeInserter.ANCHORS_SHOWN_DP), except = element.id)
                val from = if (handle.start) moved else fixed
                val to = if (handle.start) fixed else moved
                val ref = hit?.let { ShapeAnchorRef(it.elementId, it.anchorIndex) }
                val updatedValue = if (handle.start) value.copy(startAnchor = ref) else value.copy(endAnchor = ref)
                ShapeDraft(pageIndex, updatedValue, newBox, fixed, moved, (to - from).distance, 0f, readAngle(from, to), false, line?.snapped == true, anchor, nearby)
            }
            is ShapeHandle.Vertex, is ShapeHandle.AddVertex -> {
                val verts = workingVertices
                val idx = activeVertexIndex
                if (verts != null && idx in verts.indices) {
                    val snapped = if (!free && !forced) {
                        snaps.snapPoint(pageIndex, pointer, listOf(element), free)
                    } else {
                        pointer
                    }
                    verts[idx] = snapped
                    val (newBox, normVerts) = ShapeGeometry.boxAndVerticesFromPage(verts, value.widthMm, ShapeInserter.MIN_SIZE_MM)
                    val updatedValue = value.copy(vertices = normVerts)
                    val page = controller.document.pages.getOrNull(pageIndex)
                    if (page != null) {
                        val values = controller.engines.elements::valueOf
                        connectorDrafts = ShapeConnectors.previewFollowResize(page, element, { values(it) as? ShapeElement }, box, newBox)
                            .map { it.value to it.box }
                    }
                    ShapeDraft(pageIndex, updatedValue, newBox, box.center, snapped, newBox.width, newBox.height, 0f, false, false, null, emptyList())
                } else null
            }
            is ShapeHandle.Corner, is ShapeHandle.Side -> {
                val (newBox, regular) = resized(box, handle, pointer, forced, fromCenter, free, element)
                val page = controller.document.pages.getOrNull(pageIndex)
                if (page != null) {
                    val values = controller.engines.elements::valueOf
                    connectorDrafts = ShapeConnectors.previewFollowResize(page, element, { values(it) as? ShapeElement }, box, newBox)
                        .map { it.value to it.box }
                }
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
        element: CustomElement
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
            is ShapeHandle.End, is ShapeHandle.Vertex, is ShapeHandle.AddVertex -> Unit
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
        val previousValue = value
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
            val place = PlaceElements(page.id, listOf(BoxPlacement(element.id, box.x, box.y, box.width, box.height, box.rotation)) + joined)
            val changes = ArrayList<DocumentChange>(2)
            changes += place
            if (previousValue != null && draft.value != previousValue) {
                changes += UpdateElementContent(page.id, element.id, ShapeElementType.version, ShapeElementType.encode(draft.value))
            }
            controller.session.submit(if (changes.size == 1) changes[0] else CompositeChange(changes))
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
        connectorDrafts = emptyList()
        handle = null
        element = null
        value = null
        original = null
        draggedAnchorHit = null
        workingVertices = null
        activeVertexIndex = -1
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
