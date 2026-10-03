package io.github.valerioisufi.drawview

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.valerioisufi.drawview.ui.session.DrawViewSessions
import kotlinx.coroutines.runBlocking

fun main() = application {
    Window(
        onCloseRequest = {
            // The pending changes of the open document are written before exiting
            runBlocking { DrawViewSessions.flushAll() }
            exitApplication()
        },
        state = rememberWindowState(width = 1280.dp, height = 860.dp),
        title = "DrawView",
    ) {
        App()
    }
}
