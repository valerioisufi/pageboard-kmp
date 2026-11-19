package io.github.valerioisufi.pageboard.canvas.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import io.github.valerioisufi.pageboard.model.geometry.Point
import io.github.valerioisufi.pageboard.model.geometry.VectorDrawing
import io.github.valerioisufi.pageboard.model.shape.ShapeElement
import io.github.valerioisufi.pageboard.model.shape.ShapeElementType
import io.github.valerioisufi.pageboard.model.shape.ShapeGeometry

/**
 * Draws the shapes of the shapes tool ([ShapeElementType]) by their geometry ([ShapeGeometry]):
 * vectors in the PDF, hit by their outline (and their inside when filled), erased by the eraser.
 */
public object ShapeElementRenderer : ElementRenderer<ShapeElement> {
    /** How far from its outline a shape is hit, in millimeters. */
    private const val HIT_TOLERANCE_MM = 1.5f

    override val type: ShapeElementType = ShapeElementType

    override fun draw(canvas: Canvas, value: ShapeElement, widthMm: Float, heightMm: Float) {
        canvas.drawVectors(ShapeGeometry.drawing(value, widthMm, heightMm))
    }

    override fun hits(value: ShapeElement, xMm: Float, yMm: Float, widthMm: Float, heightMm: Float): Boolean =
        ShapeGeometry.hits(value, Point(xMm, yMm), widthMm, heightMm, HIT_TOLERANCE_MM)

    override fun vectors(value: ShapeElement, widthMm: Float, heightMm: Float): VectorDrawing =
        ShapeGeometry.drawing(value, widthMm, heightMm)

    override fun erasedBy(value: ShapeElement, from: Point, to: Point, radiusMm: Float, widthMm: Float, heightMm: Float): Boolean =
        ShapeGeometry.touchesSegment(value, from, to, radiusMm, widthMm, heightMm)
}

/** Draws [drawing] on this canvas, in its units: the fills, then the lines and the dots. */
public fun Canvas.drawVectors(drawing: VectorDrawing) {
    val paint = Paint().apply { isAntiAlias = true }
    for (fill in drawing.fills) {
        paint.style = PaintingStyle.Fill
        paint.color = Color(fill.argb)
        drawPath(path(fill.points, closed = true), paint)
    }
    paint.style = PaintingStyle.Stroke
    paint.strokeCap = StrokeCap.Butt
    paint.strokeJoin = StrokeJoin.Miter
    paint.strokeMiterLimit = ShapeGeometry.MITER_LIMIT
    for (line in drawing.lines) {
        paint.color = Color(line.argb)
        paint.strokeWidth = line.widthMm
        drawPath(path(line.points, line.closed), paint)
    }
    paint.style = PaintingStyle.Fill
    for (dot in drawing.dots) {
        paint.color = Color(dot.argb)
        drawCircle(Offset(dot.center.x, dot.center.y), dot.radiusMm, paint)
    }
}

private fun path(points: List<Point>, closed: Boolean): Path = Path().apply {
    points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
    if (closed) close()
}
