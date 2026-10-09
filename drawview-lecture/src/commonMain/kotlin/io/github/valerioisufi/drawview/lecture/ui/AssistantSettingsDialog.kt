package io.github.valerioisufi.drawview.lecture.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowDropUp
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.lecture.AssistantSettings
import io.github.valerioisufi.drawview.lecture.AssistantSettingsStore
import io.github.valerioisufi.drawview.lecture.LectureTask
import io.github.valerioisufi.drawview.lecture.LocalModels
import io.github.valerioisufi.drawview.lecture.ModelProvider
import io.github.valerioisufi.drawview.lecture.ReceivedLectures
import io.github.valerioisufi.drawview.lecture.WhisperModel
import io.github.valerioisufi.drawview.lecture.generated.resources.*
import io.github.valerioisufi.drawview.lecture.platform.isComputer
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveSheet
import io.github.valerioisufi.drawview.ui.util.formatDecimal
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Settings of the lecture assistant, with no path of the computer in sight: the provider of every
 * kind of request and its model, chosen among those installed (or downloaded from here), the
 * state of Ollama, the speech recognition model and the language spoken, Gemini (key), the
 * lecture (updates, language of the notes); the address of Ollama, a model file of one's own and
 * the reasoning of Gemini in "Advanced".
 */
@Composable
internal fun AssistantSettingsDialog(store: AssistantSettingsStore, localModels: LocalModels, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(store.settings) }
    var showKey by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    // Ollama checked where the student says it is (typed, or found on the network)
    LaunchedEffect(draft.localUrl) {
        delay(URL_CHECK_DELAY_MS)
        localModels.refresh(draft.localUrl)
    }
    val usesOllama = LectureTask.entries.any { it != LectureTask.TRANSCRIPTION && draft.task(it).provider == ModelProvider.LOCAL }
    val usesGemini = LectureTask.entries.any { draft.task(it).provider == ModelProvider.GEMINI }

    // A sheet on a phone, a dialog on a tablet and a desktop
    AdaptiveSheet(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.lecture_title)) },
        actions = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.lecture_action_cancel))
            }
            TextButton(onClick = {
                store.update(draft)
                onDismiss()
            }) {
                Text(stringResource(Res.string.lecture_action_save))
            }
        }
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // --- Whether the models follow the lectures here ---
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.lecture_settings_generate_here), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = draft.generateHere, onCheckedChange = { draft = draft.copy(generateHere = it) })
            }
            Hint(stringResource(Res.string.lecture_settings_generate_here_hint))
            if (draft.generateHere) {
                HorizontalDivider()
                // --- Models of every kind of request ---
                SectionTitle(Res.string.lecture_settings_models)
                for (task in LectureTask.entries) {
                    TaskModelRow(
                        task = task,
                        settings = draft,
                        localModels = localModels,
                        onChange = { draft = it.consistent() }
                    )
                }

                // --- Ollama ---
                if (usesOllama) {
                    HorizontalDivider()
                    SectionTitle(Res.string.lecture_settings_local_section)
                    OllamaStatus(localModels)
                    // Not on this device: on a computer of the local network
                    if (!isComputer) OllamaOnTheNetwork(draft.localUrl, localModels) { draft = draft.copy(localUrl = it) }
                }

                // --- Gemini ---
                if (usesGemini) {
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
                // The lectures recorded where the models are not
                Column {
                    Text(stringResource(Res.string.lecture_settings_received), style = MaterialTheme.typography.bodyMedium)
                    for (choice in ReceivedLectures.entries) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { draft = draft.copy(receivedLectures = choice) }
                        ) {
                            RadioButton(selected = draft.receivedLectures == choice, onClick = { draft = draft.copy(receivedLectures = choice) })
                            Text(
                                stringResource(
                                    when (choice) {
                                        ReceivedLectures.ASK -> Res.string.lecture_settings_received_ask
                                        ReceivedLectures.ALWAYS -> Res.string.lecture_settings_received_always
                                        ReceivedLectures.NEVER -> Res.string.lecture_settings_received_never
                                    }
                                ),
                                style = MaterialTheme.typography.bodyMedium
                            )
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

                // --- Advanced ---
                HorizontalDivider()
                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(stringResource(Res.string.lecture_settings_advanced))
                    Icon(if (showAdvanced) Icons.Outlined.ArrowDropUp else Icons.Outlined.ArrowDropDown, contentDescription = null)
                }
                if (showAdvanced) {
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
                        label = { Text(stringResource(Res.string.lecture_settings_whisper_file)) },
                        supportingText = { Text(stringResource(Res.string.lecture_settings_whisper_file_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ThinkingLevel(draft.thinkingLevel) { draft = draft.copy(thinkingLevel = it) }
                }
            }
        }
    }
}

/**
 * Online or local for [task], and the model of the chosen provider: the transcription on this
 * computer has its speech recognition model and language, the other requests a model of Ollama.
 */
@Composable
private fun TaskModelRow(task: LectureTask, settings: AssistantSettings, localModels: LocalModels, onChange: (AssistantSettings) -> Unit) {
    val model = settings.task(task)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val label = @Composable { modifier: Modifier ->
            Text(text = stringResource(taskLabel(task)), style = MaterialTheme.typography.bodyMedium, modifier = modifier)
        }
        val choice = @Composable { modifier: Modifier ->
            val providers = ModelProvider.entries
            SingleChoiceSegmentedButtonRow(modifier = modifier) {
                providers.forEachIndexed { index, provider ->
                    // Without the speech recognition here, the audio is transcribed (and followed) online
                    val possible = provider == ModelProvider.GEMINI || localModels.canTranscribe ||
                        (task != LectureTask.TRANSCRIPTION && task != LectureTask.LIVE_NOTES)
                    SegmentedButton(
                        selected = model.provider == provider,
                        enabled = possible,
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
        // Beside each other, or the label above on a narrow screen (a phone)
        BoxWithConstraints {
            if (maxWidth >= TaskRowMinWidth) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    label(Modifier.weight(1f))
                    choice(Modifier.width(220.dp))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    label(Modifier)
                    choice(Modifier.fillMaxWidth())
                }
            }
        }
        when {
            task == LectureTask.TRANSCRIPTION && model.provider == ModelProvider.LOCAL -> TranscriptionSetup(settings, localModels, onChange)
            task == LectureTask.TRANSCRIPTION -> {}
            model.provider == ModelProvider.GEMINI -> OutlinedTextField(
                value = model.gemini,
                onValueChange = { onChange(settings.withTask(task, model.copy(gemini = it))) },
                label = { Text(stringResource(Res.string.lecture_settings_gemini_model)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            else -> OllamaModelPicker(
                selected = model.local,
                recommended = AssistantSettings.defaultTask(task).local,
                localModels = localModels,
                onSelect = { onChange(settings.withTask(task, model.copy(local = it))) }
            )
        }
        taskHint(task, settings)?.let { Hint(stringResource(it)) }
        if (task == LectureTask.FINAL_REVIEW) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(Res.string.lecture_settings_review_at_end), style = MaterialTheme.typography.bodyMedium)
                    if (!settings.reviewAtTheEnd) Hint(stringResource(Res.string.lecture_settings_review_on_request_hint))
                }
                Switch(checked = settings.reviewAtTheEnd, onCheckedChange = { onChange(settings.copy(reviewAtTheEnd = it)) })
            }
        }
    }
}

/** The model of Ollama of a request: typed, or chosen among those installed and the recommended one. */
@Composable
private fun OllamaModelPicker(selected: String, recommended: String, localModels: LocalModels, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            OutlinedTextField(
                value = selected,
                onValueChange = onSelect,
                label = { Text(stringResource(Res.string.lecture_settings_ollama_model)) },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { expanded = true }) { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) }
                },
                modifier = Modifier.fillMaxWidth()
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (model in localModels.installed) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                model.name + " · " + formatSize(model.sizeBytes) +
                                    if (model.readsImages) " · " + stringResource(Res.string.lecture_local_reads_images) else ""
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelect(model.name)
                        }
                    )
                }
                if (!localModels.isInstalled(recommended)) {
                    DropdownMenuItem(
                        text = { Text("$recommended · " + stringResource(Res.string.lecture_local_recommended_not_installed)) },
                        onClick = {
                            expanded = false
                            onSelect(recommended)
                        }
                    )
                }
            }
        }
        val installed = localModels.installed.firstOrNull { it.name == selected || it.name == "$selected:latest" }
        val progress = localModels.downloads[selected]
        val error = localModels.downloadError?.takeIf { it.first == selected }?.second
        when {
            progress != null -> DownloadProgress(progress)
            error != null -> Hint(stringResource(Res.string.lecture_local_download_failed, error))
            installed != null && !installed.readsImages -> Hint(stringResource(Res.string.lecture_local_no_images))
            installed == null && selected.isNotBlank() && localModels.ollamaState == LocalModels.OllamaState.RUNNING ->
                FilledTonalButton(onClick = { localModels.pull(selected) }) {
                    Text(stringResource(Res.string.lecture_local_download, selected))
                }
        }
    }
}

