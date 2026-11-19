package io.github.valerioisufi.pageboard.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The colors of the canvas of the editor: the desk the pages lie on follows the theme, the paper
 * stays white (the pages are documents, the same in both themes).
 *
 * @param desk behind the pages.
 */
@Immutable
public data class CanvasColors(val desk: Color) {
    public companion object {
        /** The desk of [colorScheme]: its dimmest surface in the light theme, its darkest in the dark one. */
        public fun of(colorScheme: ColorScheme, darkTheme: Boolean): CanvasColors =
            CanvasColors(desk = if (darkTheme) colorScheme.surfaceContainerLowest else colorScheme.surfaceDim)
    }
}

/** The [CanvasColors] of the theme (given by `PageboardTheme`). */
public val LocalCanvasColors: ProvidableCompositionLocal<CanvasColors> = staticCompositionLocalOf { CanvasColors(desk = Color(0xFFDADAD3)) }

/**
 * The inks of the design: the swatches of the pen and of the texts, of the highlighters and of
 * the marks of the PDFs. The same in both themes: they are drawn on the paper.
 */
public object InkPalette {
    public val Black: Color = Color(0xFF1A1C18)
    public val Blue: Color = Color(0xFF1F5FBF)
    public val Red: Color = Color(0xFFD23B3B)
    public val Green: Color = Color(0xFF2F7D32)
    public val Violet: Color = Color(0xFF6B4FC9)
    public val Orange: Color = Color(0xFFE07B1F)

    /** The inks of the pen and of the texts. */
    public val inks: List<Color> = listOf(Black, Blue, Red, Green, Violet, Orange)

    internal val HighlightYellow = Color(0xFFF6E94A)
    internal val HighlightGreen = Color(0xFFA8E86B)
    internal val HighlightPink = Color(0xFFFF9EC4)
    internal val HighlightBlue = Color(0xFF8FD3FF)
    internal val HighlightOrange = Color(0xFFFFC27A)

    /** The inks of the highlighters and of the marks of the PDFs. */
    public val highlights: List<Color> = listOf(HighlightYellow, HighlightGreen, HighlightPink, HighlightBlue, HighlightOrange)

    /** The guides of a box snapped to what is around it (`FrameStyle.GUIDE_COLOR`). */
    public val Guide: Color = Color(0xFFE5397A)
}
