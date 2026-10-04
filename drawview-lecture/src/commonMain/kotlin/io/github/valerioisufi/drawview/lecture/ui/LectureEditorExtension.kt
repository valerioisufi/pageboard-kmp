package io.github.valerioisufi.drawview.lecture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdsClick
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.lecture.LectureAssistant
import io.github.valerioisufi.drawview.lecture.LectureStatus
import io.github.valerioisufi.drawview.lecture.SuggestionStatus
import io.github.valerioisufi.drawview.lecture.formatLectureTime
import io.github.valerioisufi.drawview.lecture.generated.resources.Res
import io.github.valerioisufi.drawview.lecture.generated.resources.lecture_action_cancel
import io.github.valerioisufi.drawview.lecture.generated.resources.lecture_place_go_to_page
import io.github.valerioisufi.drawview.lecture.generated.resources.lecture_place_hint
import io.github.valerioisufi.drawview.lecture.generated.resources.lecture_title
import io.github.valerioisufi.drawview.ui.components.ToolButton
import io.github.valerioisufi.drawview.ui.session.EditorExtension
import org.jetbrains.compose.resources.stringResource

/**
 * The lecture assistant in the DrawView editor: a button of the top bar (the recording time while
 * recording) and the assistant panel beside the canvas. Closing the editor stops the recording.
 */
fun LectureAssistant.editorExtension(): EditorExtension = EditorExtension(
    toolbarActions = { session, _ -> LectureToolbarButton(this@editorExtension, session.documentId) },
    sidePanel = { session, controller -> LecturePanel(this@editorExtension, session.documentId, controller) },
    canvasOverlay = { _, controller -> PlacementHint(this@editorExtension, controller) }
)

/** A [LectureAssistant] kept for the lifetime of the composition, as an [EditorExtension]. */
@Composable
fun rememberLectureEditorExtension(): EditorExtension {
    val assistant = remember { LectureAssistant() }
    return remember(assistant) { assistant.editorExtension() }
}

@Composable
private fun LectureToolbarButton(assistant: LectureAssistant, documentId: Int) {
    if (!assistant.isRecordingSupported) return
    val session = assistant.sessionFor(documentId)
    val newNotes = session?.suggestions?.count { it.status == SuggestionStatus.NEW } ?: 0
    val togglePanel = { assistant.isPanelOpen = !assistant.isPanelOpen }

    if (session?.status == LectureStatus.RECORDING) {
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
                text = formatLectureTime(session.elapsedMs),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.SemiBold
            )
            if (newNotes > 0 && !assistant.isPanelOpen) {
                Badge { Text(newNotes.toString()) }
            }
        }
    } else {
        ToolButton(onClick = togglePanel, selected = assistant.isPanelOpen) {
            BadgedBox(
                badge = {
                    if (newNotes > 0 && !assistant.isPanelOpen) Badge { Text(newNotes.toString()) }
                }
            ) {
                Icon(imageVector = Icons.Outlined.Mic, contentDescription = stringResource(Res.string.lecture_title))
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
    val notePage = note.pageIndex?.takeIf { it in controller.document.pages.indices && it !in visible }
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
