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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerType
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.controller.SelectionGroup
import io.github.valerioisufi.drawview.core.geometry.toCompose
import io.github.valerioisufi.drawview.core.render.tile.TileRenderer
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * How the frame of what is selected or edited looks and how far from its handles a pointer may
 * be, in density-independent pixels: the same frame for the lasso and for the text being written,
 * the same size on every screen.
 */
object FrameStyle {
    /** Room between the frame and what it frames. */
    const val PADDING_DP = 6f
    const val STROKE_DP = 1.5f
    const val CORNER_RADIUS_DP = 4f
    const val FILL_ALPHA = 0.06f

    /** Distance of the rotation knob from the top edge, and its radius. */
    const val ROTATION_STEM_DP = 24f
    const val ROTATION_KNOB_DP = 8f

    /** Radius of the handles of the corners (scale). */
    const val CORNER_HANDLE_DP = 6.5f

    /** The handles of the sides of a text (its width): thickness and length. */
    const val SIDE_HANDLE_WIDTH_DP = 8f
    const val SIDE_HANDLE_LENGTH_DP = 24f

    /** The guides of a box snapped to what is around it (see `SnapManager`): color and thickness. */
    const val GUIDE_COLOR = 0xFFE5397A.toInt()
    const val GUIDE_STROKE_DP = 1f

    /** How far from a handle a finger, and a pen or a mouse, may be: targets of 44 and 24 dp. */
    const val TOUCH_HIT_DP = 22f
    const val POINTER_HIT_DP = 12f

    /** The radius in pixels around a handle where a pointer of [type] takes it. */
    fun hitRadiusPx(type: PointerType, density: Float): Float = (if (type == PointerType.Touch) TOUCH_HIT_DP else POINTER_HIT_DP) * density
}

/**
 * Screen-space geometry of the frame of a selection or of the text being written, and of its
 * handles.
 */
class SelectionFrame(
    /** Corners (top-left, top-right, bottom-right, bottom-left) in screen pixels. */
    val corners: List<Offset>,
    /** The knob turning the selection, `null` when the frame cannot be turned (a text being written). */
    val rotationHandle: Offset?,
    /** Anchor of the rotation stem (middle of the top edge). */
    val rotationStemBase: Offset,
    /** Left and right side handles (the width of a single text). */
    val sideHandles: List<Offset>,
    /** Whether the corners scale what is framed (a selection). */
    val scalesFromCorners: Boolean,
    /** Live transformation mapping the source page millimeters to the screen. */
    val mmToScreen: Matrix2D
) {
    val center: Offset
        get() = Offset(corners.sumOf { it.x.toDouble() }.toFloat() / 4f, corners.sumOf { it.y.toDouble() }.toFloat() / 4f)

    /** Direction of the top edge, from left to right (unit vector). */
    val horizontalAxis: Offset
        get() = (corners[1] - corners[0]).normalized()

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

    /** The handle of the frame under [point] within [radius] pixels, if any. */
    fun handleAt(point: Offset, radius: Float): Handle? {
        fun near(handle: Offset) = hypot(point.x - handle.x, point.y - handle.y) <= radius
        return when {
            rotationHandle != null && near(rotationHandle) -> Handle.ROTATION
            scalesFromCorners && corners.any(::near) -> Handle.CORNER
            sideHandles.size == 2 && near(sideHandles[0]) -> Handle.LEFT
            sideHandles.size == 2 && near(sideHandles[1]) -> Handle.RIGHT
            else -> null
        }
    }

    /** A handle of the frame. */
    enum class Handle { ROTATION, CORNER, LEFT, RIGHT }

    companion object {
        /**
         * Computes the frame of [selection], or `null` when its page is not laid out.
         */
        fun compute(controller: DrawViewController, selection: SelectionGroup): SelectionFrame? {
            val pageToScreen = controller.pageMmToScreen(selection.pageIndex) ?: return null
            val mmToScreen = Matrix2D(selection.transform).postConcat(pageToScreen)
            return of(mmToScreen, selection.boundingBox.toCompose(), selection.baseRotation, controller.density, sideHandles = selection.isSingleText, transformable = true)
        }

        /**
         * The frame of the text being written (see `TextEditorManager`): its width handles, no
         * rotation nor scale; `null` when no text is being written.
         */
        fun ofEditedText(controller: DrawViewController): SelectionFrame? {
            val editor = controller.textEditorManager
            if (!editor.isEditing) return null
            val mmToScreen = controller.pageMmToScreen(editor.activeTextPageIndex) ?: return null
            val box = Rect(editor.boxX, editor.boxY, editor.boxX + editor.boxWidth, editor.boxY + editor.boxHeight)
            return of(mmToScreen, box, editor.rotation, controller.density, sideHandles = true, transformable = false)
        }

        private fun of(mmToScreen: Matrix2D, box: Rect, rotation: Float, density: Float, sideHandles: Boolean, transformable: Boolean): SelectionFrame? {
            val turn = Matrix2D().postRotate(rotation, box.center.x, box.center.y)
            val pts = floatArrayOf(
                box.left, box.top,
                box.right, box.top,
                box.right, box.bottom,
                box.left, box.bottom
            )
            turn.mapPoints(pts)
            mmToScreen.mapPoints(pts)
            if (pts.any { it.isNaN() }) return null
            var corners = listOf(Offset(pts[0], pts[1]), Offset(pts[2], pts[3]), Offset(pts[4], pts[5]), Offset(pts[6], pts[7]))

            // Pad the frame outward by a constant distance on the screen
            val padding = FrameStyle.PADDING_DP * density
            val ax = (corners[1] - corners[0]).normalized()
            val ay = (corners[3] - corners[0]).normalized()
            corners = listOf(
                corners[0] - ax * padding - ay * padding,
                corners[1] + ax * padding - ay * padding,
                corners[2] + ax * padding + ay * padding,
                corners[3] - ax * padding + ay * padding
            )
            val topMid = (corners[0] + corners[1]) / 2f
            val rotationHandle = if (transformable) topMid - ay * (FrameStyle.ROTATION_STEM_DP * density) else null
            val sides = if (sideHandles) listOf((corners[0] + corners[3]) / 2f, (corners[1] + corners[2]) / 2f) else emptyList()
            return SelectionFrame(corners, rotationHandle, topMid, sides, transformable, mmToScreen)
        }
    }
}

