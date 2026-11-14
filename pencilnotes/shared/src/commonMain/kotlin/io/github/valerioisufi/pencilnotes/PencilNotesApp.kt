package io.github.valerioisufi.pencilnotes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.canvas.controller.EditorClipboard
import io.github.valerioisufi.drawview.canvas.observe
import androidx.compose.ui.platform.LocalFontFamilyResolver
import io.github.valerioisufi.drawview.agent.ui.CanvasPageEngine
import io.github.valerioisufi.drawview.canvas.render.DrawViewEngines
import io.github.valerioisufi.drawview.ui.render.ComposeTextTileAdapter
import io.github.valerioisufi.drawview.ui.theme.rememberNotesFonts
import io.github.valerioisufi.drawview.explorer.DocumentLibrary
import io.github.valerioisufi.drawview.explorer.DocumentThumbnails
import io.github.valerioisufi.drawview.explorer.rememberDocumentThumbnails
import io.github.valerioisufi.drawview.explorer.ui.aboutLibraryAction
import io.github.valerioisufi.drawview.lecture.ui.LectureDialogs
import io.github.valerioisufi.drawview.lecture.ui.LectureRecordingIndicator
import io.github.valerioisufi.drawview.lecture.ui.LectureMicrophone
import io.github.valerioisufi.drawview.search.ui.settingsAction
import io.github.valerioisufi.drawview.sync.ui.CollaborationDialogs
import io.github.valerioisufi.drawview.sync.ui.InvitationLinks
import io.github.valerioisufi.drawview.sync.ui.joinAction
import io.github.valerioisufi.drawview.sync.ui.CollaborationAccess
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveRoot
import io.github.valerioisufi.drawview.ui.adaptive.currentAdaptiveInfo
import io.github.valerioisufi.drawview.ui.command.CommandHost
import io.github.valerioisufi.drawview.ui.command.CommandRoot
import io.github.valerioisufi.drawview.ui.components.AboutAppInfo
import io.github.valerioisufi.drawview.ui.components.LocalAboutAppInfo
import io.github.valerioisufi.drawview.ui.components.ThirdPartyLicense
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.valerioisufi.drawview.session.DocumentEditorViewModel
import io.github.valerioisufi.drawview.session.EditorSessionScreen
import io.github.valerioisufi.pencilnotes.generated.resources.Res
import io.github.valerioisufi.pencilnotes.generated.resources.about_description
import io.github.valerioisufi.pencilnotes.generated.resources.about_version
import io.github.valerioisufi.pencilnotes.generated.resources.app_name
import io.github.valerioisufi.pencilnotes.ui.theme.PencilNotesTheme
import io.github.valerioisufi.pencilnotes.ui.theme.cherryBombOneFontFamily
import org.jetbrains.compose.resources.stringResource

/**
 * What is copied and pasted in the editors of PencilNotes: one for the process, shared by the editors
 * of all its documents and windows, so that what is copied in a document is pasted in another one
 * (it outlives the activities of Android, recreated on a rotation).
 */
private val editorClipboard = EditorClipboard()

/**
 * PencilNotes: the DrawView document library with the PencilNotes identity.
 *
 * @param commandHost the commands of the screen shown, for the menu bar and the shortcuts of the
 *   window of the desktop.
 * @param onOpenDocumentInNewWindow opens the document in an independent desktop window when provided.
 * @param agents the external models, on a platform that lets their clients in.
 */
