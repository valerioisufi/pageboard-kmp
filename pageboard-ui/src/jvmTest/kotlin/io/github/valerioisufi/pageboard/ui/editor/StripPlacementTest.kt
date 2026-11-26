package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Where the strip of the source and the completions by it stand, never out of the window. */
class StripPlacementTest {

    private val window = 400 to 800

    private fun strip(anchor: Rect, bar: Rect? = null, docked: Boolean = false, width: Int = 200, height: Int = 40) =
        StripPlacement.strip(width, height, window.first, window.second, anchor, bar, docked, gap = 6)

    @Test
    fun theStripStandsAboveWhatItBelongsTo() {
        assertEquals(IntOffset(50, 300 - 6 - 40), strip(Rect(50f, 300f, 60f, 320f)))
    }

    @Test
    fun belowWhenThereIsNoRoomAboveOrTheBarIsThere() {
        assertEquals(IntOffset(50, 20 + 6), strip(Rect(50f, 10f, 60f, 20f)), "no room above")
        assertEquals(IntOffset(50, 320 + 6), strip(Rect(50f, 300f, 60f, 320f), bar = Rect(0f, 240f, 400f, 290f)), "the bar above")
    }

    @Test
    fun neverOutOfTheWindow() {
        val place = strip(Rect(380f, 900f, 390f, 920f))
        assertTrue(place.x + 200 <= 400 - 6 && place.y + 40 <= 800 - 6 && place.y >= 6, "$place")
    }

    @Test
    fun onAPhoneItStandsOnTheBarDockedOverTheKeyboard() {
        val bar = Rect(0f, 500f, 400f, 548f)
        assertEquals(IntOffset(6, 500 - 6 - 40), strip(Rect(50f, 100f, 60f, 120f), bar = bar, docked = true), "far from the line, on the bar")
        assertEquals(IntOffset(50, 100 - 6 - 40), strip(Rect(50f, 100f, 60f, 120f), bar = Rect(0f, 0f, 400f, 48f), docked = true), "a bar at the top: by the line")
    }

    @Test
    fun theCompletionsStandByTheStrip() {
        val strip = Rect(50f, 254f, 250f, 294f)
        assertEquals(IntOffset(50, 294 + 6), StripPlacement.byStrip(160, 100, 400, 800, strip, null, gap = 6), "below it")
        assertEquals(IntOffset(50, 640 - 6 - 100), StripPlacement.byStrip(160, 100, 400, 800, Rect(50f, 640f, 250f, 680f), Rect(0f, 690f, 400f, 740f), gap = 6), "above it over the bar")
    }
}
