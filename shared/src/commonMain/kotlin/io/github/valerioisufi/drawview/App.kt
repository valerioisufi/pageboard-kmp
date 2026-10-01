package io.github.valerioisufi.drawview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.data.DrawViewStore
import io.github.valerioisufi.drawview.latex.LatexEngine
import io.github.valerioisufi.drawview.ui.generated.resources.Res
import io.github.valerioisufi.drawview.ui.generated.resources.document_default_name
import io.github.valerioisufi.drawview.ui.screen.DocumentListScreen
import io.github.valerioisufi.drawview.ui.screen.DrawScreen
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * DrawView application: the list of the documents and the editor of the open document.
 */
@Composable
fun App() {
    DrawViewTheme {
        val appViewModel = viewModel { DrawViewAppViewModel(DrawViewStore.default) }

        // The native TeX engine unpacks its fonts at the first start: off the main thread
        LaunchedEffect(Unit) {
            withContext(Dispatchers.Default) { LatexEngine.initialize() }
        }

        val session = appViewModel.session
        if (session == null) {
            val documents by appViewModel.store.documents.collectAsState(initial = emptyList())
            val defaultName = stringResource(Res.string.document_default_name)
            DocumentListScreen(
                documents = documents,
                onDocumentClick = { id -> appViewModel.openDocument(id) },
                onCreateNewClick = { appViewModel.createAndOpenDocument(defaultName) },
                onDeleteDocument = { document -> appViewModel.deleteDocument(document.id) },
                onRenameDocument = { document, newName -> appViewModel.renameDocument(document.id, newName) },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val controller = session.controller
            if (controller == null) {
                // The document is being loaded
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            } else {
                EditorBackHandler(controller, onClose = { appViewModel.closeSession() })
                DrawScreen(
                    controller = controller,
                    onNavigateBack = { appViewModel.closeSession() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * System back (Android back gesture, Escape on the desktop, ...): closes the page grid, the text
 * editor (saving the text), the selection or the context menu, then the document.
 */
@Composable
private fun EditorBackHandler(controller: DrawViewController, onClose: () -> Unit) {
    if (LocalNavigationEventDispatcherOwner.current == null) return
    val state = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = state,
        isBackEnabled = true,
        onBackCompleted = {
            when {
                controller.isPageGridVisible -> controller.togglePageGrid()
                controller.textEditorManager.isEditing -> controller.textEditorManager.requestFinishEditing()
                controller.contextMenuPosition != null -> controller.contextMenuPosition = null
                controller.selectionManager.currentSelection != null -> controller.selectionManager.clearSelection()
                else -> onClose()
            }
        }
    )
}
