package io.github.valerioisufi.pageboard.ui.editor

import io.github.valerioisufi.pageboard.ui.command.Command
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.model.shape.ShapeElement
import io.github.valerioisufi.pageboard.ui.adaptive.AdaptiveDialog
import io.github.valerioisufi.pageboard.ui.adaptive.currentAdaptiveInfo
import io.github.valerioisufi.pageboard.ui.command.CommandMenu
import io.github.valerioisufi.pageboard.ui.command.MenuDivider
import io.github.valerioisufi.pageboard.ui.command.MenuItem
import io.github.valerioisufi.pageboard.ui.components.ToolPopover
import io.github.valerioisufi.pageboard.ui.generated.resources.*
import io.github.valerioisufi.pageboard.ui.generated.resources.shape_style
import io.github.valerioisufi.pageboard.ui.screen.EditorCommands
import io.github.valerioisufi.pageboard.ui.screen.SelectedShapesTarget
import io.github.valerioisufi.pageboard.ui.screen.ShapeSettings
import io.github.valerioisufi.pageboard.ui.theme.PageboardElevation
import io.github.valerioisufi.pageboard.ui.theme.PageboardIcons
import io.github.valerioisufi.pageboard.ui.theme.elevation
import org.jetbrains.compose.resources.stringResource

/**
 * Floating contextual action bar of the lasso selection: cut, copy, delete, the menu arranging
 * it in the stack of its page and, for texts, edit and their style (size, color, alignment: the
 * controls of the formatting bar).
 *
 * @param commands the commands of the editor, whose tooltips the buttons show.
 */
