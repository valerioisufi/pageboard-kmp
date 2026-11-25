package io.github.valerioisufi.pageboard.model.shape

import io.github.valerioisufi.pageboard.model.BrushConfig
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.geometry.Point
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

/**
 * Converts a recognized hand-drawn [Shape] (`ShapeRecognizer`) into an editable [ShapeElement]
 * and its [ShapeBox] in page millimeters.
 */
public object ShapeConverter {
    /**
     * Converts [shape] drawn with [brush] into an editable `(ShapeElement, ShapeBox)`.
     */
    public fun toShapeElement(shape: Shape, brush: BrushConfig): Pair<ShapeElement, ShapeBox> {
        val widthMm = brush.size.coerceIn(ShapeElement.MIN_WIDTH_MM, ShapeElement.MAX_WIDTH_MM)
        val stroke = if (brush.tool == StrokeTool.HIGHLIGHTER && (brush.color ushr 24) == 0xFF) {
            (brush.color and 0x00FFFFFF) or 0x66000000
        } else {
            brush.color
        }
        val dash = if (brush.tool == StrokeTool.DASHED_LINE) ShapeDash.DASHED else ShapeDash.SOLID
        return when (shape) {
            is Line -> {
                val element = ShapeElement(ShapeKind.LINE, stroke, widthMm, dash)
                element to ShapeGeometry.lineBox(shape.start, shape.end, element)
            }
            is Arrow -> {
                val element = ShapeElement(ShapeKind.LINE, stroke, widthMm, dash, endHead = ShapeHead.OPEN)
                element to ShapeGeometry.lineBox(shape.tail, shape.tip, element)
            }
            is Polyline -> {
                val (box, normalized) = ShapeGeometry.boxAndVerticesFromPage(shape.vertices.take(ShapeElement.MAX_FREE_VERTICES), widthMm, MIN_BOX_MM)
                val element = ShapeElement(ShapeKind.POLYLINE, stroke, widthMm, dash, vertices = normalized)
                element to box
            }
            is Ellipse -> {
                val w = max(shape.radiusX * 2f + widthMm, MIN_BOX_MM)
                val h = max(shape.radiusY * 2f + widthMm, MIN_BOX_MM)
                val rotDeg = shape.rotation * 180f / PI.toFloat()
                val element = ShapeElement(ShapeKind.ELLIPSE, stroke, widthMm, dash)
                val box = ShapeBox(shape.center.x - w / 2f, shape.center.y - h / 2f, w, h, rotDeg)
                element to box
            }
            is Polygon -> convertPolygon(shape, stroke, widthMm, dash)
        }
    }

    private fun convertPolygon(shape: Polygon, stroke: Int, widthMm: Float, dash: ShapeDash): Pair<ShapeElement, ShapeBox> {
        val vertices = shape.vertices
        if (vertices.size == 4 && isAxisOrRotatedRectangle(vertices)) {
            val side0 = vertices[1] - vertices[0]
            val side1 = vertices[2] - vertices[1]
            val angleRad = atan2(side0.y, side0.x)
            val rotDeg = angleRad * 180f / PI.toFloat()
            val w = max(side0.distance + widthMm, MIN_BOX_MM)
            val h = max(side1.distance + widthMm, MIN_BOX_MM)
            val cx = vertices.sumOf { it.x.toDouble() }.toFloat() / 4f
            val cy = vertices.sumOf { it.y.toDouble() }.toFloat() / 4f
            return ShapeElement(ShapeKind.RECTANGLE, stroke, widthMm, dash) to ShapeBox(cx - w / 2f, cy - h / 2f, w, h, rotDeg)
        }
        val (box, normalized) = ShapeGeometry.boxAndVerticesFromPage(vertices.take(ShapeElement.MAX_FREE_VERTICES), widthMm, MIN_BOX_MM)
        return ShapeElement(ShapeKind.FREE_POLYGON, stroke, widthMm, dash, vertices = normalized) to box
    }

    private fun isAxisOrRotatedRectangle(v: List<Point>): Boolean {
        if (v.size != 4) return false
        for (i in 0 until 4) {
            val a = v[(i + 1) % 4] - v[i]
            val b = v[(i + 2) % 4] - v[(i + 1) % 4]
            val la = a.distance
            val lb = b.distance
            if (la < 0.01f || lb < 0.01f) return false
            val dot = abs((a.x * b.x + a.y * b.y) / (la * lb))
            if (dot > 0.08f) return false
        }
        return true
    }

    private const val MIN_BOX_MM = 1f
}
