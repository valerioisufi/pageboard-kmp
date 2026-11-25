package io.github.valerioisufi.pageboard.model.shape

import io.github.valerioisufi.pageboard.model.ElementType
import io.github.valerioisufi.pageboard.model.geometry.Point
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

/**
 * Reference to an anchor of another shape on the same page: the target [elementId] and the
 * [anchorIndex] within `ShapeGeometry.anchors` of that target shape.
 */
@Serializable
public data class ShapeAnchorRef(
    public val elementId: String,
    public val anchorIndex: Int
) {
    init {
        require(elementId.isNotBlank() && elementId.length <= MAX_ELEMENT_ID_LENGTH) { "invalid elementId" }
        require(anchorIndex in 0..MAX_ANCHOR_INDEX) { "anchorIndex out of bounds: $anchorIndex" }
    }

    public companion object {
        public const val MAX_ELEMENT_ID_LENGTH: Int = 128
        public const val MAX_ANCHOR_INDEX: Int = 64
    }
}

/**
 * Parameters for geometry-tool shapes ([ShapeKind.AXES], [ShapeKind.GRID], [ShapeKind.ARC],
 * [ShapeKind.ANGLE]).
 */
@Serializable
public data class ShapeGeometryParams(
    public val ticksX: Int = 5,
    public val ticksY: Int = 5,
    public val gridCols: Int = 4,
    public val gridRows: Int = 4,
    public val startAngleDeg: Float = 0f,
    public val sweepAngleDeg: Float = 90f,
    public val showAngleLabel: Boolean = true
) {
    init {
        require(ticksX in 1..MAX_DIVISIONS && ticksY in 1..MAX_DIVISIONS) { "ticks out of bounds: $ticksX, $ticksY" }
        require(gridCols in 1..MAX_DIVISIONS && gridRows in 1..MAX_DIVISIONS) { "grid out of bounds: $gridCols, $gridRows" }
        require(startAngleDeg.isFinite() && startAngleDeg in -360f..360f) { "startAngleDeg out of bounds: $startAngleDeg" }
        require(sweepAngleDeg.isFinite() && sweepAngleDeg in -360f..360f) { "sweepAngleDeg out of bounds: $sweepAngleDeg" }
    }

    public companion object {
        public const val MAX_DIVISIONS: Int = 50
    }
}

/**
 * A shape inserted with the shapes tool: an element of the type [ShapeElementType], drawn in its
 * box (see [ShapeGeometry]). A shape of a box ([ShapeKind.isLine] false) fills its box; a line goes
 * from the middle of the left side of its box to the middle of its right side, its box turned
 * along it.
 *
 * Version 2 adds optional [label] text, normalized [vertices] for [ShapeKind.POLYLINE] and
 * [ShapeKind.FREE_POLYGON], persisted connector anchors ([startAnchor], [endAnchor]), and
 * [geometry] parameters for [ShapeKind.AXES], [ShapeKind.GRID], [ShapeKind.ARC] and [ShapeKind.ANGLE].
 *
 * Its value is checked when it is made and when it is read: a value outside the bounds below is
 * not a shape (read from another device, it is drawn as a placeholder). The defaults are part of
 * the stored format: a field at its default is not written, so a default never changes.
 */
