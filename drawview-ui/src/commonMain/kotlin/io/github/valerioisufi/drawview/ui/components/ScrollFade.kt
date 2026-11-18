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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** How long the fade of a scrolling row is at most, the margin an item brought into view keeps from its edge. */
internal val ScrollFadeLength: Dp = 24.dp

/**
 * A row scrolled by [state], its content fading out on each side where there is more of it to
 * scroll to: a bar cut by the edge of the window shows that it goes on, instead of ending with
 * half a button. Only the content fades; the background drawn before this modifier stays.
 *
 * The fade grows with what is left to scroll, up to [length], so a row that fits draws as it is.
 */
internal fun Modifier.fadingHorizontalScroll(state: ScrollState, length: Dp = ScrollFadeLength): Modifier =
    scrollFade(state, length).horizontalScroll(state)

/** The fade of [fadingHorizontalScroll], drawn over the content of a row scrolled by [state]. */
internal fun Modifier.scrollFade(state: ScrollState, length: Dp = ScrollFadeLength): Modifier =
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

/**
 * The width of a row of items [pitch] apart (an item and the space after it) that scrolls: as wide
 * as it can be, but ending in the middle of an item when the items do not fit, so the item cut is
 * seen under the fade and tells there are more, instead of a gap that tells nothing. Put it before
 * [fadingHorizontalScroll]; a row that fits keeps its width.
 */
internal fun Modifier.peekingWidth(pitch: Dp): Modifier = layout { measurable, constraints ->
    val available = constraints.maxWidth
    val content = if (constraints.hasBoundedWidth) measurable.maxIntrinsicWidth(constraints.maxHeight) else 0
    val width = if (content <= available) {
        available
    } else {
        val step = pitch.toPx()
        // Whole items, then half of the next one
        val whole = ((available - step / 2) / step).toInt().coerceAtLeast(0)
        (whole * step + step / 2).toInt().coerceIn(constraints.minWidth, available)
    }
    val placeable = measurable.measure(if (content <= available) constraints else constraints.copy(minWidth = width, maxWidth = width))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
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
