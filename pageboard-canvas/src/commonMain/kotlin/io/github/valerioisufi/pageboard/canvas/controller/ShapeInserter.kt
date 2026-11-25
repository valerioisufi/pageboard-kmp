package io.github.valerioisufi.pageboard.canvas.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.valerioisufi.pageboard.model.CustomElement
import io.github.valerioisufi.pageboard.model.geometry.Point
import io.github.valerioisufi.pageboard.model.geometry.Rect
import io.github.valerioisufi.pageboard.model.shape.ShapeAnchorRef
import io.github.valerioisufi.pageboard.model.shape.ShapeBox
import io.github.valerioisufi.pageboard.model.shape.ShapeConstraints
import io.github.valerioisufi.pageboard.model.shape.ShapeDash
import io.github.valerioisufi.pageboard.model.shape.ShapeDraftActivity
import io.github.valerioisufi.pageboard.model.shape.ShapeElement
import io.github.valerioisufi.pageboard.model.shape.ShapeElementType
import io.github.valerioisufi.pageboard.model.shape.ShapeGeometry
import io.github.valerioisufi.pageboard.model.shape.ShapeHead
import io.github.valerioisufi.pageboard.model.shape.ShapeKind
import kotlin.math.PI
import kotlin.math.atan2
import kotlinx.serialization.Serializable

/**
 * What the shapes tool draws next: the [kind] and the style of the shape. It keeps what a kind
 * does not use (the fill while a line is chosen, the heads while a box is), so that going back to
 * a kind finds its style again.
 *
 * @property heads the heads of a line: 0 (a line), 1 (an arrow, at its end), 2 (a double arrow).
 * @property head how a head is drawn.
 */
public data class ShapeToolStyle(
    val kind: ShapeKind = ShapeKind.RECTANGLE,
    val stroke: Int = DEFAULT_STROKE,
    val widthMm: Float = DEFAULT_WIDTH_MM,
    val dash: ShapeDash = ShapeDash.SOLID,
    val fill: Int? = null,
    val heads: Int = 0,
    val head: ShapeHead = ShapeHead.OPEN
) {
    init {
        require(heads in 0..2) { "heads: $heads" }
        require(head != ShapeHead.NONE) { "a head is drawn" }
    }

    /** The shape of this style. */
    public fun element(): ShapeElement = ShapeElement(
        kind = kind,
        stroke = stroke,
        widthMm = widthMm.coerceIn(ShapeElement.MIN_WIDTH_MM, ShapeElement.MAX_WIDTH_MM).takeIf { it.isFinite() } ?: DEFAULT_WIDTH_MM,
        dash = dash,
        fill = if (kind.supportsFill) fill else null,
        startHead = if (kind.supportsHeads && heads == 2) head else ShapeHead.NONE,
        endHead = if (kind.supportsHeads && heads >= 1) head else ShapeHead.NONE,
        vertices = when (kind) {
            ShapeKind.POLYLINE -> ShapeElement.DEFAULT_POLYLINE_VERTICES
            ShapeKind.FREE_POLYGON -> ShapeElement.DEFAULT_POLYGON_VERTICES
            else -> null
        },
        geometry = ShapeElement.defaultGeometryParams(kind)
    )

    /** This style taking the style of [shape] (its kind, its colors, its outline, its heads when supported). */
    public fun takingStyleOf(shape: ShapeElement): ShapeToolStyle {
        val supportsHeads = shape.kind.supportsHeads
        val supportsFill = shape.kind.supportsFill
        val shapeHeads = listOf(shape.startHead, shape.endHead).count { it != ShapeHead.NONE }
        val shapeHead = listOf(shape.endHead, shape.startHead).firstOrNull { it != ShapeHead.NONE } ?: head
        return copy(
            kind = shape.kind,
            stroke = shape.stroke,
            widthMm = shape.widthMm,
            dash = shape.dash,
            fill = if (supportsFill) shape.fill else fill,
            heads = if (supportsHeads) shapeHeads else heads,
            head = if (supportsHeads) shapeHead else head
        )
    }

    public companion object {
        /** The ink of the pen. */
        public const val DEFAULT_STROKE: Int = 0xFF1A1C18.toInt()

        /** About 1.4 pt. */
        public const val DEFAULT_WIDTH_MM: Float = 0.5f
    }
}