private fun Offset.normalized(): Offset {
    val l = hypot(x, y)
    return if (l > 0f) Offset(x / l, y / l) else Offset(1f, 0f)
}

/**
 * Draws the selected elements with their live transformation, plus the selection frame, and the
 * frame of the text being written.
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
                val bitmap = renderer.imageOf(document, image) ?: continue
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
        drawFrame(scope, frame, primary, controller.density)
    }

    /** Draws the frame of the text being written (its field, over the canvas, draws the text). */
    fun drawEditedText(scope: DrawScope, controller: DrawViewController, primary: Color) {
        val frame = SelectionFrame.ofEditedText(controller) ?: return
        drawFrame(scope, frame, primary, controller.density)
    }

    /** Draws [frame] and its handles, sized for the screen of [density]. */
    fun drawFrame(scope: DrawScope, frame: SelectionFrame, primary: Color, density: Float) = with(scope) {
        val stroke = FrameStyle.STROKE_DP * density
        val path = Path().apply {
            moveTo(frame.corners[0].x, frame.corners[0].y)
            for (i in 1 until 4) lineTo(frame.corners[i].x, frame.corners[i].y)
            close()
        }
        drawPath(path, primary.copy(alpha = FrameStyle.FILL_ALPHA))
        drawPath(
            path,
            primary,
            style = Stroke(width = stroke, join = StrokeJoin.Round, pathEffect = PathEffect.cornerPathEffect(FrameStyle.CORNER_RADIUS_DP * density))
        )

        // Rotation stem and knob
        frame.rotationHandle?.let { knob ->
            val radius = FrameStyle.ROTATION_KNOB_DP * density
            drawLine(primary, frame.rotationStemBase, knob, strokeWidth = stroke)
            drawCircle(Color.White, radius = radius, center = knob)
            drawCircle(primary, radius = radius, center = knob, style = Stroke(width = stroke * 1.5f))
            drawCircle(primary, radius = radius * 0.35f, center = knob)
        }

        // Corner (scale) handles
        if (frame.scalesFromCorners) {
            val radius = FrameStyle.CORNER_HANDLE_DP * density
            for (corner in frame.corners) {
                drawCircle(Color.White, radius = radius, center = corner)
                drawCircle(primary, radius = radius, center = corner, style = Stroke(width = stroke * 1.5f))
            }
        }

        // Side (word-wrap) handles of a single text
        if (frame.sideHandles.isNotEmpty()) {
            val edge = frame.corners[3] - frame.corners[0]
            val angle = (atan2(edge.y, edge.x) * 180f / PI.toFloat()) - 90f
            val width = FrameStyle.SIDE_HANDLE_WIDTH_DP * density
            val length = FrameStyle.SIDE_HANDLE_LENGTH_DP * density
            for (handle in frame.sideHandles) {
                translate(handle.x, handle.y) {
                    rotate(angle, pivot = Offset.Zero) {
                        val topLeft = Offset(-width / 2f, -length / 2f)
                        val size = Size(width, length)
                        val corner = CornerRadius(width / 2f, width / 2f)
                        drawRoundRect(color = primary, topLeft = topLeft, size = size, cornerRadius = corner)
                        drawRoundRect(color = Color.White, topLeft = topLeft, size = size, cornerRadius = corner, style = Stroke(width = stroke))
                    }
                }
            }
        }
    }

    /** Bounds of the frame in screen pixels (used for culling and menus). */
    fun bounds(frame: SelectionFrame): Rect {
        val points = frame.corners + listOfNotNull(frame.rotationHandle)
        return Rect(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }
}
