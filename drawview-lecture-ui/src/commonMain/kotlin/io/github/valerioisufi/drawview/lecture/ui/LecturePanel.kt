package io.github.valerioisufi.drawview.lecture.ui

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.lecture.AssistantSettings
import io.github.valerioisufi.drawview.lecture.AudioDownload
import io.github.valerioisufi.drawview.lecture.ImportStep
import io.github.valerioisufi.drawview.lecture.Lecture
import io.github.valerioisufi.drawview.lecture.LectureAnchors
import io.github.valerioisufi.drawview.lecture.LectureAssistant
import io.github.valerioisufi.drawview.lecture.LectureProblem
import io.github.valerioisufi.drawview.lecture.LectureRecorder
import io.github.valerioisufi.drawview.lecture.LectureStatus
import io.github.valerioisufi.drawview.lecture.LectureSummary
import io.github.valerioisufi.drawview.lecture.LectureTask
import io.github.valerioisufi.drawview.lecture.ModelProvider
import io.github.valerioisufi.drawview.lecture.NoteKind
import io.github.valerioisufi.drawview.lecture.NoteSuggestion
import io.github.valerioisufi.drawview.lecture.ReviewStatus
import io.github.valerioisufi.drawview.lecture.SharedData
import io.github.valerioisufi.drawview.lecture.SuggestionStatus
import io.github.valerioisufi.drawview.lecture.TokenUsage
import io.github.valerioisufi.drawview.lecture.audio.importableExtensions
import io.github.valerioisufi.drawview.lecture.formatLectureTime
import io.github.valerioisufi.drawview.lecture.platform.isComputer
import io.github.valerioisufi.drawview.lecture.resolvedPageIndex
import io.github.valerioisufi.drawview.lecture.ui.generated.resources.*
import io.github.valerioisufi.drawview.lecture.ui.platform.rememberFileOpener
import io.github.valerioisufi.drawview.platform.currentTimeMillis
import io.github.valerioisufi.drawview.ui.command.DesignMenu
import io.github.valerioisufi.drawview.ui.command.MenuDivider
import io.github.valerioisufi.drawview.ui.command.MenuItem
import io.github.valerioisufi.drawview.ui.components.BadgeTone
import io.github.valerioisufi.drawview.ui.components.DesignIconButton
import io.github.valerioisufi.drawview.ui.components.LabelBadge
import io.github.valerioisufi.drawview.ui.components.MarkdownText
import io.github.valerioisufi.drawview.ui.components.OutlineButton
import io.github.valerioisufi.drawview.ui.components.SegmentedChoice
import io.github.valerioisufi.drawview.ui.components.TonalButton
import io.github.valerioisufi.drawview.ui.extension.EditorPanel
import io.github.valerioisufi.drawview.ui.generated.resources.Res as UiRes
import io.github.valerioisufi.drawview.ui.generated.resources.date_months_short
import io.github.valerioisufi.drawview.ui.platform.rememberFileSaver
import io.github.valerioisufi.drawview.ui.render.ComposeTextTileAdapter
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import io.github.valerioisufi.drawview.ui.theme.rememberNotesFonts
import io.github.valerioisufi.drawview.ui.util.formatShortDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/** Whether the settings of the assistant are shown: asked by the header of the panel and by its recording. */
internal class LecturePanelState {
    var settingsShown by mutableStateOf(false)
}

/**
 * The recording of [documentId] sees its pages while its editor is open (and goes on without):
 * composed with the editor, the panel shown or not.
 */
@Composable
internal fun LectureEditorEffects(assistant: LectureAssistant, documentId: String, controller: DrawViewController) {
    DisposableEffect(assistant, documentId, controller) {
        val notebook = ControllerNotebookSource(controller)
        assistant.onEditorShown(documentId, notebook)
        onDispose { assistant.onEditorHidden(documentId, notebook) }
    }
}

/**
 * The panel of the lecture assistant in the editor of [documentId]: recording, suggested notes,
 * transcript, outline and final review. The editor places it (beside the canvas, or rising from
 * the bottom over it on a phone) and draws its header, with the settings of the models.
 */
internal fun lecturePanel(
    assistant: LectureAssistant,
    documentId: String,
    controller: DrawViewController,
    state: LecturePanelState
): EditorPanel = EditorPanel(
    id = "lecture",
    title = { stringResource(Res.string.lecture_title) },
    isOpen = { assistant.isPanelOpen },
    onClose = { assistant.isPanelOpen = false },
    icon = DrawViewIcons.Sparkles,
    actions = {
        // The settings of the models, where they are
        DesignIconButton(
            icon = DrawViewIcons.Sliders,
            label = stringResource(Res.string.lecture_action_settings),
            onClick = { state.settingsShown = true },
            tint = MaterialTheme.colorScheme.onSurface
        )
    },
    content = { PanelContent(assistant, documentId, controller, state) }
)

/** The settings of the assistant, while asked. */
@Composable
internal fun LectureSettings(assistant: LectureAssistant, state: LecturePanelState) {
    if (state.settingsShown) {
        AssistantSettingsDialog(store = assistant.settingsStore, localModels = assistant.localModels, onDismiss = { state.settingsShown = false })
    }
}

/** The margins of the content of the panel. */
private val PanelPadding = 20.dp

/**
 * What the panel shows under its header: what the assistant is doing (a lecture to record, the
 * one shown with its menu, the recording, the notices), then the tabs of the lecture. The part
 * above the tabs scrolls when it is long (a short window).
 */
