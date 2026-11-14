package io.github.valerioisufi.drawview.export.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.export.DocumentExport
import io.github.valerioisufi.drawview.export.ExportJobs
import io.github.valerioisufi.drawview.export.ExportScope
import io.github.valerioisufi.drawview.export.ui.generated.resources.Res
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_action
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_all_action
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_folder_action
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.ui.command.Command
import io.github.valerioisufi.drawview.ui.command.CommandGroup
import io.github.valerioisufi.drawview.ui.command.Shortcut
import io.github.valerioisufi.drawview.ui.extension.EditorContext
import io.github.valerioisufi.drawview.ui.extension.EditorExtension
import io.github.valerioisufi.drawview.ui.extension.LibraryItemsAction
import io.github.valerioisufi.drawview.ui.extension.LibrarySelection
import io.github.valerioisufi.drawview.ui.screen.EditorActionButton
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.compose.resources.stringResource

/**
 * The export of the documents in an application (see `docs/export-ux.md`): "Export…" on the
 * items of the library and on the folder shown ([libraryAction]), and in the editor
 * ([editorExtension]), opening the export sheet; the exports then go on in the
 * background, shown in the editor and, where the application places it, by [StatusHost].
 *
 * @param documents the documents of the application (those of its library).
 * @param appName the name of the application, written in the PDFs as the one that made them.
 */
public class DocumentExportUi(documents: OpenDocuments, private val appName: String? = null) {
    private val export = DocumentExport(documents)

    /** The exports asked, running one after the other while the application runs. */
    internal val jobs = ExportJobs(export, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private val preferences = ExportPreferences()

    /** The export asked in an editor, shown until it is dismissed. */
    private class EditorExport(val documentId: String, val state: ExportSheetState)

    private var editorExport by mutableStateOf<EditorExport?>(null)

    /**
     * Opens the export sheet of the document of [context]: the page on screen can be exported
     * alone, and the pages selected in the page grid are, when there are some.
     */
    private fun showSheet(context: EditorContext) {
        val pageOnScreen = context.pages.mostVisiblePageIndex()?.let { index ->
            context.document.document.pages.getOrNull(index)?.let { PageOnScreen(it.id, index) }
        }
        val selection = context.pages.selection
        val selected = if (selection.isActive) selection.pageIds.toSet() else emptySet()
        editorExport = EditorExport(
            context.documentId,
            ExportSheetState(ExportScope.Pages(context.documentId), preferences, pageOnScreen, selected)
        )
    }

    /** "Export…" in the editor of [context]: the document, the page on screen, the pages selected. */
    private fun exportCommand(context: EditorContext) = Command(
        id = "export",
        label = { stringResource(Res.string.export_action) },
        group = CommandGroup.FILE,
        section = 1,
        icon = { rememberVectorPainter(DrawViewIcons.Share) },
        shortcuts = listOf(Shortcut(Key.E, primary = true, shift = true))
    ) { showSheet(context) }

    /** "Export…" on the pages selected in the page grid of the editor of [context]. */
    private fun exportPagesCommand(context: EditorContext) = Command(
        id = "export-pages",
        label = { stringResource(Res.string.export_action) },
        group = CommandGroup.FILE,
        icon = { rememberVectorPainter(DrawViewIcons.Share) },
        isEnabled = { context.pages.selection.count > 0 }
    ) { showSheet(context) }

    /**
     * "Export…" on the items of the library, one or more notes and folders, and "Export folder…"
     * on the folder shown (see `DocumentLibrary.itemsActions`), ⇧⌘E as in the editor.
     */
    public val libraryAction: LibraryItemsAction = LibraryItemsAction(
        icon = DrawViewIcons.Share,
        label = { stringResource(Res.string.export_action) },
        folderLabel = { home -> stringResource(if (home) Res.string.export_all_action else Res.string.export_folder_action) },
        shortcut = Shortcut(Key.E, primary = true, shift = true),
        content = { selection, onDone ->
            val state = remember(selection) {
                val scope = when (selection) {
                    is LibrarySelection.Items -> ExportScope.ofSelection(selection.documentIds, selection.folderIds)
                    is LibrarySelection.Folder -> ExportScope.Folder(selection.folderId)
                }
                ExportSheetState(scope, preferences)
            }
            // The first page of the note, or of the first note among several
            val thumbnail = (selection as? LibrarySelection.Items)?.thumbnail
            ExportSheet(state, export, jobs, appName, thumbnail = thumbnail, onDismiss = onDone)
        }
    )

    /**
     * "Export…" in the top bar, the menus and the shortcuts of the editor, and on the pages
     * selected in the page grid: the page on screen can also be exported alone. The sheet is shown
     * over the canvas.
     */
    public val editorExtension: EditorExtension = EditorExtension(
        toolbarActions = { context ->
            val command = remember(context) { exportCommand(context) }
            EditorActionButton(DrawViewIcons.Share, command.label(), onClick = command.perform)
        },
        canvasOverlay = { context ->
            ExportStatusHost(
                jobs,
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = 64.dp)
            )
            editorExport?.takeIf { it.documentId == context.documentId }?.let {
                ExportSheet(it.state, export, jobs, appName, thumbnail = null, onDismiss = { editorExport = null })
            }
        },
        commands = { context -> listOf(exportCommand(context)) },
        pageGridActions = { context -> listOf(exportPagesCommand(context)) }
    )

    /**
     * The exports going on and how they went, with what can be done with their files: placed by
     * the application over its library (e.g. in `DocumentLibrary.libraryOverlay`), the editor
     * showing them by itself.
     */
    @Composable
    public fun StatusHost(modifier: Modifier = Modifier) {
        ExportStatusHost(jobs, modifier)
    }
}

/**
 * The export of the documents of [documents] (those of the library of the application) in the
 * application [appName].
 */
@Composable
public fun rememberDocumentExportUi(documents: OpenDocuments, appName: String? = null): DocumentExportUi =
    remember(documents, appName) { DocumentExportUi(documents, appName) }
