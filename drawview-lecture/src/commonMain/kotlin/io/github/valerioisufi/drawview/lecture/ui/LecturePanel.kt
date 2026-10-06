package io.github.valerioisufi.drawview.lecture.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PostAdd
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.lecture.AssistantSettings
import io.github.valerioisufi.drawview.lecture.ImportStep
import io.github.valerioisufi.drawview.lecture.LectureAssistant
import io.github.valerioisufi.drawview.lecture.audio.importableExtensions
import io.github.valerioisufi.drawview.lecture.platform.chooseFileToOpen
import io.github.valerioisufi.drawview.lecture.platform.chooseFileToSave
import io.github.valerioisufi.drawview.lecture.platform.isComputer
import io.github.valerioisufi.drawview.lecture.LectureProblem
import io.github.valerioisufi.drawview.lecture.ControllerNotebookSource
import io.github.valerioisufi.drawview.lecture.Lecture
import io.github.valerioisufi.drawview.lecture.LectureRecorder
import io.github.valerioisufi.drawview.lecture.LectureSummary
import io.github.valerioisufi.drawview.lecture.LectureTask
import io.github.valerioisufi.drawview.lecture.LectureStatus
import io.github.valerioisufi.drawview.lecture.NoteKind
import io.github.valerioisufi.drawview.lecture.NoteSuggestion
import io.github.valerioisufi.drawview.lecture.ReviewProvider
import io.github.valerioisufi.drawview.lecture.ReviewStatus
import io.github.valerioisufi.drawview.lecture.SharedData
import io.github.valerioisufi.drawview.lecture.SuggestionStatus
import io.github.valerioisufi.drawview.lecture.TokenUsage
import io.github.valerioisufi.drawview.lecture.formatLectureTime
import io.github.valerioisufi.drawview.lecture.resolvedPageIndex
import io.github.valerioisufi.drawview.lecture.generated.resources.*
import io.github.valerioisufi.drawview.latex.LatexEngine
import io.github.valerioisufi.drawview.ui.editor.MarkdownTextUtil
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val PanelWidth = 380.dp

/**
 * The panel of the lecture assistant, beside the canvas of the editor of [documentId]: recording,
 * suggested notes, transcript, outline and final review.
 */
@Composable
internal fun LecturePanel(assistant: LectureAssistant, documentId: String, controller: DrawViewController) {
    // The recording of the document sees its pages while the editor is open (and goes on without)
    DisposableEffect(assistant, documentId, controller) {
        val notebook = ControllerNotebookSource(controller)
        assistant.onEditorShown(documentId, notebook)
        onDispose { assistant.onEditorHidden(documentId, notebook) }
    }
    var showSettings by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = assistant.isPanelOpen,
        enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
        exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut()
    ) {
        Row(modifier = Modifier.fillMaxHeight()) {
            VerticalDivider()
            // A surface, so that the texts take the content color of the theme
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .width(PanelWidth)
                    .fillMaxHeight()
            ) {
                Column {
                    PanelHeader(
                        // The settings of the models, where they are
                        onSettings = { showSettings = true }.takeIf { assistant.canGenerate },
                        onClose = { assistant.isPanelOpen = false }
                    )
                    val lecture = assistant.shownLecture(documentId)
                    val recorder = assistant.recorderOf(documentId)
                    LectureSelector(assistant, documentId, lecture)
                    val importTitle = stringResource(Res.string.lecture_import_title)
                    RecordingSection(
                        assistant = assistant,
                        lecture = lecture,
                        recorder = recorder,
                        onImport = {
                            assistant.clearImportAndExportMessages()
                            chooseFileToOpen(importTitle, importableExtensions())?.let { file ->
                                assistant.importRecording(documentId, controller, file)
                            }
                        },
                        onStart = {
                            // A key is needed only when something is sent to Gemini
                            val settings = assistant.settingsStore.settings
                            if (assistant.canGenerate && settings.sentToGoogle.isNotEmpty() && !assistant.settingsStore.hasApiKey) showSettings = true
                            else assistant.record(documentId, controller)
                        },
                        onOpenSettings = { showSettings = true }
                    )
                    if (lecture != null) PlayerBar(lecture)
                    HorizontalDivider()
                    if (lecture != null) {
                        ProfessorPageBanner(lecture, controller)
                        LectureTabs(assistant, lecture, controller)
                    }
                }
            }
        }
    }

    if (showSettings) {
        AssistantSettingsDialog(store = assistant.settingsStore, localModels = assistant.localModels, onDismiss = { showSettings = false })
    }
}

