package io.github.valerioisufi.drawview.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import io.github.valerioisufi.drawview.platform.drawViewDesktopDataDirectory
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.io.File
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext

/** The size, position and placement of a window, as kept between the launches of the application. */
internal data class SavedWindow(
    val width: Float,
    val height: Float,
    val x: Float?,
    val y: Float?,
    val placement: WindowPlacement
) {
    /**
     * Where the window opens on [screens]: where it was when its top left corner is still on one
     * of them (the title bar in reach), else where the system puts it (a screen unplugged).
     */
    fun positionOn(screens: List<Rectangle>): WindowPosition {
        val left = x ?: return WindowPosition.PlatformDefault
        val top = y ?: return WindowPosition.PlatformDefault
        val visible = screens.any { it.contains((left + 40).toInt(), (top + 10).toInt()) }
        return if (visible) WindowPosition(left.dp, top.dp) else WindowPosition.PlatformDefault
    }
}

/** Reads and writes a [SavedWindow] in [file] (a properties file). */
internal class WindowStateStore(private val file: File) {

    fun read(): SavedWindow? = try {
        val properties = Properties().apply { file.inputStream().use { load(it) } }
        SavedWindow(
            width = properties.getProperty("width")?.toFloatOrNull() ?: return null,
            height = properties.getProperty("height")?.toFloatOrNull() ?: return null,
            x = properties.getProperty("x")?.toFloatOrNull(),
            y = properties.getProperty("y")?.toFloatOrNull(),
            placement = properties.getProperty("placement")?.let { name -> WindowPlacement.entries.firstOrNull { it.name == name } }
                ?: WindowPlacement.Floating
        )
    } catch (_: Exception) {
        null
    }

    fun write(window: SavedWindow) {
        val properties = Properties()
        properties["width"] = window.width.toString()
        properties["height"] = window.height.toString()
        window.x?.let { properties["x"] = it.toString() }
        window.y?.let { properties["y"] = it.toString() }
        properties["placement"] = window.placement.name
        try {
            file.parentFile?.mkdirs()
            file.outputStream().use { properties.store(it, "The window of the application") }
        } catch (_: Exception) {
            // Kept for the next launch only: not worth an error
        }
    }
}

/**
 * The state of the window [name] of the application, kept between its launches in
 * `window-<name>.properties` of the data directory: its size, its position (left to the system
 * when no longer on a screen) and whether it is maximized or full screen. [defaultSize] at the
 * first launch.
 */
@OptIn(FlowPreview::class)
@Composable
public fun rememberDrawViewWindowState(name: String = "main", defaultSize: DpSize = DpSize(1280.dp, 860.dp)): WindowState {
    val store = remember(name) { WindowStateStore(File(drawViewDesktopDataDirectory, "window-$name.properties")) }
    val saved = remember(store) { store.read() }
    val state = rememberWindowState(
        placement = saved?.placement ?: WindowPlacement.Floating,
        position = saved?.positionOn(screens()) ?: WindowPosition.PlatformDefault,
        size = saved?.let { DpSize(it.width.dp, it.height.dp) } ?: defaultSize
    )
    LaunchedEffect(state, store) {
        // Maximized or full screen, the window keeps the size and the position it floated at
        var floating = saved
        snapshotFlow { state.toSaved() }
            .debounce(500)
            .collect { window ->
                val kept = if (window.placement == WindowPlacement.Floating) {
                    window.also { floating = it }
                } else {
                    floating?.copy(placement = window.placement) ?: window
                }
                withContext(Dispatchers.IO) { store.write(kept) }
            }
    }
    return state
}

/**
 * The smallest size of the window: the layouts of a phone fit in it, so that the window can be
 * narrow without cutting the bars.
 */
@Composable
public fun FrameWindowScope.WindowMinimumSize(width: Dp = 360.dp, height: Dp = 480.dp) {
    LaunchedEffect(window, width, height) {
        window.minimumSize = Dimension(width.value.toInt(), height.value.toInt())
    }
}

/** The size, position and placement of [WindowState]. */
private fun WindowState.toSaved(): SavedWindow {
    val position = position.takeIf { it.isSpecified }
    return SavedWindow(
        width = size.width.value,
        height = size.height.value,
        x = position?.x?.value,
        y = position?.y?.value,
        placement = placement
    )
}

/** The bounds of the screens of the computer, in the units of the windows. */
private fun screens(): List<Rectangle> = try {
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }
} catch (_: Exception) {
    emptyList()
}
