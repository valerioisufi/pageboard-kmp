package io.github.valerioisufi.drawview.export.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.export.DocumentExport
import io.github.valerioisufi.drawview.export.ExportFormat
import io.github.valerioisufi.drawview.export.ExportJobs
import io.github.valerioisufi.drawview.export.ExportRequest
import io.github.valerioisufi.drawview.export.ExportScope
import io.github.valerioisufi.drawview.export.ExportWriters
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_format_markdown_hint
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_format_markdown
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_format_archive
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_format_archive_hint
import io.github.valerioisufi.drawview.export.MarkdownExporter
import io.github.valerioisufi.drawview.export.MarkdownAppendix
import io.github.valerioisufi.drawview.export.PageImageExporter
import io.github.valerioisufi.drawview.export.PdfExporter
import io.github.valerioisufi.drawview.export.ui.generated.resources.Res
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_background
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_background_hint
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_cancel
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_confirm
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_file_name
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_file_selection
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_format
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_format_combined
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_format_combined_hint
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_format_image
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_format_image_hint
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_format_images
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_format_images_hint
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_format_pdf
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_format_pdf_hint
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_format_per_note
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_format_per_note_hint
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_name_home
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_name_selection
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_notes
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_pages
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_pages_all
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_pages_count
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_pages_on_screen
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_pages_range
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_pages_range_invalid
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_pages_range_placeholder
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_pages_selected
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_problem_no_name
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_problem_no_notes
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_problem_note_missing
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_save
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_save_title
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_share
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_subfolders
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_title
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveSheet
import io.github.valerioisufi.drawview.ui.components.ChipRow
import io.github.valerioisufi.drawview.ui.components.DesignFilterChip
import io.github.valerioisufi.drawview.ui.components.SwitchRow
import io.github.valerioisufi.drawview.ui.platform.FileTarget
import io.github.valerioisufi.drawview.ui.platform.rememberFileSaver
import io.github.valerioisufi.drawview.ui.platform.rememberFileSharer
import io.github.valerioisufi.drawview.ui.render.ComposeTextTileAdapter
import io.github.valerioisufi.drawview.ui.render.LocalDrawViewEngines
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import io.github.valerioisufi.drawview.ui.theme.NotesFontFiles
import io.github.valerioisufi.drawview.ui.theme.rememberNotesFonts
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The export of what [state] holds (see `docs/export-ux.md`): its contents read from [export],
 * the choices of the user, then where the file goes (saved, or shared on the phones). Once the
 * place is chosen the export is given to [jobs] and the sheet closes ([onDismiss]): its progress
 * and its outcome are shown by `ExportStatusHost`.
 *
 * @param appName the name of the application, written in the PDFs as the one that made them.
 * @param thumbnail gives the image of the first page of what is exported, if there is one.
 */
@Composable
internal fun ExportSheet(
    state: ExportSheetState,
    export: DocumentExport,
    jobs: ExportJobs,
    appName: String?,
    thumbnail: (suspend () -> ImageBitmap?)?,
    onDismiss: () -> Unit,
    markdownAppendix: MarkdownAppendix? = null
) {
    LaunchedEffect(state, state.includeSubfolders) {
        state.contents = export.resolve(state.scope, state.includeSubfolders)
    }
    val image by produceState<ImageBitmap?>(null, thumbnail) { value = thumbnail?.invoke() }
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val notesFonts = rememberNotesFonts()
    // The engines of the editor or of the library showing the sheet
    val engines = LocalDrawViewEngines.current
    val textAdapter = remember(fontFamilyResolver, notesFonts, engines) {
        ComposeTextTileAdapter(fontFamilyResolver, notesFonts, engines.formulas, engines.code)
    }
    val fallbackName = stringResource(fallbackNameOf(state.scope))
    val start = { target: FileTarget ->
        state.remember()
        jobs.start(
            ExportRequest(
                contents = state.exportedContents(),
                format = state.format,
                options = state.options(),
                writers = ExportWriters(
                    pdf = PdfExporter(textAdapter, NotesFontFiles::bytes, creator = appName, elements = engines.elements, formulas = engines.formulas),
                    image = PageImageExporter(textAdapter, pdfTiles = engines.pdfTiles, elements = engines.elements, images = engines.images),
                    markdown = MarkdownExporter(markdownAppendix)
                ),
                path = target.path,
                fileName = state.fileName(fallbackName),
                deliver = target.deliver
            )
        )
        onDismiss()
    }
    val title = stringResource(Res.string.export_save_title)
    val save = rememberFileSaver(title, state.mimeType, start)
    val share = rememberFileSharer(title, state.mimeType, start)
    // Shared first on the phones and the tablets, saved first on the desktop
    val primary: () -> Unit = { if (state.problem == null) (share ?: save)(state.fileName(fallbackName)) }

    AdaptiveSheet(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.export_title)) },
        actions = {
            val enabled = state.problem == null
            if (share != null) {
                TextButton(onClick = { save(state.fileName(fallbackName)) }, enabled = enabled) { Text(stringResource(Res.string.export_save)) }
                Button(onClick = primary, enabled = enabled) {
                    Icon(DrawViewIcons.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(Res.string.export_share))
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.export_cancel)) }
                Button(onClick = primary, enabled = enabled) {
                    Icon(DrawViewIcons.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(Res.string.export_confirm))
                }
            }
        }
    ) {
        ExportSheetContent(
            state = state,
            thumbnail = image,
            fallbackName = fallbackName,
            onSubmit = primary,
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && state.problem == null) {
                    primary()
                    true
                } else {
                    false
                }
            }
        )
    }
}