/** The speech recognition model (downloaded from here) and the language spoken. */
@Composable
private fun TranscriptionSetup(settings: AssistantSettings, localModels: LocalModels, onChange: (AssistantSettings) -> Unit) {
    if (!localModels.canTranscribe) {
        Hint(stringResource(Res.string.lecture_whisper_unavailable))
        return
    }
    // The model chosen, or the largest one downloaded (the recommended one when none is)
    val chosen = WhisperModel.entries.firstOrNull { localModels.whisperPath(it) == settings.whisperModel }
        ?: if (settings.whisperModel.isBlank()) localModels.whisperModels.maxByOrNull { it.sizeBytes } ?: WhisperModel.LARGE_V3_TURBO_Q5 else null
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Choice(
            label = stringResource(Res.string.lecture_whisper_model),
            value = chosen?.let { it.label + " · " + formatSize(it.sizeBytes) } ?: settings.whisperModel.substringAfterLast('/'),
            options = WhisperModel.entries.map { whisperLabel(it, localModels) to it },
            onSelect = { onChange(settings.copy(whisperModel = localModels.whisperPath(it))) },
            modifier = Modifier.weight(2f)
        )
        Choice(
            label = stringResource(Res.string.lecture_settings_whisper_language),
            value = languageName(settings.whisperLanguage),
            options = SPOKEN_LANGUAGES.map { languageName(it) to it },
            onSelect = { onChange(settings.copy(whisperLanguage = it)) },
            modifier = Modifier.weight(1f)
        )
    }
    if (chosen == null) return
    val progress = localModels.downloads[chosen.fileName]
    val error = localModels.downloadError?.takeIf { it.first == chosen.fileName }?.second
    when {
        progress != null -> DownloadProgress(progress)
        chosen in localModels.whisperModels -> Hint("✓ " + stringResource(Res.string.lecture_whisper_ready))
        else -> {
            error?.let { Hint(stringResource(Res.string.lecture_local_download_failed, it)) }
            FilledTonalButton(onClick = { localModels.downloadWhisper(chosen) }) {
                Text(stringResource(Res.string.lecture_local_download, formatSize(chosen.sizeBytes)))
            }
        }
    }
}

