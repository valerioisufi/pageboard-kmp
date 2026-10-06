package io.github.valerioisufi.drawview.explorer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.valerioisufi.drawview.data.DocumentStore
import io.github.valerioisufi.drawview.data.DrawViewStore
import io.github.valerioisufi.drawview.data.OpenDocuments
import io.github.valerioisufi.drawview.explorer.platform.SystemBarsHidden
import io.github.valerioisufi.drawview.explorer.ui.AboutLicensesButton
import io.github.valerioisufi.drawview.explorer.ui.ExplorerScreen
import io.github.valerioisufi.drawview.latex.LatexEngine
import io.github.valerioisufi.drawview.ui.session.DocumentEditorViewModel
import io.github.valerioisufi.drawview.ui.session.EditorExtension
import io.github.valerioisufi.drawview.ui.session.EditorSessionScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A complete note-taking application: the document library ([ExplorerScreen]) and the DrawView
 * editor of the open document ([EditorSessionScreen]). The theme is the one of the caller.
 *
 * @param title title shown in the root folder of the library (typically the application name).
 * @param documents the documents of [store] being changed, shared with the synchronization with
 *   the other devices (see `drawview-sync`).
 * @param actions actions of the title bar of the root folder: the About & Licenses dialog by default.
 * @param fullScreenEditor hides the system bars while a document is open (Android).
 * @param editorExtension content contributed to the editor (top bar actions, side panel).
 * @param libraryOverlay content drawn over the library (e.g. a recording in progress), given the
 *   function opening a document.
 */
@Composable
fun DocumentLibrary(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    store: DocumentStore = DrawViewStore.default,
    documents: OpenDocuments = if (store === DrawViewStore.default) DrawViewStore.documents else remember(store) { OpenDocuments(store) },
    actions: @Composable RowScope.() -> Unit = { AboutLicensesButton() },
    fullScreenEditor: Boolean = false,
    editorExtension: EditorExtension? = null,
    libraryOverlay: @Composable BoxScope.(openDocument: (String) -> Unit) -> Unit = {}
) {
    val fileExplorerViewModel = viewModel { FileExplorerViewModel(store, documents) }
    val editorViewModel = viewModel { DocumentEditorViewModel(store, documents) }

    // The native TeX engine unpacks its fonts at the first start: off the main thread
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) { LatexEngine.initialize() }
    }

    val session = editorViewModel.session
    SystemBarsHidden(hidden = fullScreenEditor && session != null)
    if (session == null) {
        Box(modifier = modifier) {
            ExplorerScreen(
                fileExplorerViewModel = fileExplorerViewModel,
                onOpenDocument = { documentId -> editorViewModel.openDocument(documentId) },
                title = title,
                actions = actions,
                modifier = Modifier.fillMaxSize()
            )
            libraryOverlay { documentId -> editorViewModel.openDocument(documentId) }
        }
    } else {
        EditorSessionScreen(
            session = session,
            onClose = { editorViewModel.closeSession() },
            modifier = modifier,
            extension = editorExtension
        )
    }
}
