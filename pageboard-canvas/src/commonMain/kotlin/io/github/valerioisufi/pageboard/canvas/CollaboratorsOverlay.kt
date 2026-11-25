package io.github.valerioisufi.pageboard.canvas

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
import io.github.valerioisufi.pageboard.canvas.controller.Collaborator
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.canvas.geometry.toCompose
import io.github.valerioisufi.pageboard.canvas.geometry.toComposeMatrix
import io.github.valerioisufi.pageboard.canvas.render.ShapeElementRenderer
import io.github.valerioisufi.pageboard.canvas.render.tile.TileRenderer
import io.github.valerioisufi.pageboard.model.CustomElement
import io.github.valerioisufi.pageboard.model.ImageElement
import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.StrokeElement
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import io.github.valerioisufi.pageboard.model.shape.ShapeDraftActivity

/**
 * Draws what the other devices are doing on the document (see
 * [io.github.valerioisufi.pageboard.canvas.controller.CollaboratorPresence.collaborators]), each in its color: the strokes they are drawing, the texts
 * they are writing, the elements they select or move, their pointers with their names.
 */
internal class CollaboratorsOverlay {
    private val painter = StrokeMeshPainter()
    private val imagePaint = Paint().apply {
        filterQuality = FilterQuality.Medium
        isAntiAlias = true
    }

