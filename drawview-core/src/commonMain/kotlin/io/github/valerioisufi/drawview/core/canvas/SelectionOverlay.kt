package io.github.valerioisufi.drawview.core.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.controller.SelectionGroup
import io.github.valerioisufi.drawview.core.geometry.Matrix2D
import io.github.valerioisufi.drawview.core.render.tile.TileRenderer
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Screen-space geometry of the selection frame and of its handles.
 */
class SelectionFrame(
    /** Corners (top-left, top-right, bottom-right, bottom-left) in screen pixels. */
    val corners: List<Offset>,
    val rotationHandle: Offset,
    /** Anchor of the rotation stem (middle of the top edge). */
    val rotationStemBase: Offset,
    /** Left and right side handles (single text only). */
    val sideHandles: List<Offset>,
    /** Live transformation mapping the source page millimeters to the screen. */
    val mmToScreen: Matrix2D
) {
    val center: Offset
        get() = Offset(corners.sumOf { it.x.toDouble() }.toFloat() / 4f, corners.sumOf { it.y.toDouble() }.toFloat() / 4f)

    /** Whether [point] falls inside the (rotated) frame, expanded by [tolerance] pixels. */
    fun contains(point: Offset, tolerance: Float = 0f): Boolean {
        val c = center
        val ux = corners[1] - corners[0]
        val uy = corners[3] - corners[0]
        val lx = hypot(ux.x, ux.y)
        val ly = hypot(uy.x, uy.y)
        if (lx <= 0f || ly <= 0f) return false
        val d = point - c
        val px = (d.x * ux.x + d.y * ux.y) / lx
        val py = (d.x * uy.x + d.y * uy.y) / ly
        return kotlin.math.abs(px) <= lx / 2f + tolerance && kotlin.math.abs(py) <= ly / 2f + tolerance
    }

    companion object {
        private const val PADDING_PX = 6f
        private const val ROTATION_HANDLE_OFFSET_PX = 34f

        /**
         * Computes the frame of [selection], or `null` when its page is not laid out.
         */
        fun compute(controller: DrawViewController, selection: SelectionGroup): SelectionFrame? {
            val pageToScreen = controller.pageMmToScreen(selection.pageIndex) ?: return null
            val mmToScreen = Matrix2D(selection.transform).postConcat(pageToScreen)
            val box = selection.boundingBox
            val rotation = Matrix2D().postRotate(selection.baseRotation, box.center.x, box.center.y)
            val pts = floatArrayOf(
                box.left, box.top,
                box.right, box.top,
                box.right, box.bottom,
                box.left, box.bottom
            )
            rotation.mapPoints(pts)
            mmToScreen.mapPoints(pts)
            var corners = listOf(Offset(pts[0], pts[1]), Offset(pts[2], pts[3]), Offset(pts[4], pts[5]), Offset(pts[6], pts[7]))

            // Pad the frame outward by a constant amount of screen pixels
            val c = Offset((pts[0] + pts[4]) / 2f, (pts[1] + pts[5]) / 2f)
            val ax = (corners[1] - corners[0]).normalized()
            val ay = (corners[3] - corners[0]).normalized()
            corners = listOf(
                corners[0] - ax * PADDING_PX - ay * PADDING_PX,
                corners[1] + ax * PADDING_PX - ay * PADDING_PX,
                corners[2] + ax * PADDING_PX + ay * PADDING_PX,
                corners[3] - ax * PADDING_PX + ay * PADDING_PX
            )
            val topMid = (corners[0] + corners[1]) / 2f
            val rotationHandle = topMid - ay * ROTATION_HANDLE_OFFSET_PX
            val sides = if (selection.isSingleText) {
                listOf((corners[0] + corners[3]) / 2f, (corners[1] + corners[2]) / 2f)
            } else emptyList()
            if (c.x.isNaN()) return null
            return SelectionFrame(corners, rotationHandle, topMid, sides, mmToScreen)
        }

        private fun Offset.normalized(): Offset {
            val l = hypot(x, y)
            return if (l > 0f) Offset(x / l, y / l) else Offset(1f, 0f)
        }
    }
}

/**
 * Draws the selected elements with their live transformation, plus the selection frame.
 */
internal object SelectionOverlay {

    private val painter = StrokeMeshPainter()
    private val imagePaint = Paint().apply {
        filterQuality = FilterQuality.Medium
        isAntiAlias = true
    }

    fun draw(scope: DrawScope, controller: DrawViewController, selection: SelectionGroup, primary: Color) {
        val frame = SelectionFrame.compute(controller, selection) ?: return
        val document = controller.document
        val renderer = controller.tileManager.tileRenderer

        scope.drawIntoCanvas { canvas ->
            for (image in selection.images) {
                val bitmap = TileRenderer.ensureImageLoaded(document, image) ?: continue
                TileRenderer.drawImage(canvas, image, bitmap, frame.mmToScreen, imagePaint)
            }
            val textAdapter = renderer.textAdapter
            if (textAdapter != null) {
                for (text in selection.texts) {
                    textAdapter.drawText(canvas, text, frame.mmToScreen)
                }
            }
            for (stroke in selection.strokes) {
                painter.draw(canvas, stroke.mesh, frame.mmToScreen)
            }
        }

        with(scope) {
            val path = Path().apply {
                moveTo(frame.corners[0].x, frame.corners[0].y)
                for (i in 1 until 4) lineTo(frame.corners[i].x, frame.corners[i].y)
                close()
            }
            drawPath(path, primary.copy(alpha = 0.06f))
            drawPath(
                path,
                primary,
                style = Stroke(width = 2.5f, join = StrokeJoin.Round, pathEffect = PathEffect.cornerPathEffect(10f))
            )

            // Rotation stem and knob
            drawLine(primary, frame.rotationStemBase, frame.rotationHandle, strokeWidth = 2.5f)
            drawCircle(Color.White, radius = 14f, center = frame.rotationHandle)
            drawCircle(primary, radius = 14f, center = frame.rotationHandle, style = Stroke(width = 3f))
            drawCircle(primary, radius = 4.5f, center = frame.rotationHandle)

            // Corner (scale) handles
            for (corner in frame.corners) {
                drawCircle(Color.White, radius = 12f, center = corner)
                drawCircle(primary, radius = 12f, center = corner, style = Stroke(width = 3f))
            }

            // Side (word-wrap) handles for single texts
            if (frame.sideHandles.isNotEmpty()) {
                val edge = frame.corners[3] - frame.corners[0]
                val angle = (atan2(edge.y, edge.x) * 180f / PI.toFloat()) - 90f
                for (handle in frame.sideHandles) {
                    translate(handle.x, handle.y) {
                        rotate(angle, pivot = Offset.Zero) {
                            drawRoundRect(
                                color = primary,
                                topLeft = Offset(-10f, -24f),
                                size = Size(20f, 48f),
                                cornerRadius = CornerRadius(10f, 10f)
                            )
                            drawRoundRect(
                                color = Color.White,
                                topLeft = Offset(-10f, -24f),
                                size = Size(20f, 48f),
                                cornerRadius = CornerRadius(10f, 10f),
                                style = Stroke(width = 2f)
                            )
                        }
                    }
                }
            }
        }
    }

    /** Bounds of the frame in screen pixels (used for culling and menus). */
    fun bounds(frame: SelectionFrame): Rect {
        val xs = frame.corners.map { it.x } + frame.rotationHandle.x
        val ys = frame.corners.map { it.y } + frame.rotationHandle.y
        return Rect(xs.min(), ys.min(), xs.max(), ys.max())
    }
}
