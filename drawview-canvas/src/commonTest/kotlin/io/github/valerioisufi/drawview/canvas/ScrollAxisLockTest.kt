package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals

/** The axis of a scroll gesture (`docs/camera-bounds.md`, phase 2). */
class ScrollAxisLockTest {

    private fun lock(slop: Float = 8f) = ScrollAxisLock { slop }.apply { begin() }

    private fun ScrollAxisLock.free(delta: Offset) = filter(delta, canMoveX = true, canMoveY = true)

    @Test
    fun aMostlyVerticalStartLocksTheVerticalAxis() {
        val lock = lock()
        // Within the slop: held back
        assertEquals(Offset.Zero, lock.free(Offset(1f, 5f)))
        // Past it: what was held back, on the axis chosen
        assertEquals(Offset(0f, 10f), lock.free(Offset(1f, 5f)))
        assertEquals(Offset(0f, 6f), lock.free(Offset(3f, 6f)))
        assertEquals(Offset.Zero, lock.free(Offset(4f, 0f)))
    }

    @Test
    fun aMostlyHorizontalStartLocksTheHorizontalAxis() {
        val lock = lock()
        assertEquals(Offset(-12f, 0f), lock.free(Offset(-12f, 5f)))
        assertEquals(Offset(-3f, 0f), lock.free(Offset(-3f, 9f)))
    }

    @Test
    fun aDiagonalStartStaysFree() {
        val lock = lock()
        assertEquals(Offset(6f, -7f), lock.free(Offset(6f, -7f)))
        assertEquals(Offset(5f, 0f), lock.free(Offset(5f, 0f)))
        assertEquals(Offset(0f, 5f), lock.free(Offset(0f, 5f)))
    }

    @Test
    fun aScrollShorterThanTheSlopMovesWhenItEnds() {
        val lock = lock()
        assertEquals(Offset.Zero, lock.free(Offset(2f, 3f)))
        assertEquals(Offset(2f, 3f), lock.end())
        assertEquals(Offset.Zero, lock.end())
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

    @Test
    fun withoutASlopTheFirstDeltaChoosesTheAxis() {
        // A wheel without phases: its end is known only from the next scroll, nothing is held back
        val lock = lock(slop = 0f)
        assertEquals(Offset(0f, 48f), lock.free(Offset(0f, 48f)))
        assertEquals(Offset.Zero, lock.free(Offset(48f, 0f)))
    }
}