@Composable
private fun PanelHeader(onSettings: (() -> Unit)?, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(Res.string.lecture_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        if (onSettings != null) {
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = stringResource(Res.string.lecture_action_settings))
            }
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(Res.string.lecture_action_close))
        }
    }
}

/**
 * The lectures of the document: the one shown, the others to choose from, and the deletion of
 * the shown one (with its audio).
 */
@Composable
private fun LectureSelector(assistant: LectureAssistant, documentId: String, lecture: Lecture?) {
    val lectures = assistant.lecturesOf(documentId)
    if (lectures.isEmpty() || lecture == null) return
    var expanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<LectureSummary?>(null) }
    val shown = lectures.firstOrNull { it.directory == lecture.directory }
    val isActive = assistant.activeRecorder?.lecture === lecture || assistant.importing === lecture
    val exportTitle = stringResource(Res.string.lecture_export_title)

    Box(modifier = Modifier.padding(horizontal = 8.dp)) {
        TextButton(onClick = { expanded = true }) {
            Text(
                text = formatLectureDate(lecture.startedAt) + " · " + formatLectureTime(lecture.elapsedMs),
                style = MaterialTheme.typography.labelLarge
            )
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (summary in lectures) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                formatLectureDate(summary.startedAt) + " · " + formatLectureTime(summary.durationMs) +
                                    (if (summary.hasReview) " · ✓" else "")
                            )
                            summary.recordedElsewhere?.let {
                                Text(
                                    stringResource(Res.string.lecture_recorded_on, it),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        assistant.show(documentId, summary)
                    }
                )
            }
            if (shown != null && !isActive && assistant.canImportAndExport && lecture.status == LectureStatus.FINISHED && lecture.hasAudio) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.lecture_export_audio)) },
                    onClick = {
                        expanded = false
                        assistant.clearImportAndExportMessages()
                        val name = "${lecture.documentTitle.ifBlank { "lecture" }} ${formatLectureDate(lecture.startedAt).replace('/', '-').replace(':', '.')}"
                        chooseFileToSave(exportTitle, "$name.${assistant.exportExtension}")?.let { assistant.exportAudio(lecture, it) }
                    }
                )
            }
            // Not while it is recorded on another device
            if (shown != null && !isActive && !shown.activeElsewhere) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.lecture_delete), color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        expanded = false
                        confirmDelete = shown
                    }
                )
            }
        }
    }

    assistant.exportResult?.let { result ->
        Text(
            text = if (result.failure == null) stringResource(Res.string.lecture_export_done, result.fileName)
            else stringResource(Res.string.lecture_export_failed, result.failure),
            style = MaterialTheme.typography.bodySmall,
            color = if (result.failure == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }

    confirmDelete?.let { summary ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(Res.string.lecture_delete_title)) },
            text = { Text(stringResource(Res.string.lecture_delete_message, formatLectureDate(summary.startedAt))) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    assistant.delete(documentId, summary)
                }) {
                    Text(stringResource(Res.string.lecture_action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text(stringResource(Res.string.lecture_action_cancel)) }
            }
        )
    }
}

// ==========================================================
// Recording
// ==========================================================

