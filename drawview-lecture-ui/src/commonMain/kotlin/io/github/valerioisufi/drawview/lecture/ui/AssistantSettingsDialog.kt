package io.github.valerioisufi.drawview.lecture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
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
import io.github.valerioisufi.drawview.lecture.platform.isComputer
import io.github.valerioisufi.drawview.lecture.ui.generated.resources.*
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveSheet
import io.github.valerioisufi.drawview.ui.adaptive.currentAdaptiveInfo
import io.github.valerioisufi.drawview.ui.command.DesignMenu
import io.github.valerioisufi.drawview.ui.command.MenuItem
import io.github.valerioisufi.drawview.ui.components.BadgeTone
import io.github.valerioisufi.drawview.ui.components.ChipRow
import io.github.valerioisufi.drawview.ui.components.DesignFilterChip
import io.github.valerioisufi.drawview.ui.components.DesignIconButton
import io.github.valerioisufi.drawview.ui.components.DesignSlider
import io.github.valerioisufi.drawview.ui.components.LabelBadge
import io.github.valerioisufi.drawview.ui.components.NavigationItem
import io.github.valerioisufi.drawview.ui.components.SegmentedChoice
import io.github.valerioisufi.drawview.ui.components.SwitchRow
import io.github.valerioisufi.drawview.ui.components.TonalButton
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import io.github.valerioisufi.drawview.ui.util.formatDecimal
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The sections of the settings, in their navigation. */
private enum class SettingsSection(val title: StringResource, val icon: ImageVector) {
    REQUESTS(Res.string.lecture_settings_requests, DrawViewIcons.Sparkles),
    GEMINI(Res.string.lecture_settings_gemini_section, DrawViewIcons.Globe),
    LOCAL(Res.string.lecture_settings_local_section, DrawViewIcons.Monitor),
    LECTURE(Res.string.lecture_settings_lecture_section, DrawViewIcons.Mic),
    ADVANCED(Res.string.lecture_settings_advanced, DrawViewIcons.Sliders)
}

/**
 * Settings of the lecture assistant, with no path of the computer in sight, in sections: the
 * requests (whether the notes are written here, online or local for each one and its model, what
 * is sent to Google), Gemini (the key, the reasoning), this computer (Ollama and its models, the
 * speech recognition), the lecture (language of the notes, updates, the lectures received) and
 * "Advanced" (the address of Ollama, a model file of one's own). The sections in a pane beside them
 * where it is wide, as chips above them on a phone.
 */
@Composable
internal fun AssistantSettingsDialog(store: AssistantSettingsStore, localModels: LocalModels, onDismiss: () -> Unit) {
    val compact = currentAdaptiveInfo().window.isCompact
    // A sheet on a phone, a dialog on a tablet and a desktop
    AdaptiveSheet(onDismissRequest = onDismiss, maxWidth = 940.dp, padded = compact) {
        AssistantSettingsContent(store, localModels, onDismiss, compact)
    }
}

/** What the settings show, in the sheet of a phone ([compact]) or in the dialog. */
@Composable
internal fun ColumnScope.AssistantSettingsContent(store: AssistantSettingsStore, localModels: LocalModels, onDismiss: () -> Unit, compact: Boolean) {
    var draft by remember { mutableStateOf(store.settings) }
    var section by remember { mutableStateOf(SettingsSection.REQUESTS) }
    // Ollama checked where the student says it is (typed, or found on the network)
    LaunchedEffect(draft.localUrl) {
        delay(URL_CHECK_DELAY_MS)
        localModels.refresh(draft.localUrl)
    }
    val content: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (section) {
                SettingsSection.REQUESTS -> RequestsSection(draft, localModels) { draft = it }
                SettingsSection.GEMINI -> GeminiSection(draft, store) { draft = it }
                SettingsSection.LOCAL -> LocalSection(draft, localModels) { draft = it }
                SettingsSection.LECTURE -> LectureSection(draft) { draft = it }
                SettingsSection.ADVANCED -> AdvancedSection(draft) { draft = it }
            }
        }
    }
    val actions: @Composable () -> Unit = {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.lecture_action_cancel)) }
            Button(onClick = {
                store.update(draft)
                onDismiss()
            }) { Text(stringResource(Res.string.lecture_action_save)) }
        }
    }

    if (compact) {
        Text(
            stringResource(Res.string.lecture_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)
        )
        ChipRow(contentPadding = PaddingValues(horizontal = 24.dp)) {
            for (option in SettingsSection.entries) {
                DesignFilterChip(stringResource(option.title), selected = section == option, onClick = { section = option })
            }
        }
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) { content() }
        Box(modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp)) { actions() }
    } else {
        Row(modifier = Modifier.heightIn(max = 820.dp).fillMaxHeight(0.92f)) {
            Column(
                modifier = Modifier
                    .width(240.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 12.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    stringResource(Res.string.lecture_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 12.dp, bottom = 16.dp)
                )
                for (option in SettingsSection.entries) {
                    NavigationItem(option.icon, stringResource(option.title), selected = section == option, onClick = { section = option })
                }
            }
            Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 24.dp)) {
                Row(modifier = Modifier.padding(start = 24.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(section.title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    DesignIconButton(DrawViewIcons.Close, stringResource(Res.string.lecture_action_close), onClick = onDismiss)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                ) { content() }
                Box(modifier = Modifier.padding(horizontal = 24.dp)) { actions() }
            }
        }
    }
}

