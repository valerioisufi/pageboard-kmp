package io.github.valerioisufi.drawview.export.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.data.DrawViewStore
import io.github.valerioisufi.drawview.data.OpenDocuments
import io.github.valerioisufi.drawview.export.DocumentExport
import io.github.valerioisufi.drawview.export.ExportOptions
import io.github.valerioisufi.drawview.export.PageImageExporter
import io.github.valerioisufi.drawview.export.PdfExporter
import io.github.valerioisufi.drawview.export.generated.resources.Res
import io.github.valerioisufi.drawview.export.generated.resources.export_action
import io.github.valerioisufi.drawview.export.generated.resources.export_background
import io.github.valerioisufi.drawview.export.generated.resources.export_cancel
import io.github.valerioisufi.drawview.export.generated.resources.export_close
import io.github.valerioisufi.drawview.export.generated.resources.export_confirm
import io.github.valerioisufi.drawview.export.generated.resources.export_done
import io.github.valerioisufi.drawview.export.generated.resources.export_failed
import io.github.valerioisufi.drawview.export.generated.resources.export_format
import io.github.valerioisufi.drawview.export.generated.resources.export_format_pdf
import io.github.valerioisufi.drawview.export.generated.resources.export_format_png
import io.github.valerioisufi.drawview.export.generated.resources.export_in_progress
import io.github.valerioisufi.drawview.export.generated.resources.export_pages
import io.github.valerioisufi.drawview.export.generated.resources.export_pages_all
import io.github.valerioisufi.drawview.export.generated.resources.export_pages_on_screen
import io.github.valerioisufi.drawview.export.generated.resources.export_save_title
import io.github.valerioisufi.drawview.export.generated.resources.export_skipped
import io.github.valerioisufi.drawview.export.generated.resources.export_title
import io.github.valerioisufi.drawview.ui.components.ToolButton
import io.github.valerioisufi.drawview.ui.platform.rememberFileSaver
import io.github.valerioisufi.drawview.ui.render.ComposeTextTileAdapter
import io.github.valerioisufi.drawview.ui.session.DocumentAction
import io.github.valerioisufi.drawview.ui.session.EditorExtension
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The export of the documents in an application (see `docs/export.md`): "Export" on a document
 * of the library ([documentAction]) and in the top bar of the editor ([editorExtension]), both
 * opening the export dialog.
 *
 * @param documents the documents of the application (those of its library).
 */
class DocumentExportUi(documents: OpenDocuments) {
    private val export = DocumentExport(documents)

    /** "Export" on the document selected in the library (see `DocumentLibrary.documentActions`). */
    val documentAction: DocumentAction = DocumentAction(
        icon = Icons.Outlined.IosShare,
        label = { stringResource(Res.string.export_action) },
        content = { documentId, title, onDone -> ExportDialog(export, documentId, title, pageOnScreen = null, onDismiss = onDone) }
    )

    /** "Export" in the top bar of the editor: the page on screen can also be exported alone. */
    val editorExtension: EditorExtension = EditorExtension(
        toolbarActions = { session, controller -> ExportButton(export, session.documentId, controller) }
    )
}

/** The export of the documents of [documents] (by default those of the library of `DrawViewStore`). */
@Composable
fun rememberDocumentExportUi(documents: OpenDocuments = DrawViewStore.documents): DocumentExportUi =
    remember(documents) { DocumentExportUi(documents) }

@Composable
private fun ExportButton(export: DocumentExport, documentId: String, controller: DrawViewController) {
    var pageOnScreen by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf(false) }
    ToolButton(onClick = {
        pageOnScreen = controller.mostVisiblePageIndex()?.let { controller.document.pages.getOrNull(it)?.id }
        dialog = true
    }) {
        Icon(imageVector = Icons.Outlined.IosShare, contentDescription = stringResource(Res.string.export_action))
    }
    if (dialog) ExportDialog(export, documentId, controller.document.title, pageOnScreen, onDismiss = { dialog = false })
}

private enum class ExportFormat { PDF, PNG }

