package io.github.valerioisufi.pageboard.model.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The height of a box set by hand, as the room of its text takes it (`docs/plans/boxes-tables-quotes.md`): it comes from any device. */
class TextRoomHeightTest {

    @Test
    fun aHeightByHandIsCheckedBeforeItIsUsed() {
        assertNull(TextRoom.maxHeightOf(null, 297f))
        assertNull(TextRoom.maxHeightOf(Float.NaN, 297f))
        assertNull(TextRoom.maxHeightOf(Float.POSITIVE_INFINITY, 297f))
        assertEquals(TextRoom.MIN_HEIGHT_MM, TextRoom.maxHeightOf(-3f, 297f))
        assertEquals(297f, TextRoom.maxHeightOf(1e9f, 297f))
        assertEquals(42f, TextRoom.maxHeightOf(42f, 297f))
    }
}