@Composable
internal fun SelectionActionBar(
    controller: PageboardController,
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
        val selectedShapes = controller.shapeEditing.selectedShapes
        val singleShape = if (selectedShapes.size == 1) selectedShapes[0] else null
        var editingLabel by remember(singleShape?.first?.id) { mutableStateOf(false) }

        ActionBar(modifier = Modifier.padding(top = 16.dp)) {
            if (hasTexts && isSingleText) {
                BarAction(PageboardIcons.Pencil, stringResource(Res.string.draw_toolbar_action_edit_text), { controller.textEditorManager.startEditingSelectedText() })
                BarDivider()
            }
            if (controller.shapeEditing.canConvertSelectedStroke) {
                BarAction(PageboardIcons.Shapes, stringResource(Res.string.shape_action_convert), { controller.shapeEditing.convertSelectedStrokeToShape() })
                BarDivider()
            }
            BarAction(PageboardIcons.Scissors, stringResource(Res.string.common_action_cut), { selectionManager.cutSelection() }, command = commands?.cut)
            BarAction(PageboardIcons.Copy, stringResource(Res.string.common_action_copy), { selectionManager.copySelection() }, command = commands?.copy)
            BarAction(PageboardIcons.Trash, stringResource(Res.string.common_action_delete), { selectionManager.deleteSelection() }, command = commands?.delete, tint = MaterialTheme.colorScheme.error)
            if (commands != null) {
                BarDivider()
                ArrangeAction(commands)
                BarAction(PageboardIcons.Lock, stringResource(Res.string.command_lock_selection), { selectionManager.lockSelection(true) }, command = commands.lockSelection)
            }
            if (selectedShapes.isNotEmpty()) {
                BarDivider()
                ShapeStyleAction(controller)
                if (singleShape != null) {
                    BarAction(PageboardIcons.Text, stringResource(Res.string.shape_action_label), { editingLabel = true })
                }
            }
            if (hasTexts) {
                BarDivider()
                // Size, color and alignment of the selected texts, as in the editor
                controller.selectionManager.selectedTexts?.let { texts ->
                    TextStyleControls(texts, commands)
                }
            }
        }
        if (editingLabel && singleShape != null) {
            var labelText by remember(singleShape.first.id) { mutableStateOf(singleShape.second.label.orEmpty()) }
            AdaptiveDialog(
                onDismissRequest = { editingLabel = false },
                title = { Text(stringResource(Res.string.shape_action_label)) },
                confirmButton = {
                    TextButton(onClick = {
                        controller.shapeEditing.updateLabel(labelText)
                        editingLabel = false
                    }) { Text(stringResource(Res.string.text_editor_action_save)) }
                },
                dismissButton = {
                    TextButton(onClick = { editingLabel = false }) { Text(stringResource(Res.string.common_button_cancel)) }
                },
                text = {
                    OutlinedTextField(
                        value = labelText,
                        onValueChange = { if (it.length <= ShapeElement.MAX_LABEL_LENGTH) labelText = it },
                        placeholder = { Text(stringResource(Res.string.shape_label_hint)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            )
        }
    }
}

/**
 * The button of the selection bar opening the style of the shapes selected (the settings of the
 * shapes tool, applied to them): the shape it draws, its color and its outline.
 */
@Composable
private fun ShapeStyleAction(controller: PageboardController) {
    var expanded by remember { mutableStateOf(false) }
    val target = remember(controller) { SelectedShapesTarget(controller) }
    val label = stringResource(Res.string.shape_style)
    Box {
        BarAction(PageboardIcons.Shapes, label, { expanded = true })
        ToolPopover(expanded = expanded, onDismissRequest = { expanded = false }) {
            ShapeSettings(target, label, withHint = false)
        }
    }
}

/**
 * The button of the selection bar opening the commands that move the selection in the stack of
 * its page (one button: the bar of a phone has no room for four).
 */
@Composable
private fun ArrangeAction(commands: EditorCommands) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        BarAction(PageboardIcons.Layers, stringResource(Res.string.menu_arrange), { expanded = true })
        CommandMenu(commands.arrangements, expanded = expanded, onDismissRequest = { expanded = false })
    }
}

/**
 * Long-press context menu of the canvas: on a selection (or on an element, selected by the
 * press), cut, copy, delete and the commands arranging it in the stack; elsewhere, paste, page
 * management (add after, delete, move) and background of the page.
 *
 * @param commands the commands of the editor (the paste and the items of a selection are theirs).
 * @param onEditPageBackground opens the template configurator of the targeted page.
 */
@Composable
internal fun CanvasContextMenu(
    controller: PageboardController,
    commands: EditorCommands,
    selectionActions: List<Command> = emptyList(),
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
                .elevation(PageboardElevation.Level3, shape)
                .background(MaterialTheme.colorScheme.surfaceContainer, shape)
                .padding(8.dp)
        ) {
            val lockedTarget = controller.lockedElementForMenu
            if (lockedTarget != null) {
                MenuItem(
                    text = stringResource(Res.string.context_menu_action_unlock),
                    icon = rememberVectorPainter(PageboardIcons.LockOpen),
                    onClick = {
                        controller.selectionManager.unlockElement(lockedTarget.first, lockedTarget.second)
                    }
                )
                MenuDivider()
            }
            if (controller.selectionManager.currentSelection != null) {
                SelectionMenuItems(controller, commands, selectionActions)
                return@Column
            }
            // What was copied in any document or in another application, where the menu was
            // opened (pages after this page)
            if (remember(position) { commands.canPaste() }) {
                val pastesPages = remember(position) { commands.pastesPages() }
                MenuItem(
                    text = stringResource(if (pastesPages) Res.string.context_menu_action_paste_page else Res.string.common_action_paste),
                    icon = rememberVectorPainter(PageboardIcons.Clipboard),
                    shortcut = commands.paste.shortcutLabel(currentAdaptiveInfo().conventions).takeIf { currentAdaptiveInfo().input.hasKeyboard },
                    onClick = {
                        controller.dismissContextMenu()
                        commands.pasteAt(position)
                    }
                )
                MenuDivider()
            }
            MenuItem(
                text = stringResource(Res.string.context_menu_action_add_page_after),
                icon = rememberVectorPainter(PageboardIcons.FilePlus2),
                onClick = {
                    controller.dismissContextMenu()
                    pageManager.addNewPageAfter(pageManager.contextMenuTargetPageIndex)
                }
            )
            MenuItem(
                text = stringResource(Res.string.context_menu_action_add_page_before),
                icon = rememberVectorPainter(PageboardIcons.FilePlus2),
                onClick = {
                    controller.dismissContextMenu()
                    pageManager.addNewPageBefore(pageManager.contextMenuTargetPageIndex)
                }
            )
            MenuItem(
                text = stringResource(Res.string.context_menu_action_duplicate_page),
                icon = rememberVectorPainter(PageboardIcons.CopyPlus),
                onClick = {
                    controller.dismissContextMenu()
                    controller.document.pages.getOrNull(pageManager.contextMenuTargetPageIndex)?.let { commands.duplicatePages(listOf(it.id)) }
                }
            )
            MenuItem(
                text = stringResource(Res.string.context_menu_action_copy_page),
                icon = rememberVectorPainter(PageboardIcons.Copy),
                onClick = {
                    controller.dismissContextMenu()
                    commands.copyPage(pageManager.contextMenuTargetPageIndex)
                }
            )
            MenuItem(
                text = stringResource(Res.string.context_menu_action_reorder_page),
                icon = rememberVectorPainter(PageboardIcons.Grip),
                onClick = {
                    controller.dismissContextMenu()
                    pageManager.startPageReorderMode()
                }
            )
            MenuItem(
                text = stringResource(Res.string.context_menu_action_delete_page),
                icon = rememberVectorPainter(PageboardIcons.Trash),
                destructive = true,
                onClick = {
                    controller.dismissContextMenu()
                    pageManager.deletePage(pageManager.contextMenuTargetPageIndex)
                }
            )
            MenuDivider()
            MenuItem(
                text = stringResource(Res.string.context_menu_action_edit_background),
                icon = rememberVectorPainter(PageboardIcons.Grid),
                onClick = {
                    controller.dismissContextMenu()
                    onEditPageBackground()
                }
            )
        }
    }
}

/** The items of the context menu of a selection: cut, copy, delete, then the stack, then the actions of the extensions. */
@Composable
private fun SelectionMenuItems(controller: PageboardController, commands: EditorCommands, selectionActions: List<Command>) {
    val adaptive = currentAdaptiveInfo()
    val conventions = adaptive.conventions.takeIf { adaptive.input.hasKeyboard }
    val sections = listOf(listOf(commands.cut, commands.copy, commands.delete), commands.arrangements + commands.lockSelection) +
        listOf(selectionActions).filter { it.isNotEmpty() }
    for ((index, section) in sections.withIndex()) {
        if (index > 0) MenuDivider()
        for (command in section) {
            MenuItem(
                text = command.label(),
                icon = command.icon?.invoke(),
                shortcut = conventions?.let { command.shortcutLabel(it) },
                enabled = command.isEnabled(),
                destructive = command.destructive,
                onClick = {
                    controller.dismissContextMenu()
                    command.performIfEnabled()
                }
            )
        }
    }
}