private sealed interface ExportState {
    data object Choosing : ExportState
    class Running(val progress: Float) : ExportState
    class Done(val skipped: Int) : ExportState
    data object Failed : ExportState
}

/**
 * The export of the document [documentId] named [title]: its format (the PDF of the pages, or the
 * image of [pageOnScreen] when there is one), its pages (all of them or [pageOnScreen]) and the
 * pattern of the backgrounds; then where to save it, the progress and the result. Dismissed while
 * running, the export stops.
 */
@Composable
internal fun ExportDialog(export: DocumentExport, documentId: String, title: String, pageOnScreen: String?, onDismiss: () -> Unit) {
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val textAdapter = remember(fontFamilyResolver) { ComposeTextTileAdapter(fontFamilyResolver) }
    var format by remember { mutableStateOf(ExportFormat.PDF) }
    var onlyPageOnScreen by remember { mutableStateOf(false) }
    var backgroundPattern by remember { mutableStateOf(true) }
    var state by remember { mutableStateOf<ExportState>(ExportState.Choosing) }
    val scope = rememberCoroutineScope()

    val exporter = when (format) {
        ExportFormat.PDF -> PdfExporter(textAdapter)
        ExportFormat.PNG -> PageImageExporter(textAdapter)
    }
    val pages = if (pageOnScreen != null && (onlyPageOnScreen || format == ExportFormat.PNG)) setOf(pageOnScreen) else null
    val save = rememberFileSaver(stringResource(Res.string.export_save_title), exporter.mimeType) { target ->
        state = ExportState.Running(0f)
        scope.launch {
            state = try {
                val result = export.export(documentId, exporter, ExportOptions(pages, backgroundPattern), target) {
                    state = ExportState.Running(it)
                }
                ExportState.Done(result.skipped)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("DrawView: cannot export the document: ${e.message}")
                ExportState.Failed
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (state !is ExportState.Running) onDismiss() },
        title = { Text(stringResource(Res.string.export_title, title)) },
        text = {
            when (val current = state) {
                ExportState.Choosing -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (pageOnScreen != null) {
                        Label(Res.string.export_format)
                        Choice(Res.string.export_format_pdf, format == ExportFormat.PDF) { format = ExportFormat.PDF }
                        Choice(Res.string.export_format_png, format == ExportFormat.PNG) { format = ExportFormat.PNG }
                        if (format == ExportFormat.PDF) {
                            Label(Res.string.export_pages)
                            Choice(Res.string.export_pages_all, !onlyPageOnScreen) { onlyPageOnScreen = false }
                            Choice(Res.string.export_pages_on_screen, onlyPageOnScreen) { onlyPageOnScreen = true }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = backgroundPattern, role = Role.Checkbox) { backgroundPattern = it }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = backgroundPattern, onCheckedChange = null)
                        Text(stringResource(Res.string.export_background), modifier = Modifier.padding(start = 12.dp))
                    }
                }

                is ExportState.Running -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.export_in_progress))
                    LinearProgressIndicator(progress = { current.progress }, modifier = Modifier.fillMaxWidth())
                }

                is ExportState.Done -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.export_done))
                    if (current.skipped > 0) Text(stringResource(Res.string.export_skipped, current.skipped))
                }

                ExportState.Failed -> Text(stringResource(Res.string.export_failed))
            }
        },
        confirmButton = {
            when (state) {
                ExportState.Choosing -> TextButton(onClick = { save(DocumentExport.fileName(title, exporter)) }) {
                    Text(stringResource(Res.string.export_confirm))
                }
                is ExportState.Running -> {}
                else -> TextButton(onClick = onDismiss) { Text(stringResource(Res.string.export_close)) }
            }
        },
        dismissButton = {
            if (state == ExportState.Choosing || state is ExportState.Running) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.export_cancel)) }
            }
        }
    )
}

@Composable
private fun Label(text: StringResource) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun Choice(text: StringResource, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(stringResource(text), modifier = Modifier.padding(start = 12.dp))
    }
}
