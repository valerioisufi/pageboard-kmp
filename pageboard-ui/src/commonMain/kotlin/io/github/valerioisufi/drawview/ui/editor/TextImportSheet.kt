package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.controller.TextImport
import io.github.valerioisufi.drawview.canvas.controller.TextImportPlace
import io.github.valerioisufi.drawview.model.text.TextCleanup
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveSheet
import io.github.valerioisufi.drawview.ui.command.Command
import io.github.valerioisufi.drawview.ui.components.MarkdownText
import io.github.valerioisufi.drawview.ui.generated.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The identifier of the command an extension gives to turn the pending text import into notes
 * with a model ([DrawViewController.pendingTextImport]): the sheet of the import offers it when the
 * extension gives it (see `docs/plans/text-import.md`).
 */
public const val TEXT_TO_NOTES_COMMAND_ID: String = "text-import.as-notes"

/**
 * The sheet of a text brought into the notebook ([DrawViewController.pendingTextImport]): where it
 * goes (by topic, from its page, at the end), whether its formatting is cleaned, a preview of its
 * first lines as they will be; then its placement (see
 * [io.github.valerioisufi.drawview.canvas.controller.TextPlacer.startImport]), or the notes of an
 * assistant ([asNotes]).
 *
 * @param asNotes the command of an extension turning the text into notes, if any.
 */
@Composable
internal fun TextImportSheet(controller: DrawViewController, asNotes: Command?) {
    val import = controller.pendingTextImport ?: return
    val scope = rememberCoroutineScope()
    val byTopic = controller.textPlacer.canPlaceByTopic
    val page = import.pageIndex ?: controller.viewport.mostVisiblePageIndex()
    var place by remember(import) { mutableStateOf(if (byTopic) TextImportPlace.TOPIC else if (page != null) TextImportPlace.PAGE else TextImportPlace.END) }
    var clean by remember(import) { mutableStateOf(!import.isMarkdown) }
    val preview = remember(import, clean) { previewOf(import, clean) }
    val label = stringResource(Res.string.import_text_label)
    val close = { controller.pendingTextImport = null }

    AdaptiveSheet(
        onDismissRequest = close,
        title = { Text(stringResource(Res.string.import_text_title)) },
        actions = {
            TextButton(onClick = close) { Text(stringResource(Res.string.common_button_cancel)) }
            Button(
                onClick = {
                    controller.pendingTextImport = null
                    scope.launch { controller.textPlacer.startImport(import, place, clean, import.name.ifEmpty { label }) }
                }
            ) { Text(stringResource(Res.string.import_text_insert)) }
        }
    ) {
        TextImportOptions(
            byTopic = byTopic,
            page = page,
            place = place,
            onPlace = { place = it },
            clean = clean,
            onClean = { clean = it },
            preview = preview,
            asNotes = asNotes?.let { command -> { command.performIfEnabled() } }
        )
    }
}

/**
 * The choices of the import of a text: where it goes ([place]: by topic when [byTopic], from
 * [page] when there is one, at the end), whether it is cleaned ([clean]), its [preview], and the
 * notes of an assistant ([asNotes]) when an extension gives them.
 */
@Composable
internal fun TextImportOptions(
    byTopic: Boolean,
    page: Int?,
    place: TextImportPlace,
    onPlace: (TextImportPlace) -> Unit,
    clean: Boolean,
    onClean: (Boolean) -> Unit,
    preview: String,
    asNotes: (() -> Unit)?
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(stringResource(Res.string.import_text_where), style = MaterialTheme.typography.titleSmall)
        if (byTopic) PlaceChoice(stringResource(Res.string.import_text_by_topic), place == TextImportPlace.TOPIC) { onPlace(TextImportPlace.TOPIC) }
        if (page != null) PlaceChoice(stringResource(Res.string.import_text_from_page, page + 1), place == TextImportPlace.PAGE) { onPlace(TextImportPlace.PAGE) }
        PlaceChoice(stringResource(Res.string.import_text_at_end), place == TextImportPlace.END) { onPlace(TextImportPlace.END) }

        Spacer(Modifier.padding(top = 8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .toggleable(value = clean, role = Role.Switch, onValueChange = onClean)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(Res.string.import_text_clean), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(Res.string.import_text_clean_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = clean, onCheckedChange = null)
        }

        Spacer(Modifier.padding(top = 8.dp))
        Text(stringResource(Res.string.import_text_preview), style = MaterialTheme.typography.titleSmall)
        MarkdownText(
            preview,
            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .padding(12.dp),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium
        )

        if (asNotes != null) {
            Spacer(Modifier.padding(top = 8.dp))
            TextButton(onClick = asNotes, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.import_text_as_notes), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(Res.string.import_text_as_notes_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** The first lines of [import] as they will be placed. */
internal fun previewOf(import: TextImport, clean: Boolean): String {
    val text = if (clean) TextCleanup.toMarkdown(import.text) else import.text.trim()
    val lines = text.lines()
    return if (lines.size <= PREVIEW_LINES) text else lines.take(PREVIEW_LINES).joinToString("\n") + "\n…"
}

@Composable
private fun PlaceChoice(text: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private const val PREVIEW_LINES = 12
