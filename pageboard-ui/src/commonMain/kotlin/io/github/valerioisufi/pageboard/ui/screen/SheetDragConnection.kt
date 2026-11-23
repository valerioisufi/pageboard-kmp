package io.github.valerioisufi.pageboard.ui.screen

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

/**
 * Lets the content of a sheet that is not modal (the panels of the editor on a phone) move the
 * sheet as its handle does, as the content of a modal bottom sheet does
 * (docs/plans/small-screen-modals.md):
 * - dragged up while the sheet is short, it makes the sheet tall before the content scrolls;
 * - dragged down past the top of the content by more than [threshold] pixels, it makes the sheet
 *   short, or closes it when it is short, once the drag ends. One step per drag, so that a long
 *   drag does not close a tall sheet at once; a wheel, which has no end of drag, never closes it.
 *
 * @param isTall whether the sheet is tall now.
 * @param onTall makes the sheet tall (`true`) or short (`false`).
 * @param onClose closes the sheet.
 */
internal class SheetDragConnection(
    private val isTall: () -> Boolean,
    private val onTall: (Boolean) -> Unit,
    private val onClose: () -> Unit,
    private val threshold: Float
) : NestedScrollConnection {

    /** How far the content has been pulled down past its top in this drag. */
    private var pulled = 0f

    /** How far the content has been pushed up while the sheet is short, in this drag. */
    private var pushed = 0f

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y >= 0f || isTall()) return Offset.Zero
        // Up while short: the sheet grows first, the content keeps its place
        pushed -= available.y
        if (pushed > threshold) {
            pushed = 0f
            onTall(true)
        }
        return Offset(0f, available.y)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        if (consumed.y != 0f) pulled = 0f
        if (available.y <= 0f) return Offset.Zero
        // Down with the content at its top: counted, acted on at the end of the drag
        pulled += available.y
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        pushed = 0f
        // A fling up while short makes the sheet tall rather than scrolling the content
        if (available.y < 0f && !isTall()) {
            onTall(true)
            pulled = 0f
            return available
        }
        return Velocity.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val pulledFar = pulled > threshold
        pulled = 0f
        if (!pulledFar) return Velocity.Zero
        if (isTall()) onTall(false) else onClose()
        return available
    }
}
