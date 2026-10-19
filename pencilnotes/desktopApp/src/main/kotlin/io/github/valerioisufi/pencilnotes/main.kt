package io.github.valerioisufi.pencilnotes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.valerioisufi.drawview.lecture.LectureRecordings
import io.github.valerioisufi.drawview.lecture.ui.LectureExitDialog
import io.github.valerioisufi.drawview.platform.DRAWVIEW_DATA_DIRECTORY_PROPERTY
import io.github.valerioisufi.drawview.session.DrawViewSessions
import io.github.valerioisufi.drawview.ui.command.CommandHost
import io.github.valerioisufi.drawview.ui.command.DrawViewMenuBar
import io.github.valerioisufi.drawview.ui.window.DrawViewWindowDecor
import io.github.valerioisufi.drawview.ui.window.WindowMinimumSize
import io.github.valerioisufi.drawview.ui.window.rememberDrawViewWindowState
import io.github.valerioisufi.pencilnotes.ui.theme.PencilNotesTheme
import java.io.File
import kotlinx.coroutines.runBlocking

fun main() {
    // PencilNotes keeps its notes apart from the other DrawView applications (~/.pencilnotes),
    // unless another directory is given with -Ddrawview.dataDir=...
    if (System.getProperty(DRAWVIEW_DATA_DIRECTORY_PROPERTY).isNullOrBlank()) {
        System.setProperty(DRAWVIEW_DATA_DIRECTORY_PROPERTY, File(System.getProperty("user.home"), ".pencilnotes").absolutePath)
    }

    // The menus in the bar of the system on macOS
    System.setProperty("apple.laf.useScreenMenuBar", "true")

    application {
        // The commands of the screen shown: the menu bar and the shortcuts of the window
        val commands = remember { CommandHost() }
        var confirmExit by remember { mutableStateOf(false) }
        fun exit() {
            runBlocking {
                // The lecture being recorded is saved (its review is left for later), then the
                // pending changes of the open note
                LectureRecordings.finishAll()
                DrawViewSessions.flushAll()
            }
            exitApplication()
        }
        val windowState = rememberDrawViewWindowState()
        Window(
            onCloseRequest = { if (LectureRecordings.isRecording) confirmExit = true else exit() },
            // Its size, position and placement kept between the launches
            state = windowState,
            title = "PencilNotes",
            onKeyEvent = { commands.dispatch(it) }
        ) {
            DrawViewWindowDecor(state = windowState) {
                DrawViewMenuBar(commands)
                WindowMinimumSize()
                PencilNotesApp(commandHost = commands)
                if (confirmExit) {
                    PencilNotesTheme {
                        LectureExitDialog(onExit = { exit() }, onDismiss = { confirmExit = false })
                    }
                }
            }
        }
    }
}
