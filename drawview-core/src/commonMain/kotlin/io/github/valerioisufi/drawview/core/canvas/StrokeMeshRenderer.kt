package io.github.valerioisufi.drawview.core.canvas

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import io.github.valerioisufi.drawview.core.geometry.Matrix2D
import io.github.valerioisufi.drawview.core.model.StrokeMesh

/**
 * Draws [StrokeMesh]es with the platform `drawVertices` primitive.
 *
 * A painter owns its native paint objects and is not thread-safe: every rendering thread
 * (UI thread, tile workers) must use its own instance.
 */
expect class StrokeMeshPainter() {
    /**
     * Draws [mesh] on [canvas], mapping the mesh coordinates through [transform] when provided.
     * Translucent meshes are composited through an offscreen layer so that the overlapping
     * triangles of a single stroke do not accumulate alpha.
     *
     * @param colorOverride color used instead of the mesh color (e.g. selection previews).
     */
    fun draw(canvas: Canvas, mesh: StrokeMesh, transform: Matrix2D? = null, colorOverride: Color? = null)
}

/**
 * Convenience overload allocating a [StrokeMeshPainter] for a single draw call.
 */
fun Canvas.drawStrokeMesh(mesh: StrokeMesh, transform: Matrix2D? = null) {
    StrokeMeshPainter().draw(this, mesh, transform)
}

fun DrawScope.drawStrokeMesh(mesh: StrokeMesh, transform: Matrix2D? = null) {
    if (mesh.isEmpty) return
    drawIntoCanvas { canvas -> canvas.drawStrokeMesh(mesh, transform) }
}