@Composable
private fun RecordingSection(
    assistant: LectureAssistant,
    lecture: Lecture?,
    recorder: LectureRecorder?,
    onImport: () -> Unit,
    onStart: () -> Unit,
    onOpenSettings: () -> Unit
) {
    // One lecture at a time: another document may be recording or importing
    val recordingElsewhere = (assistant.activeRecorder?.takeIf { it !== recorder }?.lecture ?: assistant.importing)
        ?.takeIf { it !== lecture }
    val canStart = !assistant.isBusy
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when {
            !assistant.isRecordingSupported -> MutedText(stringResource(Res.string.lecture_unsupported))

            lecture == null -> {
                if (assistant.canGenerate) {
                    Text(stringResource(Res.string.lecture_idle_description), style = MaterialTheme.typography.bodyMedium)
                    MutedText(privacyNote(assistant.settingsStore.settings))
                } else {
                    Text(stringResource(Res.string.lecture_record_only_description), style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = onStart, enabled = canStart, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.lecture_action_record))
                }
                if (assistant.canImportAndExport) {
                    OutlinedButton(onClick = onImport, enabled = canStart, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.lecture_action_import))
                    }
                }
            }

            // Being recorded, or transcribed, on another device
            lecture.status != LectureStatus.FINISHED && recorder?.lecture !== lecture && assistant.importing !== lecture -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = if (lecture.status == LectureStatus.IMPORTING) stringResource(Res.string.lecture_status_processing_elsewhere)
                        else stringResource(Res.string.lecture_status_recording_on, lecture.recordedOn, formatLectureTime(lecture.elapsedMs)),
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }

            lecture.status == LectureStatus.IMPORTING -> {
                Text(
                    text = stringResource(
                        when (lecture.importStep) {
                            ImportStep.TRANSCRIBING -> Res.string.lecture_import_transcribing
                            ImportStep.REVIEWING -> Res.string.lecture_status_finalizing
                            else -> Res.string.lecture_import_converting
                        }
                    ),
                    style = MaterialTheme.typography.titleSmall
                )
                val progress = lecture.importProgress
                if (progress < 0f) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                if (lecture.elapsedMs > 0) MutedText(formatLectureTime(lecture.elapsedMs))
            }

            lecture.status == LectureStatus.RECORDING && recorder != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(Res.string.lecture_status_recording, formatLectureTime(lecture.elapsedMs)),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    LinearProgressIndicator(
                        progress = { lecture.level },
                        modifier = Modifier.width(72.dp),
                        drawStopIndicator = {}
                    )
                }
                MutedText(
                    when {
                        !assistant.canGenerate -> stringResource(Res.string.lecture_status_recording_only)
                        lecture.isAnalyzing ->
                            stringResource(Res.string.lecture_status_analyzing, assistant.settingsStore.settings.task(LectureTask.LIVE_NOTES).model)
                        lecture.pendingUpdates > 0 -> stringResource(Res.string.lecture_status_pending, lecture.pendingUpdates)
                        lecture.transcript.isEmpty() ->
                            stringResource(Res.string.lecture_status_listening, assistant.settingsStore.settings.chunkSeconds)
                        else -> lecture.currentTopic.takeIf { it.isNotBlank() }
                            ?.let { stringResource(Res.string.lecture_current_topic, it) } ?: ""
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                    if (assistant.canGenerate) {
                        OutlinedButton(onClick = { recorder.catchUp() }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(Res.string.lecture_action_catch_up), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Button(
                        onClick = { recorder.stop() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text(stringResource(Res.string.lecture_action_stop))
                    }
                }
            }

            lecture.status == LectureStatus.FINALIZING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(Res.string.lecture_status_finalizing), style = MaterialTheme.typography.titleSmall)
                }
            }

            else -> {
                Text(
                    text = stringResource(Res.string.lecture_status_finished, formatLectureTime(lecture.elapsedMs)),
                    style = MaterialTheme.typography.titleSmall
                )
                if (lecture.recordedElsewhere) {
                    MutedText(
                        if (lecture.hasAudio) stringResource(Res.string.lecture_recorded_on, lecture.recordedOn)
                        else stringResource(Res.string.lecture_audio_arriving, lecture.recordedOn)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onStart, enabled = canStart, modifier = Modifier.weight(1f)) {
                        Text(stringResource(Res.string.lecture_action_new_recording), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (assistant.canImportAndExport) {
                        OutlinedButton(onClick = onImport, enabled = canStart, modifier = Modifier.weight(1f)) {
                            Text(stringResource(Res.string.lecture_action_import_short), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        recordingElsewhere?.let {
            MutedText(stringResource(Res.string.lecture_recording_elsewhere, it.documentTitle))
        }

        assistant.startError?.let {
            ProblemBanner(stringResource(Res.string.lecture_problem_start, it))
        }
        if (assistant.microphoneDenied) {
            ProblemBanner(stringResource(Res.string.lecture_microphone_denied))
        }
        assistant.importError?.let {
            ProblemBanner(stringResource(Res.string.lecture_import_failed, it))
        }
        if (lecture?.noAudioWarning == true && lecture.status == LectureStatus.RECORDING) {
            ProblemBanner(stringResource(if (isComputer) Res.string.lecture_no_audio_warning else Res.string.lecture_no_audio_warning_device))
        }
        lecture?.problem?.let { problem ->
            val message = when (problem) {
                LectureProblem.MissingApiKey -> stringResource(Res.string.lecture_problem_no_api_key)
                is LectureProblem.Authentication -> stringResource(Res.string.lecture_problem_authentication, problem.detail)
                is LectureProblem.Retrying -> stringResource(Res.string.lecture_problem_retrying, problem.retryInSeconds, problem.detail)
                is LectureProblem.Failed -> stringResource(Res.string.lecture_problem_failed, problem.detail)
                is LectureProblem.LocalModelUnavailable -> stringResource(Res.string.lecture_problem_local_unavailable, problem.detail)
                LectureProblem.NoTranscript -> stringResource(Res.string.lecture_problem_no_transcript)
                is LectureProblem.TranscriptionUnavailable -> stringResource(Res.string.lecture_problem_transcription, problem.detail)
            }
            val needsSettings = problem is LectureProblem.MissingApiKey || problem is LectureProblem.Authentication ||
                problem is LectureProblem.LocalModelUnavailable || problem is LectureProblem.TranscriptionUnavailable
            ProblemBanner(message, action = if (needsSettings) Res.string.lecture_action_settings to onOpenSettings else null)
        }
    }
}

@Composable
private fun ProblemBanner(message: String, action: Pair<StringResource, () -> Unit>? = null) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            action?.let { (label, onClick) ->
                TextButton(onClick = onClick) { Text(stringResource(label)) }
            }
        }
    }
}

/** "The lecturer is on page N": shown when that page is not on screen. */
@Composable
private fun ProfessorPageBanner(lecture: Lecture, controller: DrawViewController) {
    val page = lecture.professorPageIndex ?: return
    if (lecture.status != LectureStatus.RECORDING) return
    // Recomputed at every redraw, recomposed only when the visible pages change
    val visible by remember(controller) {
        derivedStateOf {
            controller.version
            controller.visiblePages().map { it.index }
        }
    }
    if (page in visible || page !in controller.document.pages.indices) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Slideshow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(Res.string.lecture_professor_page, page + 1),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { controller.pageManager.jumpToPage(page) }) {
                Text(stringResource(Res.string.lecture_action_go))
            }
        }
    }
}

