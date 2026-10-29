package io.github.valerioisufi.drawview.export.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import io.github.valerioisufi.drawview.export.DocumentExport
import io.github.valerioisufi.drawview.export.ExportJobs
import io.github.valerioisufi.drawview.export.ExportScope
import io.github.valerioisufi.drawview.export.ui.generated.resources.Res
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_action
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.ui.command.Command
import io.github.valerioisufi.drawview.ui.command.CommandGroup
import io.github.valerioisufi.drawview.ui.command.Shortcut
import io.github.valerioisufi.drawview.ui.extension.DocumentAction
import io.github.valerioisufi.drawview.ui.extension.EditorContext
import io.github.valerioisufi.drawview.ui.extension.EditorExtension
import io.github.valerioisufi.drawview.ui.extension.FolderAction
import io.github.valerioisufi.drawview.ui.screen.EditorActionButton
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.compose.resources.stringResource

/**
 * The export of the documents in an application (see `docs/export-ux.md`): "Export…" on a
 * document of the library ([documentAction]), on a folder or the Home ([folderAction]), and in
 * the editor ([editorExtension]), opening the export sheet; the exports then go on in the
 * background.
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

    /** "Export…" in the editor of [context]: the document, and the page on screen to export it alone. */
    private fun exportCommand(context: EditorContext) = Command(
        id = "export",
        label = { stringResource(Res.string.export_action) },
        group = CommandGroup.FILE,
        section = 1,
        icon = { rememberVectorPainter(DrawViewIcons.Share) },
        shortcuts = listOf(Shortcut(Key.E, primary = true, shift = true))
    ) {
        val controller = context.controller
        val pageOnScreen = controller.viewport.mostVisiblePageIndex()?.let { index ->
            controller.document.pages.getOrNull(index)?.let { PageOnScreen(it.id, index) }
        }
        editorExport = EditorExport(
            context.documentId,
            ExportSheetState(ExportScope.Pages(context.documentId), preferences, pageOnScreen)
        )
    }

    /** "Export…" on the document selected in the library (see `DocumentLibrary.documentActions`). */
    public val documentAction: DocumentAction = DocumentAction(
        icon = DrawViewIcons.Share,
        label = { stringResource(Res.string.export_action) },
        content = { documentId, _, onDone -> Sheet(documentId, { ExportScope.Pages(documentId) }, onDone) }
    )

    /** "Export…" on the folder or the Home selected in the library (see `DocumentLibrary.folderActions`). */
    public val folderAction: FolderAction = FolderAction(
        icon = DrawViewIcons.Share,
        label = { stringResource(Res.string.export_action) },
        content = { folderId, _, onDone -> Sheet(folderId, { ExportScope.Folder(folderId) }, onDone) }
    )

    /**
     * "Export…" in the top bar, the menus and the shortcuts of the editor: the page on screen can
     * also be exported alone. The sheet is shown over the canvas.
     */
    public val editorExtension: EditorExtension = EditorExtension(
        toolbarActions = { context ->
            val command = remember(context) { exportCommand(context) }
            EditorActionButton(DrawViewIcons.Share, command.label(), onClick = command.perform)
        },
        canvasOverlay = { context ->
            editorExport?.takeIf { it.documentId == context.documentId }?.let {
                ExportSheet(it.state, export, jobs, appName, thumbnail = null, onDismiss = { editorExport = null })
            }
        },
        commands = { context -> listOf(exportCommand(context)) }
    )

    /** The export sheet of the scope made by [scope] for the item [key], until [onDone]. */
    @Composable
    private fun Sheet(key: String?, scope: () -> ExportScope, onDone: () -> Unit) {
        val state = remember(key) { ExportSheetState(scope(), preferences) }
        ExportSheet(state, export, jobs, appName, thumbnail = null, onDismiss = onDone)
    }
}

/**
 * The export of the documents of [documents] (those of the library of the application) in the
 * application [appName].
 */
@Composable
public fun rememberDocumentExportUi(documents: OpenDocuments, appName: String? = null): DocumentExportUi =
    remember(documents, appName) { DocumentExportUi(documents, appName) }
