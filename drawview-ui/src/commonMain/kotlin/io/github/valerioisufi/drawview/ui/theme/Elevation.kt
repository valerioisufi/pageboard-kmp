package io.github.valerioisufi.drawview.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * The elevations of the design: each a pair of shadows, a close one and a soft one, as in the
 * effects of the file.
 */
enum class DrawViewElevation(internal val shadows: List<Shadow>) {
    /** Cards, thumbnails. */
    Level1(listOf(shadow(1, 2, 0.06f), shadow(2, 8, 0.07f))),

    /** Floating bars and buttons, the recording pill, the page indicator. */
    Level2(listOf(shadow(1, 3, 0.08f), shadow(4, 16, 0.13f))),

    /** Popovers and menus over the canvas. */
    Level3(listOf(shadow(2, 6, 0.10f), shadow(12, 32, 0.20f))),

    /** A sheet of paper on the desk. */
    Paper(listOf(shadow(1, 2, 0.10f), shadow(3, 14, 0.16f)))
}

private fun shadow(y: Int, blur: Int, alpha: Float) =
    Shadow(radius = blur.dp, color = Color.Black, offset = DpOffset(0.dp, y.dp), alpha = alpha)

/** The shadows of [elevation] around [shape], drawn behind the content. */
fun Modifier.elevation(elevation: DrawViewElevation, shape: Shape): Modifier =
    elevation.shadows.fold(this) { modifier, shadow -> modifier.dropShadow(shape, shadow) }
