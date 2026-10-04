package io.github.valerioisufi.pencilnotes

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.valerioisufi.drawview.core.platform.DRAWVIEW_DATA_DIRECTORY_PROPERTY
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.valerioisufi.drawview.lecture.LectureRecordings
import io.github.valerioisufi.drawview.lecture.ui.LectureExitDialog
import io.github.valerioisufi.drawview.ui.session.DrawViewSessions
import io.github.valerioisufi.pencilnotes.ui.theme.PencilNotesTheme
import java.io.File
import kotlinx.coroutines.runBlocking

fun main() {
    // PencilNotes keeps its notes apart from the other DrawView applications (~/.pencilnotes),
    // unless another directory is given with -Ddrawview.dataDir=...
    if (System.getProperty(DRAWVIEW_DATA_DIRECTORY_PROPERTY).isNullOrBlank()) {
        System.setProperty(DRAWVIEW_DATA_DIRECTORY_PROPERTY, File(System.getProperty("user.home"), ".pencilnotes").absolutePath)
    }

    application {
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
        Window(
            onCloseRequest = { if (LectureRecordings.isRecording) confirmExit = true else exit() },
            state = rememberWindowState(width = 1280.dp, height = 860.dp),
            title = "PencilNotes",
        ) {
            PencilNotesApp()
            if (confirmExit) {
                PencilNotesTheme {
                    LectureExitDialog(onExit = { exit() }, onDismiss = { confirmExit = false })
                }
            }
        }
    }
}
