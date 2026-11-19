package io.github.valerioisufi.pageboard.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.explorer.FileExplorerViewModel
import io.github.valerioisufi.pageboard.explorer.FileExplorerViewModel.FileItem
import io.github.valerioisufi.pageboard.explorer.FileExplorerViewModel.FileType
import io.github.valerioisufi.pageboard.explorer.FileExplorerViewModel.SearchScope
import io.github.valerioisufi.pageboard.explorer.FileExplorerViewModel.Section
import io.github.valerioisufi.pageboard.explorer.FileExplorerViewModel.ViewMode
import io.github.valerioisufi.pageboard.explorer.LibraryFilter
import io.github.valerioisufi.pageboard.explorer.generated.resources.Res
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_dialog_new_note
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_empty_folder_body
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_empty_folder_title
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_empty_library_body
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_empty_library_title
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_empty_recent_body
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_empty_recent_title
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_empty_shared_body
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_empty_shared_title
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_filter_notes
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_import_pdf
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_no_results_body
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_no_results_title
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_search_scope_library
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_section_folders
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_section_notes
import io.github.valerioisufi.pageboard.ui.components.OutlineButton
import io.github.valerioisufi.pageboard.ui.theme.PageboardIcons
import io.github.valerioisufi.pageboard.ui.theme.InkPalette
import org.jetbrains.compose.resources.stringResource

/** The narrowest card of a note. */
private val NoteCardMinWidth = 176.dp

/** The narrowest card of a folder. */
private val FolderCardMinWidth = 232.dp

/** The space between the cards. */
private val CardSpacing = 16.dp

/**
 * What the library shows, scrolled together: the [header] (title, search, filters), the folders
 * as cards (in a row scrolled sideways on a phone), the notes as cards ([ViewMode.GRID]) or rows,
 * or why there is nothing to show.
 *
 * @param compact a phone: the rows, the folders in a row, the margins of a phone.
 * @param onNewNote makes a note (the empty library).
 * @param onImportPdf imports a PDF as a note (the empty library), if possible.
 */
@Composable
internal fun LibraryContent(
    actions: ItemActions,
    compact: Boolean,
    gridState: LazyGridState,
    onNewNote: () -> Unit,
    onImportPdf: (() -> Unit)?,
    modifier: Modifier = Modifier,
    header: LazyGridScope.() -> Unit
) {
    val viewModel = actions.viewModel
    val files = viewModel.shownFiles
    val folders = files.filter { it.type == FileType.FOLDER }
    val notes = files.filter { it.type == FileType.FILE }
    val asCards = !compact && viewModel.viewMode == ViewMode.GRID && actions.thumbnails != null
    val margin = if (compact) 16.dp else 28.dp
    val foldersLabel = stringResource(Res.string.explorer_section_folders)
    val notesLabel = stringResource(Res.string.explorer_section_notes)
    LazyVerticalGrid(
        columns = if (asCards) GridCells.Adaptive(NoteCardMinWidth) else GridCells.Fixed(1),
        state = gridState,
        contentPadding = PaddingValues(start = if (compact) 0.dp else margin, end = if (compact) 0.dp else margin, bottom = 112.dp),
        horizontalArrangement = Arrangement.spacedBy(CardSpacing),
        verticalArrangement = Arrangement.spacedBy(if (asCards) CardSpacing else 2.dp),
        modifier = modifier.fillMaxSize()
    ) {
        header()
        val searchHeader = viewModel.contentSearch?.headerContent
        val activeQuery = viewModel.searchQuery.trim()
        if (activeQuery.isNotEmpty() && searchHeader != null && viewModel.section != Section.TRASH) {
            item(key = "search-header", span = { GridItemSpan(maxLineSpan) }) {
                Box(modifier = Modifier.padding(horizontal = if (compact) margin else 0.dp, vertical = 4.dp)) {
                    searchHeader(activeQuery, actions.onOpenDocumentWithPlace)
                }
            }
        }
        if (viewModel.section == Section.TRASH) {
            trashContent(viewModel, actions.commands, compact, margin = if (compact) margin else 0.dp, previews = actions.thumbnails != null)
            return@LazyVerticalGrid
        }
        if (folders.isNotEmpty()) {
            sectionLabel(foldersLabel, if (compact) margin else 0.dp)
            item(key = "folders", span = { GridItemSpan(maxLineSpan) }) {
                if (compact) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = margin),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(folders, key = { it.key }) { folder ->
                            FolderCard(folder, actions, modifier = Modifier.width(FolderCardMinWidth).animateItem())
                        }
                    }
                } else {
                    FolderGrid(folders, actions)
                }
            }
        }
        if (notes.isNotEmpty()) {
            sectionLabel(notesLabel, if (compact) margin else 0.dp, top = if (folders.isEmpty()) 0.dp else 12.dp)
            if (asCards) {
                items(notes, key = { it.key }) { note -> NoteCard(note, actions, modifier = Modifier.animateItem()) }
            } else {
                items(notes, key = { it.key }, span = { GridItemSpan(maxLineSpan) }) { note ->
                    NoteRow(note, actions, modifier = Modifier.animateItem())
                }
            }
        }
        // The folders stay under a filter: its notes may be missing all the same
        if (files.isEmpty() || (notes.isEmpty() && viewModel.filter != LibraryFilter.All)) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                EmptyLibrary(viewModel, onNewNote, onImportPdf, modifier = Modifier.padding(horizontal = margin))
            }
        }
    }
}

