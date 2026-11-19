package io.github.valerioisufi.pageboard.lecture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.lecture.AudioDownload
import io.github.valerioisufi.pageboard.lecture.Lecture
import io.github.valerioisufi.pageboard.lecture.LectureAssistant
import io.github.valerioisufi.pageboard.lecture.LectureStatus
import io.github.valerioisufi.pageboard.lecture.LectureTask
import io.github.valerioisufi.pageboard.lecture.ModelProvider
import io.github.valerioisufi.pageboard.lecture.ReviewStatus
import io.github.valerioisufi.pageboard.lecture.TokenUsage
import io.github.valerioisufi.pageboard.lecture.ui.generated.resources.*
import io.github.valerioisufi.pageboard.ui.command.DesignMenu
import io.github.valerioisufi.pageboard.ui.command.MenuItem
import io.github.valerioisufi.pageboard.ui.components.MarkdownText
import io.github.valerioisufi.pageboard.ui.extension.EditorContext
import io.github.valerioisufi.pageboard.ui.theme.DrawViewIcons
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// The review of a lecture, its cost and where its pages go

/**
 * The final review: being written (here or elsewhere), failed, or written (by which model, written
 * again, its text, inserting it as pages); the requests of the lecture at the end.
 */
@Composable
internal fun ReviewTab(
    assistant: LectureAssistant,
    lecture: Lecture,
    editor: EditorContext,
    inserter: NoteInserter
) {
    val settings = assistant.settingsStore.settings
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PanelPadding, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        val review = lecture.finalReview
        val now = rememberNow()
        val elsewhere = lecture.generatingElsewhere(now)
        // Without the transcript, the audio is transcribed first; not while another device writes them
        val canRegenerate = lecture.canGenerateReview && (lecture.transcript.isNotEmpty() || lecture.hasAudio) && elsewhere == null
        elsewhere?.let { LectureNotice(DrawViewIcons.Users, stringResource(Res.string.lecture_generating_elsewhere, it.name), NoticeTone.Info) }
        when {
            lecture.reviewStatus == ReviewStatus.RUNNING || lecture.status == LectureStatus.FINALIZING -> LectureNotice(
                DrawViewIcons.Sparkles,
                if (lecture.reviewProvider == ModelProvider.LOCAL && lecture.reviewStatus == ReviewStatus.RUNNING) {
                    stringResource(Res.string.lecture_review_running_local, lecture.reviewModel.orEmpty(), lecture.reviewProgressTokens)
                } else {
                    stringResource(Res.string.lecture_review_running, lecture.reviewModel ?: settings.task(LectureTask.FINAL_REVIEW).model)
                },
                NoticeTone.Neutral
            )
            lecture.reviewStatus == ReviewStatus.FAILED -> {
                LectureNotice(DrawViewIcons.TriangleAlert, stringResource(Res.string.lecture_review_failed), NoticeTone.Error)
                if (canRegenerate) RegenerateReviewButton(assistant, lecture)
            }
            review != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        lecture.reviewModel?.let { stringResource(Res.string.lecture_review_by, it) }.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (canRegenerate) RegenerateReviewButton(assistant, lecture)
                }
                PanelCard(outlined = true) { SelectionContainer { MarkdownText(review, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium) } }
                val reviewLabel = stringResource(Res.string.lecture_review_placing)
                val scope = rememberCoroutineScope()
                InsertPagesCard(editor, byTopic = editor.placement.canPlaceByTopic) { place, pageIndex ->
                    if (place == ReviewPlace.TOPIC) {
                        scope.launch {
                            val parts = editor.placement.partsByTopic(review, fallbackPage = pageIndex)
                            inserter.startReviewPlacement(editor, parts, reviewLabel)
                        }
                    } else {
                        inserter.startReviewPlacement(editor, review, if (place == ReviewPlace.END) null else pageIndex, reviewLabel)
                    }
                }
            }
            // Written by a device with the models
            !lecture.generates -> LectureNotice(DrawViewIcons.Monitor, stringResource(Res.string.lecture_review_elsewhere), NoticeTone.Info)
            lecture.status == LectureStatus.FINISHED -> {
                // Recorded where the models are not, written on request, or interrupted before its review (e.g. the application was closed)
                if (lecture.recordedElsewhere && lecture.transcript.isEmpty()) {
                    LectureNotice(DrawViewIcons.Upload, stringResource(Res.string.lecture_review_received, lecture.recordedOn), NoticeTone.Info)
                } else {
                    BodyText(stringResource(Res.string.lecture_review_pending))
                }
                if (canRegenerate) RegenerateReviewButton(assistant, lecture, label = Res.string.lecture_review_generate)
            }
            !settings.reviewAtTheEnd -> BodyText(stringResource(Res.string.lecture_review_on_request))
            else -> BodyText(stringResource(Res.string.lecture_review_none, settings.task(LectureTask.FINAL_REVIEW).model))
        }

        UsageSummary(lecture)
    }
}

