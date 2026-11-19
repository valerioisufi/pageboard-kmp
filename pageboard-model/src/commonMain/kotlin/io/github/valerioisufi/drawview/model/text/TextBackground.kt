package io.github.valerioisufi.drawview.model.text

/**
 * The fill of a text box (`DrawText.background`): a color under its lines, around its box by an
 * inner margin, its corners rounded, both proportional to the size of its font. The fill is out of
 * the box, so that adding it does not move the text; the bounds of the text take it.
 */
public object TextBackground {

    /** The margin of the fill around the box, per typographic point of the size of the font. */
    public const val PADDING_EM: Float = 0.35f

    /** The radius of the corners of the fill, per typographic point of the size of the font. */
    public const val RADIUS_EM: Float = 0.35f

    /** How far the fill of a text of [fontSize] points goes out of its box, in millimeters. */
    public fun outsetMm(fontSize: Float): Float = PADDING_EM * fontSize * PT_TO_MM

    /** The radius of the corners of the fill of a text of [fontSize] points, in millimeters. */
    public fun radiusMm(fontSize: Float): Float = RADIUS_EM * fontSize * PT_TO_MM

    /**
     * The tints offered for a fill (ARGB): light enough for a text in a dark color, from the colors
     * of the pens (yellow, green, blue, pink, purple, orange, gray).
     */
    public val TINTS: List<Int> = listOf(
        0xFFFFF1B8.toInt(),
        0xFFDDF2D1.toInt(),
        0xFFD7E8FA.toInt(),
        0xFFFBDCE6.toInt(),
        0xFFE9DFF8.toInt(),
        0xFFFFE3C9.toInt(),
        0xFFECECEA.toInt()
    )

    private const val PT_TO_MM = 25.4f / 72f
}
