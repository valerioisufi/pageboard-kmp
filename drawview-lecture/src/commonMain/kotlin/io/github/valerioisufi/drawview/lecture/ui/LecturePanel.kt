package io.github.valerioisufi.drawview.lecture.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PostAdd
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.lecture.LectureAssistant
import io.github.valerioisufi.drawview.lecture.LectureProblem
import io.github.valerioisufi.drawview.lecture.LectureSession
import io.github.valerioisufi.drawview.lecture.LectureStatus
import io.github.valerioisufi.drawview.lecture.NoteKind
import io.github.valerioisufi.drawview.lecture.NoteSuggestion
import io.github.valerioisufi.drawview.lecture.ReviewStatus
import io.github.valerioisufi.drawview.lecture.SuggestionStatus
import io.github.valerioisufi.drawview.lecture.formatLectureTime
import io.github.valerioisufi.drawview.lecture.generated.resources.*
import io.github.valerioisufi.drawview.latex.LatexEngine
import io.github.valerioisufi.drawview.ui.editor.MarkdownTextUtil
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val PanelWidth = 380.dp

/**
 * The panel of the lecture assistant, beside the canvas of the editor of [documentId]: recording,
 * suggested notes, transcript, outline and final review.
 */
@Composable
internal fun LecturePanel(assistant: LectureAssistant, documentId: Int, controller: DrawViewController) {
    // Closing the editor stops (and reviews) its lecture
    DisposableEffect(assistant, documentId) {
        onDispose { assistant.onEditorClosed(documentId) }
    }
    var showSettings by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = assistant.isPanelOpen,
        enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
        exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut()
    ) {
        Row(modifier = Modifier.fillMaxHeight()) {
            VerticalDivider()
            // A surface, so that the texts take the content color of the theme
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .width(PanelWidth)
                    .fillMaxHeight()
            ) {
                Column {
                    PanelHeader(
                        onSettings = { showSettings = true },
                        onClose = { assistant.isPanelOpen = false }
                    )
                    val session = assistant.sessionFor(documentId)
                    RecordingSection(
                        assistant = assistant,
                        session = session,
                        onStart = {
                            if (!assistant.settingsStore.hasApiKey) showSettings = true
                            else assistant.start(documentId, controller)
                        },
                        onOpenSettings = { showSettings = true }
                    )
                    HorizontalDivider()
                    if (session != null) {
                        ProfessorPageBanner(session, controller)
                        LectureTabs(assistant, session, controller)
                    }
                }
            }
        }
    }

    if (showSettings) {
        AssistantSettingsDialog(store = assistant.settingsStore, onDismiss = { showSettings = false })
    }
}

@Composable
private fun PanelHeader(onSettings: () -> Unit, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(Res.string.lecture_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onSettings) {
            Icon(Icons.Outlined.Settings, contentDescription = stringResource(Res.string.lecture_action_settings))
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(Res.string.lecture_action_close))
        }
    }
}

// ==========================================================
// Recording
// ==========================================================

@Composable
private fun RecordingSection(
    assistant: LectureAssistant,
    session: LectureSession?,
    onStart: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when {
            !assistant.isRecordingSupported -> MutedText(stringResource(Res.string.lecture_unsupported))

            session == null -> {
                Text(stringResource(Res.string.lecture_idle_description), style = MaterialTheme.typography.bodyMedium)
                MutedText(stringResource(Res.string.lecture_privacy_note))
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.lecture_action_record))
                }
            }

            session.status == LectureStatus.RECORDING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(Res.string.lecture_status_recording, formatLectureTime(session.elapsedMs)),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    LinearProgressIndicator(
                        progress = { session.level },
                        modifier = Modifier.width(72.dp),
                        drawStopIndicator = {}
                    )
                }
                MutedText(
                    when {
                        session.isAnalyzing -> stringResource(Res.string.lecture_status_analyzing)
                        session.pendingUpdates > 0 -> stringResource(Res.string.lecture_status_pending, session.pendingUpdates)
                        session.transcript.isEmpty() ->
                            stringResource(Res.string.lecture_status_listening, assistant.settingsStore.settings.chunkSeconds)
                        else -> session.currentTopic.takeIf { it.isNotBlank() }
                            ?.let { stringResource(Res.string.lecture_current_topic, it) } ?: ""
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { session.catchUp() }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(Res.string.lecture_action_catch_up), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button(
                        onClick = { session.stop() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text(stringResource(Res.string.lecture_action_stop))
                    }
                }
            }

            session.status == LectureStatus.FINALIZING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(Res.string.lecture_status_finalizing), style = MaterialTheme.typography.titleSmall)
                }
            }

            else -> {
                Text(
                    text = stringResource(Res.string.lecture_status_finished, formatLectureTime(session.elapsedMs)),
                    style = MaterialTheme.typography.titleSmall
                )
                OutlinedButton(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.lecture_action_new_recording))
                }
            }
        }

        assistant.startError?.let {
            ProblemBanner(stringResource(Res.string.lecture_problem_start, it))
        }
        if (session?.noAudioWarning == true && session.status == LectureStatus.RECORDING) {
            ProblemBanner(stringResource(Res.string.lecture_no_audio_warning))
        }
        session?.problem?.let { problem ->
            val message = when (problem) {
                LectureProblem.MissingApiKey -> stringResource(Res.string.lecture_problem_no_api_key)
                is LectureProblem.Authentication -> stringResource(Res.string.lecture_problem_authentication, problem.detail)
                is LectureProblem.Retrying -> stringResource(Res.string.lecture_problem_retrying, problem.retryInSeconds, problem.detail)
                is LectureProblem.Failed -> stringResource(Res.string.lecture_problem_failed, problem.detail)
            }
            val needsSettings = problem is LectureProblem.MissingApiKey || problem is LectureProblem.Authentication
            ProblemBanner(message, action = if (needsSettings) Res.string.lecture_action_settings to onOpenSettings else null)
        }
    }
}

