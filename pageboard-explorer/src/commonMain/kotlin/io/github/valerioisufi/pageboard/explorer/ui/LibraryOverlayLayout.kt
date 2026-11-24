package io.github.valerioisufi.pageboard.explorer.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ParentDataModifierNode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection

/**
 * The layer of what the application draws over the library (`DocumentLibrary.libraryOverlay`).
 * It places its content as a `Box` does, and tells [onBottomReach] how high, from its bottom,
 * what it places over the band of [bandWidth] on its start side reaches: the room the navigation
 * pane keeps under its actions so that they are not covered. Zero when nothing is drawn there.
 */
@Composable
internal fun LibraryOverlayLayout(
    bandWidth: Dp,
    onBottomReach: (Dp) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Layout(content = { OverlayScope.content() }, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val children = measurables.map { measurable ->
            val data = measurable.parentData as? OverlayChildNode
            val placeable = if (data?.matchParent == true) measurable.measure(Constraints.fixed(width, height)) else measurable.measure(loose)
            placeable to data
        }
        layout(width, height) {
            val placed = ArrayList<IntRect>(children.size)
            for ((placeable, data) in children) {
                val position = (data?.alignment ?: Alignment.TopStart)
                    .align(IntSize(placeable.width, placeable.height), IntSize(width, height), layoutDirection)
                placeable.place(position)
                // What covers the whole library is no reason to move the actions
                if (data?.matchParent != true) placed += IntRect(position, IntSize(placeable.width, placeable.height))
            }
            onBottomReach(bottomReach(placed, width, height, bandWidth.roundToPx(), layoutDirection).toDp())
        }
    }
}

/**
 * How high, from the bottom of a layer [width] × [height], the [children] placed in it reach over
 * the band [band] wide on its start side: the highest top of those that overlap the band, zero
 * when none does.
 */
internal fun bottomReach(children: List<IntRect>, width: Int, height: Int, band: Int, layoutDirection: LayoutDirection): Int {
    val bandLeft = if (layoutDirection == LayoutDirection.Ltr) 0 else width - band
    val bandRight = bandLeft + band
    return children
        .filter { it.width > 0 && it.height > 0 && it.left < bandRight && it.right > bandLeft }
        .maxOfOrNull { (height - it.top).coerceIn(0, height) } ?: 0
}

/** The scope of [LibraryOverlayLayout]: the alignments of a `Box`. */
private object OverlayScope : BoxScope {
    override fun Modifier.align(alignment: Alignment): Modifier = this then OverlayChildElement(alignment, matchParent = false)
    override fun Modifier.matchParentSize(): Modifier = this then OverlayChildElement(Alignment.Center, matchParent = true)
}

/** Where a child of [LibraryOverlayLayout] goes. */
private data class OverlayChildElement(val alignment: Alignment, val matchParent: Boolean) : ModifierNodeElement<OverlayChildNode>() {
    override fun create(): OverlayChildNode = OverlayChildNode(alignment, matchParent)

    override fun update(node: OverlayChildNode) {
        node.alignment = alignment
        node.matchParent = matchParent
    }
}

/** The data of a child of [LibraryOverlayLayout]: its alignment, or the whole layer. */
private class OverlayChildNode(var alignment: Alignment, var matchParent: Boolean) : Modifier.Node(), ParentDataModifierNode {
    override fun Density.modifyParentData(parentData: Any?): Any = this@OverlayChildNode
}
