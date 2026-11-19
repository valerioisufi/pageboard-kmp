package io.github.valerioisufi.pageboard.explorer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import io.github.valerioisufi.pageboard.explorer.FileExplorerViewModel
import io.github.valerioisufi.pageboard.explorer.FileExplorerViewModel.FileItem
import io.github.valerioisufi.pageboard.explorer.FileExplorerViewModel.FileType
import io.github.valerioisufi.pageboard.ui.adaptive.ShortcutModifier
import io.github.valerioisufi.pageboard.ui.adaptive.currentAdaptiveInfo
import io.github.valerioisufi.pageboard.ui.command.Command
import io.github.valerioisufi.pageboard.ui.command.CommandMenu
import io.github.valerioisufi.pageboard.ui.command.onSecondaryClick

/** The keys held at the last press of a pointer on an item (read by its click). */
private class HeldModifiers {
    var value: PointerKeyboardModifiers? = null
}

/**
 * What a pointer does on [item] of a list or a grid: a click opens it, or selects it in selection
 * mode; with the modifier of the shortcuts (⌘ or Ctrl) it is added to the selection, with Shift
 * the selection extends to it (as in the files of the systems); a long press selects it; a click
 * of the secondary button calls [onContextMenu] with where it happened.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.fileItemInteraction(
    item: FileItem,
    viewModel: FileExplorerViewModel,
    onOpenDocument: (String) -> Unit,
    onOpenDocumentInNewWindow: ((String) -> Unit)? = null,
    onContextMenu: (DpOffset) -> Unit
): Modifier {
    val held = remember { HeldModifiers() }
    val apple = currentAdaptiveInfo().conventions.shortcutModifier == ShortcutModifier.COMMAND
    val density = LocalDensity.current
    return this
        .onSecondaryClick { position -> with(density) { onContextMenu(DpOffset(position.x.toDp(), position.y.toDp())) } }
        .pointerInput(held) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press) held.value = event.keyboardModifiers
                }
            }
        }
        .combinedClickable(
            onClick = {
                val keys = held.value
                when {
                    keys != null && keys.isShiftPressed -> viewModel.extendSelection(item)
                    keys != null && (if (apple) keys.isMetaPressed else keys.isCtrlPressed) -> viewModel.toggleSelection(item)
                    keys != null && keys.isAltPressed && item.type == FileType.FILE && onOpenDocumentInNewWindow != null -> onOpenDocumentInNewWindow(item.id)
                    viewModel.selectionMode -> viewModel.toggleSelection(item)
                    item.type == FileType.FILE -> onOpenDocument(item.id)
                    else -> viewModel.enterFolder(item)
                }
            },
            onLongClick = { viewModel.toggleSelection(item) }
        )
}

/**
 * The context menu of an item ([commands]) opened at [position] (from the top left corner of the
 * item): put it at that corner, e.g. first in a box around the item.
 */
@Composable
internal fun FileContextMenu(commands: List<Command>, position: DpOffset?, onDismiss: () -> Unit) {
    Box {
        if (position != null) {
            CommandMenu(commands = commands, expanded = true, onDismissRequest = onDismiss, offset = position)
        }
    }
}
