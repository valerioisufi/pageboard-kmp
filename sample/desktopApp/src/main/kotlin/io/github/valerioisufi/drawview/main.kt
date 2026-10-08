package io.github.valerioisufi.drawview

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.valerioisufi.drawview.ui.command.CommandHost
import io.github.valerioisufi.drawview.ui.command.DrawViewMenuBar
import io.github.valerioisufi.drawview.ui.session.DrawViewSessions
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
            state = rememberWindowState(width = 1280.dp, height = 860.dp),
            title = "DrawView",
            onKeyEvent = { commands.dispatch(it) }
        ) {
            DrawViewMenuBar(commands)
            App(commandHost = commands)
        }
    }
}
