package io.github.valerioisufi.drawview.ui.screen

import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.ui.adaptive.HeightClass
import io.github.valerioisufi.drawview.ui.adaptive.InputCapabilities
import io.github.valerioisufi.drawview.ui.adaptive.ReferenceWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolbarLayoutTest {

    /** The arrangement in [window], its buttons sized for its input, with [extensions] buttons of extensions. */
    private fun arrangementOf(window: ReferenceWindow, extensions: Int = 0): ToolbarArrangement {
        val button = InputCapabilities(window.input).minimumInteractiveSize
        // Undo, redo, six tools, two imports in three groups
        return ToolbarLayout.arrange(window.width, window.layout.heightClass, button, button * extensions, tools = 10, groups = 3)
    }

    @Test
    fun thePhonesAndTheShortWindowsHaveTheirToolsFloating() {
        assertEquals(ToolbarArrangement.FLOATING_TOOLS, arrangementOf(ReferenceWindow.PHONE))
        assertEquals(ToolbarArrangement.FLOATING_TOOLS, arrangementOf(ReferenceWindow.PHONE_LANDSCAPE))
    }

    @Test
    fun theWideWindowsHaveOneBar() {
        assertEquals(ToolbarArrangement.ONE_BAR, arrangementOf(ReferenceWindow.DESKTOP))
        assertEquals(ToolbarArrangement.ONE_BAR, arrangementOf(ReferenceWindow.TABLET_LANDSCAPE))
        // With the buttons of three extensions too (the lecture, the sharing, the export)
        assertEquals(ToolbarArrangement.ONE_BAR, arrangementOf(ReferenceWindow.DESKTOP, extensions = 3))
        assertEquals(ToolbarArrangement.ONE_BAR, arrangementOf(ReferenceWindow.TABLET_LANDSCAPE, extensions = 3))
    }

    @Test
    fun theOthersHaveTwoBars() {
        assertEquals(ToolbarArrangement.TWO_BARS, arrangementOf(ReferenceWindow.TABLET_PORTRAIT))
        assertEquals(ToolbarArrangement.TWO_BARS, arrangementOf(ReferenceWindow.TABLET_SPLIT_SCREEN))
        assertEquals(ToolbarArrangement.TWO_BARS, arrangementOf(ReferenceWindow.DESKTOP_NARROW))
        // A window of a desktop as wide as the bars
        val button = 40.dp
        val width = ToolbarLayout.topWidth(button, 0.dp) + ToolbarLayout.toolsWidth(button, 10, 3)
        assertEquals(ToolbarArrangement.ONE_BAR, ToolbarLayout.arrange(width, HeightClass.MEDIUM, button, 0.dp, 10, 3))
        assertEquals(ToolbarArrangement.TWO_BARS, ToolbarLayout.arrange(width - 1.dp, HeightClass.MEDIUM, button, 0.dp, 10, 3))
    }

    @Test
    fun theControlsOfTheWindowTakeTheirWidthFromTheTopBar() {
        // A window of a desktop as wide as the bars, with the traffic lights of macOS at the start
        val button = 40.dp
        val decor = 78.dp
        val width = ToolbarLayout.topWidth(button, 0.dp) + ToolbarLayout.toolsWidth(button, 10, 3)
        assertEquals(ToolbarArrangement.TWO_BARS, ToolbarLayout.arrange(width, HeightClass.MEDIUM, button, 0.dp, 10, 3, decor))
        assertEquals(ToolbarArrangement.ONE_BAR, ToolbarLayout.arrange(width + decor, HeightClass.MEDIUM, button, 0.dp, 10, 3, decor))
        // The width of the window still decides whether the tools float
        assertEquals(ToolbarArrangement.TWO_BARS, ToolbarLayout.arrange(620.dp, HeightClass.MEDIUM, button, 0.dp, 10, 3, decor))
        assertFalse(ToolbarLayout.pageGridFits(ToolbarLayout.topWidth(button, 0.dp), button, 0.dp, decor))
    }

    @Test
    fun theTitleBesideTheToolsTakesTheRoomTheyLeave() {
        val button = 40.dp
        val width = ToolbarLayout.topWidth(button, 0.dp) + ToolbarLayout.toolsWidth(button, 10, 3)
        // Just wide enough for one bar, the title keeps its least width; wider, it grows to its widest
        assertEquals(ToolbarLayout.TitleMinWidth, ToolbarLayout.titleMaxWidth(width, button, 0.dp, 10, 3))
        assertEquals(ToolbarLayout.TitleMinWidth + 50.dp, ToolbarLayout.titleMaxWidth(width + 50.dp, button, 0.dp, 10, 3))
        assertEquals(ToolbarLayout.TitleMaxWidth, ToolbarLayout.titleMaxWidth(width + 500.dp, button, 0.dp, 10, 3))
        // The traffic lights take their part of the room
        assertEquals(ToolbarLayout.TitleMinWidth, ToolbarLayout.titleMaxWidth(width + 50.dp, button, 0.dp, 10, 3, decorWidth = 78.dp))
    }

    @Test
    fun thePageGridGoesToTheMenuWhenTheTitleWouldBeTooShort() {
        val button = 48.dp
        assertTrue(ToolbarLayout.pageGridFits(ReferenceWindow.PHONE.width, button, extensionsWidth = 0.dp))
        assertFalse(ToolbarLayout.pageGridFits(ReferenceWindow.PHONE.width, button, extensionsWidth = button * 3))
    }

    @Test
    fun theActionsOfTheExtensionsLeaveTheTitleItsRoom() {
        val button = 48.dp
        // A phone: back and "more" beside three actions leave the title too little room
        assertFalse(ToolbarLayout.extensionsFit(ReferenceWindow.PHONE.width, button, button * 3, buttons = 2))
        assertTrue(ToolbarLayout.extensionsFit(ReferenceWindow.PHONE.width, button, button * 2, buttons = 2))
        // A tablet in split screen keeps them, with the page grid
        assertTrue(ToolbarLayout.extensionsFit(ReferenceWindow.TABLET_SPLIT_SCREEN.width, button, button * 3, buttons = 3))
    }
}