/**
 * The [ShapeToolStyle] as kept with the preferences of the user ([EditorPreferences.shapeTool]): the
 * names of the kinds as text, read back with the defaults for what is not known.
 */
@Serializable
public data class ShapeToolPreferences(
    val kind: String = ShapeKind.RECTANGLE.name,
    val stroke: Int = ShapeToolStyle.DEFAULT_STROKE,
    val widthMm: Float = ShapeToolStyle.DEFAULT_WIDTH_MM,
    val dash: String = ShapeDash.SOLID.name,
    val fill: Int? = null,
    val heads: Int = 0,
    val head: String = ShapeHead.OPEN.name
) {
    /** The style these preferences keep; the default of each part not understood. */
    public fun toStyle(): ShapeToolStyle {
        val defaults = ShapeToolStyle()
        return ShapeToolStyle(
            kind = ShapeKind.entries.firstOrNull { it.name == kind } ?: defaults.kind,
            stroke = stroke,
            widthMm = widthMm.takeIf { it.isFinite() && it in ShapeElement.MIN_WIDTH_MM..ShapeElement.MAX_WIDTH_MM } ?: defaults.widthMm,
            dash = ShapeDash.entries.firstOrNull { it.name == dash } ?: defaults.dash,
            fill = fill,
            heads = heads.takeIf { it in 0..2 } ?: defaults.heads,
            head = ShapeHead.entries.firstOrNull { it.name == head && it != ShapeHead.NONE } ?: defaults.head
        )
    }

    public companion object {
        /** The preferences keeping [style]. */
        public fun of(style: ShapeToolStyle): ShapeToolPreferences = ShapeToolPreferences(
            style.kind.name, style.stroke, style.widthMm, style.dash.name, style.fill, style.heads, style.head.name
        )
    }
}

/**
 * The shape being drawn with the shapes tool, on the page [pageIndex], in page millimeters: its
 * [value] in [box], from [start] to [end] (the corner or the end under the pointer).
 *
 * @property widthMm the width of its box, or the length of a line.
 * @property heightMm the height of its box (0 for a line).
 * @property angleDegrees the direction of a line as it is read on the page, counterclockwise from
 *   the right (0 for a box).
 * @property isRegular whether the box was made square (a square, a circle).
 * @property isAngleSnapped whether the line took a multiple of 15°.
 * @property anchor the anchor of another shape the end of a line snapped to, if any.
 * @property anchors the anchors of the other shapes near the pointer, shown while a line is drawn.
 */
public class ShapeDraft(
    public val pageIndex: Int,
    public val value: ShapeElement,
    public val box: ShapeBox,
    public val start: Point,
    public val end: Point,
    public val widthMm: Float,
    public val heightMm: Float,
    public val angleDegrees: Float,
    public val isRegular: Boolean,
    public val isAngleSnapped: Boolean,
    public val anchor: Point?,
    public val anchors: List<Point>
)

/**
 * Draws the shapes of the shapes tool: from the point pressed to the pointer, with the
 * constraints of [ShapeConstraints] (by themselves near them, always when forced), the guides and
 * the anchors of the other shapes; the shape being drawn is [draft] until it is added on top of
 * its page in one change, selected. A tap puts one of a standard size. The [style] of the tool is
 * a preference of the user.
 */
public class ShapeInserter internal constructor(private val controller: PageboardController) {

    /** What the tool draws next (a preference of the user). */
    public var style: ShapeToolStyle by mutableStateOf(ShapeToolStyle())
        private set

    /** The shape being drawn, `null` when none is. */
    public var draft: ShapeDraft? by mutableStateOf(null)
        private set

    private var pageIndex = -1
    private var start = Point.Zero
    private var startAnchorHit: SnapManager.AnchorHit? = null
    private var endAnchorHit: SnapManager.AnchorHit? = null
    private val pendingVertices = mutableListOf<Point>()

    /** Whether a multi-tap [ShapeKind.POLYLINE] or [ShapeKind.FREE_POLYGON] is currently being placed vertex by vertex. */
    public val isDrawingVertices: Boolean
        get() = pendingVertices.isNotEmpty()