/** The label of a section of the content ("Folders", "Notes"). */
private fun LazyGridScope.sectionLabel(text: String, start: Dp, top: Dp = 0.dp) {
    item(key = "label:$text", span = { GridItemSpan(maxLineSpan) }) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = start, top = top, bottom = 4.dp)
        )
    }
}

/** The folders as cards, as many in a row as fit. */
@Composable
private fun FolderGrid(folders: List<FileItem>, actions: ItemActions) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = ((maxWidth + CardSpacing) / (FolderCardMinWidth + CardSpacing)).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(CardSpacing)) {
            for (row in folders.chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(CardSpacing)) {
                    for (folder in row) FolderCard(folder, actions, modifier = Modifier.weight(1f))
                    repeat(columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * Why there is nothing to show: a library without notes (with the first actions), an empty folder
 * or section, a search or a filter without results.
 */
@Composable
private fun EmptyLibrary(viewModel: FileExplorerViewModel, onNewNote: () -> Unit, onImportPdf: (() -> Unit)?, modifier: Modifier = Modifier) {
    val query = viewModel.searchQuery.trim()
    val filter = viewModel.filter
    when {
        query.isNotEmpty() -> EmptyState(
            icon = PageboardIcons.Search,
            title = stringResource(Res.string.explorer_no_results_title, query),
            body = stringResource(Res.string.explorer_no_results_body),
            modifier = modifier,
            // It may be elsewhere: "no results" here does not mean it is not in the notes
            action = if (viewModel.canWidenSearch && !viewModel.searchCoversLibrary) {
                {
                    OutlineButton(onClick = { viewModel.selectSearchScope(SearchScope.LIBRARY) }) {
                        Text(stringResource(Res.string.explorer_search_scope_library))
                    }
                }
            } else {
                null
            }
        )
        filter != LibraryFilter.All -> {
            val label = when (filter) {
                LibraryFilter.Notes -> stringResource(Res.string.explorer_filter_notes)
                is LibraryFilter.Tagged -> viewModel.allTags.firstOrNull { it.id == filter.tagId }?.filterLabel?.invoke().orEmpty()
                LibraryFilter.All -> ""
            }
            EmptyState(icon = PageboardIcons.Search, title = stringResource(Res.string.explorer_no_results_title, label), body = null, modifier = modifier)
        }
        viewModel.section == Section.RECENT -> EmptyState(
            icon = PageboardIcons.Clock,
            title = stringResource(Res.string.explorer_empty_recent_title),
            body = stringResource(Res.string.explorer_empty_recent_body),
            modifier = modifier
        )
        viewModel.section == Section.SHARED -> EmptyState(
            icon = PageboardIcons.Users,
            title = stringResource(Res.string.explorer_empty_shared_title),
            body = stringResource(Res.string.explorer_empty_shared_body),
            modifier = modifier
        )
        viewModel.currentFolder != null -> EmptyState(
            icon = PageboardIcons.Folder,
            title = stringResource(Res.string.explorer_empty_folder_title),
            body = stringResource(Res.string.explorer_empty_folder_body),
            modifier = modifier
        )
        else -> Column(
            modifier = modifier.fillMaxWidth().padding(top = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            NotesIllustration()
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                stringResource(Res.string.explorer_empty_library_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                stringResource(Res.string.explorer_empty_library_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 360.dp)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = onNewNote, modifier = Modifier.height(52.dp)) {
                Icon(PageboardIcons.Plus, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(stringResource(Res.string.explorer_dialog_new_note), style = MaterialTheme.typography.titleMedium)
            }
            if (onImportPdf != null) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlineButton(onClick = onImportPdf) {
                    Icon(PageboardIcons.FileText, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(Res.string.explorer_import_pdf))
                }
            }
        }
    }
}

/** An empty state: its icon, its title and why, what can be done about it. */
@Composable
private fun EmptyState(icon: ImageVector, title: String, body: String?, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        if (body != null) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 360.dp)
            )
        }
        action?.invoke()
    }
}

/** Two sheets of paper and the pencil: the library waiting for its first note. */
@Composable
private fun NotesIllustration() {
    val colors = MaterialTheme.colorScheme
    Box(modifier = Modifier.width(150.dp).height(130.dp)) {
        Box(
            modifier = Modifier
                .offset(x = 18.dp, y = 10.dp)
                .width(86.dp)
                .height(108.dp)
                .rotate(-8f)
                .background(colors.surfaceContainerHighest, RoundedCornerShape(4.dp))
        )
        Column(
            modifier = Modifier
                .offset(x = 36.dp, y = 2.dp)
                .width(86.dp)
                .height(108.dp)
                .background(Color.White, RoundedCornerShape(4.dp))
                .padding(top = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            repeat(5) { Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFC9CCF2))) }
        }
        Icon(
            PageboardIcons.Spline,
            contentDescription = null,
            tint = InkPalette.Blue,
            modifier = Modifier.offset(x = 46.dp, y = 6.dp).size(22.dp)
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = (-8).dp, y = (-6).dp)
                .size(44.dp)
                .background(colors.secondaryContainer, RoundedCornerShape(14.dp))
        ) {
            Icon(PageboardIcons.Pencil, contentDescription = null, tint = colors.onSecondaryContainer, modifier = Modifier.size(22.dp))
        }
    }
}
