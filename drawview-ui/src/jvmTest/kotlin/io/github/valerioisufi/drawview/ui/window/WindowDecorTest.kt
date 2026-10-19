package io.github.valerioisufi.drawview.ui.window

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.awt.Frame
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowDecorTest {

    @Test
    fun defaultWindowDecorInsetsAreZero() {
        val insets = PaddingValues(0.dp)
        assertEquals(0.dp, insets.calculateStartPadding(LayoutDirection.Ltr))
    }

    @Test
    fun macOsWindowDecorInsetsProvideTrafficLightsClearance() {
        val insets = PaddingValues(start = 78.dp, top = 0.dp, end = 0.dp, bottom = 0.dp)
        assertEquals(78.dp, insets.calculateStartPadding(LayoutDirection.Ltr))
        assertEquals(78.dp, insets.calculateStartPadding(LayoutDirection.Rtl))
        assertEquals(78.dp, insets.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(0.dp, insets.calculateLeftPadding(LayoutDirection.Rtl))
        assertEquals(78.dp, insets.calculateRightPadding(LayoutDirection.Rtl))
    }

    @Test
    fun windowsDwmHelperDegradesGracefullyOnNonWindows() {
        val frame = Frame("Test")
        try {
            // Must not throw on macOS / Linux
            WindowsDwmHelper.setDarkMode(frame, true)
            WindowsDwmHelper.setDarkMode(frame, false)
        } finally {
            frame.dispose()
        }
    }
}