/** Writes the final review (again), online or by the local model: a button opening the two. */
@Composable
private fun RegenerateReviewButton(
    assistant: LectureAssistant,
    lecture: Lecture,
    label: StringResource = Res.string.lecture_review_regenerate
) {
    val settings = assistant.settingsStore.settings
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Icon(DrawViewIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(label))
        }
        DesignMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MenuItem(
                text = stringResource(Res.string.lecture_review_with_gemini, settings.task(LectureTask.FINAL_REVIEW).gemini),
                icon = rememberVectorPainter(DrawViewIcons.Globe),
                onClick = {
                    expanded = false
                    assistant.generate(lecture, ModelProvider.GEMINI)
                }
            )
            MenuItem(
                text = stringResource(Res.string.lecture_review_with_local, settings.task(LectureTask.FINAL_REVIEW).local),
                icon = rememberVectorPainter(DrawViewIcons.Monitor),
                onClick = {
                    expanded = false
                    assistant.generate(lecture, ModelProvider.LOCAL)
                }
            )
        }
    }
}

/** The requests of the lecture and where the tokens of the online ones went; who used them, when several devices did. */
@Composable
private fun UsageSummary(lecture: Lecture) {
    val usage = lecture.totalUsage
    if (usage.requests == 0 && usage.localRequests == 0 && usage.skippedUpdates == 0) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        UsageLines(usage)
        if (lecture.otherUsage.isNotEmpty()) {
            val lines = (listOf(stringResource(Res.string.lecture_usage_this_device) to lecture.usage) + lecture.otherUsage.values.map { it.name to it.usage })
                .filter { (_, used) -> used.requests > 0 || used.localRequests > 0 }
            Spacer(Modifier.height(6.dp))
            for ((name, used) in lines) {
                MutedText(stringResource(Res.string.lecture_usage_device, name, used.requests + used.localRequests, compactNumber(used.promptTokens + used.outputTokens)))
            }
        }
    }
}

@Composable
private fun UsageLines(usage: TokenUsage) {
    if (usage.requests > 0) {
        Text(
            stringResource(
                Res.string.lecture_usage_tokens,
                usage.requests,
                compactNumber(usage.promptTokens),
                compactNumber(usage.cachedTokens),
                compactNumber(usage.outputTokens)
            ),
            style = MaterialTheme.typography.bodySmall
        )
    }
    if (usage.promptByModality.isNotEmpty()) {
        val parts = usage.promptByModality.entries.sortedByDescending { it.value }.map { (modality, tokens) ->
            val label = when (modality.uppercase()) {
                "TEXT" -> stringResource(Res.string.lecture_modality_text)
                "IMAGE" -> stringResource(Res.string.lecture_modality_image)
                "AUDIO" -> stringResource(Res.string.lecture_modality_audio)
                "DOCUMENT" -> stringResource(Res.string.lecture_modality_document)
                else -> modality.lowercase()
            }
            "$label ${compactNumber(tokens)}"
        }
        Text(stringResource(Res.string.lecture_usage_input, parts.joinToString(" · ")), style = MaterialTheme.typography.bodySmall)
    }
    if (usage.localRequests > 0) {
        Text(stringResource(Res.string.lecture_usage_local, usage.localRequests), style = MaterialTheme.typography.bodySmall)
    }
    if (usage.skippedUpdates > 0) {
        MutedText(stringResource(Res.string.lecture_usage_skipped, usage.skippedUpdates))
    }
}