// ==========================================================
// Tabs
// ==========================================================

@Composable
private fun LectureTabs(assistant: LectureAssistant, lecture: Lecture, controller: DrawViewController) {
    var tab by remember(lecture) { mutableIntStateOf(0) }
    val newCount = lecture.suggestions.count { it.status == SuggestionStatus.NEW }
    val labels = listOf(
        stringResource(Res.string.lecture_tab_notes) + if (newCount > 0) " ($newCount)" else "",
        stringResource(Res.string.lecture_tab_transcript),
        stringResource(Res.string.lecture_tab_outline),
        stringResource(Res.string.lecture_tab_review)
    )
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val inserter = remember(fontFamilyResolver) {
        NoteInserter(TextMeasurer(fontFamilyResolver, Density(1f, 1f), LayoutDirection.Ltr))
    }

    SecondaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
        labels.forEachIndexed { index, label ->
            Tab(
                selected = tab == index,
                onClick = { tab = index },
                text = { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
            )
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        when (tab) {
            0 -> NotesTab(assistant, lecture, controller, inserter)
            1 -> TranscriptTab(lecture)
            2 -> OutlineTab(assistant, lecture)
            else -> ReviewTab(assistant, lecture, controller, inserter, assistant.settingsStore.settings)
        }
    }
}

@Composable
private fun NotesTab(assistant: LectureAssistant, lecture: Lecture, controller: DrawViewController, inserter: NoteInserter) {
    val newNotes = lecture.suggestions.filter { it.status == SuggestionStatus.NEW }.sortedByDescending { it.atMs }
    val handled = lecture.suggestions.filter { it.status != SuggestionStatus.NEW }.sortedByDescending { it.atMs }
    var showHandled by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (newNotes.isEmpty()) {
            item { MutedText(stringResource(Res.string.lecture_notes_empty), Modifier.padding(4.dp)) }
        }
        items(newNotes, key = { it.id }) { note ->
            SuggestionCard(
                note = note,
                pageIndex = note.resolvedPageIndex(controller.document),
                canListen = lecture.canPlayAudio,
                placing = assistant.placingNote?.id == note.id,
                onInsert = {
                    val started = inserter.startPlacement(
                        controller = controller,
                        suggestion = note,
                        onInserted = {
                            assistant.placingNote = null
                            lecture.updateSuggestion(note.id, SuggestionStatus.INSERTED)
                        },
                        onCancel = { if (assistant.placingNote?.id == note.id) assistant.placingNote = null }
                    )
                    if (started) assistant.placingNote = note
                },
                onCancelPlacing = { controller.placementManager.cancel() },
                onListen = { lecture.play(note) },
                onAlreadyNoted = { lecture.updateSuggestion(note.id, SuggestionStatus.ALREADY_NOTED) },
                onDismiss = { lecture.updateSuggestion(note.id, SuggestionStatus.DISMISSED) },
                onGoToPage = { page -> controller.pageManager.jumpToPage(page) }
            )
        }
        if (handled.isNotEmpty()) {
            item(key = "handled") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { showHandled = !showHandled }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(Res.string.lecture_notes_handled, handled.size),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(if (showHandled) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
                }
            }
            if (showHandled) {
                items(handled, key = { it.id }) { note ->
                    HandledRow(note, onRestore = { lecture.updateSuggestion(note.id, SuggestionStatus.NEW) })
                }
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    note: NoteSuggestion,
    pageIndex: Int?,
    canListen: Boolean,
    placing: Boolean,
    onInsert: () -> Unit,
    onCancelPlacing: () -> Unit,
    onListen: () -> Unit,
    onAlreadyNoted: () -> Unit,
    onDismiss: () -> Unit,
    onGoToPage: (Int) -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KindLabel(note.kind, important = note.importance >= 3)
                Spacer(Modifier.weight(1f))
                val time = formatLectureTime(note.atMs)
                Text(
                    text = if (note.fromFinalReview) stringResource(Res.string.lecture_note_from_review) else time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                pageIndex?.let { page ->
                    Text(
                        text = stringResource(Res.string.lecture_note_page, page + 1),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.extraSmall)
                            .clickable { onGoToPage(page) }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
            if (note.title.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(note.title, style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                MarkdownText(note.content)
            }
            if (placing) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(Res.string.lecture_place_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (placing) {
                    OutlinedButton(onClick = onCancelPlacing, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Text(stringResource(Res.string.lecture_action_cancel))
                    }
                } else {
                    FilledTonalButton(onClick = onInsert, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Icon(Icons.Outlined.PostAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.lecture_note_insert))
                    }
                }
                Spacer(Modifier.weight(1f))
                if (canListen && !note.fromFinalReview) {
                    IconButton(onClick = onListen) {
                        Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = stringResource(Res.string.lecture_note_listen))
                    }
                }
                IconButton(onClick = onAlreadyNoted) {
                    Icon(Icons.Outlined.DoneAll, contentDescription = stringResource(Res.string.lecture_note_have_it))
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(Res.string.lecture_note_dismiss))
                }
            }
        }
    }
}

