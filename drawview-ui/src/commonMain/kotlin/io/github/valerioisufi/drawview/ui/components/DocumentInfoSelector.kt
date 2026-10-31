package io.github.valerioisufi.drawview.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.canvas.observe
import io.github.valerioisufi.drawview.canvas.observeChanges
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.ui.generated.resources.*
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import io.github.valerioisufi.drawview.ui.util.formatDateTime
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.drawview.ui.screen.DocumentNames
import io.github.valerioisufi.drawview.ui.screen.DocumentRenamer
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * The title of the document in the bar of the editor, with what it holds under it ([subtitle]);
 * a tap opens its information: where it is ([location]), its dates, its pages, its format and how
 * many elements it holds. Given a [renamer], the title there is a field that renames the document:
 * Enter takes the name (kept open while another document of the folder has it), closing the
 * popover takes it if it is valid, Escape gives the old one back.
 *
 * @param document the document, `null` while it is being loaded.
 * @param renameRequests counted by the command "Rename…": each opens the popover with the title
 *   focused and selected.
 */
@Composable
internal fun DocumentInfoSelector(
    document: DrawDocument?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    location: String? = null,
    renamer: DocumentRenamer? = null,
    renameRequests: Int = 0
) {
    var expanded by remember { mutableStateOf(false) }
    // Renamed elsewhere (another device), as it changes
    val title = document?.observe { name }?.value ?: stringResource(Res.string.common_state_loading)
    val scope = rememberCoroutineScope()
    // The name being written (null: none) and why it is not taken
    var draft by remember { mutableStateOf<TextFieldValue?>(null) }
    var error by remember { mutableStateOf<StringResource?>(null) }
    // The title focused when the popover opens: asked by "Rename…"
    var focusTitle by remember { mutableStateOf(false) }
    var seenRequests by remember { mutableIntStateOf(renameRequests) }
    if (renameRequests != seenRequests) {
        seenRequests = renameRequests
        if (renamer != null && document != null) {
            draft = TextFieldValue(title, selection = TextRange(0, title.length))
            focusTitle = true
            expanded = true
        }
    }

    /** Closes the popover, renaming the document as written when [take] and the name is valid. */
    fun close(take: Boolean) {
        val name = draft?.text?.trim()
        draft = null
        error = null
        focusTitle = false
        expanded = false
        if (take && renamer != null && name != null && name != title && DocumentNames.syntaxError(name) == null) {
            scope.launch { renamer.rename(name) }
        }
    }

    /** Takes the name written (Enter): the popover closes, or stays open if the folder has it. */
    fun submit() {
        val name = draft?.text?.trim()
        if (renamer == null || name == null || name == title) return close(take = false)
        if (DocumentNames.syntaxError(name) != null) return
        scope.launch {
            if (renamer.rename(name)) close(take = false) else error = Res.string.name_error_exists
        }
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.document_info_action_expand)) {
                    if (expanded) close(take = true) else expanded = true
                }
                .padding(start = 12.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(
                imageVector = if (expanded) DrawViewIcons.ChevronUp else DrawViewIcons.ChevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        ToolPopover(
            expanded = expanded && document != null,
            onDismissRequest = { close(take = true) },
            offset = DpOffset(0.dp, 8.dp),
            takesText = renamer != null
        ) {
            if (document != null) {
                DocumentInfo(document, location) {
                    if (renamer != null) {
                        DocumentTitleField(
                            value = draft ?: TextFieldValue(title, selection = TextRange(title.length)),
                            onValueChange = {
                                draft = it
                                error = DocumentNames.syntaxError(it.text.trim())
                            },
                            error = error,
                            focused = focusTitle,
                            onSubmit = ::submit,
                            onCancel = { close(take = false) }
                        )
                    } else {
                        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * The title of the document as a field: [onSubmit] by Enter, [onCancel] by Escape, the [error] of
 * the name under it; focused when it appears if [focused].
 */
@Composable
private fun DocumentTitleField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    error: StringResource?,
    focused: Boolean,
    onSubmit: () -> Unit,
    onCancel: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    if (focused) LaunchedEffect(Unit) { focusRequester.requestFocus() }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(Res.string.document_info_label_name)) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { resource -> { Text(stringResource(resource)) } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        shape = RoundedCornerShape(12.dp),
        textStyle = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                    onCancel()
                    true
                } else false
            }
    )
}

/**
 * The information of [document]: its [title] (shown or edited) and folder, its dates, pages,
 * format and elements.
 */
@Composable
private fun DocumentInfo(document: DrawDocument, location: String?, title: @Composable () -> Unit) {
    document.observeChanges()
    val monthNames = stringArrayResource(Res.array.date_months_short)
    val firstPage = document.pages.firstOrNull()
    val notAvailable = stringResource(Res.string.common_label_not_available)
    val dimensions = firstPage?.let {
        stringResource(Res.string.document_info_value_dimensions, it.widthMm.toInt(), it.heightMm.toInt())
    } ?: notAvailable
    val elements = document.pages.sumOf { page -> page.strokes.size + page.images.size + page.texts.size + page.pdfs.size + page.markups.size }
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        title()
        if (location != null) {
            Text(location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(modifier = Modifier.height(12.dp))
        InfoRow(DrawViewIcons.Clock, stringResource(Res.string.document_info_label_created_date), document.createdAt.takeIf { it > 0L }?.let { formatDateTime(it, monthNames).trimStart('0') } ?: notAvailable)
        InfoRow(DrawViewIcons.Pencil, stringResource(Res.string.document_info_label_modified_date), document.modifiedAt.takeIf { it > 0L }?.let { formatDateTime(it, monthNames).trimStart('0') } ?: notAvailable)
        InfoRow(DrawViewIcons.FileText, stringResource(Res.string.document_info_label_pages), document.pages.size.toString())
        InfoRow(DrawViewIcons.Grid, stringResource(Res.string.document_info_label_format), dimensions)
        InfoRow(DrawViewIcons.Layers, stringResource(Res.string.document_info_label_elements_count), elements.toString())
    }
}

/** A property of the document: its icon and its name, its value at the end. */
@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}
