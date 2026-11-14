package io.github.valerioisufi.drawview.lecture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.lecture.LectureAssistant
import io.github.valerioisufi.drawview.lecture.LectureStatus
import io.github.valerioisufi.drawview.lecture.LectureSummary
import io.github.valerioisufi.drawview.lecture.audio.importableExtensions
import io.github.valerioisufi.drawview.lecture.ui.generated.resources.*
import io.github.valerioisufi.drawview.lecture.ui.platform.rememberFileOpener
import io.github.valerioisufi.drawview.ui.command.DesignMenu
import io.github.valerioisufi.drawview.ui.command.MenuItem
import io.github.valerioisufi.drawview.ui.components.DesignIconButton
import io.github.valerioisufi.drawview.ui.components.OutlineButton
import io.github.valerioisufi.drawview.ui.extension.EditorContext
import io.github.valerioisufi.drawview.ui.extension.EditorPanel
import io.github.valerioisufi.drawview.ui.generated.resources.Res as UiRes
import io.github.valerioisufi.drawview.ui.generated.resources.date_months_short
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import io.github.valerioisufi.drawview.ui.util.formatShortDateTime
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
internal fun LectureEditorEffects(assistant: LectureAssistant, documentId: String, editor: EditorContext) {
    DisposableEffect(assistant, documentId, editor) {
        val notebook = ControllerNotebookSource(editor)
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
    editor: EditorContext,
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
    content = { PanelContent(assistant, documentId, editor, state) }
)

/** The settings of the assistant, while asked. */
@Composable
internal fun LectureSettings(assistant: LectureAssistant, state: LecturePanelState) {
    if (state.settingsShown) {
        AssistantSettingsDialog(store = assistant.settingsStore, localModels = assistant.localModels, onDismiss = { state.settingsShown = false })
    }
}

/**
 * What the panel shows under its header: what the assistant is doing (a lecture to record, the
 * one shown with its menu, the recording, the notices), then the tabs of the lecture. The part
 * above the tabs scrolls when it is long (a short window).
 */
@Composable
internal fun PanelContent(assistant: LectureAssistant, documentId: String, editor: EditorContext, state: LecturePanelState) {
    val lecture = assistant.shownLecture(documentId)
    val recorder = assistant.recorderOf(documentId)
    val chooseRecording = rememberFileOpener(stringResource(Res.string.lecture_import_title), importableExtensions()) { file ->
        assistant.importRecording(documentId, editor.document.document.title, assistant.notebookOf(documentId, editor), { editor.document.document.pages.map { it.id } }, file)
    }
    val onImport = {
        assistant.clearImportAndExportMessages()
        chooseRecording()
    }
    val onStart = {
        // A key is needed only when something is sent to Gemini
        val settings = assistant.settingsStore.settings
        if (assistant.canGenerate && settings.sentToGoogle.isNotEmpty() && !assistant.settingsStore.hasApiKey) state.settingsShown = true
        else assistant.record(documentId, editor.document.document.title, assistant.notebookOf(documentId, editor))
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
                    ProfessorPageBanner(lecture, editor)
                }
                Notices(assistant, lecture, recorder, onOpenSettings)
            }
            if (lecture != null) LectureTabs(assistant, lecture, editor)
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
internal fun GenerateHereCard(assistant: LectureAssistant) {
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
internal fun lectureDate(epochMs: Long): String = formatShortDateTime(epochMs, stringArrayResource(UiRes.array.date_months_short))

/** Deleting a lecture, asked first: what goes, what stays. */
@Composable
internal fun DeleteLectureDialog(summary: LectureSummary, onConfirm: () -> Unit, onDismiss: () -> Unit) {
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
internal const val RecordingExportType = "audio/mp4"

/** The type of the exported transcripts (txt). */
internal const val TranscriptExportType = "text/plain"
