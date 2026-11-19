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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerType
import io.github.valerioisufi.pageboard.canvas.controller.DrawViewController
import io.github.valerioisufi.pageboard.canvas.controller.SelectionGroup
import io.github.valerioisufi.pageboard.canvas.geometry.toCompose
import io.github.valerioisufi.pageboard.canvas.render.tile.TileRenderer
import io.github.valerioisufi.pageboard.model.DrawCustomElement
import io.github.valerioisufi.pageboard.model.DrawElement
import io.github.valerioisufi.pageboard.model.DrawImage
import io.github.valerioisufi.pageboard.model.DrawPage
import io.github.valerioisufi.pageboard.model.DrawStroke
import io.github.valerioisufi.pageboard.model.DrawText
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import io.github.valerioisufi.pageboard.model.shape.ShapeBox
import io.github.valerioisufi.pageboard.model.shape.ShapeElement
import io.github.valerioisufi.pageboard.model.shape.ShapeGeometry
import io.github.valerioisufi.pageboard.model.text.TextChains
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * How the frame of what is selected or edited looks and how far from its handles a pointer may
 * be, in density-independent pixels: the same frame for the lasso and for the text being written,
 * the same size on every screen.
 */
public object FrameStyle {
    /** Room between the frame and what it frames. */
    internal const val PADDING_DP = 6f

    /** The size of the chevrons marking a text of a chain. */
    internal const val CHAIN_MARK_DP = 5f
    internal const val STROKE_DP = 1.5f
    internal const val CORNER_RADIUS_DP = 4f
    internal const val FILL_ALPHA = 0.06f

    /** Distance of the rotation knob from the top edge, and its radius. */
    internal const val ROTATION_STEM_DP = 24f
    internal const val ROTATION_KNOB_DP = 8f

    /** Radius of the handles of the corners (scale). */
    internal const val CORNER_HANDLE_DP = 6.5f

    /** The handles of the sides of a text (its width): thickness and length. */
    internal const val SIDE_HANDLE_WIDTH_DP = 8f
    internal const val SIDE_HANDLE_LENGTH_DP = 24f

    /** The handles of the sides of a shape (its width or its height): length along the side. */
    internal const val SHAPE_SIDE_LENGTH_DP = 18f

    /** The guides of a box snapped to what is around it (see `SnapManager`): color and thickness. */
    public const val GUIDE_COLOR: Int = 0xFFE5397A.toInt()
    internal const val GUIDE_STROKE_DP = 1f

    /** How far from a handle a finger, and a pen or a mouse, may be: targets of 44 and 24 dp. */
    internal const val TOUCH_HIT_DP = 22f
    internal const val POINTER_HIT_DP = 12f

    /** The radius in pixels around a handle where a pointer of [type] takes it. */
    internal fun hitRadiusPx(type: PointerType, density: Float): Float = (if (type == PointerType.Touch) TOUCH_HIT_DP else POINTER_HIT_DP) * density
}

/**
 * Screen-space geometry of the frame of a selection or of the text being written, and of its
 * handles.
 */
