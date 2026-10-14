package io.github.valerioisufi.drawview.export.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
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
import io.github.valerioisufi.drawview.export.generated.resources.export_format_pdf_hint
import io.github.valerioisufi.drawview.export.generated.resources.export_format_png
import io.github.valerioisufi.drawview.export.generated.resources.export_format_png_hint
import io.github.valerioisufi.drawview.export.generated.resources.export_in_progress
import io.github.valerioisufi.drawview.export.generated.resources.export_pages
import io.github.valerioisufi.drawview.export.generated.resources.export_pages_all
import io.github.valerioisufi.drawview.export.generated.resources.export_pages_on_screen
import io.github.valerioisufi.drawview.export.generated.resources.export_save_title
import io.github.valerioisufi.drawview.export.generated.resources.export_skipped
import io.github.valerioisufi.drawview.export.generated.resources.export_title
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveSheet
import io.github.valerioisufi.drawview.ui.command.Command
import io.github.valerioisufi.drawview.ui.command.CommandGroup
import io.github.valerioisufi.drawview.ui.command.Shortcut
import io.github.valerioisufi.drawview.ui.platform.rememberFileSaver
import io.github.valerioisufi.drawview.ui.render.ComposeTextTileAdapter
import io.github.valerioisufi.drawview.ui.render.LocalDrawViewEngines
import io.github.valerioisufi.drawview.ui.screen.EditorActionButton
import io.github.valerioisufi.drawview.ui.session.DocumentAction
import io.github.valerioisufi.drawview.ui.session.EditorExtension
import io.github.valerioisufi.drawview.ui.session.EditorSession
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import io.github.valerioisufi.drawview.ui.theme.NotesFontFiles
import io.github.valerioisufi.drawview.ui.theme.rememberNotesFonts
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
 * @param appName the name of the application, written in the PDFs as the one that made them.
 */
class DocumentExportUi(documents: OpenDocuments, private val appName: String? = null) {
    private val export = DocumentExport(documents)

    /** The export asked in an editor, shown until it is dismissed. */
    private class EditorExport(val documentId: String, val title: String, val pageOnScreen: String?)

    private var editorExport by mutableStateOf<EditorExport?>(null)

    /** "Export…" in the editor of [session]: the document, and the page on screen to export it alone. */
    private fun exportCommand(session: EditorSession, controller: DrawViewController) = Command(
        id = "export",
        label = { stringResource(Res.string.export_action) },
        group = CommandGroup.FILE,
        section = 1,
        icon = { rememberVectorPainter(Icons.Outlined.IosShare) },
        shortcuts = listOf(Shortcut(Key.E, primary = true, shift = true))
    ) {
        val pageOnScreen = controller.mostVisiblePageIndex()?.let { controller.document.pages.getOrNull(it)?.id }
        editorExport = EditorExport(session.documentId, controller.document.title, pageOnScreen)
    }

    /** "Export" on the document selected in the library (see `DocumentLibrary.documentActions`). */
    val documentAction: DocumentAction = DocumentAction(
        icon = DrawViewIcons.Download,
        label = { stringResource(Res.string.export_action) },
        content = { documentId, title, onDone -> ExportDialog(export, appName, documentId, title, pageOnScreen = null, onDismiss = onDone) }
    )

    /**
     * "Export" in the top bar, the menus and the shortcuts of the editor: the page on screen can
     * also be exported alone. The dialog is shown over the canvas.
     */
    val editorExtension: EditorExtension = EditorExtension(
        toolbarActions = { session, controller ->
            val command = remember(session, controller) { exportCommand(session, controller) }
            EditorActionButton(DrawViewIcons.Share, command.label(), onClick = command.perform)
        },
        canvasOverlay = { session, _ ->
            editorExport?.takeIf { it.documentId == session.documentId }?.let {
                ExportDialog(export, appName, it.documentId, it.title, it.pageOnScreen, onDismiss = { editorExport = null })
            }
        },
        commands = { session, controller -> listOf(exportCommand(session, controller)) }
    )
}

/**
 * The export of the documents of [documents] (by default those of the library of `DrawViewStore`)
 * in the application [appName].
 */
@Composable
fun rememberDocumentExportUi(documents: OpenDocuments = DrawViewStore.documents, appName: String? = null): DocumentExportUi =
    remember(documents, appName) { DocumentExportUi(documents, appName) }

internal enum class ExportFormat { PDF, PNG }

internal sealed interface ExportState {
    data object Choosing : ExportState
    class Running(val progress: Float) : ExportState
    class Done(val skipped: Int) : ExportState
    data object Failed : ExportState
}

/**
 * The export of the document [documentId] named [title] in the application [appName]: its format
 * (the PDF of the pages, or the image of [pageOnScreen] when there is one), its pages (all of them
 * or [pageOnScreen]) and the pattern of the backgrounds; then where to save it, the progress and
 * the result. Dismissed while running, the export stops.
 */
