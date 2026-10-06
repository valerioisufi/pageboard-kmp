package io.github.valerioisufi.drawview.lecture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdsClick
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.lecture.LectureAssistant
import io.github.valerioisufi.drawview.lecture.LectureRecordings
import io.github.valerioisufi.drawview.lecture.LectureStatus
import io.github.valerioisufi.drawview.lecture.SuggestionStatus
import io.github.valerioisufi.drawview.lecture.formatLectureTime
import io.github.valerioisufi.drawview.lecture.resolvedPageIndex
import io.github.valerioisufi.drawview.lecture.generated.resources.Res
import io.github.valerioisufi.drawview.lecture.generated.resources.lecture_action_cancel
import io.github.valerioisufi.drawview.lecture.generated.resources.lecture_action_stop
import io.github.valerioisufi.drawview.lecture.generated.resources.lecture_exit_confirm
import io.github.valerioisufi.drawview.lecture.generated.resources.lecture_exit_message
import io.github.valerioisufi.drawview.lecture.generated.resources.lecture_exit_title
import io.github.valerioisufi.drawview.lecture.generated.resources.lecture_indicator_open
import io.github.valerioisufi.drawview.lecture.generated.resources.lecture_indicator_reviewing
import io.github.valerioisufi.drawview.lecture.generated.resources.lecture_place_go_to_page
import io.github.valerioisufi.drawview.lecture.generated.resources.lecture_place_hint
import io.github.valerioisufi.drawview.lecture.generated.resources.lecture_title
import io.github.valerioisufi.drawview.lecture.platform.rememberMicrophoneAccess
import io.github.valerioisufi.drawview.ui.components.ToolButton
import io.github.valerioisufi.drawview.ui.session.EditorExtension
import org.jetbrains.compose.resources.stringResource

/**
 * The lecture assistant in the DrawView editor: a button of the top bar (the recording time while
 * recording), the assistant panel beside the canvas and the hints over it. Closing the editor does
 * not stop the recording (see [LectureRecordingIndicator]).
 */
fun LectureAssistant.editorExtension(): EditorExtension = EditorExtension(
    toolbarActions = { session, _ -> LectureToolbarButton(this@editorExtension, session.documentId) },
    sidePanel = { session, controller -> LecturePanel(this@editorExtension, session.documentId, controller) },
    canvasOverlay = { _, controller -> PlacementHint(this@editorExtension, controller) }
)

/** A [LectureAssistant] kept for the lifetime of the composition (the application). */
@Composable
fun rememberLectureAssistant(): LectureAssistant {
    val assistant = remember { LectureAssistant() }
    assistant.requestMicrophone = rememberMicrophoneAccess()
    return assistant
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
    val actionColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary)
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 6.dp,
        modifier = modifier
    ) {
        Row(modifier = Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (lecture.status == LectureStatus.RECORDING) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = formatLectureTime(lecture.elapsedMs) + " · " + lecture.documentTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 280.dp)
                )
            } else {
                Text(
                    text = stringResource(Res.string.lecture_indicator_reviewing, lecture.documentTitle),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 320.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = { onOpenDocument(lecture.documentId) }, colors = actionColors) {
                Text(stringResource(Res.string.lecture_indicator_open))
            }
            if (lecture.status == LectureStatus.RECORDING) {
                TextButton(onClick = { recorder.stop() }, colors = actionColors) {
                    Text(stringResource(Res.string.lecture_action_stop))
                }
            }
        }
    }
}

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
private fun LectureToolbarButton(assistant: LectureAssistant, documentId: String) {
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
                .height(32.dp)
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
            ToolButton(onClick = togglePanel, selected = assistant.isPanelOpen) {
                Icon(
                    imageVector = Icons.Outlined.Mic,
                    contentDescription = stringResource(Res.string.lecture_title),
                )
            }
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
            controller.visiblePages().map { it.index }
        }
    }
    val notePage = note.resolvedPageIndex(controller.document)?.takeIf { it !in visible }
    val actionColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary)

    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 6.dp,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 12.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.AdsClick, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.lecture_place_hint), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(4.dp))
            if (notePage != null) {
                TextButton(onClick = { controller.pageManager.jumpToPage(notePage) }, colors = actionColors) {
                    Text(stringResource(Res.string.lecture_place_go_to_page, notePage + 1))
                }
            }
            TextButton(onClick = { controller.placementManager.cancel() }, colors = actionColors) {
                Text(stringResource(Res.string.lecture_action_cancel))
            }
        }
    }
}
