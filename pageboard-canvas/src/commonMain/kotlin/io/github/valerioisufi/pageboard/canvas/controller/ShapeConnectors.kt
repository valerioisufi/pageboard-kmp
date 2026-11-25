package io.github.valerioisufi.pageboard.canvas.controller

import io.github.valerioisufi.pageboard.model.CustomElement
import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.change.BoxPlacement
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import io.github.valerioisufi.pageboard.model.geometry.Point
import io.github.valerioisufi.pageboard.model.shape.ShapeAnchorRef
import io.github.valerioisufi.pageboard.model.shape.ShapeBox
import io.github.valerioisufi.pageboard.model.shape.ShapeElement
import io.github.valerioisufi.pageboard.model.shape.ShapeGeometry

/**
 * The lines and the arrows joined to the boxes (see `docs/plans/shape-adjust.md` and
 * `docs/plans/shapes-advanced.md`): an end of a line with a persisted [ShapeAnchorRef] (or on an
 * anchor by geometric proximity for v1 lines) follows that anchor when the shape is moved, turned,
 * or resized — both during the live drag preview and at commit.
 */
internal object ShapeConnectors {
    /** How near an end of a line is to an anchor to be joined geometrically (v1 fallback), in millimeters. */
    const val JOINED_MM: Float = 0.3f

    /** A shape of a page with its value and its box. */
    class Placed(val element: CustomElement, val value: ShapeElement) {
        val box: ShapeBox get() = ShapeBox(element.x, element.y, element.width, element.height, element.rotation)
    }

    /** A connected line and its updated preview/committed box. */
    class Followed(val element: CustomElement, val value: ShapeElement, val box: ShapeBox)

    /**
     * Resolves all lines on [page] not among [moved] that follow a moved shape, mapping each
     * joined anchor by [move] (target shape, page anchor -> new page anchor).
     */
    fun resolve(
        page: Page,
        moved: Collection<CustomElement>,
        values: (CustomElement) -> ShapeElement?,
        move: (CustomElement, Point) -> Point
    ): List<Followed> {
        val movedIds = moved.mapTo(HashSet()) { it.id }
        val boxes = moved.mapNotNull { element -> values(element)?.takeIf { !it.kind.isLine }?.let { Placed(element, it) } }
        if (boxes.isEmpty()) return emptyList()

        val anchorsByShapeId = HashMap<String, Pair<CustomElement, List<Point>>>(boxes.size)
        val allAnchors = ArrayList<Pair<Point, CustomElement>>()
        for (shape in boxes) {
            val box = shape.box
            val pageAnchors = ShapeGeometry.anchors(shape.value, box.width, box.height).map { box.toPage(it) }
            anchorsByShapeId[shape.element.id] = shape.element to pageAnchors
            for (anchor in pageAnchors) allAnchors += anchor to shape.element
        }

        val followed = ArrayList<Followed>()
        for (element in page.customElements) {
            if (element.id in movedIds || element.removed != null) continue
            val value = values(element)?.takeIf { it.kind.isLine } ?: continue
            val (start, end) = ShapeGeometry.lineEnds(ShapeBox(element.x, element.y, element.width, element.height, element.rotation))

            fun resolveEnd(point: Point, ref: ShapeAnchorRef?): Point? {
                if (ref != null) {
                    val (target, targetAnchors) = anchorsByShapeId[ref.elementId] ?: return null
                    val anchor = targetAnchors.getOrNull(ref.anchorIndex) ?: return null
                    return move(target, anchor)
                }
                return allAnchors.firstOrNull { (anchor, _) -> (anchor - point).distance <= JOINED_MM }
                    ?.let { (anchor, shape) -> move(shape, anchor) }
            }

            val newStart = resolveEnd(start, value.startAnchor)
            val newEnd = resolveEnd(end, value.endAnchor)
            if (newStart == null && newEnd == null) continue
            val box = ShapeGeometry.lineBox(newStart ?: start, newEnd ?: end, value)
            followed += Followed(element, value, box)
        }
        return followed
    }

    /** The new places of connected lines when [moved] are transformed by [move]. */
    fun follow(
        page: Page,
        moved: Collection<CustomElement>,
        values: (CustomElement) -> ShapeElement?,
        move: (CustomElement, Point) -> Point
    ): List<BoxPlacement> = resolve(page, moved, values, move).map {
        BoxPlacement(it.element.id, it.box.x, it.box.y, it.box.width, it.box.height, it.box.rotation)
    }

    /** [follow] for shapes all moved by [transform] (page millimeters to page millimeters). */
    fun follow(page: Page, moved: Collection<CustomElement>, values: (CustomElement) -> ShapeElement?, transform: Matrix2D): List<BoxPlacement> =
        follow(page, moved, values) { _, anchor -> transform.mapPoint(anchor.x, anchor.y).let { Point(it.x, it.y) } }

    /** Live preview of connected lines when [moved] are transformed by [transform]. */
    fun previewFollow(page: Page, moved: Collection<CustomElement>, values: (CustomElement) -> ShapeElement?, transform: Matrix2D): List<Followed> =
        resolve(page, moved, values) { _, anchor -> transform.mapPoint(anchor.x, anchor.y).let { Point(it.x, it.y) } }

    /** [follow] for the shape [element] going from [before] to the box [after] (its handles dragged). */
    fun follow(page: Page, element: CustomElement, values: (CustomElement) -> ShapeElement?, before: ShapeBox, after: ShapeBox): List<BoxPlacement> =
        follow(page, listOf(element), values) { _, anchor -> mapAnchorResize(anchor, before, after) }

    /** Live preview of connected lines when [element] resizes from [before] to [after]. */
    fun previewFollowResize(page: Page, element: CustomElement, values: (CustomElement) -> ShapeElement?, before: ShapeBox, after: ShapeBox): List<Followed> =
        resolve(page, listOf(element), values) { _, anchor -> mapAnchorResize(anchor, before, after) }

    private fun mapAnchorResize(anchor: Point, before: ShapeBox, after: ShapeBox): Point {
        val local = before.toLocal(anchor)
        val x = if (before.width > 0f) local.x / before.width * after.width else 0f
        val y = if (before.height > 0f) local.y / before.height * after.height else 0f
        return after.toPage(Point(x, y))
    }
}
