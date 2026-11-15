package io.github.valerioisufi.drawview.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * A row scrolled by [state], its content fading out on each side where there is more of it to
 * scroll to: a bar cut by the edge of the window shows that it goes on, instead of ending with
 * half a button. Only the content fades; the background drawn before this modifier stays.
 *
 * The fade grows with what is left to scroll, up to [length], so a row that fits draws as it is.
 */
internal fun Modifier.fadingHorizontalScroll(state: ScrollState, length: Dp = 24.dp): Modifier =
    scrollFade(state, length).horizontalScroll(state)

/** The fade of [fadingHorizontalScroll], drawn over the content of a row scrolled by [state]. */
internal fun Modifier.scrollFade(state: ScrollState, length: Dp = 24.dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fade = length.toPx().coerceAtMost(size.width / 2)
            if (fade <= 0f) return@drawWithContent
            // What is left to scroll before and after what is shown; the start is on the right in RTL
            val before = (state.value / fade).coerceIn(0f, 1f)
            val after = ((state.maxValue - state.value) / fade).coerceIn(0f, 1f)
            val (left, right) = if (layoutDirection == LayoutDirection.Rtl) after to before else before to after
            fadeEdge(strength = left, from = 0f, to = fade)
            fadeEdge(strength = right, from = size.width, to = size.width - fade)
        }

/** Fades the content between [from] (faded by [strength]) and [to] (untouched). */
private fun DrawScope.fadeEdge(strength: Float, from: Float, to: Float) {
    if (strength <= 0f) return
    val left = minOf(from, to)
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(Color.Black.copy(alpha = 1f - strength), Color.Black),
            startX = from,
            endX = to
        ),
        topLeft = Offset(left, 0f),
        size = Size(kotlin.math.abs(to - from), size.height),
        blendMode = BlendMode.DstIn
    )
}
