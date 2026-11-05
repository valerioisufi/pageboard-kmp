package io.github.valerioisufi.drawview.explorer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.valerioisufi.drawview.canvas.controller.EditorClipboard
import io.github.valerioisufi.drawview.canvas.controller.MediaManager
import io.github.valerioisufi.drawview.canvas.render.DrawViewEngines
import io.github.valerioisufi.drawview.canvas.render.tile.TextFace
import io.github.valerioisufi.drawview.explorer.ui.ExplorerScreen
import io.github.valerioisufi.drawview.explorer.ui.aboutLibraryAction
import io.github.valerioisufi.drawview.explorer.ui.rememberLibraryGridState
import io.github.valerioisufi.drawview.session.DocumentEditorViewModel
import io.github.valerioisufi.drawview.session.EditorSessionScreen
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveRoot
import io.github.valerioisufi.drawview.ui.command.CommandRoot
import io.github.valerioisufi.drawview.ui.extension.DocumentTag
import io.github.valerioisufi.drawview.ui.extension.EditorExtension
import io.github.valerioisufi.drawview.ui.extension.LibraryItemsAction
import io.github.valerioisufi.drawview.ui.extension.LibraryAction
import io.github.valerioisufi.drawview.ui.platform.PickedFile
import io.github.valerioisufi.drawview.ui.platform.PickerType
import io.github.valerioisufi.drawview.ui.platform.rememberFilePicker
import io.github.valerioisufi.drawview.ui.render.LocalDrawViewEngines
import io.github.valerioisufi.drawview.ui.theme.NotesFontFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A complete note-taking application: the document library ([ExplorerScreen]) and the DrawView
 * editor of the open document ([EditorSessionScreen]). The theme is the one of the caller.
 *
 * @param title the brand of the application (its mark and its name, or its name), at the top of
 *   the library.
 * @param documents the documents of the library (see [OpenDocuments.store]) and those being
 *   changed, shared with the synchronization with the other devices (see `drawview-sync`): one
 *   per store in an application (e.g. `DrawViewStore.documents` of `drawview-store-room`).
 * @param actions actions of the application in the library (its bar or its navigation pane): the
 *   About & Licenses dialog by default.
 * @param fullScreenEditor not used any more: the editor gives itself the room of the system bars
 *   where it gains some (see `EditorScreen`). Kept so that the callers still build.
 * @param editorExtension content contributed to the editor (top bar actions, side panel).
 * @param libraryOverlay content drawn over the library (e.g. a recording in progress), given the
 *   function opening a document.
 * @param itemsActions actions of the application on the items of the library (e.g. their
 *   export): in the bar and the pane of the selection, the menus of the items, the menu of the
 *   folder shown, the menus of the window and their shortcuts.
 * @param tags what the application says of the documents (e.g. that they have a lecture): their
 *   badges and the filters of the library.
 * @param engines the engines drawing the PDF pages and the formulas (e.g. `PdfiumEngine` and
 *   `LatexEngine` of `drawview-pdf` and `drawview-latex`), in the editor, the thumbnails and what
 *   the application composes in the library ([LocalDrawViewEngines]); the formula renderer is
 *   prepared with the fonts of the notes.
 * @param thumbnails the thumbnails of the documents (the first pages of the grid of a wider
 *   window): in memory by default; [DocumentThumbnails.defaultDirectory] keeps those of a store
 *   that outlives the application.
 * @param clipboard what is copied and pasted in the editor: the one of the application, shared
 *   with its other windows, so that what is copied in a document is pasted in another one (by
 *   default, the editor of this library only).
 */
