package io.github.valerioisufi.drawview.model.geometry

/**
 * A drawing made of vectors, in millimeters of the box it is drawn in: the [fills] first, then the
 * [lines] and the [dots]. The editor, the thumbnails and the PDF draw it the same way (an element
 * whose type gives one is a vector in the PDF, not an image).
 */
public class VectorDrawing(
    public val fills: List<VectorFill> = emptyList(),
    public val lines: List<VectorLine> = emptyList(),
    public val dots: List<VectorDot> = emptyList()
) {
    public val isEmpty: Boolean get() = fills.isEmpty() && lines.isEmpty() && dots.isEmpty()
}

/** The closed polygon through [points] filled in [argb] (non-zero winding). */
public class VectorFill(public val points: List<Point>, public val argb: Int)

/**
 * The polyline through [points] ([closed] when its last point joins its first one), stroked
 * [widthMm] wide in [argb], its ends cut square, its corners mitered.
 */
public class VectorLine(public val points: List<Point>, public val closed: Boolean, public val argb: Int, public val widthMm: Float)

/** A disc of [center] and [radiusMm] in [argb]. */
public class VectorDot(public val center: Point, public val radiusMm: Float, public val argb: Int)
