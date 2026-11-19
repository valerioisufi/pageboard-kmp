package io.github.valerioisufi.pageboard.lecture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.valerioisufi.pageboard.lecture.ArrivalNotification
import io.github.valerioisufi.pageboard.lecture.DeviceModels
import io.github.valerioisufi.pageboard.lecture.LectureArrival
import io.github.valerioisufi.pageboard.lecture.LectureAssistant
import io.github.valerioisufi.pageboard.lecture.LectureStatus
import io.github.valerioisufi.pageboard.lecture.ModelProvider
import io.github.valerioisufi.pageboard.lecture.SuggestionStatus
import io.github.valerioisufi.pageboard.lecture.formatLectureTime
import io.github.valerioisufi.pageboard.lecture.ui.generated.resources.*
import io.github.valerioisufi.pageboard.lecture.ui.generated.resources.Res
import io.github.valerioisufi.pageboard.lecture.ui.generated.resources.lecture_action_cancel
import io.github.valerioisufi.pageboard.lecture.ui.generated.resources.lecture_action_stop
import io.github.valerioisufi.pageboard.lecture.ui.generated.resources.lecture_exit_confirm
import io.github.valerioisufi.pageboard.lecture.ui.generated.resources.lecture_exit_message
import io.github.valerioisufi.pageboard.lecture.ui.generated.resources.lecture_exit_title
import io.github.valerioisufi.pageboard.lecture.ui.generated.resources.lecture_title
import io.github.valerioisufi.pageboard.lecture.ui.platform.rememberMicrophoneAccess
import io.github.valerioisufi.pageboard.ui.command.Command
import io.github.valerioisufi.pageboard.ui.command.CommandGroup
import io.github.valerioisufi.pageboard.ui.components.BadgeTone
import io.github.valerioisufi.pageboard.ui.extension.DocumentTag
import io.github.valerioisufi.pageboard.ui.extension.EditorExtension
import io.github.valerioisufi.pageboard.ui.screen.EditorActionButton
import io.github.valerioisufi.pageboard.ui.theme.DrawViewElevation
import io.github.valerioisufi.pageboard.ui.theme.DrawViewIcons
import io.github.valerioisufi.pageboard.ui.theme.elevation
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * The lecture assistant in the DrawView editor: a button of the top bar (the recording time while
 * recording), the assistant panel (placed by the editor beside the canvas or rising from the
 * bottom over it; also shown and hidden from the menus), its settings and the hints over the
 * canvas. Closing the editor does not stop the recording (see [LectureRecordingIndicator]).
 */
public fun LectureAssistant.editorExtension(): EditorExtension {
    // The panel shown or hidden, where the lectures can be recorded
    val panel = Command(
        id = "lecture-panel",
        label = { stringResource(Res.string.lecture_title) },
        group = CommandGroup.VIEW,
        section = 2,
        icon = { rememberVectorPainter(DrawViewIcons.Sparkles) },
        isChecked = { isPanelOpen }
    ) { isPanelOpen = !isPanelOpen }
    val panelState = LecturePanelState()
    val textNotes = TextNotesState()
    return EditorExtension {
        toolbarActions { context -> LectureToolbarButton(this@editorExtension, context.documentId, panel) }
        canvasOverlay { context ->
            LectureEditorEffects(this@editorExtension, context.documentId, context)
            ArrivalsBanner(this@editorExtension, context.documentId)
            LectureSettings(this@editorExtension, panelState)
            TextNotesDialog(this@editorExtension, context.documentId, context, textNotes)
        }
        commands { context -> (if (isRecordingSupported) listOf(panel) else emptyList()) + textToNotesCommand(textNotes, context) }
        panels { context -> listOf(lecturePanel(this@editorExtension, context.documentId, context, panelState)) }
    }
}

/**
 * What arrived from the other devices for [documentId] (a lecture, its audio, its review), at the
 * top of the canvas: shown in the panel, or dismissed.
 */
