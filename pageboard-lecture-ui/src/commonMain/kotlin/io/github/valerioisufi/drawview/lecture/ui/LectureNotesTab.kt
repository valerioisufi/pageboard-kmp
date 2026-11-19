package io.github.valerioisufi.drawview.lecture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.lecture.Lecture
import io.github.valerioisufi.drawview.lecture.LectureAnchors
import io.github.valerioisufi.drawview.lecture.LectureAssistant
import io.github.valerioisufi.drawview.lecture.NoteKind
import io.github.valerioisufi.drawview.lecture.NoteSuggestion
import io.github.valerioisufi.drawview.lecture.SuggestionStatus
import io.github.valerioisufi.drawview.lecture.formatLectureTime
import io.github.valerioisufi.drawview.lecture.resolvedPageIndex
import io.github.valerioisufi.drawview.lecture.ui.generated.resources.*
import io.github.valerioisufi.drawview.ui.components.BadgeTone
import io.github.valerioisufi.drawview.ui.components.DesignIconButton
import io.github.valerioisufi.drawview.ui.components.LabelBadge
import io.github.valerioisufi.drawview.ui.components.MarkdownText
import io.github.valerioisufi.drawview.ui.components.OutlineButton
import io.github.valerioisufi.drawview.ui.components.SegmentedChoice
import io.github.valerioisufi.drawview.ui.components.TonalButton
import io.github.valerioisufi.drawview.ui.extension.EditorContext
import io.github.valerioisufi.drawview.ui.render.ComposeTextTileAdapter
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import io.github.valerioisufi.drawview.ui.theme.rememberNotesFonts
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

// The tabs of a lecture and the notes suggested while it is followed

// ==========================================================
// Tabs
// ==========================================================

/**
 * The tabs of the lecture as a segmented choice (the notes with how many are new, the transcript,
 * the outline, the review) and the one chosen; the player above the others while it plays.
 */
@Composable
internal fun LectureTabs(assistant: LectureAssistant, lecture: Lecture, editor: EditorContext) {
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
    val inserter = remember(fontFamilyResolver, notesFonts, editor.rendering.engines) {
        NoteInserter(ComposeTextTileAdapter(fontFamilyResolver, notesFonts, editor.rendering.engines.formulas, editor.rendering.engines.code))
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
            0 -> NotesTab(assistant, lecture, editor, inserter)
            1 -> TranscriptTab(lecture)
            2 -> OutlineTab(assistant, lecture)
            else -> ReviewTab(assistant, lecture, editor, inserter)
        }
    }
}

/** When the lecture was recorded (wall clock), with the time the writing about its end may take. */
private fun Lecture.timeSpan(): LongRange = startedAt..(startedAt + elapsedMs + LectureAnchors.AFTER_MS)

/** The notes suggested: the new ones on their cards, those handled under them (folded). */
@Composable
private fun NotesTab(assistant: LectureAssistant, lecture: Lecture, editor: EditorContext, inserter: NoteInserter) {
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
                                editor = editor,
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
                pageIndex = note.resolvedPageIndex(editor.document.document),
                canListen = lecture.canPlayAudio,
                placing = assistant.placingNote?.id == note.id,
                onInsert = {
                    scope.launch {
                        val started = inserter.startPlacement(
                            editor = editor,
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
                onCancelPlacing = { editor.placement.cancel() },
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
                    HandledRow(note, pageIndex = note.resolvedPageIndex(editor.document.document), onRestore = { lecture.updateSuggestion(note.id, SuggestionStatus.NEW) })
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
