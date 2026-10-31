package io.github.valerioisufi.drawview.explorer.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * What the bar of a narrow library shows beside the brand of the application.
 *
 * @param searchWidth the width of the search, or null when the bar has none.
 * @param buttonsInBar how many of the buttons (the Trash, then the actions of the application)
 *   stay in the bar, the first ones; the others are in the "more" menu.
 * @param overflow whether some buttons are in the "more" menu.
 */
internal data class LibraryBarFit(val searchWidth: Dp?, val buttonsInBar: Int, val overflow: Boolean)

/**
 * Which buttons of the bar of a narrow library fit beside the whole brand of the application, and
 * how wide its search is: the search narrows first, then the buttons leave from the last one into
 * the "more" menu.
 */
internal object LibraryBarLayout {
    /** The widest search, beside a brand and its buttons with room enough. */
    val SearchMaxWidth: Dp = 260.dp

    /** The narrowest search, before a button leaves the bar. */
    val SearchMinWidth: Dp = 200.dp

    /** The margin after the search. */
    val SearchEndPadding: Dp = 8.dp

    /** The space between the pieces of the bar. */
    val Spacing: Dp = 4.dp

    /**
     * What fits in a bar [width] wide (without its margins) beside a brand [brandWidth] wide,
     * with [buttons] buttons [button] wide and a search when [withSearch].
     */
    fun fit(width: Dp, brandWidth: Dp, button: Dp, buttons: Int, withSearch: Boolean): LibraryBarFit {
        val room = width - brandWidth
        fun buttonsWidth(inBar: Int, overflow: Boolean): Dp = (button + Spacing) * (inBar + if (overflow) 1 else 0)
        fun searchWidth(searchWidth: Dp): Dp = if (withSearch) searchWidth + SearchEndPadding + Spacing else 0.dp

        // Everything in the bar, the search as wide as it fits between its bounds
        val searchRoom = room - buttonsWidth(buttons, overflow = false) - SearchEndPadding - Spacing
        if (!withSearch && buttonsWidth(buttons, overflow = false) <= room) return LibraryBarFit(null, buttons, overflow = false)
        if (withSearch && searchRoom >= SearchMinWidth) return LibraryBarFit(searchRoom.coerceAtMost(SearchMaxWidth), buttons, overflow = false)

        // The search at its narrowest, the buttons leaving from the last one
        val search = if (withSearch) SearchMinWidth else null
        // A "more" button in place of one button frees no room
        if (buttons <= 1) return LibraryBarFit(search, buttons, overflow = false)
        val inBar = (buttons - 1 downTo 0).firstOrNull { searchWidth(SearchMinWidth) + buttonsWidth(it, overflow = true) <= room } ?: 0
        return LibraryBarFit(search, inBar, overflow = buttons > 0)
    }
}
