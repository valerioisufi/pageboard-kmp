package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals

class CaretRevealTest {

    private val visible = Rect(0f, 0f, 1000f, 800f)

    private fun pan(caret: Rect) = CaretReveal.panFor(caret, visible, marginX = 20f, marginY = 10f)

    @Test
    fun aCaretInSightMovesNothing() {
        assertEquals(Offset.Zero, pan(Rect(500f, 400f, 502f, 430f)))
    }

    @Test
    fun aCaretPastAnEdgeComesInsideItsMargin() {
        assertEquals(Offset(-222f, 0f), pan(Rect(1200f, 400f, 1202f, 430f)))
        assertEquals(Offset(120f, 0f), pan(Rect(-100f, 400f, -98f, 430f)))
        assertEquals(Offset(0f, -140f), pan(Rect(500f, 900f, 502f, 930f)))
        assertEquals(Offset(0f, 60f), pan(Rect(500f, -50f, 502f, -20f)))
    }

    @Test
    fun bothAxesMoveTogether() {
        assertEquals(Offset(-222f, 60f), pan(Rect(1200f, -50f, 1202f, -20f)))
    }

    @Test
    fun aRoomTooSmallShowsTheStart() {
        assertEquals(Offset(0f, 10f), CaretReveal.panFor(Rect(500f, 0f, 502f, 30f), Rect(0f, 0f, 1000f, 20f), marginX = 20f, marginY = 10f))
    }
}
