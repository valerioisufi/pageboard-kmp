package io.github.valerioisufi.drawview.core.canvas

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.core.controller.Collaborator
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.geometry.toCompose
import io.github.valerioisufi.drawview.core.render.tile.TileRenderer
import io.github.valerioisufi.drawview.model.DrawImage
import io.github.valerioisufi.drawview.model.DrawStroke
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.geometry.Matrix2D

/**
 * Draws what the other devices are doing on the document (see
 * [DrawViewController.collaborators]), each in its color: the strokes they are drawing, the texts
 * they are writing, the elements they select or move, their pointers with their names.
 */
internal class CollaboratorsOverlay {
    private val painter = StrokeMeshPainter()
    private val imagePaint = Paint().apply {
        filterQuality = FilterQuality.Medium
        isAntiAlias = true
    }

    /** The strokes being drawn (under the stroke in progress on this device). */
    fun drawInk(scope: DrawScope, controller: DrawViewController, viewport: Rect) {
        for (collaborator in controller.collaborators) {
            val mesh = collaborator.ink ?: continue
            val mmToScreen = controller.pageMmToScreen(pageIndexOf(controller, collaborator.inkPageId)) ?: continue
            scope.drawIntoCanvas { canvas ->
                if (collaborator.inkAlpha < 1f) {
                    // Fading away once drawn
                    canvas.saveLayer(viewport, Paint().apply { alpha = collaborator.inkAlpha })
                    painter.draw(canvas, mesh, mmToScreen)
                    canvas.restore()
                } else {
                    painter.draw(canvas, mesh, mmToScreen)
                }
            }
        }
    }

    /** The texts being written and the elements selected or moved, in frames with the names. */
    fun drawWork(scope: DrawScope, controller: DrawViewController, labels: TextMeasurer) {
        for (collaborator in controller.collaborators) {
            collaborator.text?.let { scope.drawText(controller, collaborator, it, labels) }
            if (collaborator.selection.isNotEmpty()) scope.drawSelection(controller, collaborator, labels)
        }
    }

    /** The pointers with the names (of the devices not writing a text). */
    fun drawPointers(scope: DrawScope, controller: DrawViewController, labels: TextMeasurer) {
        for (collaborator in controller.collaborators) {
            if (collaborator.text != null) continue
            val mmToScreen = controller.pageMmToScreen(pageIndexOf(controller, collaborator.pageId)) ?: continue
            val point = mmToScreen.mapPoint(collaborator.position.x, collaborator.position.y).toCompose()
            scope.drawCircle(Color.White, radius = with(scope) { 7.dp.toPx() }, center = point)
            scope.drawCircle(collaborator.color, radius = with(scope) { 5.dp.toPx() }, center = point)
            scope.drawName(collaborator, point + with(scope) { Offset(9.dp.toPx(), 7.dp.toPx()) }, labels)
        }
    }

    private fun DrawScope.drawText(controller: DrawViewController, collaborator: Collaborator, text: DrawText, labels: TextMeasurer) {
        val mmToScreen = controller.pageMmToScreen(pageIndexOf(controller, collaborator.textPageId)) ?: return
        drawIntoCanvas { canvas -> controller.tileManager.tileRenderer.textAdapter?.drawText(canvas, text, mmToScreen) }
        val box = Rect(text.x, text.y, text.x + text.width, text.y + text.height)
        val corners = drawFrame(box, text.rotation, mmToScreen, collaborator.color, dashed = false)
        drawName(collaborator, corners[0] - Offset(0f, 22.dp.toPx()), labels)
    }

    private fun DrawScope.drawSelection(controller: DrawViewController, collaborator: Collaborator, labels: TextMeasurer) {
        val pageIndex = pageIndexOf(controller, collaborator.selectionPageId)
        val page = controller.document.pages.getOrNull(pageIndex) ?: return
        val pageToScreen = controller.pageMmToScreen(pageIndex) ?: return
        val moved = collaborator.selectionTransform
        val mmToScreen = moved?.toMatrix()?.postConcat(pageToScreen) ?: pageToScreen
        // Moved: drawn here, hidden from the content meanwhile
        if (moved != null) {
            drawIntoCanvas { canvas ->
                for (element in collaborator.selection) {
                    when (element) {
                        is DrawImage -> controller.tileManager.tileRenderer.imageOf(controller.document, element)?.let {
                            TileRenderer.drawImage(canvas, element, it, mmToScreen, imagePaint)
                        }
                        is DrawText -> controller.tileManager.tileRenderer.textAdapter?.drawText(canvas, element, mmToScreen)
                        is DrawStroke -> painter.draw(canvas, element.mesh, mmToScreen)
                        else -> Unit
                    }
                }
            }
        }
        val bounds = collaborator.selection.mapNotNull { page.boundsOf(it) }
        if (bounds.isEmpty()) return
        val box = Rect(bounds.minOf { it.left }, bounds.minOf { it.top }, bounds.maxOf { it.right }, bounds.maxOf { it.bottom })
        val corners = drawFrame(box, 0f, mmToScreen, collaborator.color, dashed = true)
        drawName(collaborator, corners[0] - Offset(0f, 22.dp.toPx()), labels)
    }

    /** Draws the frame of [box] (page millimeters, rotated by [rotation]); returns its corners on the screen. */
    private fun DrawScope.drawFrame(box: Rect, rotation: Float, mmToScreen: Matrix2D, color: Color, dashed: Boolean): List<Offset> {
        val padding = 1f
        val points = floatArrayOf(
            box.left - padding, box.top - padding,
            box.right + padding, box.top - padding,
            box.right + padding, box.bottom + padding,
            box.left - padding, box.bottom + padding
        )
        if (rotation != 0f) Matrix2D().postRotate(rotation, box.center.x, box.center.y).mapPoints(points)
        mmToScreen.mapPoints(points)
        val corners = (0 until 4).map { Offset(points[it * 2], points[it * 2 + 1]) }
        val path = Path().apply {
            moveTo(corners[0].x, corners[0].y)
            for (corner in corners.drop(1)) lineTo(corner.x, corner.y)
            close()
        }
        drawPath(path, color.copy(alpha = 0.08f))
        drawPath(
            path,
            color,
            style = Stroke(width = 2.dp.toPx(), pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) else null)
        )
        return corners
    }

    private fun DrawScope.drawName(collaborator: Collaborator, topLeft: Offset, labels: TextMeasurer) {
        val label = labels.measure(collaborator.name, TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium))
        val padding = 4.dp.toPx()
        drawRoundRect(
            color = collaborator.color,
            topLeft = topLeft,
            size = Size(label.size.width + padding * 2, label.size.height + padding),
            cornerRadius = CornerRadius(6.dp.toPx())
        )
        drawText(label, topLeft = topLeft + Offset(padding, padding / 2))
    }

    private fun pageIndexOf(controller: DrawViewController, pageId: String?): Int =
        if (pageId == null) -1 else controller.document.pages.indexOfFirst { it.id == pageId }
}