@Composable
internal fun PanelContent(assistant: LectureAssistant, documentId: String, controller: DrawViewController, state: LecturePanelState) {
    val lecture = assistant.shownLecture(documentId)
    val recorder = assistant.recorderOf(documentId)
    val chooseRecording = rememberFileOpener(stringResource(Res.string.lecture_import_title), importableExtensions()) { file ->
        assistant.importRecording(documentId, controller.document.title, assistant.notebookOf(documentId, controller), { controller.document.pages.map { it.id } }, file)
    }
    val onImport = {
        assistant.clearImportAndExportMessages()
        chooseRecording()
    }
    val onStart = {
        // A key is needed only when something is sent to Gemini
        val settings = assistant.settingsStore.settings
        if (assistant.canGenerate && settings.sentToGoogle.isNotEmpty() && !assistant.settingsStore.hasApiKey) state.settingsShown = true
        else assistant.record(documentId, controller.document.title, assistant.notebookOf(documentId, controller))
    }
    val onOpenSettings = { state.settingsShown = true }

    BoxWithConstraints {
        val maxTop = if (lecture == null) maxHeight else maxHeight * 0.55f
        Column {
            Column(
                modifier = Modifier
                    .heightIn(max = maxTop)
                    .verticalScroll(rememberScrollState())
                    .padding(start = PanelPadding, end = PanelPadding, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (lecture == null) {
                    IdleSection(assistant, documentId, onStart = onStart, onImport = onImport)
                } else {
                    LectureHeader(assistant, documentId, lecture, recordingHere = recorder?.lecture === lecture && lecture.status == LectureStatus.RECORDING)
                    RecordingSection(assistant, lecture, recorder, onStart = onStart, onImport = onImport)
                    ProfessorPageBanner(lecture, controller)
                }
                Notices(assistant, lecture, recorder, onOpenSettings)
            }
            if (lecture != null) LectureTabs(assistant, lecture, controller)
        }
    }
}

// ==========================================================
// Idle, the lectures
// ==========================================================

/**
 * No lecture shown: what the assistant does and what leaves the device, record or import; the
 * lectures of the notebook.
 */
@Composable
private fun IdleSection(assistant: LectureAssistant, documentId: String, onStart: () -> Unit, onImport: () -> Unit) {
    if (!assistant.isRecordingSupported) {
        LectureNotice(DrawViewIcons.Mic, stringResource(Res.string.lecture_unsupported), NoticeTone.Neutral)
        return
    }
    // A recording at a time; an import is always taken, and waits its turn to be transcribed
    val canRecord = assistant.canRecord
    Text(
        stringResource(if (assistant.canGenerate) Res.string.lecture_idle_description else Res.string.lecture_record_only_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (assistant.canGenerate) PrivacyNote(privacyNote(assistant.settingsStore.settings))
    Button(onClick = onStart, enabled = canRecord, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Icon(DrawViewIcons.Mic, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(Res.string.lecture_action_record), style = MaterialTheme.typography.titleMedium)
    }
    if (assistant.canImportAndExport) {
        OutlineButton(onClick = onImport, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(DrawViewIcons.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.lecture_action_import))
        }
    }
    GenerateHereCard(assistant)
    LectureList(assistant, documentId)
}

/** What leaves the device, before recording: the shield, on the high container. */
@Composable
internal fun PrivacyNote(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(DrawViewIcons.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Whether the notes are written on this device, where they may be and are not (a phone, a tablet
 * recording only): "Write the notes on this device" and its switch.
 */
@Composable
private fun GenerateHereCard(assistant: LectureAssistant) {
    if (!assistant.mayGenerate || assistant.canGenerate) return
    val store = assistant.settingsStore
    PanelCard(
        modifier = Modifier.clip(PanelCardShape).toggleable(value = false, role = Role.Switch) { store.update(store.settings.copy(generateHere = it)) }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(Res.string.lecture_settings_generate_here), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(Res.string.lecture_settings_generate_here_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = false, onCheckedChange = null)
        }
    }
}

/** The lectures of the notebook, shown with a tap; each with its menu (deleting it). */
@Composable
private fun LectureList(assistant: LectureAssistant, documentId: String) {
    val lectures = assistant.lecturesOf(documentId)
    if (lectures.isEmpty()) return
    var confirmDelete by remember { mutableStateOf<LectureSummary?>(null) }
    Text(
        stringResource(Res.string.lecture_list_title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (summary in lectures) {
            LectureRow(summary, onClick = { assistant.show(documentId, summary) }, onDelete = { confirmDelete = summary }.takeIf { !summary.activeElsewhere })
        }
    }
    confirmDelete?.let { summary ->
        DeleteLectureDialog(summary, onConfirm = {
            confirmDelete = null
            assistant.delete(documentId, summary)
        }, onDismiss = { confirmDelete = null })
    }
}

/** A lecture of the list: its tile, when and how long, whether its review is ready, its menu. */
@Composable
private fun LectureRow(summary: LectureSummary, onClick: () -> Unit, onDelete: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IconTile(DrawViewIcons.Mic, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        Column(modifier = Modifier.weight(1f)) {
            Text(lectureDate(summary.startedAt), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    formatDuration(summary.durationMs),
                    stringResource(Res.string.lecture_review_ready).takeIf { summary.hasReview },
                    summary.recordedElsewhere?.let { stringResource(Res.string.lecture_recorded_on, it) }
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onDelete != null) {
            Box {
                DesignIconButton(DrawViewIcons.MoreVertical, stringResource(Res.string.lecture_delete), onClick = { menu = true }, selected = menu)
                DesignMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem(
                        text = stringResource(Res.string.lecture_delete),
                        icon = rememberVectorPainter(DrawViewIcons.Trash),
                        destructive = true,
                        onClick = {
                            menu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

/** "14 Sep · 10:15", the day and the hour of a lecture. */
@Composable
private fun lectureDate(epochMs: Long): String = formatShortDateTime(epochMs, stringArrayResource(UiRes.array.date_months_short))

/** Deleting a lecture, asked first: what goes, what stays. */
@Composable
private fun DeleteLectureDialog(summary: LectureSummary, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(DrawViewIcons.Trash, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(Res.string.lecture_delete_title), textAlign = TextAlign.Center) },
        text = {
            Text(
                stringResource(Res.string.lecture_delete_message, lectureDate(summary.startedAt)),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
            ) { Text(stringResource(Res.string.lecture_action_delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.lecture_action_cancel)) } }
    )
}

/** The type of the exported recordings (m4a). */
private const val RecordingExportType = "audio/mp4"

/** The type of the exported transcripts (txt). */
private const val TranscriptExportType = "text/plain"

/**
 * The lecture shown, on a card opening its menu (not while it is recorded here): finished and how
 * long, when, how many lectures the notebook has. The menu: the lectures to show, exporting the
 * audio and the transcript, transcribing it again, how the audio is shared, deleting it.
 */
@Composable
private fun LectureHeader(assistant: LectureAssistant, documentId: String, lecture: Lecture, recordingHere: Boolean) {
    val lectures = assistant.lecturesOf(documentId)
    if (lectures.isEmpty() || recordingHere) return
    var expanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<LectureSummary?>(null) }
    val shown = lectures.firstOrNull { it.directory == lecture.directory }
    val isActive = assistant.activeRecorder?.lecture === lecture || assistant.isTranscribing(lecture)
    // The lecture shown when the student chose where to save it
    val exported = remember { arrayOfNulls<Lecture>(1) }
    val saveRecording = rememberFileSaver(stringResource(Res.string.lecture_export_title), RecordingExportType) { target ->
        exported[0]?.let { assistant.exportAudio(it, target.path, target.deliver) }
    }
    val saveTranscript = rememberFileSaver(stringResource(Res.string.lecture_export_transcript_title), TranscriptExportType) { target ->
        exported[0]?.let { assistant.exportTranscript(it, target.path, target.deliver) }
    }
    val finished = lecture.status == LectureStatus.FINISHED
    val date = lectureDate(lecture.startedAt)
    val count = pluralStringResource(Res.plurals.lecture_count, lectures.size, lectures.size)

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { expanded = true }
                .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                if (finished) DrawViewIcons.CheckCircle else DrawViewIcons.Mic,
                contentDescription = null,
                tint = if (finished) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (finished) stringResource(Res.string.lecture_status_finished, formatDuration(lecture.elapsedMs)) else date,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (finished) "$date · $count" else count,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(if (expanded) DrawViewIcons.ChevronUp else DrawViewIcons.ChevronDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DesignMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (summary in lectures) {
                MenuItem(
                    text = lectureDate(summary.startedAt) + " · " + formatDuration(summary.durationMs),
                    icon = rememberVectorPainter(DrawViewIcons.Mic),
                    checked = summary.directory == lecture.directory,
                    onClick = {
                        expanded = false
                        assistant.show(documentId, summary)
                    }
                )
            }
            val canExportAudio = shown != null && !isActive && assistant.canImportAndExport && finished && lecture.hasAudio
            val canExportTranscript = shown != null && !isActive && assistant.canImportAndExport && finished && lecture.transcript.isNotEmpty()
            if (canExportAudio || canExportTranscript) {
                MenuDivider()
                val exportName = { "${lecture.documentTitle.ifBlank { "lecture" }} ${formatLectureDate(lecture.startedAt).replace('/', '-').replace(':', '.')}" }
                if (canExportAudio) {
                    MenuItem(
                        text = stringResource(Res.string.lecture_export_audio),
                        icon = rememberVectorPainter(DrawViewIcons.Download),
                        onClick = {
                            expanded = false
                            assistant.clearImportAndExportMessages()
                            exported[0] = lecture
                            saveRecording("${exportName()}.${assistant.exportExtension}")
                        }
                    )
                }
                if (canExportTranscript) {
                    MenuItem(
                        text = stringResource(Res.string.lecture_export_transcript),
                        icon = rememberVectorPainter(DrawViewIcons.FileText),
                        onClick = {
                            expanded = false
                            assistant.clearImportAndExportMessages()
                            exported[0] = lecture
                            saveTranscript("${exportName()}.${assistant.transcriptExportExtension}")
                        }
                    )
                }
            }
            // The recording transcribed again on this computer
            if (shown != null && !isActive && assistant.canRetranscribe(lecture) && assistant.canTranscribeHere) {
                MenuItem(
                    text = stringResource(Res.string.lecture_retranscribe_local),
                    icon = rememberVectorPainter(DrawViewIcons.Monitor),
                    onClick = {
                        expanded = false
                        assistant.retranscribe(lecture)
                    }
                )
            }
            SharingItems(assistant, documentId, lecture)
            // Not while it is recorded on another device
            if (shown != null && !isActive && !shown.activeElsewhere) {
                MenuDivider()
                MenuItem(
                    text = stringResource(Res.string.lecture_delete),
                    icon = rememberVectorPainter(DrawViewIcons.Trash),
                    destructive = true,
                    onClick = {
                        expanded = false
                        confirmDelete = shown
                    }
                )
            }
        }
    }

    assistant.exportResult?.let { result ->
        val isTranscript = result.fileName.endsWith(".${assistant.transcriptExportExtension}", ignoreCase = true)
        if (result.failure == null) {
            val message = if (isTranscript) Res.string.lecture_export_transcript_done else Res.string.lecture_export_done
            LectureNotice(DrawViewIcons.Download, stringResource(message, result.fileName), NoticeTone.Neutral)
        } else {
            val message = if (isTranscript) Res.string.lecture_export_transcript_failed else Res.string.lecture_export_failed
            LectureNotice(DrawViewIcons.TriangleAlert, stringResource(message, result.failure.orEmpty()), NoticeTone.Error)
        }
    }

    confirmDelete?.let { summary ->
        DeleteLectureDialog(summary, onConfirm = {
            confirmDelete = null
            assistant.delete(documentId, summary)
        }, onDismiss = { confirmDelete = null })
    }
}

/** How the lectures of the notebook are shared: the audio of this one, of the new ones, of the others' (received at once or on request). */
@Composable
private fun SharingItems(assistant: LectureAssistant, documentId: String, lecture: Lecture) {
    val sharing = assistant.sharingOf(documentId)
    MenuDivider()
    MenuItem(
        text = stringResource(Res.string.lecture_share_audio_this),
        checked = lecture.audioShared,
        alignWithIcons = true,
        onClick = { assistant.shareAudio(lecture, !lecture.audioShared) }
    )
    MenuItem(
        text = stringResource(Res.string.lecture_share_audio_new),
        checked = sharing.shareAudio,
        alignWithIcons = true,
        onClick = { assistant.changeSharing(documentId) { it.copy(shareAudio = !it.shareAudio) } }
    )
    MenuItem(
        text = stringResource(Res.string.lecture_download_audio_automatically),
        checked = sharing.downloadAudio == AudioDownload.AUTOMATIC,
        alignWithIcons = true,
        onClick = {
            assistant.changeSharing(documentId) {
                it.copy(downloadAudio = if (it.downloadAudio == AudioDownload.AUTOMATIC) AudioDownload.ON_REQUEST else AudioDownload.AUTOMATIC)
            }
        }
    )
}

// ==========================================================
// Recording
// ==========================================================

/**
 * What happens to the lecture shown: recorded elsewhere, imported, recorded here (the card of the
 * recording, "I missed something"), reviewed; finished, a new recording or an import.
 */
@Composable
private fun RecordingSection(
    assistant: LectureAssistant,
    lecture: Lecture,
    recorder: LectureRecorder?,
    onStart: () -> Unit,
    onImport: () -> Unit
) {
    val canRecord = assistant.canRecord
    when {
        // Being recorded, or transcribed, on another device
        lecture.status != LectureStatus.FINISHED && recorder?.lecture !== lecture && !assistant.isTranscribing(lecture) -> {
            val now = rememberNow()
            val silentMinutes = (now - lecture.recordingHeardAt) / 60_000
            if (lecture.recordingHeardAt > 0 && now - lecture.recordingHeardAt > LectureRecorder.SILENT_ELSEWHERE_MS) {
                // No news for long: the device may have stopped (it saves the lecture as finished when back)
                LectureNotice(
                    DrawViewIcons.TriangleAlert,
                    stringResource(Res.string.lecture_status_silent_elsewhere, lecture.recordedOn, silentMinutes.toInt()),
                    NoticeTone.Error
                )
            } else {
                LectureNotice(
                    DrawViewIcons.Users,
                    if (lecture.status == LectureStatus.IMPORTING) stringResource(Res.string.lecture_status_processing_elsewhere)
                    else stringResource(Res.string.lecture_status_recording_on, lecture.recordedOn, formatLectureTime(lecture.elapsedMs)),
                    NoticeTone.Info
                )
                // Its notes written here as its audio arrives
                if (lecture.followedHere) LectureNotice(DrawViewIcons.Monitor, stringResource(Res.string.lecture_followed_here), NoticeTone.Info)
            }
        }

        lecture.status == LectureStatus.IMPORTING -> ImportCard(assistant, lecture)

        lecture.status == LectureStatus.RECORDING && recorder != null -> {
            val settings = assistant.settingsStore.settings
            RecordingCard(
                elapsed = formatLectureTime(lecture.elapsedMs),
                level = lecture.level,
                status = when {
                    !assistant.canGenerate -> stringResource(Res.string.lecture_status_recording_only)
                    lecture.pendingUpdates > 0 -> stringResource(Res.string.lecture_status_pending, lecture.pendingUpdates)
                    lecture.transcript.isEmpty() -> stringResource(Res.string.lecture_status_listening, settings.chunkSeconds)
                    else -> lecture.currentTopic.takeIf { it.isNotBlank() }?.let { stringResource(Res.string.lecture_current_topic, it) }
                        ?: stringResource(Res.string.lecture_status_listening_short)
                },
                onStop = { recorder.stop() },
                compact = !assistant.canGenerate
            )
            if (assistant.canGenerate) {
                if (lecture.isAnalyzing) {
                    Progress(stringResource(Res.string.lecture_status_analyzing, settings.task(LectureTask.LIVE_NOTES).model))
                }
                OutlineButton(onClick = { recorder.catchUp() }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Icon(DrawViewIcons.Zap, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.lecture_action_catch_up), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Text(stringResource(Res.string.lecture_record_only_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        lecture.status == LectureStatus.FINALIZING -> Progress(stringResource(Res.string.lecture_status_finalizing))

        else -> {
            if (lecture.recordedElsewhere) AudioElsewhere(assistant, lecture)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton(onClick = onStart, enabled = canRecord, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(DrawViewIcons.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.lecture_action_new_recording), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (assistant.canImportAndExport) {
                    OutlineButton(onClick = onImport, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Icon(DrawViewIcons.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.lecture_action_import_short), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            GenerateHereCard(assistant)
        }
    }
}

/**
 * A recording being imported or transcribed here: converted, waiting its turn, transcribed, reviewed,
 * with its progress. While it waits or is transcribed it can be taken out of the queue.
 */
@Composable
private fun ImportCard(assistant: LectureAssistant, lecture: Lecture) {
    val step = lecture.importStep
    val waiting = step == ImportStep.WAITING
    ImportCard(
        title = stringResource(
            when (step) {
                ImportStep.TRANSCRIBING -> Res.string.lecture_import_transcribing
                ImportStep.REVIEWING -> Res.string.lecture_status_finalizing
                // For the recording going on here, or for the transcriptions asked before it
                ImportStep.WAITING -> if (assistant.canRecord) Res.string.lecture_import_waiting else Res.string.lecture_import_waiting_recording
                else -> Res.string.lecture_import_converting
            }
        ),
        duration = lecture.elapsedMs.takeIf { it > 0 }?.let { formatLectureTime(it) },
        // Waiting its turn: nothing moves, what was transcribed before a pause stays
        progress = if (waiting) lecture.importProgress.coerceAtLeast(0f) else lecture.importProgress,
        remove = when {
            !assistant.isTranscribing(lecture) -> null
            waiting -> stringResource(Res.string.lecture_import_remove)
            step == ImportStep.TRANSCRIBING -> stringResource(Res.string.lecture_action_stop)
            else -> null
        },
        onRemove = { assistant.cancelTranscription(lecture) }
    )
}

/**
 * The card of a recording being imported or transcribed: what is happening ([title]), how long the
 * recording is ([duration]), the [progress] of the step (0..1, negative when unknown), and the
 * action taking it out of the queue ([remove], `null`: none).
 */
@Composable
internal fun ImportCard(title: String, duration: String?, progress: Float, remove: String? = null, onRemove: () -> Unit = {}) {
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(DrawViewIcons.Upload, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (duration != null) {
                    Text(duration, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val track = MaterialTheme.colorScheme.surfaceContainerHighest
        if (progress < 0f) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), trackColor = track, gapSize = 0.dp)
        else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), trackColor = track, gapSize = 0.dp, drawStopIndicator = {})
        // Under the bar: beside the title it would leave the title no room in a narrow panel
        if (remove != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRemove) { Text(remove, maxLines = 1) }
            }
        }
    }
}

/** Something under way: a spinner and what it is. */
@Composable
private fun Progress(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, trackColor = MaterialTheme.colorScheme.primaryContainer)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The notices of the assistant: one lecture at a time, the start, the microphone, the lecture
 * deleted elsewhere, the import, the problems of the models (with the settings when they help).
 */
@Composable
private fun Notices(assistant: LectureAssistant, lecture: Lecture?, recorder: LectureRecorder?, onOpenSettings: () -> Unit) {
    // One recording at a time: another document may be recording
    val recordingElsewhere = assistant.activeRecorder?.takeIf { it !== recorder }?.lecture?.takeIf { it !== lecture }
    val settings = stringResource(Res.string.lecture_action_settings)
    recordingElsewhere?.let {
        LectureNotice(DrawViewIcons.Mic, stringResource(Res.string.lecture_recording_elsewhere, it.documentTitle), NoticeTone.Neutral)
    }
    // The other recordings being transcribed here, or waiting to be: nothing waits for them but the next one
    val transcribedBeside = assistant.transcribing.count { it !== lecture }
    if (transcribedBeside > 0) {
        LectureNotice(DrawViewIcons.Upload, pluralStringResource(Res.plurals.lecture_transcriptions_beside, transcribedBeside, transcribedBeside), NoticeTone.Neutral)
    }
    assistant.startError?.let {
        LectureNotice(DrawViewIcons.TriangleAlert, stringResource(Res.string.lecture_problem_start, it), NoticeTone.Error)
    }
    if (assistant.microphoneDenied) {
        LectureNotice(DrawViewIcons.Mic, stringResource(Res.string.lecture_microphone_denied), NoticeTone.Error)
    }
    if (lecture?.deletedElsewhere == true) {
        LectureNotice(DrawViewIcons.Trash, stringResource(Res.string.lecture_deleted_elsewhere), NoticeTone.Neutral)
    }
    assistant.importError?.let {
        LectureNotice(DrawViewIcons.Upload, stringResource(Res.string.lecture_import_failed, it), NoticeTone.Error)
    }
    if (lecture?.microphoneInterrupted == true && lecture.status == LectureStatus.RECORDING) {
        LectureNotice(DrawViewIcons.Mic, stringResource(Res.string.lecture_microphone_interrupted), NoticeTone.Warning)
    }
    if (lecture?.noAudioWarning == true && lecture.status == LectureStatus.RECORDING) {
        LectureNotice(
            DrawViewIcons.Volume,
            stringResource(if (isComputer) Res.string.lecture_no_audio_warning else Res.string.lecture_no_audio_warning_device),
            NoticeTone.Warning
        )
    }
    lecture?.problem?.let { problem ->
        val (icon, tone) = when (problem) {
            LectureProblem.MissingApiKey, is LectureProblem.Authentication -> DrawViewIcons.Key to NoticeTone.Error
            is LectureProblem.Retrying -> DrawViewIcons.Refresh to NoticeTone.Warning
            is LectureProblem.LocalModelUnavailable -> DrawViewIcons.Monitor to NoticeTone.Error
            LectureProblem.NoTranscript -> DrawViewIcons.FileText to NoticeTone.Warning
            else -> DrawViewIcons.TriangleAlert to NoticeTone.Error
        }
        val message = when (problem) {
            LectureProblem.MissingApiKey -> stringResource(Res.string.lecture_problem_no_api_key)
            is LectureProblem.Authentication -> stringResource(Res.string.lecture_problem_authentication, problem.detail)
            is LectureProblem.Retrying -> stringResource(Res.string.lecture_problem_retrying, problem.retryInSeconds, problem.detail)
            is LectureProblem.Failed -> stringResource(Res.string.lecture_problem_failed, problem.detail)
            is LectureProblem.LocalModelUnavailable -> stringResource(Res.string.lecture_problem_local_unavailable, problem.detail)
            LectureProblem.NoTranscript -> stringResource(Res.string.lecture_problem_no_transcript)
            is LectureProblem.TranscriptionUnavailable -> stringResource(Res.string.lecture_problem_transcription, problem.detail)
            is LectureProblem.QuotaExhausted -> {
                val model = problem.model.ifEmpty { "Gemini" }
                val wait = problem.retryInMs?.let { formatWait(it) }
                when {
                    problem.daily && wait != null -> stringResource(Res.string.lecture_problem_quota_day_in, model, wait)
                    problem.daily -> stringResource(Res.string.lecture_problem_quota_day, model)
                    else -> stringResource(Res.string.lecture_problem_quota, model)
                }
            }
        }
        val needsSettings = problem is LectureProblem.MissingApiKey || problem is LectureProblem.Authentication ||
            problem is LectureProblem.LocalModelUnavailable || problem is LectureProblem.TranscriptionUnavailable ||
            problem is LectureProblem.QuotaExhausted
        LectureNotice(icon, message, tone, action = if (needsSettings) settings to onOpenSettings else null)
    }
}

/** How long a lecture lasted: "1 h 28 min", "42 min". */
internal fun formatDuration(ms: Long): String {
    val minutes = (ms / 60_000).coerceAtLeast(0)
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

/** A wait of the API: "7 h 39 min", "12 min", "1 min". */
private fun formatWait(ms: Long): String {
    val minutes = ((ms + 59_999) / 60_000).coerceAtLeast(1)
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

/** "The lecturer is on page N" (and the topic), with Go: shown when that page is not on screen. */
@Composable
private fun ProfessorPageBanner(lecture: Lecture, controller: DrawViewController) {
    val page = lecture.professorPageIndex ?: return
    if (lecture.status != LectureStatus.RECORDING) return
    // Recomputed at every redraw, recomposed only when the visible pages change
    val visible by remember(controller) {
        derivedStateOf {
            controller.version
            controller.viewport.camera.moveCount
            controller.viewport.visiblePages().map { it.index }
        }
    }
    if (page in visible || page !in controller.document.pages.indices) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(DrawViewIcons.BookOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(
            text = listOfNotNull(
                stringResource(Res.string.lecture_professor_page, page + 1),
                lecture.currentTopic.takeIf { it.isNotBlank() }?.let { stringResource(Res.string.lecture_current_topic, it) }
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        TonalButton(onClick = { controller.pageManager.jumpToPage(page) }) {
            Text(stringResource(Res.string.lecture_action_go))
        }
    }
}

// ==========================================================
// Tabs
// ==========================================================

/**
 * The tabs of the lecture as a segmented choice (the notes with how many are new, the transcript,
 * the outline, the review) and the one chosen; the player above the others while it plays.
 */
@Composable
private fun LectureTabs(assistant: LectureAssistant, lecture: Lecture, controller: DrawViewController) {
    var tab by remember(lecture) { mutableIntStateOf(0) }
    val newCount = lecture.suggestions.count { it.status == SuggestionStatus.NEW }
    val labels = listOf(
        stringResource(Res.string.lecture_tab_notes) + if (newCount > 0) " · $newCount" else "",
        stringResource(Res.string.lecture_tab_transcript),
        stringResource(Res.string.lecture_tab_outline),
        stringResource(Res.string.lecture_tab_review)
    )
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val notesFonts = rememberNotesFonts()
    val inserter = remember(fontFamilyResolver, notesFonts, controller.engines) {
        NoteInserter(ComposeTextTileAdapter(fontFamilyResolver, notesFonts, controller.engines.formulas, controller.engines.code))
    }

    SegmentedChoice(
        options = labels.indices.toList(),
        selected = tab,
        label = { labels[it] },
        onSelect = { tab = it },
        textStyle = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(horizontal = PanelPadding).height(44.dp)
    )
    // Listening to a note from another tab: the player stays at hand
    if (tab != 1 && lecture.player?.isPlaying == true) {
        Box(modifier = Modifier.padding(start = PanelPadding, end = PanelPadding, top = 12.dp)) { PlayerCard(lecture) }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        when (tab) {
            0 -> NotesTab(assistant, lecture, controller, inserter)
            1 -> TranscriptTab(lecture)
            2 -> OutlineTab(assistant, lecture)
            else -> ReviewTab(assistant, lecture, controller, inserter)
        }
    }
}

/** When the lecture was recorded (wall clock), with the time the writing about its end may take. */
private fun Lecture.timeSpan(): LongRange = startedAt..(startedAt + elapsedMs + LectureAnchors.AFTER_MS)

/** The notes suggested: the new ones on their cards, those handled under them (folded). */
@Composable
private fun NotesTab(assistant: LectureAssistant, lecture: Lecture, controller: DrawViewController, inserter: NoteInserter) {
    val newNotes = lecture.suggestions.filter { it.status == SuggestionStatus.NEW }.sortedByDescending { it.atMs }
    val handled = lecture.suggestions.filter { it.status != SuggestionStatus.NEW }.sortedByDescending { it.atMs }
    var showHandled by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LazyColumn(
        contentPadding = PaddingValues(horizontal = PanelPadding, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (newNotes.isEmpty()) {
            item { MutedText(stringResource(Res.string.lecture_notes_empty), Modifier.padding(4.dp)) }
        }
        if (newNotes.size >= 2) {
            item(key = "insert-all") {
                val label = stringResource(Res.string.lecture_place_all_label, newNotes.size)
                TonalButton(
                    onClick = {
                        assistant.placingNote = null
                        scope.launch {
                            inserter.startPlacementAll(
                                controller = controller,
                                suggestions = newNotes,
                                lecture = lecture.timeSpan(),
                                label = label,
                                handwriting = assistant.handwritingTexts(lecture.documentId),
                                onInserted = { notes -> notes.forEach { lecture.updateSuggestion(it.id, SuggestionStatus.INSERTED) } },
                                onCancel = {}
                            )
                        }
                    },
                    contentPadding = PaddingValues(start = 16.dp, end = 20.dp)
                ) {
                    Icon(DrawViewIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.lecture_notes_insert_all, newNotes.size), maxLines = 1)
                }
            }
        }
        items(newNotes, key = { it.id }) { note ->
            SuggestionCard(
                note = note,
                pageIndex = note.resolvedPageIndex(controller.document),
                canListen = lecture.canPlayAudio,
                placing = assistant.placingNote?.id == note.id,
                onInsert = {
                    scope.launch {
                        val started = inserter.startPlacement(
                            controller = controller,
                            suggestion = note,
                            lecture = lecture.timeSpan(),
                            handwriting = assistant.handwritingTexts(lecture.documentId),
                            onInserted = {
                                assistant.placingNote = null
                                lecture.updateSuggestion(note.id, SuggestionStatus.INSERTED)
                            },
                            onCancel = { if (assistant.placingNote?.id == note.id) assistant.placingNote = null }
                        )
                        if (started) assistant.placingNote = note
                    }
                },
                onCancelPlacing = { controller.placementManager.cancel() },
                onListen = { lecture.play(note) },
                onAlreadyNoted = { lecture.updateSuggestion(note.id, SuggestionStatus.ALREADY_NOTED) },
                onDismiss = { lecture.updateSuggestion(note.id, SuggestionStatus.DISMISSED) }
            )
        }
        if (handled.isNotEmpty()) {
            item(key = "handled") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showHandled = !showHandled }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(Res.string.lecture_notes_handled, handled.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (showHandled) DrawViewIcons.ChevronUp else DrawViewIcons.ChevronDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            if (showHandled) {
                items(handled, key = { it.id }) { note ->
                    HandledRow(note, pageIndex = note.resolvedPageIndex(controller.document), onRestore = { lecture.updateSuggestion(note.id, SuggestionStatus.NEW) })
                }
            }
        }
    }
}

/**
 * A suggested note on its card: its kind, whether it is important, when it was said; its title
 * and its text; insert it (on its page), listen to it, already noted, dismiss.
 */
@Composable
internal fun SuggestionCard(
    note: NoteSuggestion,
    pageIndex: Int?,
    canListen: Boolean,
    placing: Boolean,
    onInsert: () -> Unit,
    onCancelPlacing: () -> Unit,
    onListen: () -> Unit,
    onAlreadyNoted: () -> Unit,
    onDismiss: () -> Unit
) {
    PanelCard(outlined = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(28.dp).background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape)
            ) {
                Icon(DrawViewIcons.Sparkles, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(16.dp))
            }
            Text(
                kindLabel(note.kind).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (note.importance >= 3) LabelBadge(stringResource(Res.string.lecture_importance_high), BadgeTone.Yellow, icon = DrawViewIcons.Star)
            TimeChip(if (note.fromFinalReview) stringResource(Res.string.lecture_note_from_review) else formatLectureTime(note.atMs))
        }
        if (note.title.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(note.title, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(6.dp))
        SelectionContainer {
            MarkdownText(note.content, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        if (placing) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.lecture_place_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (placing) {
                OutlineButton(onClick = onCancelPlacing) { Text(stringResource(Res.string.lecture_action_cancel)) }
            } else {
                TonalButton(onClick = onInsert, contentPadding = PaddingValues(start = 16.dp, end = 20.dp)) {
                    Icon(DrawViewIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        pageIndex?.let { stringResource(Res.string.lecture_note_insert_page, it + 1) } ?: stringResource(Res.string.lecture_note_insert),
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (canListen && !note.fromFinalReview) {
                DesignIconButton(DrawViewIcons.Volume, stringResource(Res.string.lecture_note_listen), onClick = onListen)
            }
            DesignIconButton(DrawViewIcons.CheckCircle, stringResource(Res.string.lecture_note_have_it), onClick = onAlreadyNoted)
            DesignIconButton(DrawViewIcons.Close, stringResource(Res.string.lecture_note_dismiss), onClick = onDismiss)
        }
    }
}

/** A time of the lecture on a chip: the high container, the primary color when it is the one playing. */
@Composable
private fun TimeChip(text: String, active: Boolean = false, onClick: (() -> Unit)? = null) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/** A note handled: inserted, already noted or dismissed, restored from here. */
@Composable
private fun HandledRow(note: NoteSuggestion, pageIndex: Int?, onRestore: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(16.dp))
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            when (note.status) {
                SuggestionStatus.INSERTED -> DrawViewIcons.CheckCircle
                SuggestionStatus.ALREADY_NOTED -> DrawViewIcons.Check
                else -> DrawViewIcons.Close
            },
            contentDescription = null,
            tint = if (note.status == SuggestionStatus.DISMISSED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(note.title.ifBlank { note.content }, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                text = listOfNotNull(
                    stringResource(
                        when (note.status) {
                            SuggestionStatus.INSERTED -> Res.string.lecture_note_status_inserted
                            SuggestionStatus.ALREADY_NOTED -> Res.string.lecture_note_status_already_noted
                            else -> Res.string.lecture_note_status_dismissed
                        }
                    ),
                    pageIndex?.takeIf { note.status == SuggestionStatus.INSERTED }?.let { stringResource(Res.string.lecture_note_page, it + 1) }
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onRestore) { Text(stringResource(Res.string.lecture_note_restore)) }
    }
}

/** The name of the kind of a note. */
@Composable
private fun kindLabel(kind: NoteKind): String = stringResource(
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

/**
 * The transcript under the player: each time on its chip (playing the recording from there), the
 * segment playing on the primary container, the words being said in italics.
 */
@Composable
private fun TranscriptTab(lecture: Lecture) {
    val listState = rememberLazyListState()
    val count = lecture.transcript.size
    // What the lecturer is saying, transcribed live: the last line
    val interim = lecture.interimTranscript.takeIf { it.isNotBlank() && lecture.status == LectureStatus.RECORDING }
    val items = count + if (interim != null) 1 else 0
    LaunchedEffect(count, interim != null) {
        if (items > 0) listState.animateScrollToItem(items - 1)
    }
    val player = lecture.player
    val playing = playingSegment(player, lecture.transcript.map { it.startMs })
    // The segment being played stays in sight
    LaunchedEffect(playing) {
        if (playing != null && player?.isPlaying == true) listState.animateScrollToItem(playing)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.padding(start = PanelPadding, end = PanelPadding, top = 14.dp)) { PlayerCard(lecture) }
        if (items == 0) {
            MutedText(stringResource(Res.string.lecture_transcript_empty), Modifier.padding(PanelPadding))
            return@Column
        }
        SelectionContainer {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = PanelPadding - 8.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(lecture.transcript.toList()) { index, segment ->
                    val isPlaying = index == playing
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isPlaying) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(16.dp))
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // The time plays the recording from there
                        TimeChip(formatLectureTime(segment.startMs), active = isPlaying, onClick = player?.let { { it.play(segment.startMs) } })
                        Text(
                            segment.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isPlaying) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                if (interim != null) {
                    item(key = "interim") {
                        Text(
                            text = interim,
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

/** The course context of the notebook on its card, then the outline of the lecture. */
@Composable
private fun OutlineTab(assistant: LectureAssistant, lecture: Lecture) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PanelPadding, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        CourseContextCard(assistant, lecture.documentId)
        Text(stringResource(Res.string.lecture_outline_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (lecture.outline.isBlank()) {
            MutedText(stringResource(Res.string.lecture_outline_empty))
        } else {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (lecture.currentTopic.isNotBlank()) {
                        // Where the lecturer is now
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(modifier = Modifier.size(6.dp).background(MaterialTheme.colorScheme.onPrimaryContainer, CircleShape))
                            Text(
                                text = stringResource(Res.string.lecture_current_topic, lecture.currentTopic),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    MarkdownText(lecture.outline, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
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
    PanelCard(outlined = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(DrawViewIcons.BookOpen, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
            Text(stringResource(Res.string.lecture_context_title), style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(10.dp))
        when {
            writing -> Progress(stringResource(Res.string.lecture_context_writing))
            editing -> {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    minLines = 3,
                    maxLines = 8,
                    shape = RoundedCornerShape(12.dp),
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
                Spacer(Modifier.height(8.dp))
                MutedText(stringResource(Res.string.lecture_context_hint))
            }
        }
        if (!editing && !writing) {
            Spacer(Modifier.height(4.dp))
            Row {
                TextButton(onClick = {
                    draft = context?.text.orEmpty()
                    editing = true
                }) {
                    Icon(DrawViewIcons.Pencil, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.lecture_context_edit))
                }
                if (assistant.canWriteContext(documentId)) {
                    TextButton(onClick = { assistant.writeContext(documentId) }) {
                        Icon(DrawViewIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.lecture_context_write))
                    }
                }
            }
        }
    }
}

/**
 * The final review: being written (here or elsewhere), failed, or written (by which model, written
 * again, its text, inserting it as pages); the requests of the lecture at the end.
 */
@Composable
private fun ReviewTab(
    assistant: LectureAssistant,
    lecture: Lecture,
    controller: DrawViewController,
    inserter: NoteInserter
) {
    val settings = assistant.settingsStore.settings
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PanelPadding, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        val review = lecture.finalReview
        val now = rememberNow()
        val elsewhere = lecture.generatingElsewhere(now)
        // Without the transcript, the audio is transcribed first; not while another device writes them
        val canRegenerate = lecture.canGenerateReview && (lecture.transcript.isNotEmpty() || lecture.hasAudio) && elsewhere == null
        elsewhere?.let { LectureNotice(DrawViewIcons.Users, stringResource(Res.string.lecture_generating_elsewhere, it.name), NoticeTone.Info) }
        when {
            lecture.reviewStatus == ReviewStatus.RUNNING || lecture.status == LectureStatus.FINALIZING -> LectureNotice(
                DrawViewIcons.Sparkles,
                if (lecture.reviewProvider == ModelProvider.LOCAL && lecture.reviewStatus == ReviewStatus.RUNNING) {
                    stringResource(Res.string.lecture_review_running_local, lecture.reviewModel.orEmpty(), lecture.reviewProgressTokens)
                } else {
                    stringResource(Res.string.lecture_review_running, lecture.reviewModel ?: settings.task(LectureTask.FINAL_REVIEW).model)
                },
                NoticeTone.Neutral
            )
            lecture.reviewStatus == ReviewStatus.FAILED -> {
                LectureNotice(DrawViewIcons.TriangleAlert, stringResource(Res.string.lecture_review_failed), NoticeTone.Error)
                if (canRegenerate) RegenerateReviewButton(assistant, lecture)
            }
            review != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        lecture.reviewModel?.let { stringResource(Res.string.lecture_review_by, it) }.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (canRegenerate) RegenerateReviewButton(assistant, lecture)
                }
                PanelCard(outlined = true) { SelectionContainer { MarkdownText(review, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium) } }
                val reviewLabel = stringResource(Res.string.lecture_review_placing)
                val scope = rememberCoroutineScope()
                InsertPagesCard(controller, byTopic = controller.textPlacer.canPlaceByTopic) { place, pageIndex ->
                    if (place == ReviewPlace.TOPIC) {
                        scope.launch {
                            val parts = controller.textPlacer.partsByTopic(review, fallbackPage = pageIndex)
                            inserter.startReviewPlacement(controller, parts, reviewLabel)
                        }
                    } else {
                        inserter.startReviewPlacement(controller, review, if (place == ReviewPlace.END) null else pageIndex, reviewLabel)
                    }
                }
            }
            // Written by a device with the models
            !lecture.generates -> LectureNotice(DrawViewIcons.Monitor, stringResource(Res.string.lecture_review_elsewhere), NoticeTone.Info)
            lecture.status == LectureStatus.FINISHED -> {
                // Recorded where the models are not, written on request, or interrupted before its review (e.g. the application was closed)
                if (lecture.recordedElsewhere && lecture.transcript.isEmpty()) {
                    LectureNotice(DrawViewIcons.Upload, stringResource(Res.string.lecture_review_received, lecture.recordedOn), NoticeTone.Info)
                } else {
                    BodyText(stringResource(Res.string.lecture_review_pending))
                }
                if (canRegenerate) RegenerateReviewButton(assistant, lecture, label = Res.string.lecture_review_generate)
            }
            !settings.reviewAtTheEnd -> BodyText(stringResource(Res.string.lecture_review_on_request))
            else -> BodyText(stringResource(Res.string.lecture_review_none, settings.task(LectureTask.FINAL_REVIEW).model))
        }

        UsageSummary(lecture)
    }
}

/** Writes the final review (again), online or by the local model: a button opening the two. */
@Composable
private fun RegenerateReviewButton(
    assistant: LectureAssistant,
    lecture: Lecture,
    label: StringResource = Res.string.lecture_review_regenerate
) {
    val settings = assistant.settingsStore.settings
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Icon(DrawViewIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(label))
        }
        DesignMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MenuItem(
                text = stringResource(Res.string.lecture_review_with_gemini, settings.task(LectureTask.FINAL_REVIEW).gemini),
                icon = rememberVectorPainter(DrawViewIcons.Globe),
                onClick = {
                    expanded = false
                    assistant.generate(lecture, ModelProvider.GEMINI)
                }
            )
            MenuItem(
                text = stringResource(Res.string.lecture_review_with_local, settings.task(LectureTask.FINAL_REVIEW).local),
                icon = rememberVectorPainter(DrawViewIcons.Monitor),
                onClick = {
                    expanded = false
                    assistant.generate(lecture, ModelProvider.LOCAL)
                }
            )
        }
    }
}

/** The requests of the lecture and where the tokens of the online ones went; who used them, when several devices did. */
@Composable
private fun UsageSummary(lecture: Lecture) {
    val usage = lecture.totalUsage
    if (usage.requests == 0 && usage.localRequests == 0 && usage.skippedUpdates == 0) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        UsageLines(usage)
        if (lecture.otherUsage.isNotEmpty()) {
            val lines = (listOf(stringResource(Res.string.lecture_usage_this_device) to lecture.usage) + lecture.otherUsage.values.map { it.name to it.usage })
                .filter { (_, used) -> used.requests > 0 || used.localRequests > 0 }
            Spacer(Modifier.height(6.dp))
            for ((name, used) in lines) {
                MutedText(stringResource(Res.string.lecture_usage_device, name, used.requests + used.localRequests, compactNumber(used.promptTokens + used.outputTokens)))
            }
        }
    }
}

@Composable
private fun UsageLines(usage: TokenUsage) {
    if (usage.requests > 0) {
        Text(
            stringResource(
                Res.string.lecture_usage_tokens,
                usage.requests,
                compactNumber(usage.promptTokens),
                compactNumber(usage.cachedTokens),
                compactNumber(usage.outputTokens)
            ),
            style = MaterialTheme.typography.bodySmall
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
        Text(stringResource(Res.string.lecture_usage_input, parts.joinToString(" · ")), style = MaterialTheme.typography.bodySmall)
    }
    if (usage.localRequests > 0) {
        Text(stringResource(Res.string.lecture_usage_local, usage.localRequests), style = MaterialTheme.typography.bodySmall)
    }
    if (usage.skippedUpdates > 0) {
        MutedText(stringResource(Res.string.lecture_usage_skipped, usage.skippedUpdates))
    }
}

/**
 * "Insert as new pages" on its card: after the page the student is looking at, or at the end of
 * the notebook, and Insert.
 */
@Composable
private fun InsertPagesCard(controller: DrawViewController, byTopic: Boolean, onInsert: (place: ReviewPlace, pageIndex: Int?) -> Unit) {
    val currentPage by remember(controller) {
        derivedStateOf {
            controller.version
            controller.viewport.camera.moveCount
            controller.viewport.mostVisiblePageIndex()
        }
    }
    var choice by remember(byTopic) { mutableStateOf(if (byTopic) ReviewPlace.TOPIC else ReviewPlace.PAGE) }
    val page = currentPage
    val place = if (choice == ReviewPlace.PAGE && page == null) ReviewPlace.END else choice
    PanelCard {
        Text(stringResource(Res.string.lecture_review_insert), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        if (byTopic) {
            InsertChoice(stringResource(Res.string.lecture_review_insert_topic), selected = place == ReviewPlace.TOPIC) { choice = ReviewPlace.TOPIC }
        }
        if (page != null) {
            InsertChoice(stringResource(Res.string.lecture_review_insert_after, page + 1), selected = place == ReviewPlace.PAGE) { choice = ReviewPlace.PAGE }
        }
        InsertChoice(stringResource(Res.string.lecture_review_insert_end), selected = place == ReviewPlace.END) { choice = ReviewPlace.END }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onInsert(place, page) }) {
            Icon(DrawViewIcons.FilePlus, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.lecture_note_insert))
        }
    }
}

/** Where the review of a lecture goes in the notebook. */
private enum class ReviewPlace {
    /** Each section next to the pages it is about. */
    TOPIC,

    /** The page shown, then new pages after it. */
    PAGE,

    /** The end of the notebook. */
    END
}

@Composable
private fun InsertChoice(text: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The audio of a lecture recorded on another device: here, arriving (how much of it), asked for,
 * or kept there (not shared).
 */
@Composable
private fun AudioElsewhere(assistant: LectureAssistant, lecture: Lecture) {
    val arriving by assistant.audioArriving.collectAsState()
    val progress = arriving[lecture.directory]
    val sharing = assistant.sharingOf(lecture.documentId)
    when {
        lecture.hasAudio -> LectureNotice(DrawViewIcons.Users, stringResource(Res.string.lecture_recorded_on, lecture.recordedOn), NoticeTone.Info)
        !lecture.audioShared -> LectureNotice(DrawViewIcons.Users, stringResource(Res.string.lecture_audio_not_shared, lecture.recordedOn), NoticeTone.Neutral)
        progress != null -> {
            LectureNotice(DrawViewIcons.Download, stringResource(Res.string.lecture_audio_progress, lecture.recordedOn, (progress * 100).toInt()), NoticeTone.Info)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), gapSize = 0.dp, drawStopIndicator = {})
        }
        sharing.downloadAudio == AudioDownload.ON_REQUEST && lecture.id !in sharing.requestedAudio -> LectureNotice(
            DrawViewIcons.Download,
            stringResource(Res.string.lecture_recorded_on, lecture.recordedOn),
            NoticeTone.Info,
            action = stringResource(Res.string.lecture_audio_download) to { assistant.requestAudio(lecture) }
        )
        else -> LectureNotice(DrawViewIcons.Download, stringResource(Res.string.lecture_audio_arriving, lecture.recordedOn), NoticeTone.Info)
    }
}

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

/** A sentence of the panel: what happens, what to do. */
@Composable
private fun BodyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** What leaves this computer with the current settings, before recording. */
@Composable
internal fun privacyNote(settings: AssistantSettings): String {
    val shared = settings.sentToGoogle
    if (shared.isEmpty()) return stringResource(Res.string.lecture_privacy_local)
    val parts = SharedData.entries.filter { it in shared }.map { data ->
        stringResource(
            when (data) {
                SharedData.TRANSCRIPT -> Res.string.lecture_privacy_transcript
                SharedData.NOTEBOOK -> Res.string.lecture_privacy_notebook
            }
        )
    }
    return stringResource(Res.string.lecture_privacy_online, parts.joinToString(", "))
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

/** Now, again every few seconds (for what depends on the time passing). */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableStateOf(currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = currentTimeMillis()
        }
    }
    return now
}