/**
 * "Insert as new pages" on its card: after the page the student is looking at, or at the end of
 * the notebook, and Insert.
 */
@Composable
private fun InsertPagesCard(editor: EditorContext, byTopic: Boolean, onInsert: (place: ReviewPlace, pageIndex: Int?) -> Unit) {
    val currentPage by remember(editor) {
        derivedStateOf {
            editor.pages.frames
            editor.pages.mostVisiblePageIndex()
        }
    }
    var choice by remember(byTopic) { mutableStateOf(if (byTopic) ReviewPlace.TOPIC else ReviewPlace.PAGE) }
    val page = currentPage
    val place = if (choice == ReviewPlace.PAGE && page == null) ReviewPlace.END else choice
    PanelCard {
        Text(stringResource(Res.string.lecture_review_insert), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        if (byTopic) {
            InsertChoice(stringResource(Res.string.lecture_review_insert_topic), selected = place == ReviewPlace.TOPIC) { choice = ReviewPlace.TOPIC }
        }
        if (page != null) {
            InsertChoice(stringResource(Res.string.lecture_review_insert_after, page + 1), selected = place == ReviewPlace.PAGE) { choice = ReviewPlace.PAGE }
        }
        InsertChoice(stringResource(Res.string.lecture_review_insert_end), selected = place == ReviewPlace.END) { choice = ReviewPlace.END }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onInsert(place, page) }) {
            Icon(DrawViewIcons.FilePlus, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.lecture_note_insert))
        }
    }
}

/** Where the review of a lecture goes in the notebook. */
private enum class ReviewPlace {
    /** Each section next to the pages it is about. */
    TOPIC,

    /** The page shown, then new pages after it. */
    PAGE,

    /** The end of the notebook. */
    END
}

@Composable
private fun InsertChoice(text: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The audio of a lecture recorded on another device: here, arriving (how much of it), asked for,
 * or kept there (not shared).
 */
@Composable
internal fun AudioElsewhere(assistant: LectureAssistant, lecture: Lecture) {
    val arriving by assistant.audioArriving.collectAsState()
    val progress = arriving[lecture.directory]
    val sharing = assistant.sharingOf(lecture.documentId)
    when {
        lecture.hasAudio -> LectureNotice(DrawViewIcons.Users, stringResource(Res.string.lecture_recorded_on, lecture.recordedOn), NoticeTone.Info)
        !lecture.audioShared -> LectureNotice(DrawViewIcons.Users, stringResource(Res.string.lecture_audio_not_shared, lecture.recordedOn), NoticeTone.Neutral)
        progress != null -> {
            LectureNotice(DrawViewIcons.Download, stringResource(Res.string.lecture_audio_progress, lecture.recordedOn, (progress * 100).toInt()), NoticeTone.Info)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), gapSize = 0.dp, drawStopIndicator = {})
        }
        sharing.downloadAudio == AudioDownload.ON_REQUEST && lecture.id !in sharing.requestedAudio -> LectureNotice(
            DrawViewIcons.Download,
            stringResource(Res.string.lecture_recorded_on, lecture.recordedOn),
            NoticeTone.Info,
            action = stringResource(Res.string.lecture_audio_download) to { assistant.requestAudio(lecture) }
        )
        else -> LectureNotice(DrawViewIcons.Download, stringResource(Res.string.lecture_audio_arriving, lecture.recordedOn), NoticeTone.Info)
    }
}
