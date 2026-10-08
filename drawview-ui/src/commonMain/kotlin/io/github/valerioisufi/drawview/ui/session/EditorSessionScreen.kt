package io.github.valerioisufi.drawview.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveRoot
import io.github.valerioisufi.drawview.ui.command.CommandRoot
import io.github.valerioisufi.drawview.ui.screen.DrawScreen

/**
 * The editor of an open document: a progress bar while the document is being loaded, then the
 * [DrawScreen]. [onClose] is called by the back button of the editor and by the system back.
 *
 * @param extension content contributed to the editor by the application (top bar actions, panels,
 *   commands, overlays).
 */
@Composable
fun EditorSessionScreen(
    session: EditorSession,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    extension: EditorExtension? = null
) {
    val controller = session.controller
    // The editor adapts to the window, the input and the platform, and its commands reach the
    // shortcuts and the menu bar (also outside of a library)
    AdaptiveRoot(modifier = modifier) {
        CommandRoot {
            if (controller == null) {
                // The document is being loaded
                Box(
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
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
                EditorBackHandler(controller, onClose = onClose)
                val extensionCommands = remember(extension, session, controller) {
                    extension?.commands?.invoke(session, controller).orEmpty()
                }
                val extensionPanels = remember(extension, session, controller) {
                    extension?.panels?.invoke(session, controller).orEmpty()
                }
                DrawScreen(
                    controller = controller,
                    onNavigateBack = onClose,
                    toolbarActions = { extension?.toolbarActions?.invoke(this, session, controller) },
                    panels = extensionPanels,
                    canvasOverlay = { extension?.canvasOverlay?.invoke(this, session, controller) },
                    extraCommands = extensionCommands
                )
            }
        }
    }
}

/**
 * System back (Android back gesture, Escape on the desktop, ...): cancels a pending placement,
 * closes the page grid, the text editor (saving the text), the context menu, the selected text
 * of the PDFs or the selection, then the document.
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
                controller.placementManager.isActive -> controller.placementManager.cancel()
                controller.isPageGridVisible -> controller.togglePageGrid()
                controller.textEditorManager.isEditing -> controller.textEditorManager.finishEditing()
                controller.contextMenuPosition != null -> controller.contextMenuPosition = null
                controller.textMarkupManager.hasSelection -> controller.textMarkupManager.clearSelection()
                controller.selectionManager.currentSelection != null -> controller.selectionManager.clearSelection()
                else -> onClose()
            }
        }
    )
}
