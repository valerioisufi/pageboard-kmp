package io.github.valerioisufi.pageboard.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.PageLayer
import io.github.valerioisufi.pageboard.ui.adaptive.AdaptiveDialog
import io.github.valerioisufi.pageboard.ui.command.DesignMenu
import io.github.valerioisufi.pageboard.ui.command.MenuDivider
import io.github.valerioisufi.pageboard.ui.command.MenuItem
import io.github.valerioisufi.pageboard.ui.components.DesignIconButton
import io.github.valerioisufi.pageboard.ui.components.TonalButton
import io.github.valerioisufi.pageboard.ui.extension.EditorPanel
import io.github.valerioisufi.pageboard.ui.generated.resources.*
import io.github.valerioisufi.pageboard.ui.theme.PageboardIcons
import org.jetbrains.compose.resources.stringResource

/**
 * Adaptive per-page layers panel hosted inside [EditorScaffold]: displays on the side on wide
 * windows and rises from the bottom on compact windows.
 */
@Composable
internal fun rememberLayersPanel(
    controller: PageboardController,
    isOpen: () -> Boolean,
    onClose: () -> Unit
): EditorPanel = remember(controller) {
    EditorPanel(
        id = "layers",
        title = { stringResource(Res.string.command_layers) },
        isOpen = isOpen,
        onClose = onClose,
        icon = PageboardIcons.Layers,
        actions = {
            @Suppress("UNUSED_VARIABLE")
            val version = controller.layers.version
            @Suppress("UNUSED_VARIABLE")
            val moved = controller.viewport.camera.moveCount
            val pageIndex = controller.viewport.mostVisiblePageIndex() ?: 0
            val page = controller.document.pages.getOrNull(pageIndex)
            val layersCount = page?.let { controller.layers.layersOf(it).size } ?: 0
            val defaultName = stringResource(Res.string.layers_default_name, layersCount + 1)
            if (page != null && !controller.isReadOnly) {
                DesignIconButton(
                    icon = PageboardIcons.Plus,
                    label = stringResource(Res.string.layers_add),
                    enabled = layersCount < PageLayer.MAX_LAYERS_PER_PAGE,
                    onClick = { controller.layers.createLayer(page, defaultName) },
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    ) {
        LayersPanelContent(controller)
    }
}

@Composable
private fun LayersPanelContent(controller: PageboardController) {
    @Suppress("UNUSED_VARIABLE")
    val layerVersion = controller.layers.version
    @Suppress("UNUSED_VARIABLE")
    val selVersion = controller.selectionManager.selectionVersion
    @Suppress("UNUSED_VARIABLE")
    val cameraMove = controller.viewport.camera.moveCount
    val pageIndex = controller.viewport.mostVisiblePageIndex() ?: 0
    val page = controller.document.pages.getOrNull(pageIndex) ?: return
    val layers = controller.layers.layersOf(page)
    val activeLayerId = controller.layers.activeLayerIdOf(page)
    val selectionOnPage = controller.selectionManager.currentSelection?.pageIndex == pageIndex
    var renamingLayer by remember(page.id) { mutableStateOf<PageLayer?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (layers.isEmpty()) {
            val defaultName = stringResource(Res.string.layers_default_name, 1)
            Text(
                text = stringResource(Res.string.layers_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!controller.isReadOnly) {
                TonalButton(onClick = { controller.layers.createLayer(page, defaultName) }) {
                    Text(stringResource(Res.string.layers_add))
                }
            }
        } else {
            val reversed = layers.asReversed()
            for (layer in reversed) {
                val ascendingIndex = layers.indexOfFirst { it.id == layer.id }
                LayerRow(
                    controller = controller,
                    page = page,
                    layer = layer,
                    isActive = activeLayerId == layer.id,
                    canMoveUp = ascendingIndex < layers.lastIndex,
                    canMoveDown = ascendingIndex > 0,
                    canAssignSelection = selectionOnPage && !layer.locked,
                    onMoveUp = { controller.layers.moveLayer(page, layer.id, ascendingIndex + 1) },
                    onMoveDown = { controller.layers.moveLayer(page, layer.id, ascendingIndex - 1) },
                    onRename = { renamingLayer = layer }
                )
            }

            // Default unlayered level at the bottom of the page stack
            val unlayeredActive = activeLayerId == null
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (unlayeredActive) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable(enabled = !controller.isReadOnly) { controller.layers.setActiveLayer(page, null) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(Res.string.layers_unlayered),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (unlayeredActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (selectionOnPage && !controller.isReadOnly) {
                    TextButton(onClick = { controller.layers.assignSelectionToLayer(null) }) {
                        Text(stringResource(Res.string.layers_assign_selection))
                    }
                }
            }
        }
    }

    renamingLayer?.let { target ->
        var draft by remember(target.id) { mutableStateOf(target.name) }
        AdaptiveDialog(
            onDismissRequest = { renamingLayer = null },
            title = { Text(stringResource(Res.string.layers_rename)) },
            confirmButton = {
                TextButton(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        controller.layers.renameLayer(page, target.id, draft)
                        renamingLayer = null
                    }
                ) { Text(stringResource(Res.string.text_editor_action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renamingLayer = null }) { Text(stringResource(Res.string.common_button_cancel)) }
            },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= PageLayer.MAX_NAME_LENGTH) draft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )
    }
}

@Composable
private fun LayerRow(
    controller: PageboardController,
    page: Page,
    layer: PageLayer,
    isActive: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canAssignSelection: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRename: () -> Unit
) {
    var menuExpanded by remember(layer.id) { mutableStateOf(false) }
    val editable = !controller.isReadOnly
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(enabled = editable) { controller.layers.setActiveLayer(page, layer.id) }
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = layer.name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        DesignIconButton(
            icon = if (layer.visible) PageboardIcons.Eye else PageboardIcons.EyeOff,
            label = stringResource(if (layer.visible) Res.string.layers_hide else Res.string.layers_show),
            enabled = editable,
            onClick = { controller.layers.setLayerVisible(page, layer.id, !layer.visible) },
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        DesignIconButton(
            icon = if (layer.locked) PageboardIcons.Lock else PageboardIcons.LockOpen,
            label = stringResource(if (layer.locked) Res.string.layers_unlock else Res.string.layers_lock),
            enabled = editable,
            onClick = { controller.layers.setLayerLocked(page, layer.id, !layer.locked) },
            tint = if (layer.locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (editable) {
            Box {
                DesignIconButton(
                    icon = PageboardIcons.MoreVertical,
                    label = stringResource(Res.string.common_action_more_options),
                    onClick = { menuExpanded = true },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DesignMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (canAssignSelection) {
                        MenuItem(
                            text = stringResource(Res.string.layers_assign_selection),
                            icon = rememberVectorPainter(PageboardIcons.Layers),
                            onClick = {
                                menuExpanded = false
                                controller.layers.assignSelectionToLayer(layer.id)
                            }
                        )
                        MenuDivider()
                    }
                    MenuItem(
                        text = stringResource(Res.string.layers_rename),
                        icon = rememberVectorPainter(PageboardIcons.Pencil),
                        onClick = {
                            menuExpanded = false
                            onRename()
                        }
                    )
                    if (canMoveUp) {
                        MenuItem(
                            text = stringResource(Res.string.layers_move_up),
                            icon = rememberVectorPainter(PageboardIcons.ChevronUp),
                            onClick = {
                                menuExpanded = false
                                onMoveUp()
                            }
                        )
                    }
                    if (canMoveDown) {
                        MenuItem(
                            text = stringResource(Res.string.layers_move_down),
                            icon = rememberVectorPainter(PageboardIcons.ChevronDown),
                            onClick = {
                                menuExpanded = false
                                onMoveDown()
                            }
                        )
                    }
                    MenuDivider()
                    MenuItem(
                        text = stringResource(Res.string.layers_delete),
                        icon = rememberVectorPainter(PageboardIcons.Trash),
                        destructive = true,
                        onClick = {
                            menuExpanded = false
                            controller.layers.deleteLayer(page, layer.id)
                        }
                    )
                }
            }
        }
    }
}
