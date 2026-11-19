package io.github.valerioisufi.drawview.ui.window

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import java.awt.Rectangle
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowStateTest {

    @Test
    fun theWindowIsKeptAndReadAgain() {
        val directory = Files.createTempDirectory("window").toFile()
        try {
            val store = WindowStateStore(directory.resolve("window-main.properties"))
            assertNull(store.read())
            val window = SavedWindow(width = 1100f, height = 760f, x = 120f, y = 80f, placement = WindowPlacement.Maximized)
            store.write(window)
            assertEquals(window, store.read())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aWindowNoLongerOnAScreenIsPlacedByTheSystem() {
        val screens = listOf(Rectangle(0, 0, 1440, 900))
        val window = SavedWindow(1000f, 700f, x = 100f, y = 50f, placement = WindowPlacement.Floating)
        assertEquals(WindowPosition(100.dp, 50.dp), window.positionOn(screens))
        // On a second screen unplugged since
        assertEquals(WindowPosition.PlatformDefault, window.copy(x = 2000f).positionOn(screens))
        assertEquals(WindowPosition.PlatformDefault, window.copy(x = null).positionOn(screens))
    }
}
