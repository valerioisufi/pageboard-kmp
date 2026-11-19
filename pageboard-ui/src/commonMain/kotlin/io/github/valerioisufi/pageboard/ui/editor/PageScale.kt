package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/**
 * Lays out what follows [layoutWidthPx] wide at the resolution of the page and draws it [scale]
 * times as large (pixels of the screen per pixel of the layout), from its top-left corner: the
 * lines of the field of a text are those the page lays out at every zoom, only their drawing is
 * scaled. Its size on the screen is the scaled one. The modifiers before it see the pixels of the
 * screen, those after it the pixels of the layout.
 */
internal fun Modifier.pageScale(layoutWidthPx: Int, scale: () -> Float): Modifier = layout { measurable, _ ->
    val placeable = measurable.measure(Constraints.fixedWidth(layoutWidthPx))
    val factor = scale()
    layout((placeable.width * factor).roundToInt(), (placeable.height * factor).roundToInt()) {
        placeable.placeWithLayer(0, 0) {
            scaleX = factor
            scaleY = factor
            transformOrigin = TransformOrigin(0f, 0f)
        }
    }
}
