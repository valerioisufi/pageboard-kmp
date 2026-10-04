package io.github.valerioisufi.drawview.lecture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.lecture.LectureAssistant
import io.github.valerioisufi.drawview.lecture.LectureStatus
import io.github.valerioisufi.drawview.lecture.SuggestionStatus
import io.github.valerioisufi.drawview.lecture.formatLectureTime
import io.github.valerioisufi.drawview.lecture.generated.resources.Res
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
    sidePanel = { session, controller -> LecturePanel(this@editorExtension, session.documentId, controller) }
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
