package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.toArgb
import io.github.valerioisufi.pageboard.model.StrokeMesh
import io.github.valerioisufi.pageboard.model.StrokeOutline
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Path
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.PathFillMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.VertexMode

internal actual class StrokeMeshPainter actual constructor() {
    private val paint = Paint().apply { isAntiAlias = true }
    private val layerPaint = Paint()
    private val pathBuilder = PathBuilder(PathFillMode.WINDING)
    private val pathCache = LinkedHashMap<StrokeOutline, Path>(32, 0.75f)

    actual fun draw(canvas: Canvas, mesh: StrokeMesh, transform: Matrix2D?, colorOverride: Color?) {
        if (mesh.isEmpty) return
        val skiaCanvas = canvas.skiaCanvas
        val color = colorOverride ?: Color(mesh.color)

        skiaCanvas.save()
        concat(skiaCanvas, transform)
        val outline = mesh.outline
        if (outline != null && !outline.isEmpty) {
            val cached = getOrCreatePath(outline)
            paint.color = color.toArgb()
            skiaCanvas.drawPath(cached, paint)
        } else {
            drawTriangles(skiaCanvas, mesh, color)
        }
        skiaCanvas.restore()
    }

    actual fun drawOutline(canvas: Canvas, outline: StrokeOutline, color: Color, transform: Matrix2D?) {
        if (outline.isEmpty) return
        val skiaCanvas = canvas.skiaCanvas
        skiaCanvas.save()
        concat(skiaCanvas, transform)
        drawOutline(skiaCanvas, outline, color)
        skiaCanvas.restore()
    }

    private fun getOrCreatePath(outline: StrokeOutline): Path {
        val existing = pathCache.remove(outline)
        if (existing != null) {
            pathCache[outline] = existing
            return existing
        }
        val built = buildPath(outline)
        if (pathCache.size >= MAX_CACHED_PATHS) {
            val iterator = pathCache.entries.iterator()
            if (iterator.hasNext()) {
                val eldest = iterator.next().value
                iterator.remove()
                eldest.close()
            }
        }
        pathCache[outline] = built
        return built
    }

    private fun buildPath(outline: StrokeOutline): Path {
        pathBuilder.setFillType(PathFillMode.WINDING)
        for (polygon in outline.polygons) {
            if (polygon.size >= 6) pathBuilder.addPoly(polygon, true)
        }
        return pathBuilder.detach()
    }

    private fun concat(skiaCanvas: org.jetbrains.skia.Canvas, transform: Matrix2D?) {
        if (transform == null) return
        skiaCanvas.concat(
            Matrix33(
                transform.a, transform.c, transform.tx,
                transform.b, transform.d, transform.ty,
                0f, 0f, 1f
            )
        )
    }

    /**
     * Fills [outline] antialiased with the non-zero rule: where the stroke overlaps itself it is
     * painted once, translucent too.
     */
    private fun drawOutline(skiaCanvas: org.jetbrains.skia.Canvas, outline: StrokeOutline, color: Color) {
        val path = buildPath(outline)
        paint.color = color.toArgb()
        try {
            skiaCanvas.drawPath(path, paint)
        } finally {
            path.close()
        }
    }

    private companion object {
        const val MAX_CACHED_PATHS = 256
    }

    /**
     * Draws the triangles of [mesh] (never antialiased): translucent through an offscreen layer, so
     * that the overlapping triangles do not accumulate alpha.
     */
    private fun drawTriangles(skiaCanvas: org.jetbrains.skia.Canvas, mesh: StrokeMesh, color: Color) {
        val translucent = color.alpha < 0.999f
        if (translucent) {
            val bounds = mesh.computeBounds()
            layerPaint.alpha = (color.alpha * 255f).toInt().coerceIn(0, 255)
            skiaCanvas.saveLayer(Rect(bounds.left, bounds.top, bounds.right, bounds.bottom), layerPaint)
            paint.color = color.copy(alpha = 1f).toArgb()
        } else {
            paint.color = color.toArgb()
        }

        for (partition in mesh.partitions) {
            if (partition.positions.isEmpty() || partition.indices.isEmpty()) continue
            skiaCanvas.drawVertices(
                VertexMode.TRIANGLES,
                partition.positions,
                null,
                null,
                partition.indices,
                BlendMode.SRC_OVER,
                paint
            )
        }

        if (translucent) skiaCanvas.restore()
    }
}