@Composable
private fun BoxScope.ArrivalsBanner(assistant: LectureAssistant, documentId: String) {
    val arrival = assistant.arrivals.lastOrNull { it.documentId == documentId } ?: return
    Row(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 16.dp)
            .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(50))
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(
                when (arrival.kind) {
                    LectureArrival.Kind.LECTURE -> Res.string.lecture_arrival_lecture
                    LectureArrival.Kind.AUDIO -> Res.string.lecture_arrival_audio
                    LectureArrival.Kind.REVIEW -> Res.string.lecture_arrival_review
                },
                arrival.from
            ),
            color = MaterialTheme.colorScheme.inverseOnSurface,
            style = MaterialTheme.typography.bodyMedium
        )
        TextButton(onClick = {
            assistant.dismissArrival(arrival)
            assistant.showArrival(arrival)
        }) {
            Text(stringResource(Res.string.lecture_arrival_show), color = MaterialTheme.colorScheme.inversePrimary)
        }
        TextButton(onClick = { assistant.dismissArrival(arrival) }) {
            Text(stringResource(Res.string.lecture_action_close), color = MaterialTheme.colorScheme.inverseOnSurface)
        }
    }
}

/**
 * The dialogs of the lecture assistant shown wherever the application is: the lectures received
 * without a transcript, to be transcribed and reviewed here (with what it costs).
 */
@Composable
public fun LectureDialogs(assistant: LectureAssistant) {
    val offer = assistant.generationOffers.firstOrNull() ?: return
    AlertDialog(
        onDismissRequest = { assistant.declineGeneration(offer) },
        title = { Text(stringResource(if (offer.live) Res.string.lecture_offer_live_title else Res.string.lecture_offer_title)) },
        text = {
            Text(
                (if (offer.live) stringResource(Res.string.lecture_offer_live_message, offer.arrival.from)
                else stringResource(Res.string.lecture_offer_message, offer.arrival.from, formatLectureTime(offer.durationMs))) + "\n\n" +
                    when {
                        offer.provider == ModelProvider.LOCAL -> stringResource(Res.string.lecture_offer_free)
                        offer.live -> stringResource(Res.string.lecture_offer_tokens_hour, offer.estimatedTokens)
                        else -> stringResource(Res.string.lecture_offer_tokens, offer.estimatedTokens)
                    }
            )
        },
        confirmButton = {
            TextButton(onClick = { assistant.acceptGeneration(offer) }) {
                Text(stringResource(if (offer.live) Res.string.lecture_offer_follow else Res.string.lecture_offer_accept))
            }
        },
        dismissButton = { TextButton(onClick = { assistant.declineGeneration(offer) }) { Text(stringResource(Res.string.lecture_action_cancel)) } }
    )
}

/**
 * A [LectureAssistant] whose notifications of the lectures arriving from the other devices are in
 * the language of the application. The application creates one for the whole process (also when
 * its windows or activities are created again: the recording goes on) and gives it to its
 * windows, each binding the microphone with [LectureMicrophone].
 *
 * @param deviceModels the models of this device of the application (see [LectureAssistant]).
 */
public fun createLectureAssistant(deviceModels: DeviceModels? = null): LectureAssistant =
    LectureAssistant(deviceModels = deviceModels).apply { describeArrival = ::arrivalNotification }

/** Asks the permission of the microphone for [assistant] from the window or activity shown. */
@Composable
public fun LectureMicrophone(assistant: LectureAssistant) {
    val access = rememberMicrophoneAccess()
    SideEffect { assistant.requestMicrophone = access }
}

/** The notification of [arrival] in the document titled [documentTitle], in the language of the application. */
private suspend fun arrivalNotification(arrival: LectureArrival, documentTitle: String): ArrivalNotification {
    val text = when (arrival.kind) {
        LectureArrival.Kind.LECTURE -> getString(Res.string.lecture_arrival_lecture, arrival.from)
        LectureArrival.Kind.AUDIO -> getString(Res.string.lecture_arrival_audio, arrival.from)
        LectureArrival.Kind.REVIEW -> getString(Res.string.lecture_arrival_review, arrival.from)
    }
    return ArrivalNotification(getString(Res.string.lecture_arrivals_channel), documentTitle.ifBlank { getString(Res.string.lecture_title) }, text)
}

/**
 * The lecture being recorded (or reviewed) while its document is closed, e.g. over the document
 * library: time, document, and the actions to open it again or to stop the recording.
 *
 * @param onOpenDocument opens the editor of a document.
 */
