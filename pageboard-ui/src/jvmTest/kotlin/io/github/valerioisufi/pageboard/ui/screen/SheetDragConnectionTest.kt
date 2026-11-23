package io.github.valerioisufi.pageboard.ui.screen

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * The content of the sheet of the panels moves the sheet as the content of a modal bottom sheet
 * does (docs/plans/small-screen-modals.md).
 */
class SheetDragConnectionTest {

    private var tall = false
    private var closed = false
    private val connection = SheetDragConnection(isTall = { tall }, onTall = { tall = it }, onClose = { closed = true }, threshold = 100f)

    /** A drag of the content by [dy] in steps of 10, which the content consumes as [consumes] tells, then released. */
    private fun drag(dy: Float, consumes: (Offset) -> Offset = { Offset.Zero }) = runBlocking {
        val step = if (dy < 0) -10f else 10f
        repeat((dy / step).toInt()) {
            val delta = Offset(0f, step)
            val pre = connection.onPreScroll(delta, NestedScrollSource.UserInput)
            val left = delta - pre
            val consumed = consumes(left)
            connection.onPostScroll(consumed, left - consumed, NestedScrollSource.UserInput)
        }
        val velocity = Velocity.Zero
        val preFling = connection.onPreFling(velocity)
        connection.onPostFling(Velocity.Zero, velocity - preFling)
    }

    @Test
    fun draggedUpWhileShortTheSheetGrowsBeforeTheContentScrolls() {
        var scrolled = 0f
        drag(-200f) { scrolled += it.y; it }
        assertEquals(true, tall)
        // The first 100 pixels grow the sheet, the content scrolls the rest
        assertEquals(-90f, scrolled)
    }

    @Test
    fun draggedDownAtTheTopATallSheetGetsShortThenClosed() {
        tall = true
        drag(300f)
        assertEquals(false, tall)
        assertEquals(false, closed)
        drag(300f)
        assertEquals(true, closed)
    }

    @Test
    fun aShortPullDoesNothing() {
        tall = true
        drag(50f)
        assertEquals(true, tall)
    }

    @Test
    fun contentScrollingBackToItsTopDoesNotCount() {
        tall = true
        var position = 150f
        // The content scrolls back 150 pixels to its top, then is pulled 60 past it
        drag(210f) { delta ->
            val taken = minOf(position, delta.y)
            position -= taken
            Offset(0f, taken)
        }
        assertEquals(true, tall)
    }
}