    /** The strokes being drawn (under the stroke in progress on this device). */
    fun drawInk(scope: DrawScope, controller: PageboardController, viewport: Rect) {
        for (collaborator in controller.presence.collaborators) {
            val mesh = collaborator.ink ?: continue
            val mmToScreen = controller.viewport.pageMmToScreen(pageIndexOf(controller, collaborator.inkPageId)) ?: continue
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

    /** The texts being written, live shape drafts, and elements selected or moved, in frames with the names. */
    fun drawWork(scope: DrawScope, controller: PageboardController, labels: TextMeasurer) {
        for (collaborator in controller.presence.collaborators) {
            collaborator.text?.let { scope.drawText(controller, collaborator, it, labels) }
            collaborator.shapeDraft?.let { scope.drawShapeDraft(controller, collaborator, it, labels) }
            if (collaborator.selection.isNotEmpty()) scope.drawSelection(controller, collaborator, labels)
        }
    }

    private fun DrawScope.drawShapeDraft(
        controller: PageboardController,
        collaborator: Collaborator,
        draft: ShapeDraftActivity,
        labels: TextMeasurer
    ) {
        val pageIndex = pageIndexOf(controller, draft.pageId)
        val mmToScreen = controller.viewport.pageMmToScreen(pageIndex) ?: return
        val boxToScreen = Matrix2D()
            .postRotate(draft.rotation, draft.width / 2f, draft.height / 2f)
            .postTranslate(draft.x, draft.y)
            .postConcat(mmToScreen)
        drawIntoCanvas { canvas ->
            canvas.save()
            canvas.concat(boxToScreen.toComposeMatrix())
            ShapeElementRenderer.draw(canvas, draft.shape, draft.width, draft.height)
            canvas.restore()
        }
        val box = Rect(draft.x, draft.y, draft.x + draft.width, draft.y + draft.height)
        val pivot = Offset(draft.x + draft.width / 2f, draft.y + draft.height / 2f)
        val corners = drawFrame(box, draft.rotation, pivot, mmToScreen, collaborator.color, dashed = true)
        drawName(collaborator, corners[0] - Offset(0f, 22.dp.toPx()), labels)
    }

    /** The pointers with the names (of the devices not writing a text). */
    fun drawPointers(scope: DrawScope, controller: PageboardController, labels: TextMeasurer) {
        for (collaborator in controller.presence.collaborators) {
            if (collaborator.text != null) continue
            val mmToScreen = controller.viewport.pageMmToScreen(pageIndexOf(controller, collaborator.pageId)) ?: continue
            val point = mmToScreen.mapPoint(collaborator.position.x, collaborator.position.y).toCompose()
            scope.drawCircle(Color.White, radius = with(scope) { 7.dp.toPx() }, center = point)
            scope.drawCircle(collaborator.color, radius = with(scope) { 5.dp.toPx() }, center = point)
            scope.drawName(collaborator, point + with(scope) { Offset(9.dp.toPx(), 7.dp.toPx()) }, labels)
        }
    }

    private fun DrawScope.drawText(controller: PageboardController, collaborator: Collaborator, text: TextElement, labels: TextMeasurer) {
        val pageIndex = pageIndexOf(controller, collaborator.textPageId)
        val mmToScreen = controller.viewport.pageMmToScreen(pageIndex) ?: return
        val adapter = controller.tileManager.tileRenderer.textAdapter
        drawIntoCanvas { canvas -> adapter?.drawText(canvas, text, mmToScreen, controller.paperOf(pageIndex)) }
        // Its box as its lines are laid out here, turned around its corner
        adapter?.measure(text)?.let { text.height = it.height; text.naturalWidth = it.width }
        val box = Rect(text.x + text.boxLeft, text.y, text.x + text.boxLeft + text.boxWidth, text.y + text.height)
        val corners = drawFrame(box, text.rotation, Offset(text.x, text.y), mmToScreen, collaborator.color, dashed = false)
        drawName(collaborator, corners[0] - Offset(0f, 22.dp.toPx()), labels)
    }

    private fun DrawScope.drawSelection(controller: PageboardController, collaborator: Collaborator, labels: TextMeasurer) {
        val pageIndex = pageIndexOf(controller, collaborator.selectionPageId)
        val page = controller.document.pages.getOrNull(pageIndex) ?: return
        val pageToScreen = controller.viewport.pageMmToScreen(pageIndex) ?: return
        val moved = collaborator.selectionTransform
        val mmToScreen = moved?.toMatrix()?.postConcat(pageToScreen) ?: pageToScreen
        // Moved: drawn here, hidden from the content meanwhile
        if (moved != null) {
            drawIntoCanvas { canvas ->
                for (element in collaborator.selection.sortedWith(Page.stackOrder)) {
                    when (element) {
                        is ImageElement -> controller.tileManager.tileRenderer.imageOf(controller.document, element)?.let {
                            TileRenderer.drawImage(canvas, element, it, mmToScreen, imagePaint)
                        }
                        is TextElement -> controller.tileManager.tileRenderer.textAdapter?.drawText(canvas, element, mmToScreen, page.background.backgroundColor)
                        is StrokeElement -> painter.draw(canvas, element.mesh, mmToScreen)
                        is CustomElement -> controller.tileManager.tileRenderer.elements.draw(canvas, element, mmToScreen)
                        else -> Unit
                    }
                }
            }
        }
        val bounds = collaborator.selection.mapNotNull { page.boundsOf(it) }
        if (bounds.isEmpty()) return
        val box = Rect(bounds.minOf { it.left }, bounds.minOf { it.top }, bounds.maxOf { it.right }, bounds.maxOf { it.bottom })
        val corners = drawFrame(box, 0f, Offset(box.left, box.top), mmToScreen, collaborator.color, dashed = true)
        drawName(collaborator, corners[0] - Offset(0f, 22.dp.toPx()), labels)
    }

    /**
     * Draws the frame of [box] (page millimeters, rotated by [rotation] around [pivot], as a text
     * turns around its corner); returns its corners on the screen.
     */
    private fun DrawScope.drawFrame(box: Rect, rotation: Float, pivot: Offset, mmToScreen: Matrix2D, color: Color, dashed: Boolean): List<Offset> {
        val padding = 1f
        val points = floatArrayOf(
            box.left - padding, box.top - padding,
            box.right + padding, box.top - padding,
            box.right + padding, box.bottom + padding,
            box.left - padding, box.bottom + padding
        )
        if (rotation != 0f) Matrix2D().postRotate(rotation, pivot.x, pivot.y).mapPoints(points)
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

    private fun pageIndexOf(controller: PageboardController, pageId: String?): Int =
        if (pageId == null) -1 else controller.document.pages.indexOfFirst { it.id == pageId }
}
