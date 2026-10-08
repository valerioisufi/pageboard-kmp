package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.ui.generated.resources.*
import io.github.valerioisufi.drawview.ui.command.Command
import io.github.valerioisufi.drawview.ui.command.CommandTooltip
import io.github.valerioisufi.drawview.ui.screen.EditorCommands
import org.jetbrains.compose.resources.stringResource

/**
 * Floating contextual action bar of the lasso selection: cut, copy, delete and, for texts,
 * edit and their style (size, color, alignment: the controls of the formatting bar).
 *
 * @param commands the commands of the editor, whose tooltips the buttons show.
 */
@Composable
fun SelectionActionBar(
    controller: DrawViewController,
    modifier: Modifier = Modifier,
    commands: EditorCommands? = null
) {
    val selectionManager = controller.selectionManager
    val selection = selectionManager.currentSelection
    // Recomposes when the selected elements change (e.g. the color of the texts)
    @Suppress("UNUSED_VARIABLE")
    val version = selectionManager.selectionVersion

    AnimatedVisibility(
        visible = selection != null && controller.isDrawingMode,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -40 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -40 }),
        modifier = modifier
    ) {
        val current = selection
        val hasTexts = current?.texts?.isNotEmpty() == true
        val isSingleText = current?.isSingleText == true

        ElevatedCard(
            modifier = Modifier.padding(top = 24.dp),
            shape = RoundedCornerShape(50),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SelectionButton(commands?.cut, onClick = { selectionManager.cutSelection() }) {
                    Icon(
                        Icons.Default.ContentCut,
                        contentDescription = stringResource(Res.string.common_action_cut),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                SelectionButton(commands?.copy, onClick = { selectionManager.copySelection() }) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(Res.string.common_action_copy),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                SelectionButton(commands?.delete, onClick = { selectionManager.deleteSelection() }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(Res.string.common_action_delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }

                if (hasTexts) {
                    VerticalDivider(
                        modifier = Modifier
                            .height(24.dp)
                            .padding(horizontal = 4.dp),
                        thickness = 1.dp
                    )

                    if (isSingleText) {
                        IconButton(onClick = { controller.textEditorManager.startEditingSelectedText() }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(Res.string.draw_toolbar_action_edit_text),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Size, color and alignment of the selected texts, as in the editor
                    controller.selectionManager.selectedTexts?.let { texts ->
                        TextStyleControls(texts, commands)
                    }
                }
            }
        }
    }
}

/** A button of the selection bar, with the tooltip of [command] when given. */
@Composable
private fun SelectionButton(command: Command?, onClick: () -> Unit, icon: @Composable () -> Unit) {
    if (command == null) {
        IconButton(onClick = onClick) { icon() }
    } else {
        CommandTooltip(command) { IconButton(onClick = onClick) { icon() } }
    }
}

/**
 * Long-press context menu of the canvas: paste, page management (add after, delete, move) and
 * background of the page.
 *
 * @param commands the commands of the editor (the paste of the menu is theirs).
 * @param onEditPageBackground opens the template configurator of the targeted page.
 */
@Composable
fun CanvasContextMenu(
    controller: DrawViewController,
    commands: EditorCommands,
    onEditPageBackground: () -> Unit
) {
    val position = controller.contextMenuPosition
    if (position == null || !controller.isDrawingMode) return
    val pageManager = controller.pageManager

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(0.dp)
    ) {
        val screenWidth = constraints.maxWidth
        val screenHeight = constraints.maxHeight

        // Actual size of the menu, known once it has been laid out
        var menuSize by remember { mutableStateOf(IntSize.Zero) }

        // Ideal position from the touch, kept inside the canvas
        val idealX = position.x.toInt() - 100
        val idealY = position.y.toInt() - 150
        val safeX = idealX.coerceIn(0, (screenWidth - menuSize.width).coerceAtLeast(0))
        val safeY = idealY.coerceIn(0, (screenHeight - menuSize.height).coerceAtLeast(0))

        ElevatedCard(
            modifier = Modifier
                .onSizeChanged { size -> menuSize = size }
                .offset { IntOffset(safeX, safeY) },
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                // What was copied here or in another application, where the menu was opened
                if (remember(position) { commands.canPaste() }) {
                    IconButton(onClick = {
                        controller.contextMenuPosition = null
                        commands.pasteAt(position)
                    }) {
                        Icon(
                            Icons.Default.ContentPaste,
                            contentDescription = stringResource(Res.string.common_action_paste),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                IconButton(onClick = {
                    controller.contextMenuPosition = null
                    pageManager.addNewPageAfter(pageManager.contextMenuTargetPageIndex)
                }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(Res.string.context_menu_action_add_page))
                }
                IconButton(onClick = {
                    controller.contextMenuPosition = null
                    pageManager.deletePage(pageManager.contextMenuTargetPageIndex)
                }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(Res.string.context_menu_action_delete_page),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                IconButton(onClick = {
                    controller.contextMenuPosition = null
                    pageManager.startPageReorderMode()
                }) {
                    Icon(Icons.Default.SwapVert, contentDescription = stringResource(Res.string.context_menu_action_reorder_page))
                }

                IconButton(onClick = {
                    controller.contextMenuPosition = null
                    onEditPageBackground()
                }) {
                    Icon(
                        Icons.Default.Palette,
                        contentDescription = stringResource(Res.string.context_menu_action_edit_background),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