public class SelectionFrame(
    /** Corners (top-left, top-right, bottom-right, bottom-left) in screen pixels. */
    public val corners: List<Offset>,
    /** The knob turning the selection, `null` when the frame cannot be turned (a text being written). */
    internal val rotationHandle: Offset?,
    /** Anchor of the rotation stem (middle of the top edge). */
    internal val rotationStemBase: Offset,
    /** Left and right side handles (the width of a single text). */
    internal val sideHandles: List<Offset>,
    /** Whether the corners scale what is framed (a selection). */
    internal val scalesFromCorners: Boolean,
    /** Live transformation mapping the source page millimeters to the screen. */
    public val mmToScreen: Matrix2D,
    /** Whether the text framed goes on from a box before it, and into a box after it (see `TextChains`). */
    internal val chainedAbove: Boolean = false,
    internal val chainedBelow: Boolean = false,
    /** The ends of a line alone in the selection (start, end): its only handles, no frame drawn. */
    internal val lineEnds: List<Offset> = emptyList(),
    /** The middles of the sides of a shape alone in the selection (top, right, bottom, left), which resize it. */
    internal val boxSides: List<Offset> = emptyList()
) {
    public val center: Offset
        get() = Offset(corners.sumOf { it.x.toDouble() }.toFloat() / 4f, corners.sumOf { it.y.toDouble() }.toFloat() / 4f)

    /** Direction of the top edge, from left to right (unit vector). */
    internal val horizontalAxis: Offset
        get() = (corners[1] - corners[0]).normalized()

    /** Whether [point] falls inside the (rotated) frame, expanded by [tolerance] pixels. */
    public fun contains(point: Offset, tolerance: Float = 0f): Boolean {
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
    internal fun handleAt(point: Offset, radius: Float): Handle? {
        fun near(handle: Offset) = hypot(point.x - handle.x, point.y - handle.y) <= radius
        // The nearer end of a short line
        if (lineEnds.size == 2) {
            val start = hypot(point.x - lineEnds[0].x, point.y - lineEnds[0].y)
            val end = hypot(point.x - lineEnds[1].x, point.y - lineEnds[1].y)
            return when {
                start <= radius && start <= end -> Handle.START
                end <= radius -> Handle.END
                else -> null
            }
        }
        if (boxSides.size == 4) {
            val side = boxSides.indices.firstOrNull { near(boxSides[it]) && !corners.any(::near) }
            when (side) {
                0 -> return Handle.TOP
                1 -> return Handle.RIGHT
                2 -> return Handle.BOTTOM
                3 -> return Handle.LEFT
            }
        }
        return when {
            rotationHandle != null && near(rotationHandle) -> Handle.ROTATION
            scalesFromCorners && corners.any(::near) -> Handle.CORNER
            sideHandles.size == 2 && near(sideHandles[0]) -> Handle.LEFT
            sideHandles.size == 2 && near(sideHandles[1]) -> Handle.RIGHT
            else -> null
        }
    }

    /** The corner (0 top left, then clockwise) under [point] within [radius] pixels, if any. */
    internal fun cornerAt(point: Offset, radius: Float): Int? =
        corners.indices.firstOrNull { hypot(point.x - corners[it].x, point.y - corners[it].y) <= radius }

    /**
     * A handle of the frame: the knob turning it, a corner, a side; the ends of a line alone
     * ([START], [END]).
     */
    public enum class Handle { ROTATION, CORNER, LEFT, RIGHT, TOP, BOTTOM, START, END }

    public companion object {
        /**
         * Computes the frame of [selection], or `null` when its page is not laid out.
         */
        internal fun compute(controller: DrawViewController, selection: SelectionGroup): SelectionFrame? {
            val pageToScreen = controller.viewport.pageMmToScreen(selection.pageIndex) ?: return null
            val mmToScreen = Matrix2D(selection.transform).postConcat(pageToScreen)
            controller.shapeEditing.single()?.let { (element, value) -> return ofShape(mmToScreen, element, value, controller.viewport.density) }
            val frame = of(mmToScreen, selection.boundingBox.toCompose(), selection.baseRotation, controller.viewport.density, sideHandles = selection.isSingleText, transformable = true)
            val text = selection.texts.singleOrNull()?.takeIf { selection.isSingleText } ?: return frame
            val chains = TextChains.of(controller.document)
            return frame?.chained(chains.previous(text.id) != null, chains.next(text.id) != null)
        }

        /**
         * The frame of the text being written (see `TextEditorManager`): its width handles, no
         * rotation nor scale; `null` when no text is being written.
         */
        public fun ofEditedText(controller: DrawViewController): SelectionFrame? {
            val editor = controller.textEditorManager
            if (!editor.isEditing) return null
            val mmToScreen = controller.viewport.pageMmToScreen(editor.activeTextPageIndex) ?: return null
            val box = Rect(editor.boxX, editor.boxY, editor.boxX + editor.boxWidth, editor.boxY + editor.boxHeight)
            return of(mmToScreen, box, editor.rotation, controller.viewport.density, sideHandles = true, transformable = false)
                ?.handlesWithin(controller.viewport.height, editor.rotation)
                ?.chained(editor.chainComesFrom, editor.chainGoesOn)
        }

        /**
         * This frame with its side handles at the middle of the part of their edges within a canvas
         * [height] high, so that a box taller than the canvas keeps them in sight; a turned frame
         * keeps them at the middle of its edges.
         */
        private fun SelectionFrame.handlesWithin(height: Float, rotation: Float): SelectionFrame {
            if (rotation != 0f || height <= 0f) return this
            val top = corners[0].y.coerceAtLeast(0f)
            val bottom = corners[3].y.coerceAtMost(height)
            if (top >= bottom) return this
            val middle = (top + bottom) / 2f
            return SelectionFrame(corners, rotationHandle, rotationStemBase, sideHandles.map { Offset(it.x, middle) }, scalesFromCorners, mmToScreen, chainedAbove, chainedBelow)
        }

        /** This frame marked as going on from a box before it ([above]) and into one after it ([below]). */
        private fun SelectionFrame.chained(above: Boolean, below: Boolean): SelectionFrame =
            if (!above && !below) this else SelectionFrame(corners, rotationHandle, rotationStemBase, sideHandles, scalesFromCorners, mmToScreen, above, below)

        /**
         * The frame of a shape alone in the selection: a line by its ends only, a shape of a box
         * by its frame with the middles of its sides.
         */
        private fun ofShape(mmToScreen: Matrix2D, element: DrawCustomElement, value: ShapeElement, density: Float): SelectionFrame? {
            val box = Rect(element.x, element.y, element.x + element.width, element.y + element.height)
            val frame = of(mmToScreen, box, element.rotation, density, sideHandles = false, transformable = true) ?: return null
            if (value.kind.isLine) {
                val (start, end) = ShapeGeometry.lineEnds(ShapeBox(element.x, element.y, element.width, element.height, element.rotation))
                val ends = listOf(start, end).map { mmToScreen.mapPoint(it.x, it.y).let { p -> Offset(p.x, p.y) } }
                return SelectionFrame(frame.corners, null, frame.rotationStemBase, emptyList(), false, mmToScreen, lineEnds = ends)
            }
            val c = frame.corners
            val sides = listOf((c[0] + c[1]) / 2f, (c[1] + c[2]) / 2f, (c[2] + c[3]) / 2f, (c[3] + c[0]) / 2f)
            return SelectionFrame(c, frame.rotationHandle, frame.rotationStemBase, emptyList(), true, mmToScreen, boxSides = sides)
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
 * Draws the frame of the selection (its elements too, with their live transformation, while they
 * are lifted), the elements put back in the tiles until the tiles draw them, and the frame of the
 * text being written.
 */
internal object SelectionOverlay {

    private val painter = StrokeMeshPainter()
    private val imagePaint = Paint().apply {
        filterQuality = FilterQuality.Medium
        isAntiAlias = true
    }

    fun draw(scope: DrawScope, controller: DrawViewController, selection: SelectionGroup, primary: Color) {
        // A shape dragged by a handle: its draft is drawn instead (see `ShapeEditing`)
        if (controller.shapeEditing.handleDraft != null) return
        val frame = SelectionFrame.compute(controller, selection) ?: return
        if (selection.isLifted) drawElements(scope, controller, selection.elements(), selection.pageIndex, frame.mmToScreen)
        drawFrame(scope, frame, primary, controller.viewport.density)
    }

    /** Draws the elements put back in the tiles that the tiles do not draw yet (see `SelectionManager`). */
    fun drawSettling(scope: DrawScope, controller: DrawViewController) {
        val settling = controller.selectionManager.settlingElements() ?: return
        val mmToScreen = controller.viewport.pageMmToScreen(settling.pageIndex) ?: return
        drawElements(scope, controller, settling.elements, settling.pageIndex, mmToScreen)
    }

    /** Draws [elements] of the page [pageIndex] with [mmToScreen], in the order of the stack. */
    private fun drawElements(scope: DrawScope, controller: DrawViewController, elements: List<DrawElement>, pageIndex: Int, mmToScreen: Matrix2D) {
        val document = controller.document
        val renderer = controller.tileManager.tileRenderer
        scope.drawIntoCanvas { canvas ->
            for (element in elements.sortedWith(DrawPage.stackOrder)) {
                when (element) {
                    is DrawImage -> renderer.imageOf(document, element)?.let { TileRenderer.drawImage(canvas, element, it, mmToScreen, imagePaint) }
                    is DrawCustomElement -> renderer.elements.draw(canvas, element, mmToScreen)
                    is DrawText -> renderer.textAdapter?.drawText(canvas, element, mmToScreen, controller.paperOf(pageIndex))
                    is DrawStroke -> painter.draw(canvas, element.mesh, mmToScreen)
                    else -> Unit
                }
            }
        }
    }

    /** Draws the frame of the text being written (its field, over the canvas, draws the text). */
    fun drawEditedText(scope: DrawScope, controller: DrawViewController, primary: Color) {
        val frame = SelectionFrame.ofEditedText(controller) ?: return
        drawFrame(scope, frame, primary, controller.viewport.density)
    }

    /** Draws [frame] and its handles, sized for the screen of [density]. */
    fun drawFrame(scope: DrawScope, frame: SelectionFrame, primary: Color, density: Float) = with(scope) {
        val stroke = FrameStyle.STROKE_DP * density
        // A line: its ends only
        if (frame.lineEnds.isNotEmpty()) {
            val radius = FrameStyle.CORNER_HANDLE_DP * density
            for (end in frame.lineEnds) {
                drawCircle(Color.White, radius = radius, center = end)
                drawCircle(primary, radius = radius, center = end, style = Stroke(width = stroke * 1.5f))
            }
            return@with
        }
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

        // A text of a chain: a chevron on the edge it goes on from, and on the one it goes on into
        // (toward their end, away from the knob turning the frame)
        val ay = (frame.corners[3] - frame.corners[0]).normalized()
        val ax = (frame.corners[1] - frame.corners[0]).normalized()
        val size = FrameStyle.CHAIN_MARK_DP * density
        fun chevron(tip: Offset) {
            val path = Path().apply {
                moveTo(tip.x - ax.x * size - ay.x * size, tip.y - ax.y * size - ay.y * size)
                lineTo(tip.x, tip.y)
                lineTo(tip.x + ax.x * size - ay.x * size, tip.y + ax.y * size - ay.y * size)
            }
            drawPath(path, primary, style = Stroke(width = stroke * 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (frame.chainedAbove) chevron(frame.corners[0] + (frame.corners[1] - frame.corners[0]) * CHAIN_MARK_AT + ay * (size * 0.4f))
        if (frame.chainedBelow) chevron(frame.corners[3] + (frame.corners[2] - frame.corners[3]) * CHAIN_MARK_AT + ay * (size * 1.6f))

        // The sides of a shape: small bars along them
        if (frame.boxSides.isNotEmpty()) {
            val width = FrameStyle.SIDE_HANDLE_WIDTH_DP * density
            val length = FrameStyle.SHAPE_SIDE_LENGTH_DP * density
            frame.boxSides.forEachIndexed { i, handle ->
                val along = if (i % 2 == 0) frame.corners[1] - frame.corners[0] else frame.corners[2] - frame.corners[1]
                val angle = atan2(along.y, along.x) * 180f / PI.toFloat()
                translate(handle.x, handle.y) {
                    rotate(angle, pivot = Offset.Zero) {
                        val topLeft = Offset(-length / 2f, -width / 2f)
                        val size = Size(length, width)
                        val corner = CornerRadius(width / 2f, width / 2f)
                        drawRoundRect(color = Color.White, topLeft = topLeft, size = size, cornerRadius = corner)
                        drawRoundRect(color = primary, topLeft = topLeft, size = size, cornerRadius = corner, style = Stroke(width = stroke * 1.5f))
                    }
                }
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

    /** Where the chevrons of a chain are along their edge. */
    private const val CHAIN_MARK_AT = 0.85f
}
