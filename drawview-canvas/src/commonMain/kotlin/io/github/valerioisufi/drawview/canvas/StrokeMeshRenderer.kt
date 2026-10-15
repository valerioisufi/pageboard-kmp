package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import io.github.valerioisufi.drawview.model.StrokeMesh
import io.github.valerioisufi.drawview.model.geometry.Matrix2D

/**
 * Draws [StrokeMesh]es: their outline ([StrokeMesh.outline]) filled as an antialiased path, or
 * their triangles with the platform `drawVertices` primitive (never antialiased) when the modeller
 * gave no outline.
 *
 * A painter owns its native paint and path objects and is not thread-safe: every rendering thread
 * (UI thread, tile workers) must use its own instance.
 */
internal expect class StrokeMeshPainter() {
    /**
     * Draws [mesh] on [canvas], mapping the mesh coordinates through [transform] when provided.
     * A translucent stroke is painted once where it overlaps itself: its outline is filled with
     * the non-zero rule, its triangles are composited through an offscreen layer.
     *
     * @param colorOverride color used instead of the mesh color (e.g. selection previews).
     */
    fun draw(canvas: Canvas, mesh: StrokeMesh, transform: Matrix2D? = null, colorOverride: Color? = null)
}

/**
 * Convenience overload allocating a [StrokeMeshPainter] for a single draw call.
 */
internal fun Canvas.drawStrokeMesh(mesh: StrokeMesh, transform: Matrix2D? = null) {
    StrokeMeshPainter().draw(this, mesh, transform)
}

internal fun DrawScope.drawStrokeMesh(mesh: StrokeMesh, transform: Matrix2D? = null) {
    if (mesh.isEmpty) return
    drawIntoCanvas { canvas -> canvas.drawStrokeMesh(mesh, transform) }
}
