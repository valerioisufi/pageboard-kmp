package io.github.valerioisufi.pageboard.ui.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How fast the canvas scrolls while a selection is dragged near its edges (`docs/plans/boxes-tables-quotes.md`). */
class EdgeScrollTest {

    @Test
    fun theCanvasScrollsTowardTheNearerEdgeFasterTheFartherIn() {
        assertEquals(0f, edgeScroll(100f, 100f, 32f), "away from the edges")
        val near = edgeScroll(100f, 16f, 32f)
        val past = edgeScroll(100f, -32f, 32f)
        assertTrue(near < 0f && past < near, "near the bottom it scrolls up, faster past it: $near, $past")
        assertTrue(edgeScroll(-100f, 300f, 32f) > 0f, "past the top: down")
        assertEquals(edgeScroll(100f, -1000f, 32f), edgeScroll(100f, -32f, 32f), "at most a speed")
    }
}