@Serializable
public data class ShapeElement(
    /** What the shape is. */
    val kind: ShapeKind,
    /** The color of the outline (ARGB). */
    val stroke: Int,
    /** The width of the outline, in millimeters of the page ([MIN_WIDTH_MM] to [MAX_WIDTH_MM]). */
    val widthMm: Float,
    /** How the outline is drawn. */
    val dash: ShapeDash = ShapeDash.SOLID,
    /** The color of the inside (ARGB), `null` for none; open shapes have none. */
    val fill: Int? = null,
    /** The head at the start of a line or polyline; none on other shapes. */
    val startHead: ShapeHead = ShapeHead.NONE,
    /** The head at the end of a line or polyline; none on other shapes. */
    val endHead: ShapeHead = ShapeHead.NONE,
    /** Optional text label rendered inside or beside the shape. */
    val label: String? = null,
    /** ARGB color of [label], or `null` to use [stroke]. */
    val labelColor: Int? = null,
    /** Font size of [label] in points ([MIN_LABEL_FONT_SIZE_PT] to [MAX_LABEL_FONT_SIZE_PT]). */
    val labelFontSizePt: Float = DEFAULT_LABEL_FONT_SIZE_PT,
    /** Normalized `[0..1]` local coordinates inside the box for [ShapeKind.POLYLINE] and [ShapeKind.FREE_POLYGON]. */
    val vertices: List<Point>? = null,
    /** Persisted anchor of another shape joined to the start of this line/polyline. */
    val startAnchor: ShapeAnchorRef? = null,
    /** Persisted anchor of another shape joined to the end of this line/polyline. */
    val endAnchor: ShapeAnchorRef? = null,
    /** Parameters for geometry shapes ([ShapeKind.AXES], [ShapeKind.GRID], [ShapeKind.ARC], [ShapeKind.ANGLE]). */
    val geometry: ShapeGeometryParams? = null
) {
    init {
        require(widthMm.isFinite() && widthMm in MIN_WIDTH_MM..MAX_WIDTH_MM) { "width out of bounds: $widthMm" }
        require(kind.supportsHeads || (startHead == ShapeHead.NONE && endHead == ShapeHead.NONE)) { "heads on a $kind" }
        require(kind.supportsFill || fill == null) { "a $kind has no fill" }
        require(label == null || label.length <= MAX_LABEL_LENGTH) { "label too long: ${label?.length}" }
        require(labelFontSizePt.isFinite() && labelFontSizePt in MIN_LABEL_FONT_SIZE_PT..MAX_LABEL_FONT_SIZE_PT) {
            "labelFontSizePt out of bounds: $labelFontSizePt"
        }
        if (kind.isVertexEditable) {
            val minVertices = if (kind == ShapeKind.POLYLINE) 2 else 3
            require(vertices != null && vertices.size in minVertices..MAX_FREE_VERTICES) {
                "vertices count invalid for $kind: ${vertices?.size}"
            }
            require(vertices.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f }) {
                "vertex out of normalized [0..1] bounds"
            }
        } else {
            require(vertices == null) { "vertices on a $kind" }
        }
        if (!kind.supportsHeads) {
            require(startAnchor == null && endAnchor == null) { "anchors on a $kind" }
        }
        if (!kind.isGeometryTool) {
            require(geometry == null) { "geometry params on a $kind" }
        }
    }

    /** This shape as [kind], its style and label kept as far as [kind] supports them. */
    public fun withKind(kind: ShapeKind): ShapeElement {
        if (kind == this.kind) return this
        val nextVertices = when {
            !kind.isVertexEditable -> null
            this.vertices != null && (kind == ShapeKind.POLYLINE || this.vertices.size >= 3) -> this.vertices
            kind == ShapeKind.POLYLINE -> DEFAULT_POLYLINE_VERTICES
            else -> DEFAULT_POLYGON_VERTICES
        }
        val nextGeometry = if (kind.isGeometryTool) geometry ?: defaultGeometryParams(kind) else null
        return copy(
            kind = kind,
            fill = if (kind.supportsFill) fill else null,
            startHead = if (kind.supportsHeads) startHead else ShapeHead.NONE,
            endHead = if (kind.supportsHeads) endHead else ShapeHead.NONE,
            startAnchor = if (kind.supportsHeads) startAnchor else null,
            endAnchor = if (kind.supportsHeads) endAnchor else null,
            vertices = nextVertices,
            geometry = nextGeometry
        )
    }

    /** This shape with the style of [style] (keeping this shape's kind, label, vertices, anchors, and geometry). */
    public fun withStyleOf(style: ShapeElement): ShapeElement = copy(
        stroke = style.stroke,
        widthMm = style.widthMm,
        dash = style.dash,
        fill = if (kind.supportsFill) style.fill else null,
        startHead = if (kind.supportsHeads) style.startHead else ShapeHead.NONE,
        endHead = if (kind.supportsHeads) style.endHead else ShapeHead.NONE,
        labelColor = style.labelColor ?: labelColor,
        labelFontSizePt = style.labelFontSizePt
    )

    public companion object {
        /** The thinnest outline, in millimeters. */
        public const val MIN_WIDTH_MM: Float = 0.05f

        /** The widest outline, in millimeters. */
        public const val MAX_WIDTH_MM: Float = 20f

        /** Maximum characters allowed in [label]. */
        public const val MAX_LABEL_LENGTH: Int = 500

        /** Default font size of [label] in points. */
        public const val DEFAULT_LABEL_FONT_SIZE_PT: Float = 12f
        public const val MIN_LABEL_FONT_SIZE_PT: Float = 6f
        public const val MAX_LABEL_FONT_SIZE_PT: Float = 72f

        /** Maximum number of vertices in [ShapeKind.POLYLINE] or [ShapeKind.FREE_POLYGON]. */
        public const val MAX_FREE_VERTICES: Int = 64

        public val DEFAULT_POLYLINE_VERTICES: List<Point> = listOf(
            Point(0f, 1f),
            Point(0.35f, 0.2f),
            Point(0.7f, 0.75f),
            Point(1f, 0f)
        )

        public val DEFAULT_POLYGON_VERTICES: List<Point> = listOf(
            Point(0.5f, 0f),
            Point(1f, 0.4f),
            Point(0.8f, 1f),
            Point(0.2f, 1f),
            Point(0f, 0.4f)
        )

        /** Default geometry parameters for [kind], or `null` when not a geometry tool shape. */
        public fun defaultGeometryParams(kind: ShapeKind): ShapeGeometryParams? = when (kind) {
            ShapeKind.AXES -> ShapeGeometryParams(ticksX = 4, ticksY = 4)
            ShapeKind.GRID -> ShapeGeometryParams(gridCols = 4, gridRows = 4)
            ShapeKind.ARC -> ShapeGeometryParams(startAngleDeg = 0f, sweepAngleDeg = 120f, showAngleLabel = false)
            ShapeKind.ANGLE -> ShapeGeometryParams(startAngleDeg = 0f, sweepAngleDeg = 45f, showAngleLabel = true)
            else -> null
        }
    }
}

