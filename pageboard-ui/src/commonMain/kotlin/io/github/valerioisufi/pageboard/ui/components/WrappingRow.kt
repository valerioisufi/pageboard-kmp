package io.github.valerioisufi.pageboard.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A row of a [main] part (a text) and a [side] part (a chip, a badge): the side beside the main
 * part when the main part keeps at least [minMainWidth] there, under it otherwise, so that a text
 * is never squeezed into a narrow column by what is beside it (docs/plans/small-screen-modals.md).
 * The side is never wider than the row: a text in it is cut by its own overflow.
 *
 * @param spacing the room between the two parts, beside or under.
 */
@Composable
internal fun WrappingRow(
    modifier: Modifier = Modifier,
    minMainWidth: Dp = 160.dp,
    spacing: Dp = 10.dp,
    main: @Composable () -> Unit,
    side: @Composable () -> Unit
) {
    Layout(contents = listOf(main, side), modifier = modifier) { (mainMeasurables, sideMeasurables), constraints ->
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        val gap = spacing.roundToPx()
        val sides = sideMeasurables.map { it.measure(Constraints(maxWidth = maxWidth)) }
        val sideWidth = sides.maxOfOrNull { it.width } ?: 0
        val sideHeight = sides.maxOfOrNull { it.height } ?: 0
        val besideWidth = if (maxWidth == Constraints.Infinity) Constraints.Infinity else maxWidth - sideWidth - (if (sides.isEmpty()) 0 else gap)
        val beside = sides.isEmpty() || besideWidth >= minMainWidth.roundToPx()
        if (beside) {
            val mains = mainMeasurables.map {
                it.measure(Constraints(minWidth = if (besideWidth == Constraints.Infinity) 0 else besideWidth, maxWidth = besideWidth))
            }
            val mainWidth = mains.maxOfOrNull { it.width } ?: 0
            val mainHeight = mains.maxOfOrNull { it.height } ?: 0
            val height = maxOf(mainHeight, sideHeight).coerceIn(constraints.minHeight, constraints.maxHeight)
            val width = (mainWidth + (if (sides.isEmpty()) 0 else gap + sideWidth)).coerceIn(constraints.minWidth, constraints.maxWidth)
            layout(width, height) {
                mains.forEach { it.placeRelative(0, (height - it.height) / 2) }
                sides.forEach { it.placeRelative(width - it.width, (height - it.height) / 2) }
            }
        } else {
            val mains = mainMeasurables.map { it.measure(Constraints(minWidth = maxWidth, maxWidth = maxWidth)) }
            val mainHeight = mains.maxOfOrNull { it.height } ?: 0
            val height = (mainHeight + gap + sideHeight).coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(maxWidth, height) {
                mains.forEach { it.placeRelative(0, 0) }
                sides.forEach { it.placeRelative(0, mainHeight + gap) }
            }
        }
    }
}
