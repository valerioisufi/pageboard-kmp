package io.github.valerioisufi.drawview.ui.window

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.awt.Frame
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowDecorTest {

    @Test
    fun defaultWindowDecorInsetsAreZero() {
        val insets = PaddingValues(0.dp)
        assertEquals(0.dp, insets.calculateStartPadding(LayoutDirection.Ltr))
    }

    @Test
    fun macOsWindowDecorInsetsProvideTrafficLightsClearance() {
        val insets = PaddingValues(start = 78.dp, top = 52.dp, end = 0.dp, bottom = 0.dp)
        assertEquals(78.dp, insets.calculateStartPadding(LayoutDirection.Ltr))
        assertEquals(78.dp, insets.calculateStartPadding(LayoutDirection.Rtl))
        assertEquals(78.dp, insets.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(0.dp, insets.calculateLeftPadding(LayoutDirection.Rtl))
        assertEquals(78.dp, insets.calculateRightPadding(LayoutDirection.Rtl))
        // The title bar of a toolbar, for a column under the traffic lights
        assertEquals(52.dp, insets.calculateTopPadding())
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

    @Test
    fun macWindowHelperIgnoresAMissingWindow() {
        // Must not throw, on macOS or elsewhere
        MacWindowHelper.useUnifiedToolbar(0L)
    }

    @Test
    fun macWindowGetsTheTitleBarOfAToolbar() {
        if (!System.getProperty("os.name").orEmpty().lowercase().contains("mac")) return
        val title = "WindowDecorTest-${System.nanoTime()}"
        val frame = Frame(title)
        try {
            // The peer of the window and its NSWindow, without showing it
            frame.pack()
            val window = onMainThread { nsWindow(title) }
            assertNotNull(window, "the NSWindow of the frame")
            val before = onMainThread { trafficLightsCenter(window) }

            MacWindowHelper.useUnifiedToolbar(Pointer.nativeValue(window))

            // The toolbar was set on the main thread: anywhere else, AppKit ends the process
            assertNotNull(onMainThread { ObjC.send(window, "toolbar") })
            assertEquals(3L, onMainThread { ObjC.sendLong(window, "toolbarStyle") }, "NSWindowToolbarStyleUnified")
            val after = onMainThread { trafficLightsCenter(window) }
            assertTrue(after > before, "the traffic lights go down to the middle of the toolbar: $before -> $after")
        } finally {
            frame.dispose()
        }
    }

    /** The NSWindow titled [title], among the windows of the application. */
    private fun nsWindow(title: String): Pointer? {
        val windows = ObjC.send(ObjC.send(ObjC.cls("NSApplication"), "sharedApplication"), "windows")
        return (0L until ObjC.sendLong(windows, "count")).asSequence()
            .mapNotNull { ObjC.send(windows, "objectAtIndex:", it) }
            .firstOrNull { ObjC.send(ObjC.send(it, "title"), "UTF8String")?.getString(0) == title }
    }

    /** The middle of the close button, in points from the top of the window, once laid out. */
    private fun trafficLightsCenter(window: Pointer): Double {
        val frameView = ObjC.send(ObjC.send(window, "contentView"), "superview")
        ObjC.sendVoid(frameView, "layoutSubtreeIfNeeded")
        val close = ObjC.send(window, "standardWindowButton:", 0L)
        val button = msgSendRect(close, "convertRect:toView:", msgSendRect(close, "bounds"), null)
        val height = msgSendRect(frameView, "bounds").height
        return height - button.y - button.height / 2
    }

    /** An `NSRect`, as a message answers it. */
    @Structure.FieldOrder("x", "y", "width", "height")
    class NSRect : Structure(), Structure.ByValue {
        @JvmField var x: Double = 0.0
        @JvmField var y: Double = 0.0
        @JvmField var width: Double = 0.0
        @JvmField var height: Double = 0.0
    }

    private fun msgSendRect(receiver: Pointer?, selector: String, vararg args: Any?): NSRect =
        NativeLibrary.getInstance("objc").getFunction("objc_msgSend")
            .invoke(NSRect::class.java, arrayOf(receiver, ObjC.sel(selector), *args)) as NSRect

    /** Runs [block] on the main thread of AppKit and waits for its result. */
    private fun <T> onMainThread(block: () -> T): T {
        val result = CompletableFuture<T>()
        ObjC.onMainThread {
            try {
                result.complete(block())
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        return result.get(10, TimeUnit.SECONDS)
    }
}