@Composable
private fun HandledRow(note: NoteSuggestion, onRestore: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(note.title.ifBlank { note.content }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = when (note.status) {
                    SuggestionStatus.INSERTED -> stringResource(Res.string.lecture_note_status_inserted)
                    SuggestionStatus.ALREADY_NOTED -> stringResource(Res.string.lecture_note_status_already_noted)
                    else -> stringResource(Res.string.lecture_note_status_dismissed)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onRestore) {
            Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = stringResource(Res.string.lecture_note_restore))
        }
    }
}

@Composable
private fun KindLabel(kind: NoteKind, important: Boolean) {
    val label = stringResource(
        when (kind) {
            NoteKind.DEFINITION -> Res.string.lecture_kind_definition
            NoteKind.FORMULA -> Res.string.lecture_kind_formula
            NoteKind.THEOREM -> Res.string.lecture_kind_theorem
            NoteKind.EXAMPLE -> Res.string.lecture_kind_example
            NoteKind.EXPLANATION -> Res.string.lecture_kind_explanation
            NoteKind.EXAM_HINT -> Res.string.lecture_kind_exam_hint
            NoteKind.ANNOUNCEMENT -> Res.string.lecture_kind_announcement
            NoteKind.REFERENCE -> Res.string.lecture_kind_reference
            NoteKind.OTHER -> Res.string.lecture_kind_other
        }
    )
    val highlighted = important || kind == NoteKind.EXAM_HINT || kind == NoteKind.ANNOUNCEMENT
    Surface(
        color = if (highlighted) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (highlighted) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.extraSmall
    ) {
        Text(
            text = if (important) "$label · ${stringResource(Res.string.lecture_importance_high)}" else label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun TranscriptTab(lecture: Lecture) {
    val listState = rememberLazyListState()
    val count = lecture.transcript.size
    LaunchedEffect(count) {
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    val player = lecture.player
    val playing = playingSegment(player, lecture.transcript.map { it.startMs })
    // The segment being played stays in sight
    LaunchedEffect(playing) {
        if (playing != null && player?.isPlaying == true) listState.animateScrollToItem(playing)
    }
    if (count == 0) {
        MutedText(stringResource(Res.string.lecture_transcript_empty), Modifier.padding(16.dp))
        return
    }
    SelectionContainer {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(lecture.transcript.toList()) { index, segment ->
                val isPlaying = index == playing
                Surface(
                    color = if (isPlaying) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    shape = MaterialTheme.shapes.small
                ) {
                    Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                        // The time plays the recording from there
                        Text(
                            text = (if (player != null) "▶ " else "") + formatLectureTime(segment.startMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = if (player != null) Modifier.clickable { player.play(segment.startMs) } else Modifier
                        )
                        Text(segment.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun OutlineTab(assistant: LectureAssistant, lecture: Lecture) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CourseContextCard(assistant, lecture.documentId)
        if (lecture.outline.isBlank()) {
            MutedText(stringResource(Res.string.lecture_outline_empty))
        } else {
            SelectionContainer {
                Column {
                    if (lecture.currentTopic.isNotBlank()) {
                        Text(
                            text = stringResource(Res.string.lecture_current_topic, lecture.currentTopic),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    MarkdownText(lecture.outline)
                }
            }
        }
    }
}

/** The course context of the notebook: shown, edited by the student, written again by the assistant. */
@Composable
private fun CourseContextCard(assistant: LectureAssistant, documentId: String) {
    val context = assistant.contextOf(documentId)
    val writing = assistant.isWritingContext(documentId)
    var editing by remember(documentId) { mutableStateOf(false) }
    var draft by remember(documentId) { mutableStateOf("") }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.lecture_context_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                if (!editing && !writing) {
                    TextButton(onClick = {
                        draft = context?.text.orEmpty()
                        editing = true
                    }) { Text(stringResource(Res.string.lecture_context_edit)) }
                    if (assistant.canWriteContext(documentId)) {
                        TextButton(onClick = { assistant.writeContext(documentId) }) { Text(stringResource(Res.string.lecture_context_write)) }
                    }
                }
            }
            when {
                writing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    MutedText(stringResource(Res.string.lecture_context_writing))
                }
                editing -> {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(modifier = Modifier.align(Alignment.End)) {
                        TextButton(onClick = { editing = false }) { Text(stringResource(Res.string.lecture_action_cancel)) }
                        TextButton(onClick = {
                            assistant.editContext(documentId, draft)
                            editing = false
                        }) { Text(stringResource(Res.string.lecture_action_save)) }
                    }
                }
                context == null || context.text.isBlank() -> MutedText(stringResource(Res.string.lecture_context_empty))
                else -> {
                    SelectionContainer { Text(context.text, style = MaterialTheme.typography.bodySmall) }
                    MutedText(stringResource(Res.string.lecture_context_hint))
                }
            }
        }
    }
}

@Composable
private fun ReviewTab(
    assistant: LectureAssistant,
    lecture: Lecture,
    controller: DrawViewController,
    inserter: NoteInserter,
    settings: AssistantSettings
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val review = lecture.finalReview
        // Without the transcript, the audio is transcribed first
        val canRegenerate = lecture.canGenerateReview && (lecture.transcript.isNotEmpty() || lecture.hasAudio)
        when {
            lecture.reviewStatus == ReviewStatus.RUNNING || lecture.status == LectureStatus.FINALIZING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    MutedText(
                        if (lecture.reviewProvider == ReviewProvider.LOCAL && lecture.reviewStatus == ReviewStatus.RUNNING) {
                            stringResource(Res.string.lecture_review_running_local, lecture.reviewModel.orEmpty(), lecture.reviewProgressTokens)
                        } else {
                            stringResource(Res.string.lecture_review_running, lecture.reviewModel ?: settings.task(LectureTask.FINAL_REVIEW).model)
                        }
                    )
                }
            }
            lecture.reviewStatus == ReviewStatus.FAILED -> {
                MutedText(stringResource(Res.string.lecture_review_failed))
                if (canRegenerate) RegenerateReviewButton(assistant, lecture, settings)
            }
            review != null -> {
                InsertPagesButton(controller) { afterPageIndex -> inserter.insertPages(controller, review, afterPageIndex) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MutedText(
                        lecture.reviewModel?.let { stringResource(Res.string.lecture_review_by, it) }.orEmpty(),
                        Modifier.weight(1f)
                    )
                    if (canRegenerate) RegenerateReviewButton(assistant, lecture, settings)
                }
                SelectionContainer { MarkdownText(review) }
            }
            // Written by a device with the models
            !lecture.generates -> MutedText(stringResource(Res.string.lecture_review_elsewhere))
            lecture.status == LectureStatus.FINISHED -> {
                // Recorded where the models are not, or interrupted before its review (e.g. the application was closed)
                MutedText(
                    if (lecture.recordedElsewhere && lecture.transcript.isEmpty()) stringResource(Res.string.lecture_review_received, lecture.recordedOn)
                    else stringResource(Res.string.lecture_review_pending)
                )
                if (canRegenerate) RegenerateReviewButton(assistant, lecture, settings, label = Res.string.lecture_review_generate)
            }
            else -> MutedText(stringResource(Res.string.lecture_review_none, settings.task(LectureTask.FINAL_REVIEW).model))
        }

        UsageSummary(lecture.usage)
    }
}

/** Writes the final review again, online or by the local model. */
@Composable
private fun RegenerateReviewButton(
    assistant: LectureAssistant,
    lecture: Lecture,
    settings: AssistantSettings,
    label: StringResource = Res.string.lecture_review_regenerate
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(stringResource(label))
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.lecture_review_with_gemini, settings.task(LectureTask.FINAL_REVIEW).gemini)) },
                onClick = {
                    expanded = false
                    assistant.generate(lecture, ReviewProvider.GEMINI)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.lecture_review_with_local, settings.task(LectureTask.FINAL_REVIEW).local)) },
                onClick = {
                    expanded = false
                    assistant.generate(lecture, ReviewProvider.LOCAL)
                }
            )
        }
    }
}