@Composable
private fun ProblemBanner(message: String, action: Pair<StringResource, () -> Unit>? = null) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            action?.let { (label, onClick) ->
                TextButton(onClick = onClick) { Text(stringResource(label)) }
            }
        }
    }
}

/** "The lecturer is on page N": shown when that page is not on screen. */
@Composable
private fun ProfessorPageBanner(session: LectureSession, controller: DrawViewController) {
    val page = session.professorPageIndex ?: return
    if (session.status != LectureStatus.RECORDING) return
    // Recomputed at every redraw, recomposed only when the visible pages change
    val visible by remember(controller) {
        derivedStateOf {
            controller.version
            controller.visiblePages().map { it.index }
        }
    }
    if (page in visible || page !in controller.document.pages.indices) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Slideshow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(Res.string.lecture_professor_page, page + 1),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { controller.pageManager.jumpToPage(page) }) {
                Text(stringResource(Res.string.lecture_action_go))
            }
        }
    }
}

// ==========================================================
// Tabs
// ==========================================================

@Composable
private fun LectureTabs(assistant: LectureAssistant, session: LectureSession, controller: DrawViewController) {
    var tab by remember(session) { mutableIntStateOf(0) }
    val newCount = session.suggestions.count { it.status == SuggestionStatus.NEW }
    val labels = listOf(
        stringResource(Res.string.lecture_tab_notes) + if (newCount > 0) " ($newCount)" else "",
        stringResource(Res.string.lecture_tab_transcript),
        stringResource(Res.string.lecture_tab_outline),
        stringResource(Res.string.lecture_tab_review)
    )
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val inserter = remember(fontFamilyResolver) {
        NoteInserter(TextMeasurer(fontFamilyResolver, Density(1f, 1f), LayoutDirection.Ltr))
    }

    SecondaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
        labels.forEachIndexed { index, label ->
            Tab(
                selected = tab == index,
                onClick = { tab = index },
                text = { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
            )
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        when (tab) {
            0 -> NotesTab(assistant, session, controller, inserter)
            1 -> TranscriptTab(session)
            2 -> OutlineTab(session)
            else -> ReviewTab(session, controller, inserter)
        }
    }
}

@Composable
private fun NotesTab(assistant: LectureAssistant, session: LectureSession, controller: DrawViewController, inserter: NoteInserter) {
    val newNotes = session.suggestions.filter { it.status == SuggestionStatus.NEW }.sortedByDescending { it.atMs }
    val handled = session.suggestions.filter { it.status != SuggestionStatus.NEW }.sortedByDescending { it.atMs }
    var showHandled by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (newNotes.isEmpty()) {
            item { MutedText(stringResource(Res.string.lecture_notes_empty), Modifier.padding(4.dp)) }
        }
        items(newNotes, key = { it.id }) { note ->
            SuggestionCard(
                note = note,
                canListen = session.canPlayAudio,
                placing = assistant.placingNote?.id == note.id,
                onInsert = {
                    val started = inserter.startPlacement(
                        controller = controller,
                        suggestion = note,
                        onInserted = {
                            assistant.placingNote = null
                            session.updateSuggestion(note.id, SuggestionStatus.INSERTED)
                        },
                        onCancel = { if (assistant.placingNote?.id == note.id) assistant.placingNote = null }
                    )
                    if (started) assistant.placingNote = note
                },
                onCancelPlacing = { controller.placementManager.cancel() },
                onListen = { session.play(note) },
                onAlreadyNoted = { session.updateSuggestion(note.id, SuggestionStatus.ALREADY_NOTED) },
                onDismiss = { session.updateSuggestion(note.id, SuggestionStatus.DISMISSED) },
                onGoToPage = { page -> controller.pageManager.jumpToPage(page) }
            )
        }
        if (handled.isNotEmpty()) {
            item(key = "handled") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { showHandled = !showHandled }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(Res.string.lecture_notes_handled, handled.size),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(if (showHandled) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
                }
            }
            if (showHandled) {
                items(handled, key = { it.id }) { note ->
                    HandledRow(note, onRestore = { session.updateSuggestion(note.id, SuggestionStatus.NEW) })
                }
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    note: NoteSuggestion,
    canListen: Boolean,
    placing: Boolean,
    onInsert: () -> Unit,
    onCancelPlacing: () -> Unit,
    onListen: () -> Unit,
    onAlreadyNoted: () -> Unit,
    onDismiss: () -> Unit,
    onGoToPage: (Int) -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KindLabel(note.kind, important = note.importance >= 3)
                Spacer(Modifier.weight(1f))
                val time = formatLectureTime(note.atMs)
                Text(
                    text = if (note.fromFinalReview) stringResource(Res.string.lecture_note_from_review) else time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                note.pageIndex?.let { page ->
                    Text(
                        text = stringResource(Res.string.lecture_note_page, page + 1),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.extraSmall)
                            .clickable { onGoToPage(page) }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
            if (note.title.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(note.title, style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                MarkdownText(note.content)
            }
            if (placing) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(Res.string.lecture_place_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (placing) {
                    OutlinedButton(onClick = onCancelPlacing, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Text(stringResource(Res.string.lecture_action_cancel))
                    }
                } else {
                    FilledTonalButton(onClick = onInsert, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Icon(Icons.Outlined.PostAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.lecture_note_insert))
                    }
                }
                Spacer(Modifier.weight(1f))
                if (canListen && !note.fromFinalReview) {
                    IconButton(onClick = onListen) {
                        Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = stringResource(Res.string.lecture_note_listen))
                    }
                }
                IconButton(onClick = onAlreadyNoted) {
                    Icon(Icons.Outlined.DoneAll, contentDescription = stringResource(Res.string.lecture_note_have_it))
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(Res.string.lecture_note_dismiss))
                }
            }
        }
    }
}

