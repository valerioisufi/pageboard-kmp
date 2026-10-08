package io.github.valerioisufi.drawview.core.text

/**
 * The sizes of the texts: typographic points, converted to the millimeters of the pages.
 */
object TextSizes {
    /** Millimeters of a typographic point. */
    const val PT_TO_MM = 0.3527f

    /**
     * Height of a line of [fontSize] points in millimeters when the fonts cannot be measured (the
     * line of the fonts of the notes is about 1.2 times their size).
     */
    fun estimatedLineHeightMm(fontSize: Float): Float = fontSize * PT_TO_MM * 1.2f
}
