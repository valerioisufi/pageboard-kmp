package io.github.valerioisufi.drawview.lecture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.lecture.AssistantSettings
import io.github.valerioisufi.drawview.lecture.AssistantSettingsStore
import io.github.valerioisufi.drawview.lecture.LectureTask
import io.github.valerioisufi.drawview.lecture.ModelProvider
import io.github.valerioisufi.drawview.lecture.generated.resources.*
import io.github.valerioisufi.drawview.lecture.transcription.WhisperTranscriber
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * Settings of the lecture assistant: the provider of every kind of request, the local models
 * (Ollama, whisper.cpp), Gemini (key, reasoning), the lecture (updates, language of the notes).
 */
@Composable
internal fun AssistantSettingsDialog(store: AssistantSettingsStore, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(store.settings) }
    var showKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.lecture_title)) },
        text = {
            Column(
                modifier = Modifier
                    .width(500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // --- Models of every kind of request ---
                SectionTitle(Res.string.lecture_settings_models)
                for (task in LectureTask.entries) {
                    TaskModelRow(
                        task = task,
                        settings = draft,
                        onChange = { draft = it.consistent() }
                    )
                }

                // --- On this computer ---
                HorizontalDivider()
                SectionTitle(Res.string.lecture_settings_local_section)
                OutlinedTextField(
                    value = draft.localUrl,
                    onValueChange = { draft = draft.copy(localUrl = it) },
                    label = { Text(stringResource(Res.string.lecture_settings_local_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.whisperModel,
                    onValueChange = { draft = draft.copy(whisperModel = it) },
                    label = { Text(stringResource(Res.string.lecture_settings_whisper_model)) },
                    placeholder = { Text(WhisperTranscriber.modelPath(draft).ifEmpty { WhisperTranscriber.modelsDirectory() + "/ggml-large-v3-turbo-q5_0.bin" }) },
                    supportingText = { Text(stringResource(Res.string.lecture_settings_whisper_model_hint, WhisperTranscriber.modelsDirectory())) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft.whisperServer,
                        onValueChange = { draft = draft.copy(whisperServer = it) },
                        label = { Text(stringResource(Res.string.lecture_settings_whisper_server)) },
                        placeholder = { Text(WhisperTranscriber.serverPath(draft)) },
                        supportingText = { Text(stringResource(Res.string.lecture_settings_whisper_server_hint, WhisperTranscriber.serverPath(draft.copy(whisperServer = "")))) },
                        singleLine = true,
                        modifier = Modifier.weight(2f)
                    )
                    OutlinedTextField(
                        value = draft.whisperLanguage,
                        onValueChange = { draft = draft.copy(whisperLanguage = it) },
                        label = { Text(stringResource(Res.string.lecture_settings_whisper_language)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                // --- Gemini ---
                HorizontalDivider()
                SectionTitle(Res.string.lecture_settings_gemini_section)
                OutlinedTextField(
                    value = draft.apiKey,
                    onValueChange = { draft = draft.copy(apiKey = it) },
                    label = { Text(stringResource(Res.string.lecture_settings_api_key)) },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                imageVector = if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = stringResource(
                                    if (showKey) Res.string.lecture_settings_hide_key else Res.string.lecture_settings_show_key
                                )
                            )
                        }
                    },
                    supportingText = {
                        Text(
                            stringResource(
                                if (draft.apiKey.isBlank() && store.usesEnvironmentKey) Res.string.lecture_settings_api_key_env
                                else Res.string.lecture_settings_api_key_hint
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Column {
                    Text(stringResource(Res.string.lecture_settings_thinking), style = MaterialTheme.typography.bodyMedium)
                    val levels = AssistantSettings.THINKING_LEVELS
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        levels.forEachIndexed { index, level ->
                            SegmentedButton(
                                selected = draft.thinkingLevel == level,
                                onClick = { draft = draft.copy(thinkingLevel = level) },
                                shape = SegmentedButtonDefaults.itemShape(index, levels.size)
                            ) {
                                Text(
                                    stringResource(
                                        when (level) {
                                            "high" -> Res.string.lecture_settings_thinking_high
                                            "medium" -> Res.string.lecture_settings_thinking_medium
                                            else -> Res.string.lecture_settings_thinking_low
                                        }
                                    )
                                )
                            }
                        }
                    }
                }

                // --- The lecture ---
                HorizontalDivider()
                SectionTitle(Res.string.lecture_settings_lecture_section)
                Column {
                    Text(stringResource(Res.string.lecture_settings_chunk, draft.chunkSeconds), style = MaterialTheme.typography.bodyMedium)
                    val range = AssistantSettings.CHUNK_SECONDS_RANGE
                    Slider(
                        value = draft.chunkSeconds.toFloat(),
                        onValueChange = { draft = draft.copy(chunkSeconds = ((it / 15f).roundToInt() * 15).coerceIn(range)) },
                        valueRange = range.first.toFloat()..range.last.toFloat(),
                        steps = (range.last - range.first) / 15 - 1
                    )
                    Hint(stringResource(Res.string.lecture_settings_chunk_hint))
                }

                OutlinedTextField(
                    value = draft.notesLanguage,
                    onValueChange = { draft = draft.copy(notesLanguage = it) },
                    label = { Text(stringResource(Res.string.lecture_settings_language)) },
                    supportingText = { Text(stringResource(Res.string.lecture_settings_language_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                store.update(draft)
                onDismiss()
            }) {
                Text(stringResource(Res.string.lecture_action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.lecture_action_cancel))
            }
        }
    )
}

/**
 * Online or local for [task], and the model of the chosen provider (none for the transcription:
 * Gemini transcribes in the requests of the suggestions, whisper.cpp uses its model).
 */
@Composable
private fun TaskModelRow(task: LectureTask, settings: AssistantSettings, onChange: (AssistantSettings) -> Unit) {
    val model = settings.task(task)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(taskLabel(task)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            val providers = ModelProvider.entries
            SingleChoiceSegmentedButtonRow(modifier = Modifier.width(220.dp)) {
                providers.forEachIndexed { index, provider ->
                    SegmentedButton(
                        selected = model.provider == provider,
                        onClick = {
                            var changed = settings.withTask(task, model.copy(provider = provider))
                            // Back to the audio online: the suggestions online as well
                            if (task == LectureTask.TRANSCRIPTION && provider == ModelProvider.GEMINI) {
                                changed = changed.withTask(
                                    LectureTask.LIVE_NOTES,
                                    changed.task(LectureTask.LIVE_NOTES).copy(provider = ModelProvider.GEMINI)
                                )
                            }
                            onChange(changed)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, providers.size)
                    ) {
                        Text(
                            stringResource(
                                if (provider == ModelProvider.GEMINI) Res.string.lecture_provider_online else Res.string.lecture_provider_local
                            )
                        )
                    }
                }
            }
        }
        if (task != LectureTask.TRANSCRIPTION) {
            OutlinedTextField(
                value = model.model,
                onValueChange = { value ->
                    onChange(
                        settings.withTask(
                            task,
                            if (model.provider == ModelProvider.GEMINI) model.copy(gemini = value) else model.copy(local = value)
                        )
                    )
                },
                label = {
                    Text(
                        stringResource(
                            if (model.provider == ModelProvider.GEMINI) Res.string.lecture_settings_gemini_model else Res.string.lecture_settings_ollama_model
                        )
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        taskHint(task, settings)?.let { Hint(stringResource(it)) }
    }
}

private fun taskLabel(task: LectureTask): StringResource = when (task) {
    LectureTask.TRANSCRIPTION -> Res.string.lecture_task_transcription
    LectureTask.LIVE_NOTES -> Res.string.lecture_task_live_notes
    LectureTask.CATCH_UP -> Res.string.lecture_task_catch_up
    LectureTask.FINAL_REVIEW -> Res.string.lecture_task_final_review
}

/** What the choice of [task] implies. */
private fun taskHint(task: LectureTask, settings: AssistantSettings): StringResource? {
    val local = settings.task(task).provider == ModelProvider.LOCAL
    return when (task) {
        LectureTask.TRANSCRIPTION -> if (local) Res.string.lecture_hint_transcription_local else Res.string.lecture_hint_transcription_online
        LectureTask.LIVE_NOTES -> if (local) Res.string.lecture_hint_live_local else null
        LectureTask.CATCH_UP -> if (local) Res.string.lecture_hint_catch_up_local else null
        LectureTask.FINAL_REVIEW -> if (local) Res.string.lecture_hint_review_local else null
    }
}

@Composable
private fun SectionTitle(text: StringResource) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