@Composable
fun PencilNotesApp(
    commandHost: CommandHost = remember { CommandHost() },
    onOpenDocumentInNewWindow: ((String) -> Unit)? = null,
    agents: PencilNotesAgents? = null
) {
    PencilNotesTheme {
        val aboutAppInfo = AboutAppInfo(
            name = stringResource(Res.string.app_name),
            version = stringResource(Res.string.about_version),
            description = stringResource(Res.string.about_description),
            additionalLicenses = listOf(CherryBombOneLicense),
            mark = { PencilNotesMark(size = 64.dp) },
            nameFontFamily = cherryBombOneFontFamily()
        )
        // The documents of PencilNotes, in its data directory: those open are shared by the library,
        // the editors, the collaboration and the export
        val documents = PencilNotesData.documents
        // What works on them, shared by the windows
        val services = PencilNotesServices
        val lectureAssistant = services.lectureAssistant
        val collaboration = services.collaboration
        val export = services.export
        val search = services.search
        val engines = services.engines
        LectureMicrophone(lectureAssistant)
        CollaborationAccess(collaboration)
        AgentPages(agents, engines)
        SideEffect { agents?.contentSearch = search }
        val editorExtension = remember(agents) { services.editorExtension(agents) }
        // The dialogs of the application adapt like the library, and its commands reach the window:
        // one root for all of them
        AdaptiveRoot(modifier = Modifier.fillMaxSize()) {
            CommandRoot(host = commandHost) {
                CompositionLocalProvider(LocalAboutAppInfo provides aboutAppInfo) {
                    CollaborationDialogs(collaboration)
                    LectureDialogs(lectureAssistant)
                    DocumentLibrary(
                        title = { PencilNotesBrand() },
                        documents = documents,
                        sessions = PencilNotesData.sessions,
                        actions = listOfNotNull(collaboration.joinAction(), search.settingsAction(), agents?.libraryAction, aboutLibraryAction()),
                        editorExtension = editorExtension,
                        itemsActions = listOf(export.libraryAction),
                        tags = listOf(services.lectureTag),
                        engines = engines,
                        thumbnails = rememberDocumentThumbnails(documents, DocumentThumbnails.defaultDirectory(), engines),
                        search = search,
                        libraryOverlay = { openDocument ->
                            LectureRecordingIndicator(
                                assistant = lectureAssistant,
                                onOpenDocument = openDocument,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(16.dp)
                            )
                            // The exports going on, above the button of a new note on the phones
                            export.StatusHost(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(start = 16.dp, end = 16.dp, bottom = if (currentAdaptiveInfo().window.isCompact) 88.dp else 16.dp)
                            )
                        },
                        onOpenDocumentInNewWindow = onOpenDocumentInNewWindow,
                        clipboard = editorClipboard,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/** The mark (a pencil on the primary color) and the wordmark of PencilNotes, in Cherry Bomb One. */
@Composable
private fun PencilNotesBrand() {
    val name = stringResource(Res.string.app_name)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PencilNotesMark(size = 40.dp)
        Text(
            text = name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 28.sp, lineHeight = 34.sp),
            fontFamily = cherryBombOneFontFamily(),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** The mark of PencilNotes: a pencil on the primary color. */
@Composable
private fun PencilNotesMark(size: Dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(size * 14 / 40))
    ) {
        Icon(DrawViewIcons.Pencil, contentDescription = null, tint = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(size * 22 / 40))
    }
}

/** Opens the invitation [link] to a shared document (a `drawview://join` link opened on the device). */
fun openInvitation(link: String) = InvitationLinks.open(link)

/** The font of the PencilNotes title, listed by the About & Licenses dialog. */
private val CherryBombOneLicense = ThirdPartyLicense(
    name = "Cherry Bomb One",
    license = "SIL OFL 1.1",
    copyright = "Copyright © 2019 The Cherry Bomb Project Authors (https://github.com/satsuyako/CherryBomb)",
    purpose = "Typography of the PencilNotes title.",
    notice = "Cherry Bomb One is licensed under the SIL Open Font License, Version 1.1. This license is available with a FAQ at: https://openfontlicense.org"
)

/**
 * A standalone document editor window for PencilNotes (multi-window desktop mode).
 */
@Composable
fun PencilNotesDocumentWindow(
    documentId: String,
    onClose: () -> Unit,
    commandHost: CommandHost = remember { CommandHost() },
    onTitleChange: (String) -> Unit = {},
    onModifiedChange: (Boolean) -> Unit = {},
    agents: PencilNotesAgents? = null
) {
    PencilNotesTheme {
        val documents = PencilNotesData.documents
        val export = PencilNotesServices.export
        val engines = PencilNotesServices.engines
        AgentPages(agents, engines)
        val editorViewModel = viewModel(key = "doc-$documentId") {
            DocumentEditorViewModel(documents, PencilNotesData.sessions, engines, clipboard = editorClipboard).apply {
                openDocument(documentId)
            }
        }
        val session = editorViewModel.session
        // The title of the window, renamed with the document (from its title, the library or
        // another device)
        val title = session?.controller?.document?.observe { title }?.value
        LaunchedEffect(title) {
            title?.let(onTitleChange)
        }
        // Whether the document has changes to undo, for the frame of the window
        val modified = session?.controller?.historyManager?.canUndo == true
        LaunchedEffect(modified) { onModifiedChange(modified) }
        AdaptiveRoot(modifier = Modifier.fillMaxSize()) {
            CommandRoot(host = commandHost) {
                if (session != null) {
                    EditorSessionScreen(
                        session = session,
                        onClose = onClose,
                        extension = agents?.let { export.editorExtension + it.editorExtension } ?: export.editorExtension
                    )
                }
            }
        }
    }
}

/**
 * Gives the external models the pages of the documents in no editor, laid out and drawn with the
 * fonts of this window, as an editor would (see `docs/agent-ergonomics.md`).
 */
@Composable
private fun AgentPages(agents: PencilNotesAgents?, engines: DrawViewEngines) {
    agents ?: return
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val notesFonts = rememberNotesFonts()
    val pages = remember(fontFamilyResolver, notesFonts, engines) {
        CanvasPageEngine(ComposeTextTileAdapter(fontFamilyResolver, notesFonts, engines.formulas, engines.code), engines.pdfTiles, engines.elements, engines.images)
    }
    SideEffect { agents.pageEngine = pages }
}
