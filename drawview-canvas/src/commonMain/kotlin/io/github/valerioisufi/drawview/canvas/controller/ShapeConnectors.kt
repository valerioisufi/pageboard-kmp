package io.github.valerioisufi.drawview.canvas.controller

import io.github.valerioisufi.drawview.model.DrawCustomElement
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.change.BoxPlacement
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import io.github.valerioisufi.drawview.model.geometry.Point
import io.github.valerioisufi.drawview.model.shape.ShapeBox
import io.github.valerioisufi.drawview.model.shape.ShapeElement
import io.github.valerioisufi.drawview.model.shape.ShapeGeometry

/**
 * The lines and the arrows joined to the boxes (see `docs/shape-adjust.md`): an end of a line on
 * an anchor of a shape (a corner, the middle of a side, the center: where the end snapped when it
 * was drawn) follows that anchor when the shape is moved, turned or resized. Nothing is stored: a
 * line is joined to a shape as long as its end is on one of its anchors, so a line drawn elsewhere
 * and put there joins it, and one moved away leaves it.
 */
internal object ShapeConnectors {
    /** How near an end of a line is to an anchor to be joined to it, in millimeters. */
    const val JOINED_MM: Float = 0.3f

    /** A shape of a page with its value and its box. */
    class Placed(val element: DrawCustomElement, val value: ShapeElement) {
        val box: ShapeBox get() = ShapeBox(element.x, element.y, element.width, element.height, element.rotation)
    }

    /**
     * The new places of the lines of [page] not among [moved] with an end on an anchor of a shape
     * of [moved], that end taken where [move] takes the anchor (a point of the page to its new
     * place); [values] reads the value of a shape.
     */
    fun follow(page: DrawPage, moved: Collection<DrawCustomElement>, values: (DrawCustomElement) -> ShapeElement?, move: (DrawCustomElement, Point) -> Point): List<BoxPlacement> {
        val movedIds = moved.mapTo(HashSet()) { it.id }
        val boxes = moved.mapNotNull { element -> values(element)?.takeIf { !it.kind.isLine }?.let { Placed(element, it) } }
        if (boxes.isEmpty()) return emptyList()
        // Each anchor of the shapes moved, on the page, with the shape it is of
        val anchors = boxes.flatMap { shape ->
            val box = shape.box
            ShapeGeometry.anchors(shape.value, box.width, box.height).map { box.toPage(it) to shape.element }
        }
        val placements = ArrayList<BoxPlacement>()
        for (element in page.customElements) {
            if (element.id in movedIds) continue
            val value = values(element)?.takeIf { it.kind.isLine } ?: continue
            val (start, end) = ShapeGeometry.lineEnds(ShapeBox(element.x, element.y, element.width, element.height, element.rotation))
            fun followed(point: Point): Point? = anchors.firstOrNull { (anchor, _) -> (anchor - point).distance <= JOINED_MM }?.let { (anchor, shape) -> move(shape, anchor) }
            val newStart = followed(start)
            val newEnd = followed(end)
            if (newStart == null && newEnd == null) continue
            val box = ShapeGeometry.lineBox(newStart ?: start, newEnd ?: end, value)
            placements += BoxPlacement(element.id, box.x, box.y, box.width, box.height, box.rotation)
        }
        return placements
    }

    /** [follow] for shapes all moved by [transform] (page millimeters to page millimeters). */
    fun follow(page: DrawPage, moved: Collection<DrawCustomElement>, values: (DrawCustomElement) -> ShapeElement?, transform: Matrix2D): List<BoxPlacement> =
        follow(page, moved, values) { _, anchor -> transform.mapPoint(anchor.x, anchor.y).let { Point(it.x, it.y) } }

    /** [follow] for the shape [element] going from [before] to the box [after] (its handles dragged). */
    fun follow(page: DrawPage, element: DrawCustomElement, values: (DrawCustomElement) -> ShapeElement?, before: ShapeBox, after: ShapeBox): List<BoxPlacement> =
        follow(page, listOf(element), values) { _, anchor ->
            val local = before.toLocal(anchor)
            val x = if (before.width > 0f) local.x / before.width * after.width else 0f
            val y = if (before.height > 0f) local.y / before.height * after.height else 0f
            after.toPage(Point(x, y))
        }
}