/** The kinds of shapes of the shapes tool. */
@Serializable
public enum class ShapeKind {
    /** A straight line, an arrow with its heads. */
    LINE,
    RECTANGLE,

    /** A rectangle whose corners are arcs of a fifth of its shorter side (6 mm at most). */
    ROUNDED_RECTANGLE,
    ELLIPSE,

    /** A triangle with its apex at the middle of the top of its box. */
    TRIANGLE,

    /** A triangle with its right angle at the bottom left of its box. */
    RIGHT_TRIANGLE,

    /** The middles of the four sides of its box joined. */
    DIAMOND,

    /** A regular pentagon, its apex up, stretched to its box. */
    PENTAGON,

    /** A regular hexagon, its sides flat at the top and the bottom, stretched to its box. */
    HEXAGON,

    /** An open multi-segment polyline whose [ShapeElement.vertices] can be edited individually. */
    POLYLINE,

    /** A closed free-form polygon whose [ShapeElement.vertices] can be edited individually. */
    FREE_POLYGON,

    /** Cartesian coordinate axes with tick marks and arrowheads. */
    AXES,

    /** A rectangular coordinate grid of cells. */
    GRID,

    /** An elliptical arc between two angles. */
    ARC,

    /** Two rays from a vertex with an inner angle arc and optional degree label. */
    ANGLE;

    /** Whether the shape is a straight two-ended line. */
    public val isLine: Boolean get() = this == LINE

    /** Whether the shape is edited by individual vertices ([POLYLINE] or [FREE_POLYGON]). */
    public val isVertexEditable: Boolean get() = this == POLYLINE || this == FREE_POLYGON

    /** Whether the shape is a geometry tool shape ([AXES], [GRID], [ARC], [ANGLE]). */
    public val isGeometryTool: Boolean get() = this == AXES || this == GRID || this == ARC || this == ANGLE

    /** Whether the shape is open (cannot be filled). */
    public val isOpen: Boolean get() = this == LINE || this == POLYLINE || this == AXES || this == ARC || this == ANGLE

    /** Whether the shape supports arrowheads at its ends. */
    public val supportsHeads: Boolean get() = this == LINE || this == POLYLINE

    /** Whether the shape supports a fill color. */
    public val supportsFill: Boolean get() = !isOpen
}

/** How the outline of a shape is drawn. */
@Serializable
public enum class ShapeDash { SOLID, DASHED, DOTTED }

/** The head at an end of a line. */
@Serializable
public enum class ShapeHead {
    NONE,

    /** Two wings. */
    OPEN,

    /** A filled triangle. */
    FILLED
}

/**
 * Version 1 wire/storage surrogate of [ShapeElement], used exclusively by [ShapeElementType.migrate]
 * so v1 payloads decode strictly without accepting unknown v2 keys.
 */
@Serializable
private data class ShapeElementV1(
    val kind: ShapeKind,
    val stroke: Int,
    val widthMm: Float,
    val dash: ShapeDash = ShapeDash.SOLID,
    val fill: Int? = null,
    val startHead: ShapeHead = ShapeHead.NONE,
    val endHead: ShapeHead = ShapeHead.NONE
) {
    fun toV2(): ShapeElement = ShapeElement(
        kind = kind,
        stroke = stroke,
        widthMm = widthMm,
        dash = dash,
        fill = fill,
        startHead = startHead,
        endHead = endHead
    )
}

/**
 * The shapes as a type of element (`pageboard.shape`): the value is a [ShapeElement] in CBOR,
 * format 2 (migrating format 1 via [migrate]). A value that does not hold the checks of
 * [ShapeElement] is not read (a placeholder).
 */
public object ShapeElementType : ElementType<ShapeElement> {
    override val id: String = "pageboard.shape"

    override val version: Int = 2

    override val serializer: KSerializer<ShapeElement> = ShapeElement.serializer()

    @OptIn(ExperimentalSerializationApi::class)
    override fun migrate(payload: ByteArray, fromVersion: Int): ShapeElement? {
        if (fromVersion != 1) return null
        return try {
            val v1 = Cbor.decodeFromByteArray(ShapeElementV1.serializer(), payload)
            // Reject v2-only enum kinds if somehow tagged version 1
            if (v1.kind.ordinal > ShapeKind.HEXAGON.ordinal) return null
            v1.toV2()
        } catch (_: Exception) {
            null
        }
    }

    override fun searchText(value: ShapeElement): String? = value.label?.takeIf { it.isNotBlank() }
}
