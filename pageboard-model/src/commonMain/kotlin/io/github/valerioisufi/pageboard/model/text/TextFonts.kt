package io.github.valerioisufi.pageboard.model.text

/**
 * Custom font families selectable on a text element (`TextElement.fontFamily`) and synchronized
 * across devices (`null` for the default sans-serif).
 */
public enum class TextFont(public val id: String?) {
    SANS(null),
    JAKARTA("jakarta"),
    MONO("mono"),
    SERIF("serif")
}

/** Maps stored `TextElement.fontFamily` identifiers to [TextFont] entries. */
public object TextFonts {
    /** The [TextFont] for [id] (`null` or unknown identifiers fall back to [TextFont.SANS]). */
    public fun byId(id: String?): TextFont = TextFont.entries.firstOrNull { it.id == id } ?: TextFont.SANS
}