/** The requests of the lecture and where the tokens of the online ones went. */
@Composable
private fun UsageSummary(usage: TokenUsage) {
    if (usage.requests == 0 && usage.localRequests == 0 && usage.skippedUpdates == 0) return
    HorizontalDivider()
    if (usage.requests > 0) {
        MutedText(
            stringResource(
                Res.string.lecture_usage_tokens,
                usage.requests,
                compactNumber(usage.promptTokens),
                compactNumber(usage.cachedTokens),
                compactNumber(usage.outputTokens)
            )
        )
    }
    if (usage.promptByModality.isNotEmpty()) {
        val parts = usage.promptByModality.entries.sortedByDescending { it.value }.map { (modality, tokens) ->
            val label = when (modality.uppercase()) {
                "TEXT" -> stringResource(Res.string.lecture_modality_text)
                "IMAGE" -> stringResource(Res.string.lecture_modality_image)
                "AUDIO" -> stringResource(Res.string.lecture_modality_audio)
                "DOCUMENT" -> stringResource(Res.string.lecture_modality_document)
                else -> modality.lowercase()
            }
            "$label ${compactNumber(tokens)}"
        }
        MutedText(stringResource(Res.string.lecture_usage_input, parts.joinToString(" · ")))
    }
    if (usage.localRequests > 0) {
        MutedText(stringResource(Res.string.lecture_usage_local, usage.localRequests))
    }
    if (usage.skippedUpdates > 0) {
        MutedText(stringResource(Res.string.lecture_usage_skipped, usage.skippedUpdates))
    }
}

