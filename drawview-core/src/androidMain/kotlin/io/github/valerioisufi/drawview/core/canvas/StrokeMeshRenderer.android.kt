package io.github.valerioisufi.drawview.core.canvas

import android.graphics.Paint
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import io.github.valerioisufi.drawview.core.geometry.Matrix2D
import io.github.valerioisufi.drawview.core.model.StrokeMesh

actual class StrokeMeshPainter actual constructor() {
    private val paint = Paint().apply { isAntiAlias = true }
    private val androidMatrix = android.graphics.Matrix()
    private val matrixValues = FloatArray(9)

    actual fun draw(canvas: Canvas, mesh: StrokeMesh, transform: Matrix2D?, colorOverride: Color?) {
        if (mesh.isEmpty) return
        val androidCanvas = canvas.nativeCanvas
        val color = colorOverride ?: mesh.color
        val translucent = color.alpha < 0.999f

        val saveCount = androidCanvas.save()
        if (transform != null) {
            matrixValues[0] = transform.a; matrixValues[1] = transform.c; matrixValues[2] = transform.tx
            matrixValues[3] = transform.b; matrixValues[4] = transform.d; matrixValues[5] = transform.ty
            matrixValues[6] = 0f; matrixValues[7] = 0f; matrixValues[8] = 1f
            androidMatrix.setValues(matrixValues)
            androidCanvas.concat(androidMatrix)
        }

        if (translucent) {
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

        androidCanvas.restoreToCount(saveCount)
    }
}
