package io.github.valerioisufi.pencilnotes

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.pageboard.canvas.controller.EditorClipboard
import io.github.valerioisufi.pageboard.canvas.observe
import androidx.compose.ui.platform.LocalFontFamilyResolver
import io.github.valerioisufi.pageboard.agent.ui.CanvasPageEngine
import io.github.valerioisufi.pageboard.canvas.render.PageboardEngines
import io.github.valerioisufi.pageboard.ui.render.ComposeTextTileAdapter
import io.github.valerioisufi.pageboard.export.ExportWriters
import io.github.valerioisufi.pageboard.export.PageImageExporter
import io.github.valerioisufi.pageboard.export.PdfExporter
import io.github.valerioisufi.pageboard.ui.theme.NotesFontFiles
import io.github.valerioisufi.pageboard.ui.theme.rememberNotesFonts
import io.github.valerioisufi.pageboard.explorer.DocumentLibrary
import io.github.valerioisufi.pageboard.explorer.DocumentThumbnails
import io.github.valerioisufi.pageboard.explorer.rememberDocumentThumbnails
import io.github.valerioisufi.pageboard.explorer.ui.aboutLibraryAction
import io.github.valerioisufi.pageboard.lecture.ui.LectureDialogs
import io.github.valerioisufi.pageboard.lecture.ui.LectureRecordingIndicator
import io.github.valerioisufi.pageboard.lecture.ui.LectureMicrophone
import io.github.valerioisufi.pageboard.search.ui.settingsAction
import io.github.valerioisufi.pageboard.sync.ui.CollaborationDialogs
import io.github.valerioisufi.pageboard.sync.ui.InvitationLinks
import io.github.valerioisufi.pageboard.sync.ui.joinAction
import io.github.valerioisufi.pageboard.sync.ui.shareAction
import io.github.valerioisufi.pageboard.sync.ui.CollaborationAccess
import io.github.valerioisufi.pageboard.ui.adaptive.AdaptiveRoot
import io.github.valerioisufi.pageboard.ui.adaptive.currentAdaptiveInfo
import io.github.valerioisufi.pageboard.ui.command.CommandHost
import io.github.valerioisufi.pageboard.ui.command.CommandRoot
import io.github.valerioisufi.pageboard.ui.components.AboutAppInfo
import io.github.valerioisufi.pageboard.ui.components.LocalAboutAppInfo
import io.github.valerioisufi.pageboard.ui.components.ThirdPartyLicense
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.valerioisufi.pageboard.session.DocumentEditorViewModel
import io.github.valerioisufi.pageboard.session.EditorSessionScreen
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
 * PencilNotes: the Pageboard document library with the PencilNotes identity.
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
    // The documents of PencilNotes, in its data directory: those open are shared by the library,
    // the editors, the collaboration and the export; what works on them is shared by the windows
    val documents = PencilNotesData.documents
    val services = PencilNotesServices
    PencilNotesWindow(commandHost, agents, showsApplicationDialogs = true) {
        val aboutAppInfo = AboutAppInfo(
            name = stringResource(Res.string.app_name),
            version = stringResource(Res.string.about_version),
            description = stringResource(Res.string.about_description),
            additionalLicenses = listOf(CherryBombOneLicense),
            mark = { PencilNotesMark(size = 64.dp) },
            nameFontFamily = cherryBombOneFontFamily()
        )
        val editorExtension = remember(agents) { services.editorExtension(agents) }
        CompositionLocalProvider(LocalAboutAppInfo provides aboutAppInfo) {
            DocumentLibrary(
                title = { PencilNotesBrand() },
                documents = documents,
                sessions = PencilNotesData.sessions,
                actions = listOfNotNull(services.collaboration.joinAction(), services.search.settingsAction(), services.export.importAction, agents?.libraryAction, aboutLibraryAction()),
                editorExtension = editorExtension,
                itemsActions = listOf(services.export.libraryAction, services.collaboration.shareAction()),
                tags = listOf(services.lectureTag),
                engines = services.engines,
                thumbnails = rememberDocumentThumbnails(documents, DocumentThumbnails.defaultDirectory(), services.engines),
                search = services.search,
                libraryOverlay = { openDocument ->
                    LectureRecordingIndicator(
                        assistant = services.lectureAssistant,
                        onOpenDocument = openDocument,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                    )
                    // The exports going on, above the button of a new note on the phones
                    services.export.StatusHost(
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

/**
 * A window of PencilNotes showing [content]: its theme, its dialogs and commands (one adaptive
 * root, the commands reaching the window through [commandHost]), and the services of the
 * application bound to it (the microphone and the local network asked from it, the pages of the
 * documents given to the external models [agents]). The dialogs of the application (the
 * invitations, the join requests, the lectures received) are shown by one window only, the one
 * [showsApplicationDialogs].
 */
@Composable
private fun PencilNotesWindow(
    commandHost: CommandHost,
    agents: PencilNotesAgents?,
    showsApplicationDialogs: Boolean,
    content: @Composable () -> Unit
) {
    val services = PencilNotesServices
    PencilNotesTheme {
        LectureMicrophone(services.lectureAssistant)
        CollaborationAccess(services.collaboration)
        AgentPages(agents, services.engines)
        SideEffect {
            agents?.contentSearch = services.search
            agents?.lectureAssistant = services.lectureAssistant
            agents?.collaboration = services.collaboration
        }
        AdaptiveRoot(modifier = Modifier.fillMaxSize()) {
            CommandRoot(host = commandHost) {
                if (showsApplicationDialogs) {
                    CollaborationDialogs(services.collaboration)
                    LectureDialogs(services.lectureAssistant)
                }
                content()
            }
        }
    }
}

/** The mark (the icon of the application) and the wordmark of PencilNotes, in Cherry Bomb One. */
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

/**
 * The mark of PencilNotes: its icon, the P of the wordmark underlined by a stroke, in the colors
 * of the brand whatever the theme.
 */
@Composable
private fun PencilNotesMark(size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 9 / 40))
            .background(BrandGreen)
    ) {
        Image(PencilNotesLogo, contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}

private val BrandGreen = Color(0xFF3E691A)

/** The P and its underline, on the canvas of the icon of the application. */
private val PencilNotesLogo: ImageVector by lazy {
    ImageVector.Builder("PencilNotesLogo", 24.dp, 24.dp, 1024f, 1024f)
        .addPath(addPathNodes(PencilNotesLogoP), fill = SolidColor(Color(0xFFBDF292)))
        .addPath(addPathNodes(PencilNotesLogoLine), fill = SolidColor(Color(0xFFEBE865)))
        .build()
}

/** Opens the invitation [link] to a shared document (a `pageboard://join` link opened on the device). */
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
 * A document of PencilNotes in a window of its own (a window of the desktop, a task of Android),
 * with the editor of the library: the lectures, the sharing, the export, the external models
 * [agents], and the texts placed next to the pages they are about.
 *
 * @param showsApplicationDialogs whether this window shows the dialogs of the application (on
 *   the desktop, when the library is closed and this is the first document window).
 */
@Composable
fun PencilNotesDocumentWindow(
    documentId: String,
    onClose: () -> Unit,
    commandHost: CommandHost = remember { CommandHost() },
    onTitleChange: (String) -> Unit = {},
    onModifiedChange: (Boolean) -> Unit = {},
    agents: PencilNotesAgents? = null,
    showsApplicationDialogs: Boolean = false
) {
    val services = PencilNotesServices
    val editorViewModel = viewModel(key = "doc-$documentId") {
        DocumentEditorViewModel(PencilNotesData.documents, PencilNotesData.sessions, services.engines, clipboard = editorClipboard).apply {
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
    PencilNotesWindow(commandHost, agents, showsApplicationDialogs) {
        val extension = remember(agents) { services.editorExtension(agents) }
        if (session != null) {
            EditorSessionScreen(session = session, onClose = onClose, extension = extension)
        }
    }
}

/**
 * Gives the external models the pages of the documents in no editor, laid out and drawn with the
 * fonts of this window, as an editor would (see `docs/plans/agent-ergonomics.md`).
 */
@Composable
private fun AgentPages(agents: PencilNotesAgents?, engines: PageboardEngines) {
    agents ?: return
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val notesFonts = rememberNotesFonts()
    val textAdapter = remember(fontFamilyResolver, notesFonts, engines) {
        ComposeTextTileAdapter(fontFamilyResolver, notesFonts, engines.formulas, engines.code)
    }
    val pages = remember(textAdapter, engines) { CanvasPageEngine(textAdapter, engines.pdfTiles, engines.elements, engines.images) }
    // The exports of the models are drawn as the export sheet draws them
    val writers = remember(textAdapter, engines) {
        ExportWriters(
            pdf = PdfExporter(textAdapter, NotesFontFiles::bytesOrNull, creator = "PencilNotes", elements = engines.elements, formulas = engines.formulas),
            image = PageImageExporter(textAdapter, pdfTiles = engines.pdfTiles, elements = engines.elements, images = engines.images)
        )
    }
    SideEffect {
        agents.pageEngine = pages
        agents.exportWriters = writers
    }
}
