package io.github.valerioisufi.pencilnotes

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.valerioisufi.drawview.core.platform.DRAWVIEW_DATA_DIRECTORY_PROPERTY
import io.github.valerioisufi.drawview.ui.session.DrawViewSessions
import java.io.File
import kotlinx.coroutines.runBlocking

fun main() {
    // PencilNotes keeps its notes apart from the other DrawView applications (~/.pencilnotes),
    // unless another directory is given with -Ddrawview.dataDir=...
    if (System.getProperty(DRAWVIEW_DATA_DIRECTORY_PROPERTY).isNullOrBlank()) {
        System.setProperty(DRAWVIEW_DATA_DIRECTORY_PROPERTY, File(System.getProperty("user.home"), ".pencilnotes").absolutePath)
    }

    application {
        Window(
            onCloseRequest = {
                // The pending changes of the open note are written before exiting
                runBlocking { DrawViewSessions.flushAll() }
                exitApplication()
            },
            state = rememberWindowState(width = 1280.dp, height = 860.dp),
            title = "PencilNotes",
        ) {
            PencilNotesApp()
        }
    }
}