    public fun updateStyle(style: ShapeToolStyle) {
        if (this.style == style) return
        this.style = style
        controller.preferencesChanged()
    }

    internal fun applyPreferences(preferences: EditorPreferences) {
        style = preferences.shapeTool.toStyle()
    }

    /** Starts a shape at [at] (page millimeters) of the page [pageIndex]: nothing is shown until the pointer moves. */
    internal fun begin(pageIndex: Int, at: Point, free: Boolean) {
        this.pageIndex = pageIndex
        val snaps = controller.snapManager
        startAnchorHit = if (style.kind.isLine) snaps.anchorHitFor(pageIndex, at, free = free) else null
        endAnchorHit = null
        start = startAnchorHit?.point ?: at
        draft = null
    }

    /**
     * The pointer at [pointer] (page millimeters of the page the shape started on): the shape
     * follows it, square or straight when [forced], around its start when [fromCenter], without
     * snapping by itself nor to the guides when [free].
     */
    internal fun update(pointer: Point, forced: Boolean, fromCenter: Boolean, free: Boolean) {
        if (pageIndex < 0) return
        val value = style.element()
        val snaps = controller.snapManager
        draft = if (value.kind.isLine) {
            val hit = if (forced) null else snaps.anchorHitFor(pageIndex, pointer, free = free)
            endAnchorHit = hit
            val anchor = hit?.point
            val line = when {
                anchor != null -> null
                forced || !free -> ShapeConstraints.line(start, pointer, forced)
                else -> null
            }
            val end = anchor ?: line?.end ?: pointer
            val angle = line?.angleDegrees ?: readAngle(start, end)
            val box = ShapeGeometry.lineBox(start, end, value)
            val nearby = if (free) emptyList() else snaps.anchorsNear(pageIndex, pointer, snaps.toMm(pageIndex, ANCHORS_SHOWN_DP))
            val linked = value.copy(
                startAnchor = startAnchorHit?.let { ShapeAnchorRef(it.elementId, it.anchorIndex) },
                endAnchor = hit?.let { ShapeAnchorRef(it.elementId, it.anchorIndex) }
            )
            ShapeDraft(pageIndex, linked, box, start, end, (end - start).distance, 0f, angle, false, line?.snapped == true, anchor, nearby)
        } else {
            endAnchorHit = null
            val corner = if (forced || free) pointer else snaps.snapPoint(pageIndex, pointer, emptyList(), free)
            val constrained = if (free && !forced) {
                ShapeConstraints.ConstrainedBox(boxBetween(start, pointer, fromCenter), false)
            } else {
                ShapeConstraints.box(start, corner, forced, fromCenter)
            }
            if (constrained.regular || fromCenter) snaps.clearGuides()
            val box = constrained.box
            ShapeDraft(
                pageIndex, value, ShapeBox(box.left, box.top, box.width, box.height), start, corner,
                box.width, box.height, 0f, constrained.regular, false, null, emptyList()
            )
        }
        reportDraftPresence(draft)
    }

    /**
     * Adds the shape drawn on top of its page, selected (one step of undo); `null` when it is too
     * small to be one, or the document may only be read.
     */
    internal fun commit(): CustomElement? {
        val shape = draft
        cancel()
        if (shape == null) return null
        val box = shape.box
        val tooSmall = if (shape.value.kind.isLine) box.width < MIN_SIZE_MM else box.width < MIN_SIZE_MM && box.height < MIN_SIZE_MM
        if (tooSmall) return null
        // A box may be flat (a line drawn as a rectangle), never empty
        val width = box.width.coerceAtLeast(MIN_SIZE_MM)
        val height = box.height.coerceAtLeast(MIN_SIZE_MM)
        return controller.customElementManager.add(
            ShapeElementType, shape.value, shape.pageIndex, Rect(box.x, box.y, box.x + width, box.y + height), box.rotation
        )
    }

