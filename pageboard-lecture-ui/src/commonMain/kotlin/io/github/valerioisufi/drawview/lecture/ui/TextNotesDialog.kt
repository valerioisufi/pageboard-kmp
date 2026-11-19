package io.github.valerioisufi.drawview.lecture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.canvas.controller.TextImport
import io.github.valerioisufi.drawview.lecture.LectureAssistant
import io.github.valerioisufi.drawview.lecture.ModelProvider
import io.github.valerioisufi.drawview.lecture.TextNotes
import io.github.valerioisufi.drawview.lecture.TextNotesEstimate
import io.github.valerioisufi.drawview.lecture.ui.generated.resources.*
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveSheet
import io.github.valerioisufi.drawview.ui.command.Command
import io.github.valerioisufi.drawview.ui.command.CommandGroup
import io.github.valerioisufi.drawview.ui.editor.TEXT_TO_NOTES_COMMAND_ID
import io.github.valerioisufi.drawview.ui.extension.EditorContext
import io.github.valerioisufi.drawview.ui.render.ComposeTextTileAdapter
import io.github.valerioisufi.drawview.ui.theme.rememberNotesFonts
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** A text being turned into notes by the assistant (see `docs/plans/text-import.md`), until it is placed or dropped. */
internal class TextNotesState {
    /** The text imported, taken from the sheet of the import; `null`: nothing going on. */
    var import: TextImport? by mutableStateOf(null)
}

/**
 * The command the sheet of the import offers ([TEXT_TO_NOTES_COMMAND_ID]): takes the text waiting
 * in [editor] and opens the dialog of the notes ([TextNotesDialog]).
 */
internal fun textToNotesCommand(state: TextNotesState, editor: EditorContext) = Command(
    id = TEXT_TO_NOTES_COMMAND_ID,
    label = { stringResource(Res.string.lecture_text_notes_title) },
    group = CommandGroup.INSERT,
    section = 1,
    isEnabled = { editor.edits.pendingTextImport != null && !editor.document.isReadOnly }
) {
    state.import = editor.edits.pendingTextImport
    editor.edits.pendingTextImport = null
}

/**
 * Turning a text into notes: what the request would cost (the tokens with Gemini, free with a
 * local model), sent only when the student says so; then how many notes and how much of the text
 * they hold, and their placement as the notes of a lecture; back to the sheet of the text at any
 * moment.
 */
@Composable
internal fun TextNotesDialog(assistant: LectureAssistant, documentId: String, editor: EditorContext, state: TextNotesState) {
    val import = state.import ?: return
    val scope = rememberCoroutineScope()
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val notesFonts = rememberNotesFonts()
    val inserter = remember(fontFamilyResolver, notesFonts, editor.rendering.engines) {
        NoteInserter(ComposeTextTileAdapter(fontFamilyResolver, notesFonts, editor.rendering.engines.formulas, editor.rendering.engines.code))
    }
    var estimate by remember(import) { mutableStateOf<TextNotesEstimate?>(null) }
    var running by remember(import) { mutableStateOf(false) }
    var result by remember(import) { mutableStateOf<TextNotes?>(null) }
    var failed by remember(import) { mutableStateOf<String?>(null) }
    LaunchedEffect(import) { estimate = assistant.estimateTextNotes(documentId, import.text) }
    val backToText = {
        state.import = null
        editor.edits.pendingTextImport = import
    }
    val label = stringResource(Res.string.lecture_text_notes_title)

    AdaptiveSheet(
        onDismissRequest = { if (!running) backToText() },
        title = { Text(label) },
        actions = {
            TextButton(onClick = backToText, enabled = !running) { Text(stringResource(Res.string.lecture_text_notes_back)) }
            val notes = result
            val current = estimate
            if (notes != null) {
                Button(
                    onClick = {
                        state.import = null
                        scope.launch {
                            inserter.startPlacementAll(
                                editor, notes.notes, lecture = null, label = label,
                                handwriting = assistant.handwritingTexts(documentId), onInserted = {}, onCancel = {}
                            )
                        }
                    },
                    enabled = notes.notes.isNotEmpty()
                ) { Text(stringResource(Res.string.lecture_text_notes_insert)) }
            } else {
                Button(
                    onClick = {
                        running = true
                        failed = null
                        scope.launch {
                            try {
                                result = assistant.textNotes(documentId, import.text)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failed = e.message ?: e::class.simpleName
                            } finally {
                                running = false
                            }
                        }
                    },
                    enabled = !running && current != null && !current.tooLong && !current.missingApiKey
                ) { Text(stringResource(Res.string.lecture_text_notes_send)) }
            }
        }
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val current = estimate
            val notes = result
            when {
                notes != null -> {
                    Text(stringResource(Res.string.lecture_text_notes_done, notes.notes.size, (notes.coveredShare * 100).roundToInt()), style = MaterialTheme.typography.bodyMedium)
                    if (notes.coveredShare < LOW_COVERAGE) {
                        Text(stringResource(Res.string.lecture_text_notes_left_out), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                running -> {
                    Text(stringResource(Res.string.lecture_text_notes_running), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(modifier = Modifier.padding(vertical = 8.dp))
                }
                current == null -> Text(stringResource(Res.string.lecture_text_notes_no_editor), style = MaterialTheme.typography.bodyMedium)
                current.tooLong -> Text(stringResource(Res.string.lecture_text_notes_too_long, TextNotes.MAX_TEXT_CHARS), style = MaterialTheme.typography.bodyMedium)
                current.missingApiKey -> Text(stringResource(Res.string.lecture_text_notes_no_key), style = MaterialTheme.typography.bodyMedium)
                current.provider == ModelProvider.GEMINI -> Text(
                    stringResource(Res.string.lecture_text_notes_cost_gemini, current.model, current.tokens, current.chars),
                    style = MaterialTheme.typography.bodyMedium
                )
                else -> Text(stringResource(Res.string.lecture_text_notes_cost_local, current.model), style = MaterialTheme.typography.bodyMedium)
            }
            failed?.let { Text(stringResource(Res.string.lecture_text_notes_failed, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** Under this share of the words of the text, the notes are said to leave much of it out. */
private const val LOW_COVERAGE = 0.6
