package io.github.valerioisufi.drawview.ui.window

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DesktopAppIconTest {

    @Test
    fun setDockIconNullDoesNotThrow() {
        DesktopAppIcon.setDockIcon(null)
    }

    @Test
    fun setDockIconImageDoesNotThrow() {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        DesktopAppIcon.setDockIcon(image)
    }

    @Test
    fun loadIconImageReturnsNullForMissingResource() {
        assertNull(DesktopAppIcon.loadIconImage("/icons/missing_icon.png"))
    }

    @Test
    fun loadIconPainterReturnsNullForMissingResource() {
        assertNull(DesktopAppIcon.loadIconPainter("/icons/missing_icon.png"))
    }
}
