package io.github.valerioisufi.drawview.core.geometry

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Rect

/**
 * Represents a physical measurement strictly independent of device screen pixel density.
 *
 * In a vector drawing or document-rendering context, this class ensures that spatial dimensions
 * maintain their true physical scale regardless of display metrics. It eagerly computes
 * and stores the measurement across multiple standard physical units upon instantiation.
 */
@Stable
@Immutable
class Measure(size: Float, type: Unit) {
    /**
     * Enumeration of supported physical units of measurement.
     */
    enum class Unit {
        INCH, DOT, CM, MM
    }

    /** The calculated measurement value expressed in inches. */
    val inch: Float
    /** The calculated measurement value expressed in typographical points (dots, 1/72 inch). */
    val pt: Float
    /** The calculated measurement value expressed in centimeters. */
    val cm: Float
    /** The calculated measurement value expressed in millimeters. */
    val mm: Float

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
@Stable inline val Int.mm: Measure get() = Measure(size = this.toFloat(), type = Measure.Unit.MM)

/** Converts an [Int] value into a [Measure] instance representing centimeters. */
@Stable inline val Int.cm: Measure get() = Measure(size = this.toFloat(), type = Measure.Unit.CM)

/** Converts an [Int] value into a [Measure] instance representing typographical points. */
@Stable inline val Int.pt: Measure get() = Measure(size = this.toFloat(), type = Measure.Unit.DOT)

/** Converts an [Int] value into a [Measure] instance representing inches. */
@Stable inline val Int.inch: Measure get() = Measure(size = this.toFloat(), type = Measure.Unit.INCH)

/** Converts a [Float] value into a [Measure] instance representing millimeters. */
@Stable inline val Float.mm: Measure get() = Measure(size = this, type = Measure.Unit.MM)

/** Converts a [Float] value into a [Measure] instance representing centimeters. */
@Stable inline val Float.cm: Measure get() = Measure(size = this, type = Measure.Unit.CM)

/** Converts a [Float] value into a [Measure] instance representing typographical points. */
@Stable inline val Float.pt: Measure get() = Measure(size = this, type = Measure.Unit.DOT)

/** Converts a [Float] value into a [Measure] instance representing inches. */
@Stable inline val Float.inch: Measure get() = Measure(size = this, type = Measure.Unit.INCH)

/**
 * A strongly-typed wrapper class representing an exact pixel value on the screen.
 *
 * This wrapper prevents accidental interchange of raw pixel coordinate floats
 * with density-independent physical [Measure] values during UI rendering and Canvas calculations.
 *
 * @property px The precise float representation of screen pixels.
 */
@Stable
@Immutable
data class Px(val px: Float)

/** Wraps an [Int] pixel value into a typed [Px] instance. */
@Stable inline val Int.px: Px get() = Px(this.toFloat())

/** Wraps a [Float] pixel value into a typed [Px] instance. */
@Stable inline val Float.px: Px get() = Px(this)

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
@Stable
@Immutable
class Dimension(val width: Measure, val height: Measure) {

    companion object {
        enum class Length {
            WIDTH, HEIGHT
        }

        enum class Orientation {
            VERTICAL, HORIZONTAL
        }

        fun A3(orientation: Orientation = Orientation.VERTICAL): Dimension {
            return if (orientation == Orientation.VERTICAL)
                Dimension(297f.mm, 420f.mm)
            else
                Dimension(420f.mm, 297f.mm)
        }

        fun A4(orientation: Orientation = Orientation.VERTICAL): Dimension {
            return if (orientation == Orientation.VERTICAL)
                Dimension(210f.mm, 297f.mm)
            else
                Dimension(297f.mm, 210f.mm)
        }

        fun A5(orientation: Orientation = Orientation.VERTICAL): Dimension {
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
    fun calcHeightFromWidthPx(widthPx: Px): Float {
        return (height.mm * widthPx.px) / width.mm
    }

    /**
     * Calculates required pixel width to maintain the page's exact physical aspect ratio,
     * given known pixel height constraints.
     */
    fun calcWidthFromHeightPx(heightPx: Px): Float {
        return (width.mm * heightPx.px) / height.mm
    }

    /**
     * Translates physical page height into screen pixels based on DPI.
     */
    fun calcHeightFromResolutionPxInch(resolutionPxInch: Float): Float {
        return height.inch * resolutionPxInch
    }

    /**
     * Translates physical page width into screen pixels based on DPI.
     */
    fun calcWidthFromResolutionPxInch(resolutionPxInch: Float): Float {
        return width.inch * resolutionPxInch
    }

    /**
     * Calculates corresponding scaled pixel length on the canvas for a physical measurement.
     */
    fun calcPxFromDim(dim: Measure, length: Px, lengthType: Length = Length.WIDTH): Float {
        return when (lengthType) {
            Length.WIDTH -> (dim.mm / width.mm) * length.px
            Length.HEIGHT -> (dim.mm / height.mm) * length.px
        }
    }

    /**
     * Projects a screen-dependent pixel measurement back into an absolute physical [Measure].
     */
    fun calcDimFromPx(dimPx: Float, length: Px, lengthType: Length = Length.WIDTH): Measure {
        return when (lengthType) {
            Length.WIDTH -> Measure(dimPx * width.mm / length.px, Measure.Unit.MM)
            Length.HEIGHT -> Measure(dimPx * height.mm / length.px, Measure.Unit.MM)
        }
    }

    /**
     * Maps an X-axis screen coordinate to an absolute document coordinate in typographical points.
     */
    fun calcXPt(xPx: Float, rectPage: Rect): Float = (xPx - rectPage.left) * width.pt / rectPage.width

    /**
     * Maps a Y-axis screen coordinate to an absolute document coordinate in typographical points.
     */
    fun calcYPt(yPx: Float, rectPage: Rect): Float = (yPx - rectPage.top) * height.pt / rectPage.height

    /**
     * Maps an absolute document X-axis coordinate in points to a screen pixel coordinate.
     */
    fun calcXPx(xPt: Float, rectPage: Rect): Float = (xPt * rectPage.width / width.pt) + rectPage.left

    /**
     * Maps an absolute document Y-axis coordinate in points to a screen pixel coordinate.
     */
    fun calcYPx(yPt: Float, rectPage: Rect): Float = (yPt * rectPage.height / height.pt) + rectPage.top

    /**
     * Maps an X-axis screen coordinate to an absolute document coordinate in millimeters.
     */
    fun calcXMm(xPx: Float, rectPage: Rect): Float = (xPx - rectPage.left) * width.mm / rectPage.width

    /**
     * Maps a Y-axis screen coordinate to an absolute document coordinate in millimeters.
     */
    fun calcYMm(yPx: Float, rectPage: Rect): Float = (yPx - rectPage.top) * height.mm / rectPage.height

    /**
     * Maps an absolute document X coordinate in millimeters to a screen pixel coordinate.
     */
    fun calcXPxFromMm(xMm: Float, rectPage: Rect): Float = (xMm * rectPage.width / width.mm) + rectPage.left

    /**
     * Maps an absolute document Y coordinate in millimeters to a screen pixel coordinate.
     */
    fun calcYPxFromMm(yMm: Float, rectPage: Rect): Float = (yMm * rectPage.height / height.mm) + rectPage.top

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Dimension) return false
        return width == other.width && height == other.height
    }

    override fun hashCode(): Int = 31 * width.hashCode() + height.hashCode()

    override fun toString(): String = "Dimension(${width.mm} x ${height.mm} mm)"
}
