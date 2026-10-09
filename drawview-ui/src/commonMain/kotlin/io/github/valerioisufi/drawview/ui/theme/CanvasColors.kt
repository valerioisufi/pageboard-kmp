package io.github.valerioisufi.drawview.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The colors of the canvas of the editor: the desk the pages lie on follows the theme, the paper
 * stays white (the pages are documents, the same in both themes).
 *
 * @param desk behind the pages.
 */
@Immutable
data class CanvasColors(val desk: Color) {
    companion object {
        /** The desk of [colorScheme]: its dimmest surface in the light theme, its darkest in the dark one. */
        fun of(colorScheme: ColorScheme, darkTheme: Boolean): CanvasColors =
            CanvasColors(desk = if (darkTheme) colorScheme.surfaceContainerLowest else colorScheme.surfaceDim)
    }
}

/** The [CanvasColors] of the theme (given by `DrawViewTheme`). */
val LocalCanvasColors = staticCompositionLocalOf { CanvasColors(desk = Color(0xFFDADAD3)) }

/**
 * The inks of the design: the swatches of the pen and of the texts, of the highlighters and of
 * the marks of the PDFs. The same in both themes: they are drawn on the paper.
 */
object InkPalette {
    val Black = Color(0xFF1A1C18)
    val Blue = Color(0xFF1F5FBF)
    val Red = Color(0xFFD23B3B)
    val Green = Color(0xFF2F7D32)
    val Violet = Color(0xFF6B4FC9)
    val Orange = Color(0xFFE07B1F)

    /** The inks of the pen and of the texts. */
    val inks: List<Color> = listOf(Black, Blue, Red, Green, Violet, Orange)

    val HighlightYellow = Color(0xFFF6E94A)
    val HighlightGreen = Color(0xFFA8E86B)
    val HighlightPink = Color(0xFFFF9EC4)
    val HighlightBlue = Color(0xFF8FD3FF)
    val HighlightOrange = Color(0xFFFFC27A)

    /** The inks of the highlighters and of the marks of the PDFs. */
    val highlights: List<Color> = listOf(HighlightYellow, HighlightGreen, HighlightPink, HighlightBlue, HighlightOrange)

    /** The guides of a box snapped to what is around it (`FrameStyle.GUIDE_COLOR`). */
    val Guide = Color(0xFFE5397A)
}