    /**
     * Appends a vertex at [at] (page millimeters) on [pageIndex] for vertex-editable shapes.
     * For [ShapeKind.FREE_POLYGON], tapping back near the first vertex with at least 3 vertices
     * closes and commits the polygon, returning `true`.
     */
    internal fun addVertex(pageIndex: Int, at: Point, free: Boolean): Boolean {
        if (!style.kind.isVertexEditable) return false
        if (this.pageIndex != pageIndex) {
            pendingVertices.clear()
            this.pageIndex = pageIndex
        }
        val snapped = if (free) at else controller.snapManager.snapPoint(pageIndex, at, emptyList(), free)
        if (style.kind == ShapeKind.FREE_POLYGON && pendingVertices.size >= 3) {
            val closeRadiusMm = controller.snapManager.toMm(pageIndex, VERTEX_CLOSE_DP)
            if ((snapped - pendingVertices[0]).distance <= closeRadiusMm) {
                finishVertices()
                return true
            }
        }
        pendingVertices += snapped
        draft = vertexDraft(pageIndex, pendingVertices, snapped)
        reportDraftPresence(draft)
        return false
    }

    /** Updates the live preview with a hover/drag [pointer] while multi-tap vertex insertion is active. */
    internal fun previewVertex(pointer: Point, free: Boolean) {
        if (pageIndex < 0 || pendingVertices.isEmpty() || !style.kind.isVertexEditable) return
        val snapped = if (free) pointer else controller.snapManager.snapPoint(pageIndex, pointer, emptyList(), free)
        draft = vertexDraft(pageIndex, pendingVertices + snapped, snapped)
        reportDraftPresence(draft)
    }

    /**
     * Finishes and commits the vertices gathered so far (`>= 2` for polylines, `>= 3` for free
     * polygons), selecting the newly created shape, or cancels if too few vertices exist.
     */
    public fun finishVertices(): CustomElement? {
        val verts = pendingVertices.toList()
        val targetPage = pageIndex
        cancel()
        val minCount = if (style.kind == ShapeKind.FREE_POLYGON) 3 else 2
        if (targetPage < 0 || verts.size < minCount) return null
        val value = style.element()
        val (box, normalized) = ShapeGeometry.boxAndVerticesFromPage(verts, value.widthMm, MIN_SIZE_MM)
        val finalValue = value.copy(vertices = normalized)
        return controller.customElementManager.add(
            ShapeElementType, finalValue, targetPage, Rect(box.x, box.y, box.x + box.width, box.y + box.height), box.rotation
        )
    }

    private fun vertexDraft(targetPage: Int, points: List<Point>, pointer: Point): ShapeDraft {
        val base = style.element()
        val previewPoints = when (points.size) {
            0 -> listOf(pointer, pointer + Point(MIN_SIZE_MM, 0f))
            1 -> listOf(points[0], points[0] + Point(MIN_SIZE_MM, 0f))
            else -> points
        }
        val (box, normalized) = ShapeGeometry.boxAndVerticesFromPage(previewPoints, base.widthMm, MIN_SIZE_MM)
        val previewValue = if (base.kind == ShapeKind.FREE_POLYGON && previewPoints.size < 3) {
            base.copy(kind = ShapeKind.POLYLINE, fill = null, startHead = ShapeHead.NONE, endHead = ShapeHead.NONE, vertices = normalized)
        } else {
            base.copy(vertices = normalized)
        }
        return ShapeDraft(
            pageIndex = targetPage,
            value = previewValue,
            box = box,
            start = previewPoints.first(),
            end = pointer,
            widthMm = box.width,
            heightMm = box.height,
            angleDegrees = 0f,
            isRegular = false,
            isAngleSnapped = false,
            anchor = null,
            anchors = emptyList()
        )
    }

    /** Drops the shape being drawn. */
    internal fun cancel() {
        draft = null
        pageIndex = -1
        startAnchorHit = null
        endAnchorHit = null
        pendingVertices.clear()
        controller.snapManager.clearGuides()
        controller.presence.reportShapeDraft(null)
    }

    private fun reportDraftPresence(shapeDraft: ShapeDraft?) {
        if (!controller.presence.isFollowed) return
        if (shapeDraft == null) {
            controller.presence.reportShapeDraft(null)
            return
        }
        val page = controller.document.pages.getOrNull(shapeDraft.pageIndex) ?: return
        val b = shapeDraft.box
        controller.presence.reportShapeDraft(
            ShapeDraftActivity(
                pageId = page.id,
                shape = shapeDraft.value,
                x = b.x,
                y = b.y,
                width = b.width.coerceAtLeast(MIN_SIZE_MM),
                height = b.height.coerceAtLeast(MIN_SIZE_MM),
                rotation = b.rotation
            )
        )
    }

