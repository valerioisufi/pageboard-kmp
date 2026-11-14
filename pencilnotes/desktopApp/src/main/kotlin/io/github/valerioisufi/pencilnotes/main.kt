package io.github.valerioisufi.pencilnotes

import androidx.compose.runtime.LaunchedEffect
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
import javax.swing.JOptionPane
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    // PencilNotes keeps its notes apart from the other DrawView applications (~/.pencilnotes),
    // unless another directory is given with -Ddrawview.dataDir=...
    if (System.getProperty(DRAWVIEW_DATA_DIRECTORY_PROPERTY).isNullOrBlank()) {
        System.setProperty(DRAWVIEW_DATA_DIRECTORY_PROPERTY, File(System.getProperty("user.home"), ".pencilnotes").absolutePath)
    }
    val dataDirectory = File(System.getProperty(DRAWVIEW_DATA_DIRECTORY_PROPERTY))

    // A command of the terminal: a client of the application, before anything of the interface starts
    if (args.firstOrNull() in AgentCli.COMMANDS) exitProcess(AgentCli(dataDirectory).run(args.toList()))

    // One process on the database of a directory (see docs/agent-tools.md), to the end of main
    val directoryLock = DataDirectoryLock.acquire(dataDirectory)
    if (directoryLock == null) {
        JOptionPane.showMessageDialog(
            null,
            "PencilNotes is already running on these notes.",
            "PencilNotes",
            JOptionPane.INFORMATION_MESSAGE
        )
        exitProcess(1)
    }

    // The menus in the bar of the system on macOS
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    // The native parts of the windows on macOS (title bar, menus, dialogs) light or dark as the
    // system, like the theme: the JDK keeps them light otherwise. Read once, before AWT starts
    System.setProperty("apple.awt.application.appearance", "system")

    // The application icon in the macOS Dock
    DesktopAppIcon.setDockIcon(DesktopAppIcon.loadIconImage("/icons/icon_mac.png"))

    directoryLock.use {
        // The external models on this machine, while the user keeps their access on
        val agents = PencilNotesAgents(
            readFile = { path, maxBytes -> File(path).takeIf { it.isFile && it.length() <= maxBytes }?.readBytes() },
            journal = FileAgentJournal(dataDirectory)
        )
        // The proposals still waiting and what the models changed, back before a client or a window asks
        runBlocking { agents.agents.restore() }
        val host = AgentHost(dataDirectory, agents.agents.access, agents.endpoint)
        host.start()
        try {
            runApplication(agents)
        } finally {
            host.stop()
        }
    }
}

/** The windows of PencilNotes, until the last one is closed. */
private fun runApplication(agents: PencilNotesAgents) {
    application {
        val windowManager = rememberDesktopWindowManager()
        val appIcon = rememberDesktopIcon("/icons/icon.png")
        var confirmExit by remember { mutableStateOf(false) }

        // A proposal for a document in no editor: its review opens it
        LaunchedEffect(agents) {
            agents.reviewRequests.collect { windowManager.openDocument(it) }
        }

        fun exit() {
            // The texts being written, saved in their documents
            PencilNotesData.sessions.saveWriting()
            runBlocking {
                // The lecture being recorded is saved (its review is left for later), then the
                // pending changes of the open note
                LectureRecordings.finishAll()
                PencilNotesData.sessions.flushAll()
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
                        },
                        agents = agents
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
                var docModified by remember { mutableStateOf(false) }

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
                        SetWindowDocumentModified(docModified)
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
                            onTitleChange = { docTitle = it },
                            onModifiedChange = { docModified = it },
                            agents = agents
                        )
                    }
                }
            }
        }
    }
}

