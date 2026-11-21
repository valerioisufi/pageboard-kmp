package io.github.valerioisufi.pageboard.lecture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.lecture.AudioDownload
import io.github.valerioisufi.pageboard.lecture.ImportStep
import io.github.valerioisufi.pageboard.lecture.Lecture
import io.github.valerioisufi.pageboard.lecture.LectureAssistant
import io.github.valerioisufi.pageboard.lecture.LectureProblem
import io.github.valerioisufi.pageboard.lecture.LectureRecorder
import io.github.valerioisufi.pageboard.lecture.LectureStatus
import io.github.valerioisufi.pageboard.lecture.LectureSummary
import io.github.valerioisufi.pageboard.lecture.LectureTask
import io.github.valerioisufi.pageboard.lecture.formatLectureTime
import io.github.valerioisufi.pageboard.lecture.platform.isComputer
import io.github.valerioisufi.pageboard.lecture.ui.generated.resources.*
import io.github.valerioisufi.pageboard.ui.command.DesignMenu
import io.github.valerioisufi.pageboard.ui.command.MenuDivider
import io.github.valerioisufi.pageboard.ui.command.MenuItem
import io.github.valerioisufi.pageboard.ui.components.OutlineButton
import io.github.valerioisufi.pageboard.ui.components.TonalButton
import io.github.valerioisufi.pageboard.ui.extension.EditorContext
import io.github.valerioisufi.pageboard.ui.platform.rememberFileSaver
import io.github.valerioisufi.pageboard.ui.theme.PageboardIcons
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// The header of a lecture in the panel: its recording, its import, its sharing and its notices

/**
 * The lecture shown, on a card opening its menu (not while it is recorded here): finished and how
 * long, when, how many lectures the notebook has. The menu: the lectures to show, exporting the
 * audio and the transcript, transcribing it again, how the audio is shared, deleting it.
 */
