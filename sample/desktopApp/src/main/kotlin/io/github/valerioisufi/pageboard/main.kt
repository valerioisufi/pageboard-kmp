package io.github.valerioisufi.pageboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.valerioisufi.pageboard.ui.command.CommandHost
import io.github.valerioisufi.pageboard.ui.command.PageboardMenuBar
import io.github.valerioisufi.pageboard.ui.window.DesktopAppIcon
import io.github.valerioisufi.pageboard.ui.window.PageboardWindowDecor
import io.github.valerioisufi.pageboard.ui.window.SetWindowDocumentModified
import io.github.valerioisufi.pageboard.ui.window.WindowMinimumSize
import io.github.valerioisufi.pageboard.ui.window.rememberDesktopIcon
import io.github.valerioisufi.pageboard.ui.window.rememberDesktopWindowManager
import io.github.valerioisufi.pageboard.ui.window.rememberPageboardWindowState
import kotlinx.coroutines.runBlocking

fun main() {
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

        // The primary library window
        if (windowManager.isLibraryOpen) {
            val libraryCommands = remember { CommandHost() }
            val libraryWindowState = rememberPageboardWindowState("library")
            Window(
                onCloseRequest = {
                    windowManager.closeLibrary()
                    if (windowManager.shouldExit) {
                        SampleData.sessions.saveWriting()
                        runBlocking { SampleData.sessions.flushAll() }
                        exitApplication()
                    }
                },
                state = libraryWindowState,
                title = "Pageboard",
                icon = appIcon,
                onKeyEvent = { libraryCommands.dispatch(it) }
            ) {
                PageboardWindowDecor(state = libraryWindowState) {
                    PageboardMenuBar(libraryCommands)
                    WindowMinimumSize()
                    App(
                        commandHost = libraryCommands,
                        onOpenDocumentInNewWindow = { docId -> windowManager.openDocument(docId) }
                    )
                }
            }
        }

        // Secondary windows: one for each document open independently
        for (documentId in windowManager.openDocumentIds) {
            key(documentId) {
                val docCommands = remember { CommandHost() }
                val docWindowState = rememberPageboardWindowState("doc-$documentId")
                var docTitle by remember { mutableStateOf("Pageboard") }
                var docModified by remember { mutableStateOf(false) }

                Window(
                    onCloseRequest = {
                        windowManager.closeDocument(documentId)
                        if (windowManager.shouldExit) {
                            SampleData.sessions.saveWriting()
                            runBlocking { SampleData.sessions.flushAll() }
                            exitApplication()
                        }
                    },
                    state = docWindowState,
                    title = docTitle,
                    icon = appIcon,
                    onKeyEvent = { docCommands.dispatch(it) }
                ) {
                    PageboardWindowDecor(state = docWindowState) {
                        SetWindowDocumentModified(docModified)
                        PageboardMenuBar(docCommands)
                        WindowMinimumSize()
                        SampleDocumentWindow(
                            documentId = documentId,
                            onClose = {
                                windowManager.closeDocument(documentId)
                                if (windowManager.shouldExit) {
                                    SampleData.sessions.saveWriting()
                                    runBlocking { SampleData.sessions.flushAll() }
                                    exitApplication()
                                }
                            },
                            commandHost = docCommands,
                            onTitleChange = { docTitle = "$it — Pageboard" },
                            onModifiedChange = { docModified = it }
                        )
                    }
                }
            }
        }
    }
}

