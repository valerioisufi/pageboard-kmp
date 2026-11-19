package io.github.valerioisufi.pageboard.canvas

import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import io.github.valerioisufi.pageboard.model.StrokeMesh
import io.github.valerioisufi.pageboard.model.StrokeOutline
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D

internal actual class StrokeMeshPainter actual constructor() {
    private val paint = Paint().apply { isAntiAlias = true }
    private val path = Path()
    private val androidMatrix = android.graphics.Matrix()
    private val matrixValues = FloatArray(9)

    actual fun draw(canvas: Canvas, mesh: StrokeMesh, transform: Matrix2D?, colorOverride: Color?) {
        if (mesh.isEmpty) return
        val androidCanvas = canvas.nativeCanvas
        val color = colorOverride ?: Color(mesh.color)

        val saveCount = androidCanvas.save()
        concat(androidCanvas, transform)
        val outline = mesh.outline
        if (outline != null && !outline.isEmpty) {
            drawOutline(androidCanvas, outline, color)
        } else {
            drawTriangles(androidCanvas, mesh, color)
        }
        androidCanvas.restoreToCount(saveCount)
    }

    actual fun drawOutline(canvas: Canvas, outline: StrokeOutline, color: Color, transform: Matrix2D?) {
        if (outline.isEmpty) return
        drawOutline(canvas.nativeCanvas, outline, color, transform)
    }

    /** [drawOutline] on a canvas of Android, also the one of a front buffer. */
    fun drawOutline(androidCanvas: android.graphics.Canvas, outline: StrokeOutline, color: Color, transform: Matrix2D?) {
        val saveCount = androidCanvas.save()
        concat(androidCanvas, transform)
        drawOutline(androidCanvas, outline, color)
        androidCanvas.restoreToCount(saveCount)
    }

    private fun concat(androidCanvas: android.graphics.Canvas, transform: Matrix2D?) {
        if (transform == null) return
        matrixValues[0] = transform.a; matrixValues[1] = transform.c; matrixValues[2] = transform.tx
        matrixValues[3] = transform.b; matrixValues[4] = transform.d; matrixValues[5] = transform.ty
        matrixValues[6] = 0f; matrixValues[7] = 0f; matrixValues[8] = 1f
        androidMatrix.setValues(matrixValues)
        androidCanvas.concat(androidMatrix)
    }

    /**
     * Fills [outline] antialiased with the non-zero rule: where the stroke overlaps itself it is
     * painted once, translucent too.
     */
    private fun drawOutline(androidCanvas: android.graphics.Canvas, outline: StrokeOutline, color: Color) {
        path.rewind()
        path.fillType = Path.FillType.WINDING
        for (polygon in outline.polygons) {
            if (polygon.size < 6) continue
            path.moveTo(polygon[0], polygon[1])
            var i = 2
            while (i + 1 < polygon.size) {
                path.lineTo(polygon[i], polygon[i + 1])
                i += 2
            }
            path.close()
        }
        paint.color = color.toArgb()
        androidCanvas.drawPath(path, paint)
    }

    /**
     * Draws the triangles of [mesh] (never antialiased): translucent through an offscreen layer, so
     * that the overlapping triangles do not accumulate alpha.
     */
    private fun drawTriangles(androidCanvas: android.graphics.Canvas, mesh: StrokeMesh, color: Color) {
        if (color.alpha < 0.999f) {
            val bounds = mesh.computeBounds()
            androidCanvas.saveLayerAlpha(
                bounds.left, bounds.top, bounds.right, bounds.bottom,
                (color.alpha * 255f).toInt().coerceIn(0, 255)
            )
            paint.color = color.copy(alpha = 1f).toArgb()
        } else {
            paint.color = color.toArgb()
        }

        for (partition in mesh.partitions) {
            if (partition.positions.isEmpty() || partition.indices.isEmpty()) continue
            // Requires API 29+ on hardware accelerated canvases (minSdk is 29).
            androidCanvas.drawVertices(
                android.graphics.Canvas.VertexMode.TRIANGLES,
                partition.positions.size,
                partition.positions,
                0,
                null,
                0,
                null,
                0,
                partition.indices,
                0,
                partition.indices.size,
                paint
            )
        }
    }
}
