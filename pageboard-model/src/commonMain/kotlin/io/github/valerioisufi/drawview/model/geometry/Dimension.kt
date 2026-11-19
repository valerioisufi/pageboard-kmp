package io.github.valerioisufi.drawview.model.geometry


/**
 * Represents a physical measurement strictly independent of device screen pixel density.
 *
 * In a vector drawing or document-rendering context, this class ensures that spatial dimensions
 * maintain their true physical scale regardless of display metrics. It eagerly computes
 * and stores the measurement across multiple standard physical units upon instantiation.
 */
public class Measure(size: Float, type: Unit) {
    /**
     * Enumeration of supported physical units of measurement.
     */
    public enum class Unit {
        INCH, DOT, CM, MM
    }

    /** The calculated measurement value expressed in inches. */
    public val inch: Float
    /** The calculated measurement value expressed in typographical points (dots, 1/72 inch). */
    public val pt: Float
    /** The calculated measurement value expressed in centimeters. */
    public val cm: Float
    /** The calculated measurement value expressed in millimeters. */
    public val mm: Float

    init {
        when (type) {
            Unit.INCH -> {
                inch = size
                mm = inch * 25.4f
                cm = mm / 10f
                pt = mm * 2.83465f
            }
            Unit.DOT -> {
                pt = size
                mm = pt / 2.83465f
                cm = mm / 10f
                inch = mm / 25.4f
            }
            Unit.CM -> {
                cm = size
                mm = cm * 10f
                inch = mm / 25.4f
                pt = mm * 2.83465f
            }
            Unit.MM -> {
                mm = size
                cm = mm / 10f
                inch = mm / 25.4f
                pt = mm * 2.83465f
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Measure) return false
        return mm == other.mm
    }

    override fun hashCode(): Int = mm.hashCode()

    override fun toString(): String = "${mm}mm (${pt}pt)"
}

/** Converts an [Int] value into a [Measure] instance representing millimeters. */
public inline val Int.mm: Measure get() = Measure(size = this.toFloat(), type = Measure.Unit.MM)

/** Converts an [Int] value into a [Measure] instance representing centimeters. */
public inline val Int.cm: Measure get() = Measure(size = this.toFloat(), type = Measure.Unit.CM)

/** Converts an [Int] value into a [Measure] instance representing typographical points. */
public inline val Int.pt: Measure get() = Measure(size = this.toFloat(), type = Measure.Unit.DOT)

/** Converts an [Int] value into a [Measure] instance representing inches. */
public inline val Int.inch: Measure get() = Measure(size = this.toFloat(), type = Measure.Unit.INCH)

/** Converts a [Float] value into a [Measure] instance representing millimeters. */
public inline val Float.mm: Measure get() = Measure(size = this, type = Measure.Unit.MM)

/** Converts a [Float] value into a [Measure] instance representing centimeters. */
public inline val Float.cm: Measure get() = Measure(size = this, type = Measure.Unit.CM)

/** Converts a [Float] value into a [Measure] instance representing typographical points. */
public inline val Float.pt: Measure get() = Measure(size = this, type = Measure.Unit.DOT)

/** Converts a [Float] value into a [Measure] instance representing inches. */
public inline val Float.inch: Measure get() = Measure(size = this, type = Measure.Unit.INCH)

/**
 * A strongly-typed wrapper class representing an exact pixel value on the screen.
 *
 * This wrapper prevents accidental interchange of raw pixel coordinate floats
 * with density-independent physical [Measure] values during UI rendering and Canvas calculations.
 *
 * @property px The precise float representation of screen pixels.
 */
public data class Px(val px: Float)

/** Wraps an [Int] pixel value into a typed [Px] instance. */
public inline val Int.px: Px get() = Px(this.toFloat())

/** Wraps a [Float] pixel value into a typed [Px] instance. */
public inline val Float.px: Px get() = Px(this)

/**
 * Represents the absolute physical boundaries (width and height) of a document page.
 *
 * This class acts as the mathematical bridge between physical document layout and
 * UI rendering space. It provides scaling and mapping functions to accurately translate
 * physical boundaries into renderable pixel matrices based on screen resolutions or aspect ratios.
 *
 * @property width The physical width of the page boundary as a [Measure].
 * @property height The physical height of the page boundary as a [Measure].
 */
public class Dimension(public val width: Measure, public val height: Measure) {

    public companion object {
        public enum class Length {
            WIDTH, HEIGHT
        }

        public enum class Orientation {
            VERTICAL, HORIZONTAL
        }

        public fun A3(orientation: Orientation = Orientation.VERTICAL): Dimension {
            return if (orientation == Orientation.VERTICAL)
                Dimension(297f.mm, 420f.mm)
            else
                Dimension(420f.mm, 297f.mm)
        }

        public fun A4(orientation: Orientation = Orientation.VERTICAL): Dimension {
            return if (orientation == Orientation.VERTICAL)
                Dimension(210f.mm, 297f.mm)
            else
                Dimension(297f.mm, 210f.mm)
        }

        public fun A5(orientation: Orientation = Orientation.VERTICAL): Dimension {
            return if (orientation == Orientation.VERTICAL)
                Dimension(148f.mm, 210f.mm)
            else
                Dimension(210f.mm, 148f.mm)
        }
    }

    /**
     * Calculates required pixel height to maintain the page's exact physical aspect ratio,
     * given known pixel width constraints.
     */
    public fun calcHeightFromWidthPx(widthPx: Px): Float {
        return (height.mm * widthPx.px) / width.mm
    }

    /**
     * Maps an X-axis screen coordinate to an absolute document coordinate in millimeters.
     */
    public fun calcXMm(xPx: Float, rectPage: Rect): Float = (xPx - rectPage.left) * width.mm / rectPage.width

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Dimension) return false
        return width == other.width && height == other.height
    }

    override fun hashCode(): Int = 31 * width.hashCode() + height.hashCode()

    override fun toString(): String = "Dimension(${width.mm} x ${height.mm} mm)"
}
