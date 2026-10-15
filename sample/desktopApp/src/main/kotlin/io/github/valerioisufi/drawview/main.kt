package io.github.valerioisufi.drawview

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.valerioisufi.drawview.session.DrawViewSessions
import io.github.valerioisufi.drawview.ui.command.CommandHost
import io.github.valerioisufi.drawview.ui.command.DrawViewMenuBar
import io.github.valerioisufi.drawview.ui.window.WindowMinimumSize
import io.github.valerioisufi.drawview.ui.window.rememberDrawViewWindowState
import kotlinx.coroutines.runBlocking

fun main() {
    // The menus in the bar of the system on macOS
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    application {
        // The commands of the screen shown: the menu bar and the shortcuts of the window
        val commands = remember { CommandHost() }
        Window(
            onCloseRequest = {
                // The pending changes of the open document are written before exiting
                runBlocking { DrawViewSessions.flushAll() }
                exitApplication()
            },
            // Its size, position and placement kept between the launches
            state = rememberDrawViewWindowState(),
            title = "DrawView",
            onKeyEvent = { commands.dispatch(it) }
        ) {
            DrawViewMenuBar(commands)
            WindowMinimumSize()
            App(commandHost = commands)
        }
    }
}