/** A model in the menu: size, the recommended one, those downloaded. */
@Composable
private fun whisperLabel(model: WhisperModel, localModels: LocalModels): String = buildString {
    append(model.label).append(" · ").append(formatSize(model.sizeBytes))
    if (model == WhisperModel.LARGE_V3_TURBO_Q5) append(" · ").append(stringResource(Res.string.lecture_local_recommended))
    if (model in localModels.whisperModels) append(" ✓")
}

/** Ollama: running, being looked for, or to be installed. */
@Composable
private fun OllamaStatus(localModels: LocalModels) {
    val uriHandler = LocalUriHandler.current
    when (localModels.ollamaState) {
        LocalModels.OllamaState.CHECKING -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Hint(stringResource(Res.string.lecture_local_ollama_checking))
        }
        LocalModels.OllamaState.RUNNING -> Hint("✓ " + stringResource(Res.string.lecture_local_ollama_running, localModels.ollamaVersion.orEmpty()))
        LocalModels.OllamaState.NOT_FOUND -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Res.string.lecture_local_ollama_missing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isComputer) {
                    FilledTonalButton(onClick = { uriHandler.openUri(OLLAMA_DOWNLOAD_URL) }) {
                        Text(stringResource(Res.string.lecture_local_ollama_install))
                    }
                }
                TextButton(onClick = { localModels.refresh() }) {
                    Text(stringResource(Res.string.lecture_local_check_again))
                }
            }
        }
    }
}

