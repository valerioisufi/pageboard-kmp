package io.github.valerioisufi.drawview.lecture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import io.github.valerioisufi.drawview.lecture.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** Settings of the lecture assistant: API key, model, updates, slides, course. */
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
                    .width(460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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

                OutlinedTextField(
                    value = draft.model,
                    onValueChange = { draft = draft.copy(model = it) },
                    label = { Text(stringResource(Res.string.lecture_settings_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Column {
                    Text(stringResource(Res.string.lecture_settings_chunk, draft.chunkSeconds), style = MaterialTheme.typography.bodyMedium)
                    val range = AssistantSettings.CHUNK_SECONDS_RANGE
                    Slider(
                        value = draft.chunkSeconds.toFloat(),
                        onValueChange = { draft = draft.copy(chunkSeconds = ((it / 15f).roundToInt() * 15).coerceIn(range)) },
                        valueRange = range.first.toFloat()..range.last.toFloat(),
                        steps = (range.last - range.first) / 15 - 1
                    )
                    Text(
                        stringResource(Res.string.lecture_settings_chunk_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column {
                    Text(stringResource(Res.string.lecture_settings_neighbors, draft.neighborPages), style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = draft.neighborPages.toFloat(),
                        onValueChange = { draft = draft.copy(neighborPages = it.roundToInt()) },
                        valueRange = 0f..3f,
                        steps = 2
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(Res.string.lecture_settings_slides), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(Res.string.lecture_settings_slides_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = draft.sendSlides, onCheckedChange = { draft = draft.copy(sendSlides = it) })
                }

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

                OutlinedTextField(
                    value = draft.notesLanguage,
                    onValueChange = { draft = draft.copy(notesLanguage = it) },
                    label = { Text(stringResource(Res.string.lecture_settings_language)) },
                    supportingText = { Text(stringResource(Res.string.lecture_settings_language_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = draft.courseContext,
                    onValueChange = { draft = draft.copy(courseContext = it) },
                    label = { Text(stringResource(Res.string.lecture_settings_context)) },
                    supportingText = { Text(stringResource(Res.string.lecture_settings_context_hint)) },
                    minLines = 3,
                    maxLines = 8,
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