/** What leaves this computer with the current settings, before recording. */
@Composable
private fun privacyNote(settings: AssistantSettings): String {
    val shared = settings.sentToGoogle
    if (shared.isEmpty()) return stringResource(Res.string.lecture_privacy_local)
    val parts = SharedData.entries.filter { it in shared }.map { data ->
        stringResource(
            when (data) {
                SharedData.AUDIO -> Res.string.lecture_privacy_audio
                SharedData.TRANSCRIPT -> Res.string.lecture_privacy_transcript
                SharedData.NOTEBOOK -> Res.string.lecture_privacy_notebook
            }
        )
    }
    return stringResource(Res.string.lecture_privacy_online, parts.joinToString(", "))
}

/**
 * "Insert as new pages", with the position of the pages: after the page the student is looking
 * at, or at the end of the notebook.
 */
@Composable
private fun InsertPagesButton(controller: DrawViewController, onInsert: (afterPageIndex: Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var currentPage by remember { mutableStateOf<Int?>(null) }
    Box {
        FilledTonalButton(onClick = {
            currentPage = mostVisiblePageIndex(controller)
            expanded = true
        }) {
            Icon(Icons.Outlined.PostAdd, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(Res.string.lecture_review_insert))
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            currentPage?.let { page ->
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.lecture_review_insert_after, page + 1)) },
                    onClick = {
                        expanded = false
                        onInsert(page)
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.lecture_review_insert_end)) },
                onClick = {
                    expanded = false
                    onInsert(null)
                }
            )
        }
    }
}

