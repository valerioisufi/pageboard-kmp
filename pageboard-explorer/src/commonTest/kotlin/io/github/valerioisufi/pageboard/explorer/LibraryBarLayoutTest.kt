package io.github.valerioisufi.pageboard.explorer

import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.explorer.ui.LibraryBarFit
import io.github.valerioisufi.pageboard.explorer.ui.LibraryBarLayout
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryBarLayoutTest {

    /** The brand of PencilNotes: its mark, the space and its name. */
    private val brand = 217.dp

    /** A button for a finger. */
    private val button = 48.dp

    @Test
    fun aWideBarKeepsEverything() {
        assertEquals(LibraryBarFit(null, 4, overflow = false), LibraryBarLayout.fit(600.dp, brand, button, 4, withSearch = false))
        assertEquals(LibraryBarFit(260.dp, 4, overflow = false), LibraryBarLayout.fit(900.dp, brand, button, 4, withSearch = true))
    }

    @Test
    fun theSearchNarrowsBeforeAButtonLeaves() {
        // 217 + 4 × 52 + 212 = 637: the search 200 dp wide
        val fit = LibraryBarLayout.fit(637.dp, brand, button, 4, withSearch = true)
        assertEquals(LibraryBarFit(200.dp, 4, overflow = false), fit)
        assertEquals(LibraryBarFit(230.dp, 4, overflow = false), LibraryBarLayout.fit(667.dp, brand, button, 4, withSearch = true))
    }

    @Test
    fun theLastButtonsLeaveIntoTheMenu() {
        // A phone 360 dp wide, 336 dp without the margins: the Trash and "more"
        assertEquals(LibraryBarFit(null, 1, overflow = true), LibraryBarLayout.fit(336.dp, brand, button, 4, withSearch = false))
        // The grid at 600 dp, 552 dp without the margins: the search at its narrowest, the Trash and "more"
        assertEquals(LibraryBarFit(200.dp, 1, overflow = true), LibraryBarLayout.fit(552.dp, brand, button, 4, withSearch = true))
    }

    @Test
    fun aNarrowBarKeepsOnlyTheMenu() {
        assertEquals(LibraryBarFit(null, 0, overflow = true), LibraryBarLayout.fit(250.dp, brand, button, 4, withSearch = false))
    }

    @Test
    fun aMenuNeverTakesTheRoomOfOneButton() {
        assertEquals(LibraryBarFit(null, 1, overflow = false), LibraryBarLayout.fit(240.dp, brand, button, 1, withSearch = false))
    }
}
