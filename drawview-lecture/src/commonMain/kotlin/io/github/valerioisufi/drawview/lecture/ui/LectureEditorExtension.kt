package io.github.valerioisufi.drawview.lecture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.lecture.LectureArrival
import io.github.valerioisufi.drawview.lecture.LectureAssistant
import io.github.valerioisufi.drawview.lecture.LectureRecordings
import io.github.valerioisufi.drawview.lecture.LectureStatus
import io.github.valerioisufi.drawview.lecture.ModelProvider
import io.github.valerioisufi.drawview.lecture.SuggestionStatus
import io.github.valerioisufi.drawview.lecture.formatLectureTime
import io.github.valerioisufi.drawview.lecture.generated.resources.*
import io.github.valerioisufi.drawview.lecture.generated.resources.Res
import io.github.valerioisufi.drawview.lecture.generated.resources.lecture_action_cancel
import io.github.valerioisufi.drawview.lecture.generated.resources.lecture_action_stop
import io.github.valerioisufi.drawview.lecture.generated.resources.lecture_exit_confirm
import io.github.valerioisufi.drawview.lecture.generated.resources.lecture_exit_message
import io.github.valerioisufi.drawview.lecture.generated.resources.lecture_exit_title
import io.github.valerioisufi.drawview.lecture.generated.resources.lecture_place_go_to_page
import io.github.valerioisufi.drawview.lecture.generated.resources.lecture_place_hint
import io.github.valerioisufi.drawview.lecture.generated.resources.lecture_title
import io.github.valerioisufi.drawview.lecture.platform.rememberMicrophoneAccess
import io.github.valerioisufi.drawview.lecture.resolvedPageIndex
import io.github.valerioisufi.drawview.ui.adaptive.currentAdaptiveInfo
import io.github.valerioisufi.drawview.ui.command.Command
import io.github.valerioisufi.drawview.ui.command.CommandGroup
import io.github.valerioisufi.drawview.ui.components.BadgeTone
import io.github.valerioisufi.drawview.ui.extension.DocumentTag
import io.github.valerioisufi.drawview.ui.extension.EditorExtension
import io.github.valerioisufi.drawview.ui.screen.EditorActionButton
import io.github.valerioisufi.drawview.ui.theme.DrawViewElevation
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import io.github.valerioisufi.drawview.ui.theme.elevation
import org.jetbrains.compose.resources.stringResource

/**
 * The lecture assistant in the DrawView editor: a button of the top bar (the recording time while
 * recording), the assistant panel (placed by the editor beside the canvas or rising from the
 * bottom over it; also shown and hidden from the menus), its settings and the hints over the
 * canvas. Closing the editor does not stop the recording (see [LectureRecordingIndicator]).
 */
fun LectureAssistant.editorExtension(): EditorExtension {
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
    return EditorExtension(
        toolbarActions = { context -> LectureToolbarButton(this@editorExtension, context.documentId, panel) },
        canvasOverlay = { context ->
            LectureEditorEffects(this@editorExtension, context.documentId, context.controller)
            PlacementHint(this@editorExtension, context.controller)
            ArrivalsBanner(this@editorExtension, context.documentId)
            LectureSettings(this@editorExtension, panelState)
        },
        commands = { if (isRecordingSupported) listOf(panel) else emptyList() },
        panels = { context -> listOf(lecturePanel(this@editorExtension, context.documentId, context.controller, panelState)) }
    )
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
fun LectureDialogs(assistant: LectureAssistant) {
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
 * A [LectureAssistant] kept for the lifetime of the application (also when its window or activity
 * is created again: the recording goes on).
 */
@Composable
fun rememberLectureAssistant(): LectureAssistant {
    val assistant = viewModel { LectureAssistantHolder() }.assistant
    assistant.requestMicrophone = rememberMicrophoneAccess()
    return assistant
}

private class LectureAssistantHolder : ViewModel() {
    val assistant = LectureAssistant()
}

/**
 * The lecture being recorded (or reviewed) while its document is closed, e.g. over the document
 * library: time, document, and the actions to open it again or to stop the recording.
 *
 * @param onOpenDocument opens the editor of a document.
 */
@Composable
fun LectureRecordingIndicator(assistant: LectureAssistant, onOpenDocument: (String) -> Unit, modifier: Modifier = Modifier) {
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
                    style = MaterialTheme.typography.labelLarge,
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
fun LectureAssistant.documentTag(): DocumentTag = DocumentTag(
    id = "lecture",
    label = { stringResource(Res.string.lecture_tag) },
    filterLabel = { stringResource(Res.string.lecture_tag_filter) },
    icon = DrawViewIcons.Mic,
    tone = BadgeTone.Blue,
    documents = { documentsWithLectures }
)

/**
 * Asks to stop the recording before the application exits ([LectureRecordings.isRecording]).
 *
 * @param onExit stops the recordings ([LectureRecordings.finishAll]) and exits.
 */
@Composable
fun LectureExitDialog(onExit: () -> Unit, onDismiss: () -> Unit) {
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
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.SemiBold
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

/**
 * While a note is being placed: what to do, a shortcut to the page of the note when it is not on
 * screen, and the cancellation.
 */
@Composable
private fun BoxScope.PlacementHint(assistant: LectureAssistant, controller: DrawViewController) {
    val note = assistant.placingNote ?: return
    if (!controller.placementManager.isActive) return
    // Recomputed at every redraw, recomposed only when the visible pages change
    val visible by remember(controller) {
        derivedStateOf {
            controller.version
            controller.viewport.visiblePages().map { it.index }
        }
    }
    val notePage = note.resolvedPageIndex(controller.document)?.takeIf { it !in visible }
    val keyboard = currentAdaptiveInfo().input.hasKeyboard
    val shape = RoundedCornerShape(24.dp)

    Row(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 12.dp)
            .elevation(DrawViewElevation.Level3, shape)
            .background(MaterialTheme.colorScheme.inverseSurface, shape)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(DrawViewIcons.Plus, contentDescription = null, tint = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.size(18.dp))
        Text(stringResource(Res.string.lecture_place_hint), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.inverseOnSurface)
        if (notePage != null) {
            Button(
                onClick = { controller.pageManager.jumpToPage(notePage) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.inversePrimary, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                Text(stringResource(Res.string.lecture_place_go_to_page, notePage + 1))
            }
        }
        // Esc cancels where there is a keyboard: its key, else the button
        if (keyboard) {
            Text(
                "Esc",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.8f),
                modifier = Modifier
                    .padding(end = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .clickable { controller.placementManager.cancel() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        } else {
            TextButton(
                onClick = { controller.placementManager.cancel() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary)
            ) {
                Text(stringResource(Res.string.lecture_action_cancel))
            }
        }
    }
}
