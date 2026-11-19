package io.github.valerioisufi.pageboard.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Image
import java.awt.Taskbar
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Manages desktop application icons across operating systems: sets the macOS Dock icon at runtime
 * and loads window icons for Compose [androidx.compose.ui.window.Window].
 */
public object DesktopAppIcon {

    /**
     * Sets the macOS application Dock icon to [image].
     *
     * Safe to invoke on any platform or in headless environments: silently degrades if the desktop
     * taskbar or the icon image feature is unsupported by the host operating system.
     */
    public fun setDockIcon(image: Image?) {
        if (image == null) return
        try {
            if (Taskbar.isTaskbarSupported()) {
                val taskbar = Taskbar.getTaskbar()
                if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    taskbar.iconImage = image
                }
            }
        } catch (_: Throwable) {
            // Silently degrade when headless or running in a restricted sandbox
        }
    }

    /**
     * Loads a [BufferedImage] from the given classpath [resourcePath].
     *
     * Returns `null` if the resource is not found or fails to decode.
     */
    public fun loadIconImage(resourcePath: String): BufferedImage? {
        return try {
            DesktopAppIcon::class.java.getResourceAsStream(resourcePath)?.use { stream ->
                ImageIO.read(stream)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Loads an icon [Painter] from the given classpath [resourcePath].
     *
     * Returns `null` if the resource is not found or fails to decode.
     */
    public fun loadIconPainter(resourcePath: String): Painter? {
        val image = loadIconImage(resourcePath) ?: return null
        return BitmapPainter(image.toComposeImageBitmap())
    }
}

/**
 * Remembers an icon [Painter] loaded from the classpath [resourcePath].
 */
@Composable
public fun rememberDesktopIcon(resourcePath: String): Painter? {
    return remember(resourcePath) {
        DesktopAppIcon.loadIconPainter(resourcePath)
    }
}
