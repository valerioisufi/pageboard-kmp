package io.github.valerioisufi.drawview.explorer

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.valerioisufi.drawview.data.DocumentStore
import io.github.valerioisufi.drawview.data.DrawViewStore
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
 * @param actions actions of the title bar of the root folder: the About & Licenses dialog by default.
 * @param fullScreenEditor hides the system bars while a document is open (Android).
 * @param editorExtension content contributed to the editor (top bar actions, side panel).
 */
@Composable
fun DocumentLibrary(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    store: DocumentStore = DrawViewStore.default,
    actions: @Composable RowScope.() -> Unit = { AboutLicensesButton() },
    fullScreenEditor: Boolean = false,
    editorExtension: EditorExtension? = null
) {
    val fileExplorerViewModel = viewModel { FileExplorerViewModel(store) }
    val editorViewModel = viewModel { DocumentEditorViewModel(store) }

    // The native TeX engine unpacks its fonts at the first start: off the main thread
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) { LatexEngine.initialize() }
    }

    val session = editorViewModel.session
    SystemBarsHidden(hidden = fullScreenEditor && session != null)
    if (session == null) {
        ExplorerScreen(
            fileExplorerViewModel = fileExplorerViewModel,
            onOpenDocument = { documentId -> editorViewModel.openDocument(documentId) },
            title = title,
            actions = actions,
            modifier = modifier
        )
    } else {
        EditorSessionScreen(
            session = session,
            onClose = { editorViewModel.closeSession() },
            modifier = modifier,
            extension = editorExtension
        )
    }
}
