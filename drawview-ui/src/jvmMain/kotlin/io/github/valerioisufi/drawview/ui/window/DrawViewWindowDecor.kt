package io.github.valerioisufi.drawview.ui.window

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.Window

/**
 * Applies Windows Desktop Window Manager (DWM) attributes, such as immersive dark mode.
 */
internal object WindowsDwmHelper {
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1 = 19
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20

    fun setDarkMode(window: Window, dark: Boolean) {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        if (!os.contains("win")) return

        try {
            val nativeClass = Class.forName("com.sun.jna.Native")
            val getWindowPointerMethod = nativeClass.getMethod("getWindowPointer", Window::class.java)
            val hwnd = getWindowPointerMethod.invoke(null, window) as? Long ?: return
            if (hwnd == 0L) return

            val nativeLibraryClass = Class.forName("com.sun.jna.NativeLibrary")
            val getInstanceMethod = nativeLibraryClass.getMethod("getInstance", String::class.java)
            val dwmapi = getInstanceMethod.invoke(null, "dwmapi")
            val getFunctionMethod = nativeLibraryClass.getMethod("getFunction", String::class.java)
            val dwmSetWindowAttribute = getFunctionMethod.invoke(dwmapi, "DwmSetWindowAttribute")
            val invokeIntMethod = dwmSetWindowAttribute.javaClass.getMethod("invokeInt", Array<Any>::class.java)

            val darkModeValue = IntArray(1) { if (dark) 1 else 0 }
            val res = invokeIntMethod.invoke(
                dwmSetWindowAttribute,
                arrayOf<Any>(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, darkModeValue, 4)
            ) as? Int ?: -1
            if (res != 0) {
                invokeIntMethod.invoke(
                    dwmSetWindowAttribute,
                    arrayOf<Any>(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1, darkModeValue, 4)
                )
            }
        } catch (_: Throwable) {
            // Silently degrade if JNA or dwmapi is unavailable
        }
    }
}

/**
 * Configures the platform window chrome (macOS unified title bar with full window content,
 * Windows DWM dark mode title bar) and provides [LocalWindowDecorInsets] and [LocalWindowDraggableArea].
 *
 * @param state the [WindowState] of the window, used to detect fullscreen placement.
 * @param isDark whether the active theme is dark, used to synchronize the native title bar on Windows.
 * @param content the window content composable.
 */
@Composable
public fun FrameWindowScope.DrawViewWindowDecor(
    state: WindowState,
    isDark: Boolean = false,
    content: @Composable () -> Unit
) {
    val os = remember { System.getProperty("os.name").orEmpty().lowercase() }
    val isMac = remember(os) { os.contains("mac") || os.contains("darwin") }
    val isWindows = remember(os) { os.contains("win") }

    LaunchedEffect(window) {
        if (isMac) {
            window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
            window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
            window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
        }
    }

    LaunchedEffect(window, isDark) {
        if (isWindows) {
            WindowsDwmHelper.setDarkMode(window, isDark)
        }
    }

    val isFullscreen = state.placement == WindowPlacement.Fullscreen
    val insets = remember(isMac, isFullscreen) {
        if (isMac && !isFullscreen) {
            PaddingValues(start = 78.dp, top = 0.dp, end = 0.dp, bottom = 0.dp)
        } else {
            PaddingValues(0.dp)
        }
    }

    val draggableArea: @Composable (Modifier, @Composable () -> Unit) -> Unit = { modifier, areaContent ->
        WindowDraggableArea(modifier = modifier) {
            areaContent()
        }
    }

    CompositionLocalProvider(
        LocalWindowDecorInsets provides insets,
        LocalWindowDraggableArea provides draggableArea
    ) {
        content()
    }
}

/**
 * Sets the document modified state of the window on macOS, showing a dot in the close button.
 */
@Composable
public fun FrameWindowScope.SetWindowDocumentModified(modified: Boolean) {
    LaunchedEffect(window, modified) {
        window.rootPane.putClientProperty("Window.documentModified", modified)
    }
}
