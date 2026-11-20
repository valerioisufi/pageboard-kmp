package io.github.valerioisufi.pageboard.handwriting.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.handwriting.InkReading
import io.github.valerioisufi.pageboard.handwriting.ui.generated.resources.Res
import io.github.valerioisufi.pageboard.handwriting.ui.generated.resources.handwriting_action_cancel
import io.github.valerioisufi.pageboard.handwriting.ui.generated.resources.handwriting_action_convert
import io.github.valerioisufi.pageboard.handwriting.ui.generated.resources.handwriting_convert_to_text
import io.github.valerioisufi.pageboard.handwriting.ui.generated.resources.handwriting_nothing_read
import io.github.valerioisufi.pageboard.handwriting.ui.generated.resources.handwriting_other_readings
import io.github.valerioisufi.pageboard.handwriting.ui.generated.resources.handwriting_reading
import io.github.valerioisufi.pageboard.model.StrokeElement
import io.github.valerioisufi.pageboard.ui.command.Command
import io.github.valerioisufi.pageboard.ui.command.CommandGroup
import io.github.valerioisufi.pageboard.ui.extension.EditorContext
import io.github.valerioisufi.pageboard.ui.extension.EditorExtension
import org.jetbrains.compose.resources.stringResource

/**
 * The conversion of handwriting in the editor: "Convert to text" in the context menu of a
 * selection of strokes, offered when the device reads the language of the notes, and the sheet
 * that shows the reading, editable, with the other readings, before the strokes are replaced by the
 * text box as one change to undo ([HandwritingConversion]).
 */
public fun handwritingEditorExtension(conversion: HandwritingConversion): EditorExtension {
    val state = ConversionState()
    return EditorExtension {
        selectionActions { context ->
            listOf(
                Command(
                    id = "handwriting.convertToText",
                    label = { stringResource(Res.string.handwriting_convert_to_text) },
                    group = CommandGroup.EDIT,
                    isEnabled = { state.readsText && !context.document.isReadOnly && context.edits.selection?.isStrokesOnly == true },
                    perform = { state.request(context) }
                )
            )
        }
        canvasOverlay { context ->
            LaunchedEffect(conversion) { state.readsText = conversion.readsText() }
            ConversionSheet(state, conversion, context)
        }
    }
}

/** The conversion asked, while its sheet is shown. */
private class ConversionState {
    var readsText by mutableStateOf(false)
    var pending by mutableStateOf<Pending?>(null)

    class Pending(val documentId: String, val pageIndex: Int, val strokeIds: Set<String>)

    fun request(context: EditorContext) {
        val selection = context.edits.selection ?: return
        pending = Pending(context.documentId, selection.pageIndex, selection.strokeIds)
    }
}

@Composable
private fun ConversionSheet(state: ConversionState, conversion: HandwritingConversion, context: EditorContext) {
    val pending = state.pending?.takeIf { it.documentId == context.documentId } ?: return
    val page = remember(pending) { context.document.document.pages.getOrNull(pending.pageIndex) }
    val strokes: List<StrokeElement> = remember(pending, page) { page?.strokes?.filter { it.id in pending.strokeIds }.orEmpty() }
    var reading by remember(pending) { mutableStateOf<InkReading?>(null) }
    var read by remember(pending) { mutableStateOf(false) }
    var text by remember(pending) { mutableStateOf("") }
    LaunchedEffect(pending) {
        reading = conversion.read(strokes)
        text = reading?.text.orEmpty()
        read = true
    }
    val dismiss = { state.pending = null }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(Res.string.handwriting_convert_to_text)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!read) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    if (reading == null) {
                        Text(stringResource(Res.string.handwriting_nothing_read), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(stringResource(Res.string.handwriting_reading)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    val others = reading?.alternatives.orEmpty().filter { it != text }.take(MAX_ALTERNATIVES)
                    if (others.isNotEmpty()) {
                        Text(stringResource(Res.string.handwriting_other_readings), style = MaterialTheme.typography.labelMedium)
                        for (other in others) TextButton(onClick = { text = other }) { Text(other) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = read && text.isNotBlank() && page != null && strokes.isNotEmpty(),
                onClick = {
                    if (page != null) {
                        conversion.replace(context.document, page.id, strokes, conversion.textBox(strokes, text.trim()))
                        context.edits.clearSelection()
                    }
                    dismiss()
                }
            ) { Text(stringResource(Res.string.handwriting_action_convert)) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(Res.string.handwriting_action_cancel)) } }
    )
}

private const val MAX_ALTERNATIVES = 5
