package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The axis of a scroll gesture (`docs/plans/camera-bounds.md`, phases 2 and 3), at 1 pixel per dp. */
class ScrollAxisLockTest {

    private fun lock() = ScrollAxisLock { 1f }.apply { begin() }

    private fun ScrollAxisLock.free(delta: Offset) = filter(delta, canMoveX = true, canMoveY = true)

    @Test
    fun aScrollGoesOnTheAxisOfItsFirstMoveAtOnce() {
        val lock = lock()
        // Nothing held back
        assertEquals(Offset(0f, 5f), lock.free(Offset(1f, 5f)))
        assertEquals(Offset(0f, 6f), lock.free(Offset(3f, 6f)))
        assertEquals(Offset(0f, 8f), lock.free(Offset(-2f, 8f)))
    }

    @Test
    fun aHorizontalFirstMoveGoesOnTheHorizontalAxis() {
        val lock = lock()
        assertEquals(Offset(-12f, 0f), lock.free(Offset(-12f, 5f)))
        assertEquals(Offset(-3f, 0f), lock.free(Offset(-3f, 4f)))
    }

    @Test
    fun theSidewaysShakeOfAVerticalScrollNeverBreaksIt() {
        val lock = lock()
        // Back and forth, drifting a little one way
        repeat(200) { i ->
            val moved = lock.free(Offset(if (i % 2 == 0) 4f else -3f, 10f))
            assertEquals(0f, moved.x, "event $i")
        }
    }

    @Test
    fun aDiagonalScrollBreaksFreeAfterAShortMove() {
        val lock = lock()
        // As AWT reports a macOS trackpad: the two axes in separate events
        assertEquals(Offset(0f, 5f), lock.free(Offset(0f, 5f)))
        assertEquals(Offset.Zero, lock.free(Offset(5f, 0f)))
        assertEquals(Offset(0f, 5f), lock.free(Offset(0f, 5f)))
        assertEquals(Offset.Zero, lock.free(Offset(5f, 0f)))
        assertEquals(Offset(0f, 5f), lock.free(Offset(0f, 5f)))
        // 15 across, 15 along: free, until the gesture ends
        assertEquals(Offset(5f, 0f), lock.free(Offset(5f, 0f)))
        assertEquals(Offset(1f, 10f), lock.free(Offset(1f, 10f)))
    }

    @Test
    fun aTurnAcrossTheAxisLaterInTheGestureBreaksFree() {
        val lock = lock()
        repeat(30) { lock.free(Offset(0f, 10f)) }
        // The direction is read on the last part of the gesture, not on all of it
        assertEquals(Offset.Zero, lock.free(Offset(10f, 0f)))
        val turned = (0 until 4).map { lock.free(Offset(10f, 0f)) }
        assertTrue(turned.any { it.x == 10f }, turned.toString())
    }

    @Test
    fun aNewGestureChoosesItsAxisAgain() {
        val lock = lock()
        assertEquals(Offset(0f, 20f), lock.free(Offset(0f, 20f)))
        lock.begin()
        assertEquals(Offset(20f, 0f), lock.free(Offset(20f, 1f)))
    }

    @Test
    fun anAxisTheContentCannotMoveOnIsLeftOut() {
        val lock = lock()
        // Nothing to choose: given at once, without the fixed axis
        assertEquals(Offset(0f, 3f), lock.filter(Offset(2f, 3f), canMoveX = false, canMoveY = true))
        assertEquals(Offset.Zero, lock.filter(Offset(5f, 0f), canMoveX = false, canMoveY = true))
        lock.begin()
        assertEquals(Offset(4f, 0f), lock.filter(Offset(4f, 30f), canMoveX = true, canMoveY = false))
    }
}