@Composable
public fun LectureRecordingIndicator(assistant: LectureAssistant, onOpenDocument: (String) -> Unit, modifier: Modifier = Modifier) {
    val recorder = assistant.activeRecorder ?: return
    val lecture = recorder.lecture
    val recording = lecture.status == LectureStatus.RECORDING
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = { onOpenDocument(lecture.documentId) },
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = shape,
        modifier = modifier.elevation(DrawViewElevation.Level2, shape)
    ) {
        Row(
            modifier = Modifier.height(52.dp).padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (recording) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp)) {
                    Box(modifier = Modifier.size(20.dp).background(MaterialTheme.colorScheme.error.copy(alpha = 0.28f), CircleShape))
                    Box(modifier = Modifier.size(10.dp).background(MaterialTheme.colorScheme.error, CircleShape))
                }
            }
            Column(modifier = Modifier.widthIn(max = 260.dp)) {
                Text(
                    text = stringResource(if (recording) Res.string.lecture_indicator_recording else Res.string.lecture_indicator_reviewing_label).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f),
                    maxLines = 1
                )
                Text(
                    text = if (recording) lecture.documentTitle + " · " + formatLectureTime(lecture.elapsedMs) else lecture.documentTitle,
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (recording) {
                val stop = stringResource(Res.string.lecture_action_stop)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.inverseOnSurface)
                        .clickable(onClickLabel = stop) { recorder.stop() }
                ) {
                    Icon(DrawViewIcons.Stop, contentDescription = stop, tint = MaterialTheme.colorScheme.inverseSurface, modifier = Modifier.size(16.dp))
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

/** The tag of the documents with a lecture: their badge and the filter of the library. */
public fun LectureAssistant.documentTag(): DocumentTag = DocumentTag(
    id = "lecture",
    label = { stringResource(Res.string.lecture_tag) },
    filterLabel = { stringResource(Res.string.lecture_tag_filter) },
    icon = DrawViewIcons.Mic,
    tone = BadgeTone.Blue,
    documents = { documentsWithLectures }
)

/**
 * Asks to stop the recording before the application exits (while [LectureAssistant.isBusy]).
 *
 * @param onExit saves the recording ([LectureAssistant.finishRecording]) and exits.
 */
@Composable
public fun LectureExitDialog(onExit: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.lecture_exit_title)) },
        text = { Text(stringResource(Res.string.lecture_exit_message)) },
        confirmButton = { TextButton(onClick = onExit) { Text(stringResource(Res.string.lecture_exit_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.lecture_action_cancel)) } }
    )
}

@Composable
private fun LectureToolbarButton(assistant: LectureAssistant, documentId: String, panel: Command) {
    if (!assistant.isRecordingSupported) return
    val lecture = assistant.shownLecture(documentId)
    val recording = assistant.recorderOf(documentId)?.lecture?.takeIf { it.status == LectureStatus.RECORDING }
    val newNotes = (recording ?: lecture)?.suggestions?.count { it.status == SuggestionStatus.NEW } ?: 0
    val togglePanel = { assistant.isPanelOpen = !assistant.isPanelOpen }
    val newNotesText = if (newNotes > 99) "99+" else newNotes.toString()

    if (recording != null) {
        // Recording: time of the lecture, red dot, new suggestions
        val minTimeWidth = if (recording.elapsedMs >= 3600_000L) 64.dp else 44.dp
        Row(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .height(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.errorContainer)
                .clickable(onClick = togglePanel)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
            )
            Text(
                text = formatLectureTime(recording.elapsedMs),
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = minTimeWidth)
            )
            if (newNotes > 0 && !assistant.isPanelOpen) {
                Badge { Text(newNotesText, maxLines = 1) }
            }
        }
    } else {
        Box(modifier = Modifier.zIndex(1f)) {
            EditorActionButton(
                icon = DrawViewIcons.Sparkles,
                label = panel.label(),
                onClick = togglePanel,
                active = true,
                highlighted = assistant.isPanelOpen
            )
            if (newNotes > 0 && !assistant.isPanelOpen) {
                Badge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = (-2).dp),
                ) {
                    Text(newNotesText, maxLines = 1)
                }
            }
        }
    }
}

