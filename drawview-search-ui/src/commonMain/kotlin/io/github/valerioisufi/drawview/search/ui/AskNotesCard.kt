package io.github.valerioisufi.drawview.search.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.explorer.DocumentOpener
import io.github.valerioisufi.drawview.ml.ModelCatalog
import io.github.valerioisufi.drawview.search.CitedPassage
import io.github.valerioisufi.drawview.search.NotesAnswer
import io.github.valerioisufi.drawview.search.ui.generated.resources.Res
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_ask_answered_by
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_ask_button
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_ask_error
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_ask_install_hint
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_ask_no_passages
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_ask_online_button
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_ask_online_privacy
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_ask_sources_title
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_ask_thinking
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_ask_scope
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_ask_title
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_indexing_status
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_searching
import io.github.valerioisufi.drawview.ui.components.MarkdownText
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.stringResource

/**
 * Why the results of a search may be missing something: the notes not read yet, a search still
 * running. Nothing otherwise.
 */
@Composable
internal fun IndexingNotice(search: ContentSearch, modifier: Modifier = Modifier) {
    val progress by search.indexingProgress.collectAsState()
    val indexing = progress.isIndexing && progress.indexedDocuments < progress.totalDocuments
    if (!indexing && !search.isSearchSlow) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.padding(vertical = 2.dp)
    ) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        Text(
            text = if (indexing) {
                stringResource(Res.string.search_indexing_status, progress.indexedDocuments, progress.totalDocuments)
            } else {
                stringResource(Res.string.search_searching)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Card shown at the top of the library search results when the query is at least 3 characters
 * long, offering to answer the question from the passages found in the notes (View); nothing
 * without a way to answer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun AskNotesCard(
    search: ContentSearch,
    query: String,
    openDocument: DocumentOpener,
    modifier: Modifier = Modifier
) {
    val trimmed = query.trim()
    if (trimmed.length < 3) return

    val installedIds by (search.installedModels?.installed ?: MutableStateFlow(emptySet())).collectAsState()
    val hasLocalAnswerModel = search.runtimeAvailable && (
        search.answerModelSpec.id in installedIds ||
            ModelCatalog.GEMMA_4_E2B.id in installedIds ||
            ModelCatalog.GEMMA_4_E4B.id in installedIds
        )
    val canAskOnline = search.onlineBackend?.isAvailable == true
    val answer = search.currentAnswer
    val isAnswering = search.isAnswering
    val error = search.answerError
    val scope by search.scope.collectAsState()
    if (!search.runtimeAvailable && !canAskOnline && answer == null && !isAnswering && error == null) return

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = DrawViewIcons.Sparkles,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(Res.string.search_ask_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (!isAnswering) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (hasLocalAnswerModel) {
                            FilledTonalButton(
                                onClick = { search.askOnDevice(trimmed) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = stringResource(Res.string.search_ask_button),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                        if (canAskOnline) {
                            OutlinedButton(
                                onClick = { search.askOnline(trimmed) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(
                                    imageVector = DrawViewIcons.Globe,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp).padding(end = 4.dp)
                                )
                                Text(
                                    text = stringResource(Res.string.search_ask_online_button),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }

            if (scope != null && answer == null && !isAnswering) {
                Text(
                    text = stringResource(Res.string.search_ask_scope),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!hasLocalAnswerModel && search.runtimeAvailable && answer == null && !isAnswering) {
                Text(
                    text = stringResource(Res.string.search_ask_install_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (canAskOnline && answer == null && !isAnswering) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = DrawViewIcons.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = stringResource(Res.string.search_ask_online_privacy),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isAnswering) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(Res.string.search_ask_thinking),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (error != null && !isAnswering) {
                Text(
                    text = stringResource(Res.string.search_ask_error, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (answer != null && !isAnswering) {
                AnswerBody(
                    answer = answer,
                    onOpenCitation = { cited -> search.openAnchor(cited.hit.anchor, openDocument) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnswerBody(
    answer: NotesAnswer,
    onOpenCitation: (CitedPassage) -> Unit
) {
    if (answer.passages.isEmpty()) {
        Text(
            text = stringResource(Res.string.search_ask_no_passages),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    MarkdownText(
        markdown = answer.text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth()
    )

    val displayedCitations = answer.citations.ifEmpty {
        answer.passages.mapIndexed { idx, hit -> CitedPassage(number = idx + 1, hit = hit) }
    }

    if (displayedCitations.isNotEmpty()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(Res.string.search_ask_sources_title),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(Res.string.search_ask_answered_by, answer.modelName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (citation in displayedCitations) {
                    CitationChip(
                        citation = citation,
                        onClick = { onOpenCitation(citation) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CitationChip(
    citation: CitedPassage,
    onClick: () -> Unit
) {
    val hit = citation.hit
    val location = ContentSearch.formatLocation(hit.anchor)
    val icon = ContentSearch.iconForKind(hit.kind)
    val label = buildString {
        append('[')
        append(citation.number)
        append("] ")
        append(hit.title.ifBlank { hit.anchor.documentId })
        if (location.isNotEmpty()) {
            append(" · ")
            append(location)
        }
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(13.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
