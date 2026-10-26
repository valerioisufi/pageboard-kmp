package io.github.valerioisufi.pencilnotes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import io.github.valerioisufi.drawview.ui.window.DesktopAppIcon
import io.github.valerioisufi.drawview.ui.window.DrawViewWindowDecor
import io.github.valerioisufi.drawview.ui.window.SetWindowDocumentModified
import io.github.valerioisufi.drawview.ui.window.WindowMinimumSize
import io.github.valerioisufi.drawview.ui.window.rememberDesktopIcon
import io.github.valerioisufi.drawview.ui.window.rememberDesktopWindowManager
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
    // The native parts of the windows on macOS (title bar, menus, dialogs) light or dark as the
    // system, like the theme: the JDK keeps them light otherwise. Read once, before AWT starts
    System.setProperty("apple.awt.application.appearance", "system")

    // The application icon in the macOS Dock
    DesktopAppIcon.setDockIcon(DesktopAppIcon.loadIconImage("/icons/icon_mac.png"))

    application {
        val windowManager = rememberDesktopWindowManager()
        val appIcon = rememberDesktopIcon("/icons/icon.png")
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

        // 1. Primary Library Window
        if (windowManager.isLibraryOpen) {
            val commands = remember { CommandHost() }
            val windowState = rememberDrawViewWindowState("library")
            Window(
                onCloseRequest = {
                    if (windowManager.openDocumentIds.isNotEmpty()) {
                        windowManager.closeLibrary()
                    } else if (LectureRecordings.isRecording) {
                        confirmExit = true
                    } else {
                        exit()
                    }
                },
                state = windowState,
                title = "PencilNotes",
                icon = appIcon,
                onKeyEvent = { commands.dispatch(it) }
            ) {
                DrawViewWindowDecor(state = windowState) {
                    DrawViewMenuBar(commands)
                    WindowMinimumSize()
                    PencilNotesApp(
                        commandHost = commands,
                        onOpenDocumentInNewWindow = { docId ->
                            windowManager.openDocument(docId)
                        }
                    )
                    if (confirmExit) {
                        PencilNotesTheme {
                            LectureExitDialog(onExit = { exit() }, onDismiss = { confirmExit = false })
                        }
                    }
                }
            }
        }

        // 2. Secondary Document Windows
        for (documentId in windowManager.openDocumentIds) {
            key(documentId) {
                val docCommands = remember { CommandHost() }
                val docWindowState = rememberDrawViewWindowState("doc-$documentId")
                var docTitle by remember { mutableStateOf("PencilNotes") }
                val session = remember(documentId) { DrawViewSessions.find(documentId) }

                Window(
                    onCloseRequest = {
                        windowManager.closeDocument(documentId)
                        if (windowManager.shouldExit) {
                            if (LectureRecordings.isRecording) confirmExit = true else exit()
                        }
                    },
                    state = docWindowState,
                    title = docTitle,
                    icon = appIcon,
                    onKeyEvent = { docCommands.dispatch(it) }
                ) {
                    DrawViewWindowDecor(state = docWindowState) {
                        SetWindowDocumentModified(session?.controller?.historyManager?.canUndo == true)
                        DrawViewMenuBar(docCommands)
                        WindowMinimumSize()
                        PencilNotesDocumentWindow(
                            documentId = documentId,
                            onClose = {
                                windowManager.closeDocument(documentId)
                                if (windowManager.shouldExit) {
                                    if (LectureRecordings.isRecording) confirmExit = true else exit()
                                }
                            },
                            commandHost = docCommands,
                            onTitleChange = { docTitle = it }
                        )
                    }
                }
            }
        }
    }
}