@Composable
internal fun LectureHeader(assistant: LectureAssistant, documentId: String, lecture: Lecture, recordingHere: Boolean) {
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
                if (finished) PageboardIcons.CheckCircle else PageboardIcons.Mic,
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
            Icon(if (expanded) PageboardIcons.ChevronUp else PageboardIcons.ChevronDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DesignMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (summary in lectures) {
                MenuItem(
                    text = lectureDate(summary.startedAt) + " · " + formatDuration(summary.durationMs),
                    icon = rememberVectorPainter(PageboardIcons.Mic),
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
                        icon = rememberVectorPainter(PageboardIcons.Download),
                        onClick = {
                            expanded = false
                            exported[0] = lecture
                            saveRecording("${exportName()}.${assistant.exportExtension}")
                        }
                    )
                }
                if (canExportTranscript) {
                    MenuItem(
                        text = stringResource(Res.string.lecture_export_transcript),
                        icon = rememberVectorPainter(PageboardIcons.FileText),
                        onClick = {
                            expanded = false
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
                    icon = rememberVectorPainter(PageboardIcons.Monitor),
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
                    icon = rememberVectorPainter(PageboardIcons.Trash),
                    destructive = true,
                    onClick = {
                        expanded = false
                        confirmDelete = shown
                    }
                )
            }
        }
    }

    assistant.exportingFileName?.let { fileName ->
        val progress = assistant.exportProgress
        val queued = assistant.queuedExports
        val title = stringResource(Res.string.lecture_export_running, fileName)
        val text = if (queued > 0) "$title · ${stringResource(Res.string.lecture_export_queued, queued)}" else title
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(PageboardIcons.Download, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
            val track = MaterialTheme.colorScheme.surfaceContainerHighest
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), trackColor = track, gapSize = 0.dp)
            } else {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), trackColor = track, gapSize = 0.dp, drawStopIndicator = {})
            }
        }
    }

    assistant.exportResults.forEach { result ->
        val isTranscript = result.fileName.endsWith(".${assistant.transcriptExportExtension}", ignoreCase = true)
        val closeAction = stringResource(Res.string.lecture_action_close) to { assistant.dismissExportResult(result) }
        if (result.failure == null) {
            val message = if (isTranscript) Res.string.lecture_export_transcript_done else Res.string.lecture_export_done
            LectureNotice(PageboardIcons.Download, stringResource(message, result.fileName), NoticeTone.Neutral, action = closeAction)
        } else {
            val message = if (isTranscript) Res.string.lecture_export_transcript_failed else Res.string.lecture_export_failed
            LectureNotice(PageboardIcons.TriangleAlert, stringResource(message, result.failure.orEmpty()), NoticeTone.Error, action = closeAction)
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
internal fun RecordingSection(
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
                    PageboardIcons.TriangleAlert,
                    stringResource(Res.string.lecture_status_silent_elsewhere, lecture.recordedOn, silentMinutes.toInt()),
                    NoticeTone.Error
                )
            } else {
                LectureNotice(
                    PageboardIcons.Users,
                    if (lecture.status == LectureStatus.IMPORTING) stringResource(Res.string.lecture_status_processing_elsewhere)
                    else stringResource(Res.string.lecture_status_recording_on, lecture.recordedOn, formatLectureTime(lecture.elapsedMs)),
                    NoticeTone.Info
                )
                // Its notes written here as its audio arrives
                if (lecture.followedHere) LectureNotice(PageboardIcons.Monitor, stringResource(Res.string.lecture_followed_here), NoticeTone.Info)
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
                    Icon(PageboardIcons.Zap, contentDescription = null, modifier = Modifier.size(18.dp))
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
                    Icon(PageboardIcons.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.lecture_action_new_recording), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (assistant.canImportAndExport) {
                    OutlineButton(onClick = onImport, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Icon(PageboardIcons.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
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
            IconTile(PageboardIcons.Upload, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
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

/**
 * The notices of the assistant: one lecture at a time, the start, the microphone, the lecture
 * deleted elsewhere, the import, the problems of the models (with the settings when they help).
 */
@Composable
internal fun Notices(assistant: LectureAssistant, lecture: Lecture?, recorder: LectureRecorder?, onOpenSettings: () -> Unit) {
    // One recording at a time: another document may be recording
    val recordingElsewhere = assistant.activeRecorder?.takeIf { it !== recorder }?.lecture?.takeIf { it !== lecture }
    val settings = stringResource(Res.string.lecture_action_settings)
    recordingElsewhere?.let {
        LectureNotice(PageboardIcons.Mic, stringResource(Res.string.lecture_recording_elsewhere, it.documentTitle), NoticeTone.Neutral)
    }
    // The other recordings being transcribed here, or waiting to be: nothing waits for them but the next one
    val transcribedBeside = assistant.transcribing.count { it !== lecture }
    if (transcribedBeside > 0) {
        LectureNotice(PageboardIcons.Upload, pluralStringResource(Res.plurals.lecture_transcriptions_beside, transcribedBeside, transcribedBeside), NoticeTone.Neutral)
    }
    assistant.startError?.let {
        LectureNotice(PageboardIcons.TriangleAlert, stringResource(Res.string.lecture_problem_start, it), NoticeTone.Error)
    }
    if (assistant.microphoneDenied) {
        LectureNotice(PageboardIcons.Mic, stringResource(Res.string.lecture_microphone_denied), NoticeTone.Error)
    }
    if (lecture?.deletedElsewhere == true) {
        LectureNotice(PageboardIcons.Trash, stringResource(Res.string.lecture_deleted_elsewhere), NoticeTone.Neutral)
    }
    assistant.importError?.let {
        LectureNotice(PageboardIcons.Upload, stringResource(Res.string.lecture_import_failed, it), NoticeTone.Error)
    }
    if (lecture?.microphoneInterrupted == true && lecture.status == LectureStatus.RECORDING) {
        LectureNotice(PageboardIcons.Mic, stringResource(Res.string.lecture_microphone_interrupted), NoticeTone.Warning)
    }
    if (lecture?.noAudioWarning == true && lecture.status == LectureStatus.RECORDING) {
        LectureNotice(
            PageboardIcons.Volume,
            stringResource(if (isComputer) Res.string.lecture_no_audio_warning else Res.string.lecture_no_audio_warning_device),
            NoticeTone.Warning
        )
    }
    lecture?.problem?.let { problem ->
        val (icon, tone) = when (problem) {
            LectureProblem.MissingApiKey, is LectureProblem.Authentication -> PageboardIcons.Key to NoticeTone.Error
            is LectureProblem.Retrying -> PageboardIcons.Refresh to NoticeTone.Warning
            is LectureProblem.LocalModelUnavailable -> PageboardIcons.Monitor to NoticeTone.Error
            LectureProblem.NoTranscript -> PageboardIcons.FileText to NoticeTone.Warning
            else -> PageboardIcons.TriangleAlert to NoticeTone.Error
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

/** "The lecturer is on page N" (and the topic), with Go: shown when that page is not on screen. */
@Composable
internal fun ProfessorPageBanner(lecture: Lecture, editor: EditorContext) {
    val page = lecture.professorPageIndex ?: return
    if (lecture.status != LectureStatus.RECORDING) return
    // Recomputed at every redraw, recomposed only when the visible pages change
    val visible by remember(editor) {
        derivedStateOf {
            editor.pages.frames
            editor.pages.visiblePageIndices()
        }
    }
    if (page in visible || page !in editor.document.document.pages.indices) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(PageboardIcons.BookOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(
            text = listOfNotNull(
                stringResource(Res.string.lecture_professor_page, page + 1),
                lecture.currentTopic.takeIf { it.isNotBlank() }?.let { stringResource(Res.string.lecture_current_topic, it) }
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        TonalButton(onClick = { editor.pages.jumpToPage(page) }) {
            Text(stringResource(Res.string.lecture_action_go))
        }
    }
}