@Composable
private fun HandledRow(note: NoteSuggestion, onRestore: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(note.title.ifBlank { note.content }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = when (note.status) {
                    SuggestionStatus.INSERTED -> stringResource(Res.string.lecture_note_status_inserted)
                    SuggestionStatus.ALREADY_NOTED -> stringResource(Res.string.lecture_note_status_already_noted)
                    else -> stringResource(Res.string.lecture_note_status_dismissed)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onRestore) {
            Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = stringResource(Res.string.lecture_note_restore))
        }
    }
}

@Composable
private fun KindLabel(kind: NoteKind, important: Boolean) {
    val label = stringResource(
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
    val highlighted = important || kind == NoteKind.EXAM_HINT || kind == NoteKind.ANNOUNCEMENT
    Surface(
        color = if (highlighted) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (highlighted) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.extraSmall
    ) {
        Text(
            text = if (important) "$label · ${stringResource(Res.string.lecture_importance_high)}" else label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun TranscriptTab(session: LectureSession) {
    val listState = rememberLazyListState()
    val count = session.transcript.size
    LaunchedEffect(count) {
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    if (count == 0) {
        MutedText(stringResource(Res.string.lecture_transcript_empty), Modifier.padding(16.dp))
        return
    }
    SelectionContainer {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(session.transcript.toList()) { segment ->
                Column {
                    Text(
                        text = formatLectureTime(segment.startMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(segment.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun OutlineTab(session: LectureSession) {
    if (session.outline.isBlank()) {
        MutedText(stringResource(Res.string.lecture_outline_empty), Modifier.padding(16.dp))
        return
    }
    SelectionContainer {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            if (session.currentTopic.isNotBlank()) {
                Text(
                    text = stringResource(Res.string.lecture_current_topic, session.currentTopic),
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(8.dp))
            }
            MarkdownText(session.outline)
        }
    }
}

@Composable
private fun ReviewTab(session: LectureSession, controller: DrawViewController, inserter: NoteInserter) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val review = session.finalReview
        when {
            session.reviewStatus == ReviewStatus.RUNNING || session.status == LectureStatus.FINALIZING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    MutedText(stringResource(Res.string.lecture_review_running))
                }
            }
            session.reviewStatus == ReviewStatus.FAILED -> {
                MutedText(stringResource(Res.string.lecture_review_failed))
                if (session.status == LectureStatus.FINISHED) {
                    OutlinedButton(onClick = { session.retryFinalReview() }) { Text(stringResource(Res.string.lecture_action_retry)) }
                }
            }
            review != null -> {
                InsertPagesButton(controller) { afterPageIndex -> inserter.insertPages(controller, review, afterPageIndex) }
                SelectionContainer { MarkdownText(review) }
            }
            else -> MutedText(stringResource(Res.string.lecture_review_none))
        }

        val usage = session.usage
        if (usage.requests > 0) {
            HorizontalDivider()
            MutedText(
                stringResource(
                    Res.string.lecture_usage,
                    usage.requests,
                    compactNumber(usage.promptTokens),
                    compactNumber(usage.cachedTokens),
                    compactNumber(usage.outputTokens)
                )
            )
        }
    }
}

/**
 * "Insert as new pages", with the position of the pages: after the page the student is looking
 * at, or at the end of the notebook.
 */
@Composable
private fun InsertPagesButton(controller: DrawViewController, onInsert: (afterPageIndex: Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var currentPage by remember { mutableStateOf<Int?>(null) }
    Box {
        FilledTonalButton(onClick = {
            currentPage = mostVisiblePageIndex(controller)
            expanded = true
        }) {
            Icon(Icons.Outlined.PostAdd, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(Res.string.lecture_review_insert))
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            currentPage?.let { page ->
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.lecture_review_insert_after, page + 1)) },
                    onClick = {
                        expanded = false
                        onInsert(page)
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.lecture_review_insert_end)) },
                onClick = {
                    expanded = false
                    onInsert(null)
                }
            )
        }
    }
}

/** The page taking the largest part of the viewport. */
internal fun mostVisiblePageIndex(controller: DrawViewController): Int? =
    controller.visiblePages().maxByOrNull { page ->
        minOf(page.rect.bottom, controller.viewportHeight) - maxOf(page.rect.top, 0f)
    }?.index

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

/**
 * Markdown of the notes, formatted as on the canvas: delimiters hidden and, once the LaTeX engine
 * is ready, the formulas rendered by MicroTeX inline.
 */
@Composable
private fun MarkdownText(markdown: String) {
    val color = MaterialTheme.colorScheme.onSurface
    val style = MaterialTheme.typography.bodyMedium
    val density = LocalDensity.current
    val latexReady by LatexEngine.isReady.collectAsState()
    val content = remember(markdown, color, style, density, latexReady) {
        try {
            if (latexReady) markdownWithFormulas(markdown, color, style, density) else null
        } catch (_: Throwable) {
            null
        } ?: (
            MarkdownTextUtil.toLiveAnnotatedString(
                text = markdown,
                selection = TextRange(markdown.length + 1),
                isMarkdownEnabled = true,
                defaultColor = color
            ) to emptyMap()
        )
    }
    Text(content.first, inlineContent = content.second, style = style, color = color)
}

/** The Markdown text with its formulas as inline images (see [MarkdownTextUtil.toCanvasText]). */
private fun markdownWithFormulas(
    markdown: String,
    color: Color,
    style: TextStyle,
    density: Density
): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val fontPx = with(density) { style.fontSize.toPx() }
    val canvasText = MarkdownTextUtil.toCanvasText(markdown, true, color, fontPx, fontAscentPx = fontPx * 0.8f) { latex, sizePx ->
        LatexEngine.renderFormula(latex, sizePx, color.toArgb())?.let {
            MarkdownTextUtil.RenderedFormula(it.bitmap, it.width.toFloat(), it.height.toFloat(), it.baselineRatio)
        }
    }
    val inline = mutableMapOf<String, InlineTextContent>()
    val text = buildAnnotatedString {
        var last = 0
        canvasText.placeholders.forEachIndexed { index, placeholder ->
            append(canvasText.text.subSequence(last, placeholder.start))
            val id = "formula$index"
            appendInlineContent(id)
            last = placeholder.end
            val formula = canvasText.formulas.firstOrNull { it.placeholderIndex == index }
            inline[id] = InlineTextContent(
                with(density) {
                    Placeholder(
                        width = placeholder.widthPx.toSp(),
                        height = placeholder.heightPx.toSp(),
                        placeholderVerticalAlign = if (placeholder.aboveBaseline) PlaceholderVerticalAlign.AboveBaseline else PlaceholderVerticalAlign.TextTop
                    )
                }
            ) {
                if (formula != null) {
                    // The body placeholder holds the ascent: the descent hangs below it
                    Image(
                        bitmap = formula.image,
                        contentDescription = null,
                        modifier = Modifier
                            .wrapContentSize(Alignment.TopStart, unbounded = true)
                            .requiredSize(with(density) { formula.widthPx.toDp() }, with(density) { formula.heightPx.toDp() })
                    )
                }
            }
        }
        append(canvasText.text.subSequence(last, canvasText.text.length))
    }
    return text to inline
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
