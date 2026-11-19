package io.github.valerioisufi.pageboard.lecture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.lecture.Lecture
import io.github.valerioisufi.pageboard.lecture.LectureAssistant
import io.github.valerioisufi.pageboard.lecture.LectureStatus
import io.github.valerioisufi.pageboard.lecture.formatLectureTime
import io.github.valerioisufi.pageboard.lecture.ui.generated.resources.*
import io.github.valerioisufi.pageboard.ui.components.MarkdownText
import io.github.valerioisufi.pageboard.ui.theme.PageboardIcons
import org.jetbrains.compose.resources.stringResource

// The transcript, the outline and the course context of a lecture

/**
 * The transcript under the player: each time on its chip (playing the recording from there), the
 * segment playing on the primary container, the words being said in italics.
 */
@Composable
internal fun TranscriptTab(lecture: Lecture) {
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
internal fun OutlineTab(assistant: LectureAssistant, lecture: Lecture) {
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
            Icon(PageboardIcons.BookOpen, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
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
                    Icon(PageboardIcons.Pencil, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.lecture_context_edit))
                }
                if (assistant.canWriteContext(documentId)) {
                    TextButton(onClick = { assistant.writeContext(documentId) }) {
                        Icon(PageboardIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.lecture_context_write))
                    }
                }
            }
        }
    }
}