/** The name of the file of [scope] when it has none of its own: the Home, a selection. */
internal fun fallbackNameOf(scope: ExportScope): StringResource =
    if (scope is ExportScope.Folder) Res.string.export_name_home else Res.string.export_file_selection

/**
 * What the export sheet shows of [state]: what is exported ([thumbnail], its name, its notes and
 * pages), the formats, the pages, the options, the name of the file and why it cannot be exported
 * yet. [onSubmit] exports from the keyboard of the fields.
 */
@Composable
internal fun ExportSheetContent(
    state: ExportSheetState,
    thumbnail: ImageBitmap?,
    fallbackName: String,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Summary(state, thumbnail)

        Label(Res.string.export_format)
        // Four formats on two rows, so that their hints stay readable
        val rows = state.formats.chunked(if (state.formats.size > 3) 2 else state.formats.size.coerceAtLeast(1))
        Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in rows) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (format in row) {
                        val (name, hint) = formatTexts(format, state.exportedPages)
                        FormatCard(
                            icon = formatIcon(format),
                            name = stringResource(name),
                            hint = stringResource(hint),
                            selected = state.format == format,
                            onSelect = { state.format = format },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                }
            }
        }

        val pageChoices = state.pageChoices
        // An archive holds the whole note
        if (state.isSingleDocument && pageChoices.size > 1 && state.format != ExportFormat.ARCHIVE) {
            Label(Res.string.export_pages)
            ChipRow {
                for (choice in pageChoices) {
                    DesignFilterChip(
                        label = when (choice) {
                            PageChoice.ALL -> stringResource(Res.string.export_pages_all)
                            PageChoice.ON_SCREEN -> stringResource(Res.string.export_pages_on_screen)
                            PageChoice.SELECTED -> stringResource(Res.string.export_pages_selected, state.selectedPages.size)
                            PageChoice.RANGE -> stringResource(Res.string.export_pages_range)
                        },
                        selected = state.pages == choice,
                        onClick = { state.pages = choice }
                    )
                }
            }
            if (state.pages == PageChoice.RANGE) {
                val invalid = state.range.isNotBlank() && state.rangeIndices == null
                OutlinedTextField(
                    value = state.range,
                    onValueChange = { state.range = it },
                    placeholder = { Text(stringResource(Res.string.export_pages_range_placeholder)) },
                    isError = invalid,
                    supportingText = if (invalid) {
                        { Text(stringResource(Res.string.export_pages_range_invalid, state.contents?.pages ?: 0)) }
                    } else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        // The paper is drawn on the pages, not in a text
        if (state.format != ExportFormat.MARKDOWN && state.format != ExportFormat.ARCHIVE) {
            SwitchRow(
                text = stringResource(Res.string.export_background),
                hint = stringResource(Res.string.export_background_hint),
                checked = state.backgroundPattern,
                onCheckedChange = { state.backgroundPattern = it }
            )
        }
        if (state.hasFolders) {
            SwitchRow(
                text = stringResource(Res.string.export_subfolders),
                hint = null,
                checked = state.includeSubfolders,
                onCheckedChange = { state.includeSubfolders = it }
            )
        }

        Label(Res.string.export_file_name)
        OutlinedTextField(
            value = state.baseName(fallbackName),
            onValueChange = { state.editedName = it },
            suffix = { Text(".${state.extension}") },
            singleLine = true,
            isError = state.problem == ExportProblem.NO_NAME,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        val problem = when (state.problem) {
            ExportProblem.NO_NOTES -> Res.string.export_problem_no_notes
            ExportProblem.NOTE_MISSING -> Res.string.export_problem_note_missing
            ExportProblem.NO_NAME -> Res.string.export_problem_no_name
            else -> null
        }
        if (problem != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(DrawViewIcons.TriangleAlert, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Text(stringResource(problem), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Whether folders are exported (their subfolders can be left out). */
private val ExportSheetState.hasFolders: Boolean
    get() = when (val scope = scope) {
        is ExportScope.Folder -> true
        is ExportScope.Items -> scope.folderIds.isNotEmpty()
        is ExportScope.Pages -> false
    }

/** What is exported: the image of its first page (or its icon), its name, its notes and pages. */
@Composable
private fun Summary(state: ExportSheetState, thumbnail: ImageBitmap?) {
    val colors = MaterialTheme.colorScheme
    val contents = state.contents
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surfaceContainerHighest)
                .border(1.dp, colors.outlineVariant, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnail != null) {
                Image(thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().fillMaxHeight())
            } else {
                val icon = when (state.scope) {
                    is ExportScope.Pages -> DrawViewIcons.FileText
                    is ExportScope.Folder -> DrawViewIcons.Folder
                    is ExportScope.Items -> DrawViewIcons.Layers
                }
                Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            val name = contents?.name ?: when (state.scope) {
                is ExportScope.Folder -> stringResource(Res.string.export_name_home)
                is ExportScope.Items -> stringResource(Res.string.export_name_selection)
                is ExportScope.Pages -> ""
            }
            Text(name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = when {
                contents == null -> "…"
                state.isSingleDocument -> pluralStringResource(Res.plurals.export_pages_count, contents.pages, contents.pages)
                else -> pluralStringResource(Res.plurals.export_notes, contents.documents.size, contents.documents.size) + " · " +
                    pluralStringResource(Res.plurals.export_pages_count, contents.pages, contents.pages)
            }
            Text(details, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
}

/** The name and the consequence of [format] for [pages] pages. */
private fun formatTexts(format: ExportFormat, pages: Int): Pair<StringResource, StringResource> = when (format) {
    ExportFormat.PDF -> Res.string.export_format_pdf to Res.string.export_format_pdf_hint
    ExportFormat.IMAGES -> if (pages == 1) {
        Res.string.export_format_image to Res.string.export_format_image_hint
    } else {
        Res.string.export_format_images to Res.string.export_format_images_hint
    }
    ExportFormat.PDF_PER_DOCUMENT -> Res.string.export_format_per_note to Res.string.export_format_per_note_hint
    ExportFormat.COMBINED_PDF -> Res.string.export_format_combined to Res.string.export_format_combined_hint
    ExportFormat.MARKDOWN -> Res.string.export_format_markdown to Res.string.export_format_markdown_hint
    ExportFormat.ARCHIVE -> Res.string.export_format_archive to Res.string.export_format_archive_hint
}

private fun formatIcon(format: ExportFormat): ImageVector = when (format) {
    ExportFormat.PDF -> DrawViewIcons.FileText
    ExportFormat.IMAGES -> DrawViewIcons.Image
    ExportFormat.PDF_PER_DOCUMENT -> DrawViewIcons.Layers
    ExportFormat.COMBINED_PDF -> DrawViewIcons.BookOpen
    ExportFormat.MARKDOWN -> DrawViewIcons.Hash
    ExportFormat.ARCHIVE -> DrawViewIcons.Layers
}

/** The label of a group of choices. */
@Composable
private fun Label(text: StringResource) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
    )
}

/** A format to choose: its [icon], its [name] and what it gives ([hint]); the primary container when [selected]. */
@Composable
private fun FormatCard(icon: ImageVector, name: String, hint: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape,
        color = if (selected) colors.primaryContainer else colors.surfaceContainer,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
        border = if (selected) BorderStroke(2.dp, colors.primary) else BorderStroke(1.dp, colors.outlineVariant),
        modifier = modifier.clip(shape).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.height(2.dp))
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant
            )
        }
    }
}

