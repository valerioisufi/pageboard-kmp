package io.github.valerioisufi.pageboard.ui.adaptive

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** The frame of the sheets for each kind of window and input (docs/plans/small-screen-modals.md). */
class ModalFrameTest {

    private fun frame(window: ReferenceWindow) = ModalFrame.of(window.layout, touch = window.input.touch || window.input.stylus)

    @Test
    fun aPhoneGetsABottomSheet() {
        assertEquals(ModalFrame.BottomSheet, frame(ReferenceWindow.PHONE))
    }

    @Test
    fun aPhoneInLandscapeGetsADialog() {
        assertEquals(ModalFrame.Dialog, frame(ReferenceWindow.PHONE_LANDSCAPE))
    }

    @Test
    fun tabletsAndDesktopsGetADialog() {
        for (window in listOf(ReferenceWindow.TABLET_PORTRAIT, ReferenceWindow.TABLET_SPLIT_SCREEN, ReferenceWindow.TABLET_LANDSCAPE, ReferenceWindow.DESKTOP_NARROW, ReferenceWindow.DESKTOP)) {
            assertEquals(ModalFrame.Dialog, frame(window), window.name)
        }
    }

    @Test
    fun aNarrowDesktopWindowGetsADialogTakingIt() {
        assertEquals(ModalFrame.FullWindowDialog, ModalFrame.of(WindowLayout.of(360.dp, 480.dp), touch = false))
    }

    @Test
    fun aNarrowWindowTouchedGetsABottomSheet() {
        assertEquals(ModalFrame.BottomSheet, ModalFrame.of(WindowLayout.of(360.dp, 480.dp), touch = true))
    }
}
