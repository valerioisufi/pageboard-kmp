package io.github.valerioisufi.drawview.core.canvas

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.toArgb
import io.github.valerioisufi.drawview.core.geometry.Matrix2D
import io.github.valerioisufi.drawview.core.model.StrokeMesh
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.VertexMode

actual class StrokeMeshPainter actual constructor() {
    private val paint = Paint().apply { isAntiAlias = true }
    private val layerPaint = Paint()

    actual fun draw(canvas: Canvas, mesh: StrokeMesh, transform: Matrix2D?, colorOverride: Color?) {
        if (mesh.isEmpty) return
        val skiaCanvas = canvas.skiaCanvas
        val color = colorOverride ?: mesh.color
        val translucent = color.alpha < 0.999f

        skiaCanvas.save()
        if (transform != null) {
            skiaCanvas.concat(
                Matrix33(
                    transform.a, transform.c, transform.tx,
                    transform.b, transform.d, transform.ty,
                    0f, 0f, 1f
                )
            )
        }

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
        skiaCanvas.restore()
    }
}