/** A group of settings on its card: the lowest container rounded 20. */
@Composable
private fun SettingsCard(title: StringResource? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        title?.let { Text(stringResource(it), style = MaterialTheme.typography.titleSmall) }
        content()
    }
}

/** Whether the notes are written here; online or local for every request, and its model; what is sent to Google. */
@Composable
private fun RequestsSection(draft: AssistantSettings, localModels: LocalModels, onChange: (AssistantSettings) -> Unit) {
    Text(stringResource(Res.string.lecture_settings_requests_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SettingsCard {
        SwitchRow(
            text = stringResource(Res.string.lecture_settings_generate_here),
            hint = stringResource(Res.string.lecture_settings_generate_here_hint),
            checked = draft.generateHere,
            onCheckedChange = { onChange(draft.copy(generateHere = it)) }
        )
    }
    if (!draft.generateHere) return
    for (task in LectureTask.entries) {
        TaskModelRow(task = task, settings = draft, localModels = localModels, onChange = onChange)
    }
    PrivacyNote(privacyNote(draft))
}

/** The key of Gemini and how much it reasons. */
@Composable
private fun GeminiSection(draft: AssistantSettings, store: AssistantSettingsStore, onChange: (AssistantSettings) -> Unit) {
    var showKey by remember { mutableStateOf(false) }
    SettingsCard(Res.string.lecture_settings_api_key_title) {
        OutlinedTextField(
            value = draft.apiKey,
            onValueChange = { onChange(draft.copy(apiKey = it)) },
            label = { Text(stringResource(Res.string.lecture_settings_api_key)) },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                DesignIconButton(
                    icon = if (showKey) DrawViewIcons.EyeOff else DrawViewIcons.Eye,
                    label = stringResource(if (showKey) Res.string.lecture_settings_hide_key else Res.string.lecture_settings_show_key),
                    onClick = { showKey = !showKey }
                )
            },
            supportingText = {
                Text(
                    stringResource(
                        if (draft.apiKey.isBlank() && store.usesEnvironmentKey) Res.string.lecture_settings_api_key_env
                        else Res.string.lecture_settings_api_key_hint
                    )
                )
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
    }
    SettingsCard(Res.string.lecture_settings_thinking) {
        ThinkingLevel(draft.thinkingLevel) { onChange(draft.copy(thinkingLevel = it)) }
    }
}

/** Ollama and its models (on a computer of the network, from a phone), the speech recognition of this computer. */
@Composable
private fun LocalSection(draft: AssistantSettings, localModels: LocalModels, onChange: (AssistantSettings) -> Unit) {
    SettingsCard(Res.string.lecture_settings_ollama) {
        OllamaStatus(localModels)
        InstalledModels(localModels)
        // Not on this device: on a computer of the local network
        if (!isComputer) OllamaOnTheNetwork(draft.localUrl, localModels) { onChange(draft.copy(localUrl = it)) }
    }
    SettingsCard(Res.string.lecture_whisper_model) {
        TranscriptionSetup(draft, localModels, onChange)
    }
}

/** The language of the notes, how often the assistant listens and writes notes, the lectures received without a transcript. */
@Composable
private fun LectureSection(draft: AssistantSettings, onChange: (AssistantSettings) -> Unit) {
    SettingsCard {
        OutlinedTextField(
            value = draft.notesLanguage,
            onValueChange = { onChange(draft.copy(notesLanguage = it)) },
            label = { Text(stringResource(Res.string.lecture_settings_language)) },
            supportingText = { Text(stringResource(Res.string.lecture_settings_language_hint)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
    }
    SettingsCard {
        Text(stringResource(Res.string.lecture_settings_chunk, draft.chunkSeconds), style = MaterialTheme.typography.bodyLarge)
        val range = AssistantSettings.CHUNK_SECONDS_RANGE
        DesignSlider(
            value = draft.chunkSeconds.toFloat(),
            onValueChange = { onChange(draft.copy(chunkSeconds = ((it / 15f).roundToInt() * 15).coerceIn(range))) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / 15 - 1,
            modifier = Modifier.fillMaxWidth()
        )
        Hint(stringResource(Res.string.lecture_settings_chunk_hint))
    }
    SettingsCard {
        Text(stringResource(Res.string.lecture_settings_notes, draft.notesSeconds / 60), style = MaterialTheme.typography.bodyLarge)
        val range = AssistantSettings.NOTES_SECONDS_RANGE
        DesignSlider(
            value = draft.notesSeconds.toFloat(),
            onValueChange = { onChange(draft.copy(notesSeconds = ((it / 60f).roundToInt() * 60).coerceIn(range))) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / 60 - 1,
            modifier = Modifier.fillMaxWidth()
        )
        Hint(stringResource(Res.string.lecture_settings_notes_hint))
    }
    // The lectures recorded where the models are not
    SettingsCard(Res.string.lecture_settings_received) {
        Column {
            for (choice in ReceivedLectures.entries) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .selectable(selected = draft.receivedLectures == choice, role = Role.RadioButton) { onChange(draft.copy(receivedLectures = choice)) }
                ) {
                    RadioButton(selected = draft.receivedLectures == choice, onClick = null, modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp))
                    Spacer(Modifier.width(8.dp))
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
    }
}

/** The address of Ollama and a speech recognition model file of one's own. */
@Composable
private fun AdvancedSection(draft: AssistantSettings, onChange: (AssistantSettings) -> Unit) {
    SettingsCard {
        OutlinedTextField(
            value = draft.localUrl,
            onValueChange = { onChange(draft.copy(localUrl = it)) },
            label = { Text(stringResource(Res.string.lecture_settings_local_url)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = draft.whisperModel,
            onValueChange = { onChange(draft.copy(whisperModel = it)) },
            label = { Text(stringResource(Res.string.lecture_settings_whisper_file)) },
            supportingText = { Text(stringResource(Res.string.lecture_settings_whisper_file_hint)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * A request on its card: its name and what the choice implies beside online or this computer and
 * the model of the one chosen (one above the other on a narrow screen); the language spoken of the
 * transcription, whether the final review is written at the end.
 */
@Composable
private fun TaskModelRow(task: LectureTask, settings: AssistantSettings, localModels: LocalModels, onChange: (AssistantSettings) -> Unit) {
    val model = settings.task(task)
    val label = @Composable {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = stringResource(taskLabel(task)), style = MaterialTheme.typography.titleSmall)
            taskHint(task, settings)?.let { Hint(stringResource(it)) }
        }
    }
    val choice = @Composable {
        // The transcription is on this computer only: no choice of the provider
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (task == LectureTask.TRANSCRIPTION) {
                TranscriptionSetup(settings, localModels, onChange)
                return@Column
            }
            SegmentedChoice(
                options = ModelProvider.entries,
                selected = model.provider,
                label = { stringResource(if (it == ModelProvider.GEMINI) Res.string.lecture_provider_online else Res.string.lecture_provider_local) },
                icon = { if (it == ModelProvider.GEMINI) DrawViewIcons.Globe else DrawViewIcons.Monitor },
                textStyle = MaterialTheme.typography.labelMedium,
                onSelect = { onChange(settings.withTask(task, model.copy(provider = it))) }
            )
            when {
                model.provider == ModelProvider.GEMINI -> OutlinedTextField(
                    value = model.gemini,
                    onValueChange = { onChange(settings.withTask(task, model.copy(gemini = it))) },
                    label = { Text(stringResource(Res.string.lecture_settings_gemini_model)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                else -> OllamaModelPicker(
                    selected = model.local,
                    recommended = AssistantSettings.defaultTask(task).local,
                    localModels = localModels,
                    onSelect = { onChange(settings.withTask(task, model.copy(local = it))) }
                )
            }
        }
    }
    SettingsCard {
        // Beside each other, or the label above on a narrow screen (a phone)
        BoxWithConstraints {
            if (maxWidth >= TaskRowMinWidth) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) { label() }
                    Box(modifier = Modifier.width(TaskChoiceWidth)) { choice() }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    label()
                    choice()
                }
            }
        }
        if (task == LectureTask.TRANSCRIPTION) {
            Choice(
                label = stringResource(Res.string.lecture_settings_whisper_language),
                value = languageName(settings.spokenLanguage),
                options = SPOKEN_LANGUAGES.map { languageName(it) to it },
                onSelect = { onChange(settings.copy(spokenLanguage = it)) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (task == LectureTask.FINAL_REVIEW) {
            SwitchRow(
                text = stringResource(Res.string.lecture_settings_review_at_end),
                hint = stringResource(Res.string.lecture_settings_review_on_request_hint).takeIf { !settings.reviewAtTheEnd },
                checked = settings.reviewAtTheEnd,
                onCheckedChange = { onChange(settings.copy(reviewAtTheEnd = it)) }
            )
        }
    }
}

/** The models of Ollama installed: the name, the size, whether it reads images, the recommended ones, those downloading. */
@Composable
private fun InstalledModels(localModels: LocalModels) {
    if (localModels.installed.isEmpty()) return
    val recommended = LectureTask.entries.map { AssistantSettings.defaultTask(it).local }.toSet()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (model in localModels.installed) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(model.name, style = MaterialTheme.typography.titleSmall)
                Text(formatSize(model.sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (model.readsImages) LabelBadge(stringResource(Res.string.lecture_local_reads_images), BadgeTone.Blue, icon = DrawViewIcons.Image)
                if (model.name in recommended || model.name.removeSuffix(":latest") in recommended) {
                    LabelBadge(stringResource(Res.string.lecture_local_recommended), BadgeTone.Green, icon = DrawViewIcons.Star)
                }
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
                    IconButton(onClick = { expanded = true }) { Icon(DrawViewIcons.ChevronDown, contentDescription = null, modifier = Modifier.size(20.dp)) }
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
            DesignMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (model in localModels.installed) {
                    MenuItem(
                        text = model.name + " · " + formatSize(model.sizeBytes) +
                            if (model.readsImages) " · " + stringResource(Res.string.lecture_local_reads_images) else "",
                        checked = model.name == selected,
                        alignWithIcons = true,
                        onClick = {
                            expanded = false
                            onSelect(model.name)
                        }
                    )
                }
                if (!localModels.isInstalled(recommended)) {
                    MenuItem(
                        text = "$recommended · " + stringResource(Res.string.lecture_local_recommended_not_installed),
                        icon = rememberVectorPainter(DrawViewIcons.Download),
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
                TonalButton(onClick = { localModels.pull(selected) }) {
                    Text(stringResource(Res.string.lecture_local_download, selected))
                }
        }
    }
}

/** The speech recognition model of this computer (downloaded from here). */
@Composable
private fun TranscriptionSetup(settings: AssistantSettings, localModels: LocalModels, onChange: (AssistantSettings) -> Unit) {
    if (!localModels.canTranscribe) {
        Hint(stringResource(Res.string.lecture_whisper_unavailable))
        return
    }
    // The model chosen, or the largest one downloaded (the recommended one when none is)
    val chosen = WhisperModel.entries.firstOrNull { localModels.whisperPath(it) == settings.whisperModel }
        ?: if (settings.whisperModel.isBlank()) localModels.whisperModels.maxByOrNull { it.sizeBytes } ?: WhisperModel.LARGE_V3_TURBO_Q5 else null
    Choice(
        label = stringResource(Res.string.lecture_whisper_model),
        value = chosen?.let { it.label + " · " + formatSize(it.sizeBytes) } ?: settings.whisperModel.substringAfterLast('/'),
        options = WhisperModel.entries.map { whisperLabel(it, localModels) to it },
        onSelect = { onChange(settings.copy(whisperModel = localModels.whisperPath(it))) },
        modifier = Modifier.fillMaxWidth()
    )
    if (chosen == null) return
    val progress = localModels.downloads[chosen.fileName]
    val error = localModels.downloadError?.takeIf { it.first == chosen.fileName }?.second
    when {
        progress != null -> DownloadProgress(progress)
        chosen in localModels.whisperModels -> {
            Ready(chosen.label + " · " + stringResource(Res.string.lecture_whisper_ready))
            if (localModels.usesNeuralEngine) NeuralEngineSetup(chosen, localModels)
        }
        else -> {
            error?.let { Hint(stringResource(Res.string.lecture_local_download_failed, it)) }
            TonalButton(onClick = { localModels.downloadWhisper(chosen) }) {
                Text(stringResource(Res.string.lecture_local_download, formatSize(chosen.sizeBytes)))
            }
        }
    }
}

/**
 * The encoder of [model] for the Neural Engine of a Mac (docs/transcription-queue.md): downloaded, being
 * downloaded or prepared, or to download.
 */
@Composable
private fun NeuralEngineSetup(model: WhisperModel, localModels: LocalModels) {
    val name = model.encoderFileName
    val progress = localModels.downloads[name]
    val error = localModels.downloadError?.takeIf { it.first == name }?.second
    when {
        model in localModels.encoders && name !in localModels.preparing ->
            Ready(stringResource(Res.string.lecture_whisper_neural_engine_ready))
        name in localModels.preparing -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, trackColor = MaterialTheme.colorScheme.primaryContainer)
            Spacer(Modifier.width(8.dp))
            Hint(stringResource(Res.string.lecture_whisper_neural_engine_preparing))
        }
        progress != null -> {
            Hint(stringResource(Res.string.lecture_whisper_neural_engine))
            DownloadProgress(progress)
        }
        else -> {
            Hint(stringResource(Res.string.lecture_whisper_neural_engine))
            error?.let { Hint(stringResource(Res.string.lecture_local_download_failed, it)) }
            TonalButton(onClick = { localModels.downloadEncoder(model) }) {
                Text(stringResource(Res.string.lecture_whisper_neural_engine_download, formatSize(model.encoderSizeBytes)))
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
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, trackColor = MaterialTheme.colorScheme.primaryContainer)
            Spacer(Modifier.width(8.dp))
            Hint(stringResource(Res.string.lecture_local_ollama_checking))
        }
        LocalModels.OllamaState.RUNNING -> Ready(stringResource(Res.string.lecture_local_ollama_running, localModels.ollamaVersion.orEmpty()))
        LocalModels.OllamaState.NOT_FOUND -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Res.string.lecture_local_ollama_missing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isComputer) {
                    TonalButton(onClick = { uriHandler.openUri(OLLAMA_DOWNLOAD_URL) }) {
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
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TonalButton(onClick = { localModels.findOnNetwork() }, enabled = !localModels.isSearching) {
            Text(stringResource(Res.string.lecture_local_find))
        }
        if (localModels.isSearching) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
    }
    for (server in localModels.foundServers) {
        TextButton(onClick = { onUrl(server) }) {
            if (server == url) {
                Icon(DrawViewIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(server)
        }
    }
}

@Composable
private fun DownloadProgress(progress: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val track = MaterialTheme.colorScheme.surfaceContainerHighest
        if (progress < 0f) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), trackColor = track, gapSize = 0.dp)
        else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), trackColor = track, gapSize = 0.dp, drawStopIndicator = {})
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
                IconButton(onClick = { expanded = true }) { Icon(DrawViewIcons.ChevronDown, contentDescription = null, modifier = Modifier.size(20.dp)) }
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
        DesignMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((text, option) in options) {
                MenuItem(
                    text = text,
                    checked = text == value,
                    alignWithIcons = true,
                    onClick = {
                        expanded = false
                        onSelect(option)
                    }
                )
            }
        }
    }
}

/** How much Gemini reasons: fast, balanced, thorough. */
@Composable
private fun ThinkingLevel(level: String, onChange: (String) -> Unit) {
    SegmentedChoice(
        options = AssistantSettings.THINKING_LEVELS,
        selected = level,
        label = {
            stringResource(
                when (it) {
                    "high" -> Res.string.lecture_settings_thinking_high
                    "medium" -> Res.string.lecture_settings_thinking_medium
                    else -> Res.string.lecture_settings_thinking_low
                }
            )
        },
        onSelect = onChange
    )
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
        LectureTask.TRANSCRIPTION -> Res.string.lecture_hint_transcription_local
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

/** Something ready: the check in the primary color and what it is. */
@Composable
private fun Ready(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(DrawViewIcons.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Narrower than this, the label of a request is above its choice. */
private val TaskRowMinWidth = 520.dp

/** The width of the choice of a request beside its label: the longest model of whisper.cpp with its size. */
private val TaskChoiceWidth = 320.dp

/** Ollama is checked again this long after its address was typed. */
private const val URL_CHECK_DELAY_MS = 600L