    /**
     * Adds a shape of the style of the tool, of a standard size, centered on [at] (page
     * millimeters) of the page [pageIndex], selected.
     */
    public fun insertAt(pageIndex: Int, at: Point): CustomElement? {
        cancel()
        val value = style.element()
        val page = controller.document.pages.getOrNull(pageIndex) ?: return null
        return if (value.kind.isLine) {
            val half = STANDARD_LINE_MM / 2f
            val box = ShapeGeometry.lineBox(Point(at.x - half, at.y), Point(at.x + half, at.y), value)
            controller.customElementManager.add(ShapeElementType, value, pageIndex, Rect(box.x, box.y, box.x + box.width, box.y + box.height), 0f)
        } else {
            val wide = value.kind == ShapeKind.RECTANGLE || value.kind == ShapeKind.ROUNDED_RECTANGLE ||
                value.kind == ShapeKind.AXES || value.kind == ShapeKind.GRID
            val width = (if (wide) STANDARD_WIDE_MM else STANDARD_SIDE_MM).coerceAtMost(page.widthMm)
            val height = STANDARD_SIDE_MM.coerceAtMost(page.heightMm)
            val left = (at.x - width / 2f).coerceIn(0f, (page.widthMm - width).coerceAtLeast(0f))
            val top = (at.y - height / 2f).coerceIn(0f, (page.heightMm - height).coerceAtLeast(0f))
            controller.customElementManager.add(ShapeElementType, value, pageIndex, Rect(left, top, left + width, top + height), 0f)
        }
    }

    /** Adds a shape of the style of the tool in the middle of the page shown the most, selected (see [insertAt]). */
    public fun insertInView(): CustomElement? {
        val pageIndex = controller.viewport.mostVisiblePageIndex() ?: return null
        val page = controller.document.pages.getOrNull(pageIndex) ?: return null
        val viewport = controller.viewport
        // The middle of the part of the page on the screen
        val shown = viewport.visiblePages().firstOrNull { it.index == pageIndex }?.rect
        val center = shown?.let {
            val left = maxOf(it.left, 0f)
            val right = minOf(it.right, viewport.width)
            val top = maxOf(it.top, 0f)
            val bottom = minOf(it.bottom, viewport.height)
            viewport.screenToPageMm(pageIndex, androidx.compose.ui.geometry.Offset((left + right) / 2f, (top + bottom) / 2f))
        }
        val at = center?.let { Point(it.x.coerceIn(0f, page.widthMm), it.y.coerceIn(0f, page.heightMm)) } ?: Point(page.widthMm / 2f, page.heightMm / 2f)
        return insertAt(pageIndex, at)
    }

    private fun boxBetween(a: Point, b: Point, fromCenter: Boolean): Rect = if (fromCenter) {
        val dx = kotlin.math.abs(b.x - a.x)
        val dy = kotlin.math.abs(b.y - a.y)
        Rect(a.x - dx, a.y - dy, a.x + dx, a.y + dy)
    } else {
        Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
    }

    private fun readAngle(from: Point, to: Point): Float {
        val degrees = -atan2(to.y - from.y, to.x - from.x) * 180f / PI.toFloat()
        return if (degrees < 0f) degrees + 360f else degrees
    }

    internal companion object {
        /** The smallest shape drawn, in millimeters: a smaller drag is no shape. */
        const val MIN_SIZE_MM = 0.5f

        /** The shapes put by a tap or a command: a box of a side, a rectangle as wide, a line as long. */
        const val STANDARD_SIDE_MM = 20f
        const val STANDARD_WIDE_MM = 30f
        const val STANDARD_LINE_MM = 30f

        /** How near the pointer the anchors of the other shapes are shown while a line is drawn. */
        const val ANCHORS_SHOWN_DP = 48f

        /** Tap radius around the start vertex that closes a free polygon. */
        const val VERTEX_CLOSE_DP = 18f
    }
}
