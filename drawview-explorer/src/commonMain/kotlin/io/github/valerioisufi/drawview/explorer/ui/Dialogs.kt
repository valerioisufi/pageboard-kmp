package io.github.valerioisufi.drawview.explorer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.core.model.PageBackground
import io.github.valerioisufi.drawview.data.FolderSummary
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel
import io.github.valerioisufi.drawview.explorer.generated.resources.Res
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_cancel
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_confirm
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_create_note
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_details_folder
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_dialog_new_note
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_import_image
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_import_pdf_short
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_input_name_short
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_move_empty_state
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_move_here
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_new_note_paper
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_paper_blank
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_paper_dotted
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_paper_grid
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_paper_lined
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_root_folder
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_untitled_note
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveSheet
import io.github.valerioisufi.drawview.ui.command.DesignMenu
import io.github.valerioisufi.drawview.ui.command.MenuItem
import io.github.valerioisufi.drawview.ui.components.DesignIconButton
import io.github.valerioisufi.drawview.ui.components.OutlineButton
import io.github.valerioisufi.drawview.ui.generated.resources.Res as DrawViewRes
import io.github.valerioisufi.drawview.ui.generated.resources.common_action_close
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Asks the name of a new item (or the new name of an item), under its [icon]. [isAllowedInput]
 * returns the error of a name, `null` when it is valid; [error] is an error found after the
 * confirmation.
 */
