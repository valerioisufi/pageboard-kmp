package io.github.valerioisufi.pageboard.model.shape

import io.github.valerioisufi.pageboard.model.ElementType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * A shape inserted with the shapes tool: an element of the type [ShapeElementType], drawn in its
 * box (see [ShapeGeometry]). A shape of a box ([ShapeKind.isLine] false) fills its box; a line goes
 * from the middle of the left side of its box to the middle of its right side, its box turned
 * along it.
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
    /** The color of the inside (ARGB), `null` for none; a line has none. */
    val fill: Int? = null,
    /** The head at the start of a line (its left end); none on a shape of a box. */
    val startHead: ShapeHead = ShapeHead.NONE,
    /** The head at the end of a line (its right end); none on a shape of a box. */
    val endHead: ShapeHead = ShapeHead.NONE
) {
    init {
        require(widthMm.isFinite() && widthMm in MIN_WIDTH_MM..MAX_WIDTH_MM) { "width out of bounds: $widthMm" }
        require(kind.isLine || (startHead == ShapeHead.NONE && endHead == ShapeHead.NONE)) { "heads on a $kind" }
        require(!kind.isLine || fill == null) { "a line has no fill" }
    }

    /** This shape as [kind], its style kept as far as [kind] has it (a line has no fill, a box no heads). */
    public fun withKind(kind: ShapeKind): ShapeElement = when {
        kind == this.kind -> this
        kind.isLine -> copy(kind = kind, fill = null)
        else -> copy(kind = kind, startHead = ShapeHead.NONE, endHead = ShapeHead.NONE)
    }

    /** This shape with the style of [style] (its kind kept, see [withKind]). */
    public fun withStyleOf(style: ShapeElement): ShapeElement = style.withKind(kind)

    public companion object {
        /** The thinnest outline, in millimeters. */
        public const val MIN_WIDTH_MM: Float = 0.05f

        /** The widest outline, in millimeters. */
        public const val MAX_WIDTH_MM: Float = 20f
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
    HEXAGON;

    /** Whether the shape is a line (two ends) rather than a shape of a box. */
    public val isLine: Boolean get() = this == LINE
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
 * The shapes as a type of element (`drawview.shape`): the value is a [ShapeElement] in CBOR,
 * format 1. A value that does not hold the checks of [ShapeElement] is not read (a placeholder).
 */
public object ShapeElementType : ElementType<ShapeElement> {
    override val id: String = "drawview.shape"

    override val version: Int = 1

    override val serializer: KSerializer<ShapeElement> = ShapeElement.serializer()
}
