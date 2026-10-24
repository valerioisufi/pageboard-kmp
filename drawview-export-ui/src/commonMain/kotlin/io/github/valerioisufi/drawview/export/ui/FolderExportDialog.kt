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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.github.valerioisufi.drawview.export.DocumentExport
import io.github.valerioisufi.drawview.export.FolderExportFormat
import io.github.valerioisufi.drawview.export.FolderExportOptions
import io.github.valerioisufi.drawview.export.PdfExporter
import io.github.valerioisufi.drawview.export.ui.generated.resources.Res
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_background
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_cancel
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_close
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_confirm
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_folder_done
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_folder_empty
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_folder_failed
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_folder_format_pdf
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_folder_format_pdf_hint
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_folder_format_zip
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_folder_format_zip_hint
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_folder_include_subfolders
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_folder_notes_count
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_format
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_in_progress
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_save_title
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_skipped
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_title
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveSheet
import io.github.valerioisufi.drawview.ui.platform.rememberFileSaver
import io.github.valerioisufi.drawview.ui.render.ComposeTextTileAdapter
import io.github.valerioisufi.drawview.ui.render.LocalDrawViewEngines
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import io.github.valerioisufi.drawview.ui.theme.NotesFontFiles
import io.github.valerioisufi.drawview.ui.theme.rememberNotesFonts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The export of the folder [folderId] named [title] in the application [appName]: its format
 * (ZIP archive with individual PDFs or a single combined PDF), whether to include subfolders,
 * and the background pattern; then where to save it, the progress and the result. Dismissed while
 * running, the export stops.
 */
@Composable
internal fun FolderExportDialog(
    export: DocumentExport,
    appName: String?,
    folderId: String?,
    title: String,
    onDismiss: () -> Unit
) {
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val notesFonts = rememberNotesFonts()
    val engines = LocalDrawViewEngines.current
    val textAdapter = remember(fontFamilyResolver, notesFonts, engines) {
        ComposeTextTileAdapter(fontFamilyResolver, notesFonts, engines.formulas, engines.code)
    }
    var format by remember { mutableStateOf(FolderExportFormat.ZIP) }
    var includeSubfolders by remember { mutableStateOf(true) }
    var backgroundPattern by remember { mutableStateOf(true) }
    var state by remember { mutableStateOf<ExportState>(ExportState.Choosing) }
    var noteCount by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(export, folderId, includeSubfolders) {
        val items = export.folderDocuments(folderId, includeSubfolders = includeSubfolders)
        noteCount = items.size
    }

    val save = rememberFileSaver(stringResource(Res.string.export_save_title), format.mimeType) { target ->
        state = ExportState.Running(0f)
        scope.launch {
            state = try {
                val exporter = PdfExporter(textAdapter, NotesFontFiles::bytes, creator = appName, elements = engines.elements)
                val options = FolderExportOptions(includeSubfolders = includeSubfolders, backgroundPattern = backgroundPattern)
                val result = export.exportFolder(
                    folderId = folderId,
                    folderName = title,
                    format = format,
                    options = options,
                    pdfExporter = exporter,
                    path = target.path
                ) {
                    state = ExportState.Running(it)
                }
                target.deliver(target.path)
                ExportState.Done(result.skipped)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("DrawView: cannot export the folder: ${e.message}")
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
                    Button(
                        onClick = { save(DocumentExport.folderFileName(title, format)) },
                        enabled = (noteCount ?: 0) > 0
                    ) {
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
        FolderExportBody(
            state = state,
            format = format,
            onFormat = { format = it },
            includeSubfolders = includeSubfolders,
            onIncludeSubfolders = { includeSubfolders = it },
            backgroundPattern = backgroundPattern,
            onBackgroundPattern = { backgroundPattern = it },
            noteCount = noteCount
        )
    }
}

/**
 * What the folder export dialog shows in [state]: the choices (format, include subfolders,
 * background pattern, note count summary), the progress, the result.
 */
@Composable
internal fun FolderExportBody(
    state: ExportState,
    format: FolderExportFormat,
    onFormat: (FolderExportFormat) -> Unit,
    includeSubfolders: Boolean,
    onIncludeSubfolders: (Boolean) -> Unit,
    backgroundPattern: Boolean,
    onBackgroundPattern: (Boolean) -> Unit,
    noteCount: Int?
) {
    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        when (val current = state) {
            ExportState.Choosing -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label(Res.string.export_format)
                Choice(
                    text = Res.string.export_folder_format_zip,
                    hint = Res.string.export_folder_format_zip_hint,
                    selected = format == FolderExportFormat.ZIP,
                    onSelect = { onFormat(FolderExportFormat.ZIP) }
                )
                Choice(
                    text = Res.string.export_folder_format_pdf,
                    hint = Res.string.export_folder_format_pdf_hint,
                    selected = format == FolderExportFormat.COMBINED_PDF,
                    onSelect = { onFormat(FolderExportFormat.COMBINED_PDF) }
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .toggleable(value = includeSubfolders, role = Role.Switch, onValueChange = onIncludeSubfolders)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(Res.string.export_folder_include_subfolders),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = includeSubfolders,
                        onCheckedChange = null,
                        thumbContent = if (includeSubfolders) {
                            { Icon(DrawViewIcons.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                        } else null
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = backgroundPattern, role = Role.Switch, onValueChange = onBackgroundPattern)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(Res.string.export_background),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = backgroundPattern,
                        onCheckedChange = null,
                        thumbContent = if (backgroundPattern) {
                            { Icon(DrawViewIcons.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                        } else null
                    )
                }
                if (noteCount != null) {
                    if (noteCount == 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(DrawViewIcons.TriangleAlert, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
                            Text(
                                text = stringResource(Res.string.export_folder_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(Res.string.export_folder_notes_count, noteCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                        )
                    }
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
                Outcome(DrawViewIcons.CheckCircle, MaterialTheme.colorScheme.primary, stringResource(Res.string.export_folder_done))
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

            ExportState.Failed -> Outcome(DrawViewIcons.TriangleAlert, MaterialTheme.colorScheme.error, stringResource(Res.string.export_folder_failed))
        }
    }
}