@Composable
public fun DocumentLibrary(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    documents: OpenDocuments,
    actions: List<LibraryAction> = listOf(aboutLibraryAction()),
    fullScreenEditor: Boolean = false,
    editorExtension: EditorExtension? = null,
    libraryOverlay: @Composable BoxScope.(openDocument: (String) -> Unit) -> Unit = {},
    itemsActions: List<LibraryItemsAction> = emptyList(),
    tags: List<DocumentTag> = emptyList(),
    engines: DrawViewEngines = DrawViewEngines.None,
    thumbnails: DocumentThumbnails = rememberDocumentThumbnails(documents, engines = engines),
    search: LibrarySearch? = null,
    onOpenDocumentInNewWindow: ((String) -> Unit)? = null,
    clipboard: EditorClipboard = remember { EditorClipboard() }
) {
    val fileExplorerViewModel = viewModel { FileExplorerViewModel(documents) }
    // What has been in the trash too long is deleted for good
    val clock = LocalLibraryClock.current
    LaunchedEffect(documents) { TrashKeeper(documents.store, clock).run() }
    val editorViewModel = viewModel { DocumentEditorViewModel(documents, engines, clipboard = clipboard) }

    // The formula engine may unpack its fonts at the first start: off the main thread. The text
    // of the formulas (\text{}) is in the font of the notes, as their text
    LaunchedEffect(engines) {
        val formulas = engines.formulas ?: return@LaunchedEffect
        withContext(Dispatchers.Default) {
            val faces = listOf(TextFace.REGULAR, TextFace.BOLD, TextFace.ITALIC, TextFace.BOLD_ITALIC)
            formulas.prepare(faces.associateWith { NotesFontFiles.bytes(it) })
        }
    }

    val session = editorViewModel.session
    // Outside the branch of the library: back from a note, the list is where it was
    val gridState = rememberLibraryGridState(fileExplorerViewModel)
    // The library and the editor adapt to the window, the input and the platform; their commands
    // reach the shortcuts and the menu bar of the application (see CommandRoot)
    CompositionLocalProvider(LocalDrawViewEngines provides engines) {
        AdaptiveRoot(modifier = modifier) {
            CommandRoot {
                if (session == null) {
                    // A PDF or an image dropped from the system or picked: a note of it, in the folder shown
                    val importFiles: (List<PickedFile>) -> Boolean = { files ->
                        val imported = files.filter { it.extension in MediaManager.IMPORTED_EXTENSIONS }
                        val first = imported.firstOrNull()
                        if (first != null) {
                            val name = fileExplorerViewModel.availableName(first.name.substringBeforeLast('.'))
                            editorViewModel.createAndOpenDocument(name, fileExplorerViewModel.currentFolder?.id) { controller ->
                                for (file in imported) controller.mediaManager.importFile(file.bytes, file.extension)
                            }
                        }
                        first != null
                    }
                    val pdfPicker = rememberFilePicker(PickerType.PDF) { file -> importFiles(listOf(file)) }
                    val imagePicker = rememberFilePicker(PickerType.IMAGE) { file -> importFiles(listOf(file)) }
                    Box {
                        ExplorerScreen(
                            fileExplorerViewModel = fileExplorerViewModel,
                            onOpenDocument = { documentId -> editorViewModel.openDocument(documentId) },
                            onOpenDocumentWithPlace = DocumentOpener { documentId, then ->
                                editorViewModel.openDocument(documentId, then)
                            },
                            title = title,
                            actions = actions,
                            itemsActions = itemsActions,
                            tags = tags,
                            thumbnails = thumbnails,
                            search = search,
                            onFilesDropped = importFiles,
                            onImportPdf = pdfPicker,
                            onImportImage = imagePicker,
                            onOpenDocumentInNewWindow = onOpenDocumentInNewWindow,
                            gridState = gridState,
                            modifier = Modifier.fillMaxSize()
                        )
                        libraryOverlay { documentId -> editorViewModel.openDocument(documentId) }
                    }
                } else {
                    EditorSessionScreen(
                        session = session,
                        onClose = { editorViewModel.closeSession() },
                        extension = editorExtension
                    )
                }
            }
        }
    }
}
