package io.github.valerioisufi.drawview.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveRoot
import io.github.valerioisufi.drawview.ui.command.CommandRoot
import io.github.valerioisufi.drawview.ui.extension.EditorContext
import io.github.valerioisufi.drawview.ui.extension.EditorExtension

/**
 * The editor of the document of [context] with the contributions of [extension] (top bar actions,
 * panels, commands, overlays): the [DrawScreen], adapted to the window, the input and the
 * platform, its commands in the shortcuts and the menu bar. [onClose] is called by the back
 * button of the editor and by the system back; [location] is shown under the title (e.g. the
 * folder of the document). The editor takes the room of the system bars where it gains some
 * (the status bar stays beside a notch or an island).
 *
 * Whoever keeps the document creates its editor; `EditorSessionScreen` of `drawview-session`
 * does it for the documents of a store.
 */
@Composable
public fun EditorScreen(
    context: EditorContext,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    location: String? = null,
    extension: EditorExtension? = null
) {
    val controller = context.controller
    // The editor adapts to the window, the input and the platform, and its commands reach the
    // shortcuts and the menu bar (also outside of a library)
    AdaptiveRoot(modifier = modifier) {
        CommandRoot {
            EditorBackHandler(controller, onClose = onClose)
            val extensionCommands = remember(extension, context) { extension?.commands?.invoke(context).orEmpty() }
            val extensionPanels = remember(extension, context) { extension?.panels?.invoke(context).orEmpty() }
            val extensionPageGridActions = remember(extension, context) { extension?.pageGridActions?.invoke(context).orEmpty() }
            DrawScreen(
                controller = controller,
                location = location,
                onNavigateBack = onClose,
                toolbarActions = { extension?.toolbarActions?.invoke(this, context) },
                panels = extensionPanels,
                canvasOverlay = { extension?.canvasOverlay?.invoke(this, context) },
                extraCommands = extensionCommands,
                pageGridActions = extensionPageGridActions,
                readOnlyNote = { extension?.readOnlyNote?.invoke(context) }
            )
        }
    }
}

/**
 * System back (Android back gesture, Escape on the desktop, ...): cancels a pending placement,
 * leaves the selection of the pages, closes the page grid, the text editor (saving the text), the context menu, the selected text
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
                controller.isPageGridVisible && controller.pageManager.selection.isActive -> controller.pageManager.selection.clear()
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