/** The page taking the largest part of the viewport. */
internal fun mostVisiblePageIndex(controller: DrawViewController): Int? =
    controller.visiblePages().maxByOrNull { page ->
        minOf(page.rect.bottom, controller.viewportHeight) - maxOf(page.rect.top, 0f)
    }?.index

// ==========================================================
// Common
// ==========================================================

@Composable
private fun MutedText(text: String, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/**
 * Markdown of the notes, formatted as on the canvas: delimiters hidden and, once the LaTeX engine
 * is ready, the formulas rendered by MicroTeX inline.
 */
@Composable
private fun MarkdownText(markdown: String) {
    val color = MaterialTheme.colorScheme.onSurface
    val style = MaterialTheme.typography.bodyMedium
    val density = LocalDensity.current
    val latexReady by LatexEngine.isReady.collectAsState()
    val content = remember(markdown, color, style, density, latexReady) {
        try {
            if (latexReady) markdownWithFormulas(markdown, color, style, density) else null
        } catch (_: Throwable) {
            null
        } ?: (
            MarkdownTextUtil.toLiveAnnotatedString(
                text = markdown,
                selection = TextRange(markdown.length + 1),
                isMarkdownEnabled = true,
                defaultColor = color
            ) to emptyMap()
        )
    }
    Text(content.first, inlineContent = content.second, style = style, color = color)
}

/** The Markdown text with its formulas as inline images (see [MarkdownTextUtil.toCanvasText]). */
private fun markdownWithFormulas(
    markdown: String,
    color: Color,
    style: TextStyle,
    density: Density
): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val fontPx = with(density) { style.fontSize.toPx() }
    val canvasText = MarkdownTextUtil.toCanvasText(markdown, true, color, fontPx, fontAscentPx = fontPx * 0.8f) { latex, sizePx ->
        LatexEngine.renderFormula(latex, sizePx, color.toArgb())?.let {
            MarkdownTextUtil.RenderedFormula(it.bitmap, it.width.toFloat(), it.height.toFloat(), it.baselineRatio)
        }
    }
    val inline = mutableMapOf<String, InlineTextContent>()
    val text = buildAnnotatedString {
        var last = 0
        canvasText.placeholders.forEachIndexed { index, placeholder ->
            append(canvasText.text.subSequence(last, placeholder.start))
            val id = "formula$index"
            appendInlineContent(id)
            last = placeholder.end
            val formula = canvasText.formulas.firstOrNull { it.placeholderIndex == index }
            inline[id] = InlineTextContent(
                with(density) {
                    Placeholder(
                        width = placeholder.widthPx.toSp(),
                        height = placeholder.heightPx.toSp(),
                        placeholderVerticalAlign = if (placeholder.aboveBaseline) PlaceholderVerticalAlign.AboveBaseline else PlaceholderVerticalAlign.TextTop
                    )
                }
            ) {
                if (formula != null) {
                    // The body placeholder holds the ascent: the descent hangs below it
                    Image(
                        bitmap = formula.image,
                        contentDescription = null,
                        modifier = Modifier
                            .wrapContentSize(Alignment.TopStart, unbounded = true)
                            .requiredSize(with(density) { formula.widthPx.toDp() }, with(density) { formula.heightPx.toDp() })
                    )
                }
            }
        }
        append(canvasText.text.subSequence(last, canvasText.text.length))
    }
    return text to inline
}

/** "04/10/2026 14:30" in the local time zone. */
@OptIn(ExperimentalTime::class)
internal fun formatLectureDate(epochMs: Long): String {
    val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    fun two(value: Int) = value.toString().padStart(2, '0')
    return "${two(t.day)}/${two(t.month.ordinal + 1)}/${t.year} ${two(t.hour)}:${two(t.minute)}"
}

/** 1234 -> "1.2k", 2345678 -> "2.3M". */
internal fun compactNumber(value: Long): String {
    fun oneDecimal(v: Double): String {
        val tenths = kotlin.math.round(v * 10).toLong()
        return if (tenths % 10 == 0L) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
    }
    return when {
        value >= 1_000_000 -> oneDecimal(value / 1_000_000.0) + "M"
        value >= 1_000 -> oneDecimal(value / 1_000.0) + "k"
        else -> value.toString()
    }
}