@Composable
internal fun ExportDialog(
    export: DocumentExport,
    appName: String?,
    documentId: String,
    title: String,
    pageOnScreen: String?,
    onDismiss: () -> Unit
) {
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val notesFonts = rememberNotesFonts()
    // The engines of the editor or of the library showing the dialog
    val engines = LocalDrawViewEngines.current
    val textAdapter = remember(fontFamilyResolver, notesFonts, engines) { ComposeTextTileAdapter(fontFamilyResolver, notesFonts, engines.formulas) }
    var format by remember { mutableStateOf(ExportFormat.PDF) }
    var onlyPageOnScreen by remember { mutableStateOf(false) }
    var backgroundPattern by remember { mutableStateOf(true) }
    var state by remember { mutableStateOf<ExportState>(ExportState.Choosing) }
    val scope = rememberCoroutineScope()

    val exporter = when (format) {
        ExportFormat.PDF -> PdfExporter(textAdapter, NotesFontFiles::bytes, creator = appName, elements = engines.elements)
        ExportFormat.PNG -> PageImageExporter(textAdapter, pdfTiles = engines.pdfTiles, elements = engines.elements)
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

    AdaptiveSheet(
        onDismissRequest = { if (state !is ExportState.Running) onDismiss() },
        title = { Text(stringResource(Res.string.export_title, title)) },
        actions = {
            when (state) {
                ExportState.Choosing -> {
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.export_cancel)) }
                    Button(onClick = { save(DocumentExport.fileName(title, exporter)) }) {
                        Icon(DrawViewIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(Res.string.export_confirm))
                    }
                }
                is ExportState.Running -> TextButton(onClick = onDismiss) { Text(stringResource(Res.string.export_cancel)) }
                else -> TextButton(onClick = onDismiss) { Text(stringResource(Res.string.export_close)) }
            }
        }
    ) {
        ExportBody(
            state = state,
            format = format,
            onFormat = { format = it },
            onlyPageOnScreen = onlyPageOnScreen,
            onOnlyPageOnScreen = { onlyPageOnScreen = it },
            backgroundPattern = backgroundPattern,
            onBackgroundPattern = { backgroundPattern = it },
            withPageOnScreen = pageOnScreen != null
        )
    }
}

/**
 * What the export dialog shows in [state]: the choices (the format and the pages
 * [withPageOnScreen], the pattern of the backgrounds), the progress, the result.
 */
@Composable
internal fun ExportBody(
    state: ExportState,
    format: ExportFormat,
    onFormat: (ExportFormat) -> Unit,
    onlyPageOnScreen: Boolean,
    onOnlyPageOnScreen: (Boolean) -> Unit,
    backgroundPattern: Boolean,
    onBackgroundPattern: (Boolean) -> Unit,
    withPageOnScreen: Boolean
) {
    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        when (val current = state) {
            ExportState.Choosing -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (withPageOnScreen) {
                    Label(Res.string.export_format)
                    Choice(Res.string.export_format_pdf, Res.string.export_format_pdf_hint, format == ExportFormat.PDF) { onFormat(ExportFormat.PDF) }
                    Choice(Res.string.export_format_png, Res.string.export_format_png_hint, format == ExportFormat.PNG) { onFormat(ExportFormat.PNG) }
                    Label(Res.string.export_pages)
                    // The image is of the page on screen only
                    val png = format == ExportFormat.PNG
                    Choice(Res.string.export_pages_all, null, !onlyPageOnScreen && !png, enabled = !png) { onOnlyPageOnScreen(false) }
                    Choice(Res.string.export_pages_on_screen, null, onlyPageOnScreen || png, enabled = !png) { onOnlyPageOnScreen(true) }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .toggleable(value = backgroundPattern, role = Role.Switch, onValueChange = onBackgroundPattern)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(Res.string.export_background), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(
                        checked = backgroundPattern,
                        onCheckedChange = null,
                        thumbContent = if (backgroundPattern) {
                            { Icon(DrawViewIcons.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                        } else null
                    )
                }
            }

            is ExportState.Running -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.export_in_progress), style = MaterialTheme.typography.bodyLarge)
                LinearProgressIndicator(
                    progress = { current.progress },
                    modifier = Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    gapSize = 0.dp,
                    drawStopIndicator = {}
                )
            }

            is ExportState.Done -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Outcome(DrawViewIcons.CheckCircle, MaterialTheme.colorScheme.primary, stringResource(Res.string.export_done))
                if (current.skipped > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(DrawViewIcons.TriangleAlert, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
                        Text(stringResource(Res.string.export_skipped, current.skipped), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }

            ExportState.Failed -> Outcome(DrawViewIcons.TriangleAlert, MaterialTheme.colorScheme.error, stringResource(Res.string.export_failed))
        }
    }
}

/** The label of a group of choices. */
@Composable
private fun Label(text: StringResource) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
    )
}

/** A choice: its radio, its [text] and what it does ([hint]); the primary container when [selected]. */
@Composable
private fun Choice(text: StringResource, hint: StringResource?, selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.primaryContainer.copy(alpha = if (enabled) 1f else 0.5f) else Color.Transparent)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, color = if (selected) colors.onPrimaryContainer else colors.onSurface)
            if (hint != null) {
                Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant)
            }
        }
    }
}

/** How the export went: its [icon] in [tint] and its [text]. */
@Composable
private fun Outcome(icon: ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}
