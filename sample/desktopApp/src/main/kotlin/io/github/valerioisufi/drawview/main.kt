package io.github.valerioisufi.drawview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.valerioisufi.drawview.ui.command.CommandHost
import io.github.valerioisufi.drawview.ui.command.DrawViewMenuBar
import io.github.valerioisufi.drawview.ui.window.DesktopAppIcon
import io.github.valerioisufi.drawview.ui.window.DrawViewWindowDecor
import io.github.valerioisufi.drawview.ui.window.SetWindowDocumentModified
import io.github.valerioisufi.drawview.ui.window.WindowMinimumSize
import io.github.valerioisufi.drawview.ui.window.rememberDesktopIcon
import io.github.valerioisufi.drawview.ui.window.rememberDesktopWindowManager
import io.github.valerioisufi.drawview.ui.window.rememberDrawViewWindowState
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
            val libraryWindowState = rememberDrawViewWindowState("library")
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
                title = "DrawView",
                icon = appIcon,
                onKeyEvent = { libraryCommands.dispatch(it) }
            ) {
                DrawViewWindowDecor(state = libraryWindowState) {
                    DrawViewMenuBar(libraryCommands)
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
                val docWindowState = rememberDrawViewWindowState("doc-$documentId")
                var docTitle by remember { mutableStateOf("DrawView") }
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
                    DrawViewWindowDecor(state = docWindowState) {
                        SetWindowDocumentModified(docModified)
                        DrawViewMenuBar(docCommands)
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
                            onTitleChange = { docTitle = "$it — DrawView" },
                            onModifiedChange = { docModified = it }
                        )
                    }
                }
            }
        }
    }
}

