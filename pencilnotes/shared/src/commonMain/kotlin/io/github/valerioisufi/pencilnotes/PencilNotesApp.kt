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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.canvas.controller.EditorClipboard
import io.github.valerioisufi.drawview.canvas.observe
import io.github.valerioisufi.drawview.canvas.controller.PageTopics
import androidx.compose.ui.platform.LocalFontFamilyResolver
import io.github.valerioisufi.drawview.agent.ui.CanvasPageEngine
import io.github.valerioisufi.drawview.canvas.render.DrawViewEngines
import io.github.valerioisufi.drawview.ui.render.ComposeTextTileAdapter
import io.github.valerioisufi.drawview.ui.theme.rememberNotesFonts
import io.github.valerioisufi.drawview.explorer.DocumentLibrary
import io.github.valerioisufi.drawview.explorer.DocumentThumbnails
import io.github.valerioisufi.drawview.explorer.rememberDocumentThumbnails
import io.github.valerioisufi.drawview.explorer.ui.aboutLibraryAction
import io.github.valerioisufi.drawview.export.ui.rememberDocumentExportUi
import io.github.valerioisufi.drawview.latex.LatexEngine
import io.github.valerioisufi.drawview.lecture.ui.LectureDialogs
import io.github.valerioisufi.drawview.lecture.ui.LectureRecordingIndicator
import io.github.valerioisufi.drawview.lecture.ui.documentTag
import io.github.valerioisufi.drawview.lecture.ui.editorExtension
import io.github.valerioisufi.drawview.lecture.ui.onlineAnswerBackend
import io.github.valerioisufi.drawview.lecture.ui.rememberLectureAssistant
import io.github.valerioisufi.drawview.lecture.ui.searchSource
import io.github.valerioisufi.drawview.ml.InstalledModels
import io.github.valerioisufi.drawview.ml.LoadedModels
import io.github.valerioisufi.drawview.ml.ModelDownloads
import io.github.valerioisufi.drawview.ml.litert.LiteRtRuntime
import io.github.valerioisufi.drawview.pdf.PdfiumEngine
import io.github.valerioisufi.drawview.search.MutableIndexingConditions
import io.github.valerioisufi.drawview.search.ui.ContentSearch
import io.github.valerioisufi.drawview.search.ui.rememberContentSearch
import io.github.valerioisufi.drawview.search.ui.settingsAction
import io.github.valerioisufi.drawview.sync.iroh.IrohInternet
import io.github.valerioisufi.drawview.sync.ui.CollaborationDialogs
import io.github.valerioisufi.drawview.sync.ui.InvitationLinks
import io.github.valerioisufi.drawview.sync.ui.editorExtension
import io.github.valerioisufi.drawview.sync.ui.joinAction
import io.github.valerioisufi.drawview.sync.ui.rememberCollaboration
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
import kotlinx.coroutines.flow.combine
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
        // the editor, the collaboration and the export
        val documents = PencilNotesData.documents
        // Lecture recording, followed by Gemini or a local model: it goes on in the background
        val lectureAssistant = rememberLectureAssistant()
        // The documents shared with the devices of the local network, with their lectures, and over
        // the internet (iroh) once the user turns it on in the sharing
        val collaboration = rememberCollaboration(documents, attachments = lectureAssistant.attachments, internet = IrohInternet)
        // A tablet uses the models of a computer of the local network (Ollama)
        LaunchedEffect(lectureAssistant, collaboration) { lectureAssistant.localModels.nearbyHosts = collaboration::nearbyHosts }
        // The documents exported as PDF or as the image of a page
        val export = rememberDocumentExportUi(documents, appName = "PencilNotes")
        // The PDF pages drawn by PDFium, the formulas by MicroTeX
        // (the search, made after the engines it reads the PDFs with, is given to them once made)
        val contentSearch = remember { mutableStateOf<ContentSearch?>(null) }
        val engines = remember {
            DrawViewEngines(
                pdfTiles = PdfiumEngine::createTileAdapter,
                formulas = LatexEngine.formulaRenderer,
                topics = PageTopics { documentId, text -> contentSearch.value?.engine?.pagesAbout(documentId, text).orEmpty() }
            )
        }
        AgentPages(agents, engines)
        // Search in the contents of the notes, and the local models (EmbeddingGemma 2, Gemma 4)
        val liteRtRuntime = remember { LiteRtRuntime() }
        val installedModels = remember { InstalledModels() }
        val modelDownloads = remember(installedModels) { ModelDownloads(installedModels) }
        val loadedModels = remember(liteRtRuntime) { LoadedModels(liteRtRuntime) }
        val indexingConditions = remember { MutableIndexingConditions(initialCanIndex = true) }
        LaunchedEffect(lectureAssistant, indexingConditions) {
            snapshotFlow { !lectureAssistant.isBusy }.collect { canIndex ->
                indexingConditions.setCanIndex(canIndex)
            }
        }
        val lectureSearchSource = remember(lectureAssistant, documents.store) {
            listOf(lectureAssistant.searchSource(documents.store))
        }
        val onlineAnswerBackend = remember(lectureAssistant) {
            lectureAssistant.onlineAnswerBackend()
        }
        val search = rememberContentSearch(
            store = documents.store,
            engines = engines,
            extraSources = lectureSearchSource,
            conditions = indexingConditions,
            loadedModels = loadedModels,
            installedModels = installedModels,
            downloads = modelDownloads,
            runtimeAvailable = liteRtRuntime.isAvailable,
            onlineBackend = onlineAnswerBackend,
            onOpenLecture = lectureAssistant::openLectureAt
        )
        // A text placed next to the pages it is about (the review of a lecture, a text imported)
        SideEffect {
            contentSearch.value = search
            agents?.contentSearch = search
        }
        // The documents with a lecture: their badge and a filter of the library
        val lectureTag = remember(lectureAssistant) { lectureAssistant.documentTag() }
        val editorExtension = remember(lectureAssistant, collaboration, export, agents) {
            val extension = lectureAssistant.editorExtension() + collaboration.editorExtension() + export.editorExtension
            agents?.let { extension + it.editorExtension } ?: extension
        }
        // The lectures of the documents deleted for good go with them (not of the ones in the trash)
        LaunchedEffect(lectureAssistant) {
            var known: Set<String>? = null
            combine(documents.store.documents, documents.store.trash) { summaries, trash ->
                summaries.mapTo(mutableSetOf()) { it.id } + trash.flatMap { it.documentIds }
            }.collect { ids ->
                known?.let { lectureAssistant.forgetDocuments(it - ids) }
                known = ids
            }
        }
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
                        actions = listOfNotNull(collaboration.joinAction(), search.settingsAction(), agents?.libraryAction, aboutLibraryAction()),
                        editorExtension = editorExtension,
                        itemsActions = listOf(export.libraryAction),
                        tags = listOf(lectureTag),
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
        val export = rememberDocumentExportUi(documents, appName = "PencilNotes")
        // (the search, made after the engines it reads the PDFs with, is given to them once made)
        val contentSearch = remember { mutableStateOf<ContentSearch?>(null) }
        val engines = remember {
            DrawViewEngines(
                pdfTiles = PdfiumEngine::createTileAdapter,
                formulas = LatexEngine.formulaRenderer,
                topics = PageTopics { documentId, text -> contentSearch.value?.engine?.pagesAbout(documentId, text).orEmpty() }
            )
        }
        AgentPages(agents, engines)
        val editorViewModel = viewModel(key = "doc-$documentId") {
            DocumentEditorViewModel(documents, engines, clipboard = editorClipboard).apply {
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
        CanvasPageEngine(ComposeTextTileAdapter(fontFamilyResolver, notesFonts, engines.formulas, engines.code), engines.pdfTiles, engines.elements)
    }
    SideEffect { agents.pageEngine = pages }
}