@Composable
fun RequestNameDialog(
    title: String = "",
    labelTextField: String = "",
    textConfirmButton: String = "",
    onDismissRequest: () -> Unit,
    onConfirm: (inputText: String) -> Unit,
    isAllowedInput: (inputText: String) -> StringResource? = { null },
    initialText: String = "",
    error: StringResource? = null,
    icon: ImageVector = DrawViewIcons.FolderPlus
) {
    var inputError by remember { mutableStateOf<StringResource?>(null) }
    var text by remember { mutableStateOf(initialText) }
    val shownError = inputError ?: error

    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(text = title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    inputError = isAllowedInput(it)
                },
                label = { Text(labelTextField) },
                singleLine = true,
                isError = shownError != null,
                supportingText = shownError?.let { resource -> { Text(stringResource(resource)) } },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }, enabled = text.isNotBlank() && inputError == null) {
                Text(text = textConfirmButton)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(Res.string.explorer_action_cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}

/**
 * Asks to confirm an action that cannot be undone ([title], [textDescription]), under the icon of
 * the bin; [note] says what else happens (e.g. a shared note: the others keep their copy).
 */
@Composable
fun ConfirmActionDialog(
    title: String = "",
    textDescription: String = "",
    textConfirmButton: String = stringResource(Res.string.explorer_action_confirm),
    textCancelButton: String = stringResource(Res.string.explorer_action_cancel),
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    note: String? = null
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = { Icon(DrawViewIcons.Trash, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(text = title, textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = textDescription, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                if (note != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(DrawViewIcons.Users, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
                        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
            ) {
                Text(text = textConfirmButton)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = textCancelButton)
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}

/**
 * Chooses the folder where items are moved ([subtitle]: how many, from where), navigating the
 * folder tree from the root along the path shown above the folders. The folders being moved
 * cannot be entered ([isFolderSelected]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveFileDialog(
    getSubFolders: (String?) -> List<FolderSummary>,
    isFolderSelected: (folderId: String) -> Boolean,
    isValidMove: (targetFolderId: String?) -> Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: (targetFolderId: String?) -> Unit,
    title: String = "",
    subtitle: String? = null
) {
    val rootName = stringResource(Res.string.explorer_root_folder)
    // The folders from the root (null) to the current one
    var path by remember { mutableStateOf(listOf<Pair<String?, String>>(null to rootName)) }
    val currentFolderId = path.last().first
    val subFolders = getSubFolders(currentFolderId)

    BasicAlertDialog(onDismissRequest = onDismissRequest) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.height(16.dp))
                // The path: the root and the folders entered, each going back there
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                    path.forEachIndexed { index, (_, name) ->
                        val last = index == path.lastIndex
                        if (index > 0) Icon(DrawViewIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp).padding(horizontal = 0.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !last) { path = path.take(index + 1) }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            if (index == 0) Icon(DrawViewIcons.Home, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text(
                                name,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 148.dp, max = 320.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(16.dp))
                        .padding(6.dp)
                ) {
                    if (subFolders.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(DrawViewIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                            Text(stringResource(Res.string.explorer_move_empty_state), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn {
                            items(subFolders, key = { it.id }) { folder ->
                                val enabled = !isFolderSelected(folder.id)
                                val content = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable(enabled = enabled) { path = path + (folder.id to folder.name) }
                                        .padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(DrawViewIcons.Folder, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
                                    Text(folder.name, style = MaterialTheme.typography.labelLarge, color = content, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Icon(DrawViewIcons.ChevronRight, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onDismissRequest) { Text(stringResource(Res.string.explorer_action_cancel)) }
                    Button(onClick = { onConfirm(currentFolderId) }, enabled = isValidMove(currentFolderId)) {
                        Text(stringResource(Res.string.explorer_move_here))
                    }
                }
            }
        }
    }
}

/** The papers a new note can start on. */
private enum class Paper(val label: StringResource, val background: () -> PageBackground) {
    BLANK(Res.string.explorer_paper_blank, { PageBackground.Solid() }),
    LINED(Res.string.explorer_paper_lined, { PageBackground.Ruled() }),
    GRID(Res.string.explorer_paper_grid, { PageBackground.Grid() }),
    DOTTED(Res.string.explorer_paper_dotted, { PageBackground.Dotted() })
}

/**
 * Makes a note: its name, its paper, its folder (the one shown by default); or a note of a PDF or
 * an image imported. A sheet on a phone, a dialog wider; the note made is opened.
 */
@Composable
internal fun NewNoteDialog(
    viewModel: FileExplorerViewModel,
    onOpenDocument: (String) -> Unit,
    onImportPdf: (() -> Unit)?,
    onImportImage: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var folderId by remember { mutableStateOf(viewModel.currentFolderIdOrNull) }
    val untitled = stringResource(Res.string.explorer_untitled_note)
    var name by remember { mutableStateOf(viewModel.availableNoteName(untitled, folderId)) }
    var paper by remember { mutableStateOf(Paper.LINED) }
    var error by remember { mutableStateOf<StringResource?>(null) }
    val create = {
        viewModel.createNote(
            name.trim(), paper.background(), folderId,
            onCreated = { id ->
                onDismiss()
                onOpenDocument(id)
            },
            onError = { error = it }
        )
    }
    AdaptiveSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.explorer_dialog_new_note),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                DesignIconButton(DrawViewIcons.Close, stringResource(DrawViewRes.string.common_action_close), onDismiss)
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    error = viewModel.validateNoteName(it.trim(), folderId)
                },
                label = { Text(stringResource(Res.string.explorer_input_name_short)) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { resource -> { Text(stringResource(resource)) } },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(stringResource(Res.string.explorer_new_note_paper), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (option in Paper.entries) {
                    PaperOption(option, selected = option == paper, onClick = { paper = option }, modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            FolderChooser(viewModel, folderId) { chosen ->
                folderId = chosen
                error = viewModel.validateNoteName(name.trim(), chosen)
            }
            if (onImportPdf != null || onImportImage != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (onImportPdf != null) {
                        OutlineButton(onClick = { onDismiss(); onImportPdf() }, modifier = Modifier.weight(1f)) {
                            Icon(DrawViewIcons.FileText, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(Res.string.explorer_import_pdf_short), maxLines = 1)
                        }
                    }
                    if (onImportImage != null) {
                        OutlineButton(onClick = { onDismiss(); onImportImage() }, modifier = Modifier.weight(1f)) {
                            Icon(DrawViewIcons.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(Res.string.explorer_import_image), maxLines = 1)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = create,
                enabled = name.isNotBlank() && error == null,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Icon(DrawViewIcons.Pencil, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(stringResource(Res.string.explorer_create_note), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** A paper of a new note: its preview, ringed when chosen, and its name. */
@Composable
private fun PaperOption(paper: Paper, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Column(modifier = modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(shape)
                .background(Color.White)
                .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
        ) {
            PaperPattern(paper)
        }
        Text(
            stringResource(paper.label),
            style = if (selected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** The lines, the grid or the dots of [paper], small. */
@Composable
private fun PaperPattern(paper: Paper) {
    val rule = Color(0xFFC9CCF2)
    val margin = Color(0xFFF2A7A7)
    Canvas(modifier = Modifier.fillMaxSize()) {
        val step = size.width / 7f
        when (paper) {
            Paper.BLANK -> Unit
            Paper.LINED -> {
                var y = step * 2
                while (y < size.height) {
                    drawLine(rule, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                    y += step
                }
                drawLine(margin, Offset(step * 1.4f, 0f), Offset(step * 1.4f, size.height), strokeWidth = 1.dp.toPx())
            }
            Paper.GRID -> {
                var x = step / 2
                while (x < size.width) {
                    drawLine(rule, Offset(x, 0f), Offset(x, size.height), strokeWidth = 0.6.dp.toPx())
                    x += step / 1.5f
                }
                var y = step / 2
                while (y < size.height) {
                    drawLine(rule, Offset(0f, y), Offset(size.width, y), strokeWidth = 0.6.dp.toPx())
                    y += step / 1.5f
                }
            }
            Paper.DOTTED -> {
                var y = step / 2
                while (y < size.height) {
                    var x = step / 2
                    while (x < size.width) {
                        drawCircle(Color(0xFFB0B4C8), radius = 1.dp.toPx(), center = Offset(x, y))
                        x += step / 1.2f
                    }
                    y += step / 1.2f
                }
            }
        }
    }
}

/** The folder of a new note, chosen in a menu of every folder with its path. */
@Composable
private fun FolderChooser(viewModel: FileExplorerViewModel, folderId: String?, onChoose: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val rootName = stringResource(Res.string.explorer_root_folder)
    val paths = viewModel.folderPaths
    val chosenName = paths.firstOrNull { it.first == folderId }?.second ?: rootName
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(DrawViewIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(Res.string.explorer_details_folder), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(chosenName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(DrawViewIcons.ChevronDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        DesignMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val home = rememberVectorPainter(DrawViewIcons.Home)
            val folder = rememberVectorPainter(DrawViewIcons.Folder)
            MenuItem(rootName, onClick = { onChoose(null); expanded = false }, icon = home, selected = folderId == null)
            for ((id, path) in paths) {
                MenuItem(path, onClick = { onChoose(id); expanded = false }, icon = folder, selected = folderId == id)
            }
        }
    }
}