/**
 * Ollama on a computer of the local network (this device has none): its address, typed or found
 * among the devices of the network.
 */
@Composable
private fun OllamaOnTheNetwork(url: String, localModels: LocalModels, onUrl: (String) -> Unit) {
    Hint(stringResource(Res.string.lecture_local_network_hint))
    OutlinedTextField(
        value = url,
        onValueChange = onUrl,
        label = { Text(stringResource(Res.string.lecture_settings_local_url)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { localModels.findOnNetwork() }, enabled = !localModels.isSearching) {
            Text(stringResource(Res.string.lecture_local_find))
        }
        if (localModels.isSearching) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
    }
    for (server in localModels.foundServers) {
        TextButton(onClick = { onUrl(server) }) {
            Text((if (server == url) "✓ " else "") + server)
        }
    }
}

@Composable
private fun DownloadProgress(progress: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (progress < 0f) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Hint(
            if (progress < 0f) stringResource(Res.string.lecture_local_downloading_unknown)
            else stringResource(Res.string.lecture_local_downloading, (progress * 100).roundToInt())
        )
    }
}

/** A field showing [value], with a menu of [options] (label and value). */
@Composable
private fun <T> Choice(label: String, value: String, options: List<Pair<String, T>>, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { expanded = true }) { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) }
            },
            modifier = Modifier.fillMaxWidth()
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((text, option) in options) {
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    }
                )
            }
        }
    }
}

@Composable
private fun ThinkingLevel(level: String, onChange: (String) -> Unit) {
    Column {
        Text(stringResource(Res.string.lecture_settings_thinking), style = MaterialTheme.typography.bodyMedium)
        val levels = AssistantSettings.THINKING_LEVELS
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            levels.forEachIndexed { index, value ->
                SegmentedButton(
                    selected = level == value,
                    onClick = { onChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, levels.size)
                ) {
                    Text(
                        stringResource(
                            when (value) {
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

/** The languages offered for the speech: "auto", then by ISO code. */
private val SPOKEN_LANGUAGES = listOf("auto", "it", "en", "es", "fr", "de", "pt", "nl", "pl", "ro", "el", "ru", "uk", "zh", "ja", "ar")

/** The name of a language in that language ("auto": detected). */
@Composable
private fun languageName(code: String): String = when (code) {
    "auto" -> stringResource(Res.string.lecture_language_auto)
    "it" -> "Italiano"
    "en" -> "English"
    "es" -> "Español"
    "fr" -> "Français"
    "de" -> "Deutsch"
    "pt" -> "Português"
    "nl" -> "Nederlands"
    "pl" -> "Polski"
    "ro" -> "Română"
    "el" -> "Ελληνικά"
    "ru" -> "Русский"
    "uk" -> "Українська"
    "zh" -> "中文"
    "ja" -> "日本語"
    "ar" -> "العربية"
    else -> code
}

/** 574041195 -> "574 MB", 9600000000 -> "9.6 GB". */
internal fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> formatDecimal(bytes / 1_000_000_000f, 1) + " GB"
    else -> "${(bytes / 1_000_000.0).roundToInt()} MB"
}

private const val OLLAMA_DOWNLOAD_URL = "https://ollama.com/download"

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

/** Narrower than this, the label of a request is above its choice. */
private val TaskRowMinWidth = 400.dp

/** Ollama is checked again this long after its address was typed. */
private const val URL_CHECK_DELAY_MS = 600L
