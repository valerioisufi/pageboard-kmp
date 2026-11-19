package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import io.github.valerioisufi.pageboard.model.StrokeMesh
import io.github.valerioisufi.pageboard.model.StrokeOutline
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D

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

    /**
     * Fills [outline] with [color] on [canvas], antialiased with the non-zero rule, mapping its
     * coordinates through [transform] when provided: the stroke in progress, without its triangles.
     */
    fun drawOutline(canvas: Canvas, outline: StrokeOutline, color: Color, transform: Matrix2D? = null)
}
