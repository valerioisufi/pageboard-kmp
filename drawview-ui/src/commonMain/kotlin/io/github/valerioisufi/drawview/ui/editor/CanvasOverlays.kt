package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.ui.adaptive.currentAdaptiveInfo
import io.github.valerioisufi.drawview.ui.command.MenuDivider
import io.github.valerioisufi.drawview.ui.command.MenuItem
import io.github.valerioisufi.drawview.ui.generated.resources.*
import io.github.valerioisufi.drawview.ui.screen.EditorCommands
import io.github.valerioisufi.drawview.ui.theme.DrawViewElevation
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import io.github.valerioisufi.drawview.ui.theme.elevation
import org.jetbrains.compose.resources.stringResource

/**
 * Floating contextual action bar of the lasso selection: cut, copy, delete and, for texts,
 * edit and their style (size, color, alignment: the controls of the formatting bar).
 *
 * @param commands the commands of the editor, whose tooltips the buttons show.
 */
@Composable
internal fun SelectionActionBar(
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

        ActionBar(modifier = Modifier.padding(top = 16.dp)) {
            if (hasTexts && isSingleText) {
                BarAction(DrawViewIcons.Pencil, stringResource(Res.string.draw_toolbar_action_edit_text), { controller.textEditorManager.startEditingSelectedText() })
                BarDivider()
            }
            BarAction(DrawViewIcons.Scissors, stringResource(Res.string.common_action_cut), { selectionManager.cutSelection() }, command = commands?.cut)
            BarAction(DrawViewIcons.Copy, stringResource(Res.string.common_action_copy), { selectionManager.copySelection() }, command = commands?.copy)
            BarAction(DrawViewIcons.Trash, stringResource(Res.string.common_action_delete), { selectionManager.deleteSelection() }, command = commands?.delete, tint = MaterialTheme.colorScheme.error)
            if (hasTexts) {
                BarDivider()
                // Size, color and alignment of the selected texts, as in the editor
                controller.selectionManager.selectedTexts?.let { texts ->
                    TextStyleControls(texts, commands)
                }
            }
        }
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
internal fun CanvasContextMenu(
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

        val shape = RoundedCornerShape(16.dp)
        Column(
            modifier = Modifier
                .onSizeChanged { size -> menuSize = size }
                .offset { IntOffset(safeX, safeY) }
                .width(IntrinsicSize.Max)
                .elevation(DrawViewElevation.Level3, shape)
                .background(MaterialTheme.colorScheme.surfaceContainer, shape)
                .padding(8.dp)
        ) {
            // What was copied here or in another application, where the menu was opened
            if (remember(position) { commands.canPaste() }) {
                MenuItem(
                    text = stringResource(Res.string.common_action_paste),
                    icon = rememberVectorPainter(DrawViewIcons.Clipboard),
                    shortcut = commands.paste.shortcutLabel(currentAdaptiveInfo().conventions).takeIf { currentAdaptiveInfo().input.hasKeyboard },
                    onClick = {
                        controller.contextMenuPosition = null
                        commands.pasteAt(position)
                    }
                )
                MenuDivider()
            }
            MenuItem(
                text = stringResource(Res.string.context_menu_action_add_page_after),
                icon = rememberVectorPainter(DrawViewIcons.FilePlus2),
                onClick = {
                    controller.contextMenuPosition = null
                    pageManager.addNewPageAfter(pageManager.contextMenuTargetPageIndex)
                }
            )
            MenuItem(
                text = stringResource(Res.string.context_menu_action_reorder_page),
                icon = rememberVectorPainter(DrawViewIcons.Grip),
                onClick = {
                    controller.contextMenuPosition = null
                    pageManager.startPageReorderMode()
                }
            )
            MenuItem(
                text = stringResource(Res.string.context_menu_action_delete_page),
                icon = rememberVectorPainter(DrawViewIcons.Trash),
                destructive = true,
                onClick = {
                    controller.contextMenuPosition = null
                    pageManager.deletePage(pageManager.contextMenuTargetPageIndex)
                }
            )
            MenuDivider()
            MenuItem(
                text = stringResource(Res.string.context_menu_action_edit_background),
                icon = rememberVectorPainter(DrawViewIcons.Grid),
                onClick = {
                    controller.contextMenuPosition = null
                    onEditPageBackground()
                }
            )
        }
    }
}
