package io.github.valerioisufi.drawview.search.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.ml.DownloadProgress
import io.github.valerioisufi.drawview.ml.InstalledModels
import io.github.valerioisufi.drawview.ml.ModelCatalog
import io.github.valerioisufi.drawview.ml.ModelDownloads
import io.github.valerioisufi.drawview.ml.ModelSpec
import io.github.valerioisufi.drawview.search.IndexingProgress
import io.github.valerioisufi.drawview.search.ui.generated.resources.Res
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_embedding_status
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_index_ready
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_indexing_status
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_model_cancel
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_model_delete
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_model_download
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_model_downloading
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_model_failed
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_model_installed
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_model_select
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_model_use
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_runtime_unavailable
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_settings_action
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_settings_answer_description
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_settings_answer_title
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_settings_close
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_settings_privacy
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_settings_semantic_description
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_settings_semantic_title
import io.github.valerioisufi.drawview.search.ui.generated.resources.search_settings_title
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveSheet
import io.github.valerioisufi.drawview.ui.components.BadgeTone
import io.github.valerioisufi.drawview.ui.components.LabelBadge
import io.github.valerioisufi.drawview.ui.components.OutlineButton
import io.github.valerioisufi.drawview.ui.components.TonalButton
import io.github.valerioisufi.drawview.ui.extension.LibraryAction
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

/**
 * [LibraryAction] opening [SearchSettingsDialog] in the document library.
 */
public fun ContentSearch.settingsAction(): LibraryAction = LibraryAction(
    icon = DrawViewIcons.Sparkles,
    label = { stringResource(Res.string.search_settings_action) },
    content = { onDismiss -> SearchSettingsDialog(search = this, onDismiss = onDismiss) }
)

/**
 * Dialog and sheet showing the status of the search index and the on-device models for semantic
 * search (EmbeddingGemma 2) and "Ask your notes" (Gemma 4).
 */
@Composable
public fun SearchSettingsDialog(
    search: ContentSearch,
    onDismiss: () -> Unit
) {
    LaunchedEffect(search.installedModels) {
        search.installedModels?.refresh()
    }
    AdaptiveSheet(onDismissRequest = onDismiss, maxWidth = 580.dp) {
        SearchSettingsContent(search = search, onDismiss = onDismiss)
    }
}

@Composable
internal fun SearchSettingsContent(
    search: ContentSearch,
    onDismiss: () -> Unit
) {
    val indexingProgress by search.indexingProgress.collectAsState()
    val installedModels = search.installedModels
    val downloads = search.downloads

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(Res.string.search_settings_title),
            style = MaterialTheme.typography.titleLarge
        )

        PrivacyBanner(text = stringResource(Res.string.search_settings_privacy))

        IndexingStatusCard(progress = indexingProgress)

        if (!search.runtimeAvailable || installedModels == null || downloads == null) {
            PrivacyBanner(text = stringResource(Res.string.search_runtime_unavailable))
        } else {
            val installedIds by installedModels.installed.collectAsState()
            val progressMap by downloads.progress.collectAsState()
            val failureMap by downloads.failures.collectAsState()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(Res.string.search_settings_semantic_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(Res.string.search_settings_semantic_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ModelCard(
                    spec = search.embeddingSpec,
                    installed = search.embeddingSpec.id in installedIds,
                    progress = progressMap[search.embeddingSpec.id],
                    failure = failureMap[search.embeddingSpec.id],
                    isActive = null,
                    onSelect = null,
                    onDownload = { downloads.download(search.embeddingSpec) },
                    onCancel = { downloads.cancel(search.embeddingSpec) },
                    onDelete = { installedModels.delete(search.embeddingSpec) }
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(Res.string.search_settings_answer_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(Res.string.search_settings_answer_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val answerCandidates = listOf(ModelCatalog.GEMMA_4_E2B, ModelCatalog.GEMMA_4_E4B)
                for (spec in answerCandidates) {
                    val isInstalled = spec.id in installedIds
                    ModelCard(
                        spec = spec,
                        installed = isInstalled,
                        progress = progressMap[spec.id],
                        failure = failureMap[spec.id],
                        isActive = if (isInstalled) search.answerModelSpec.id == spec.id else null,
                        onSelect = if (isInstalled && search.answerModelSpec.id != spec.id) {
                            { search.answerModelSpec = spec }
                        } else {
                            null
                        },
                        onDownload = { downloads.download(spec) },
                        onCancel = { downloads.cancel(spec) },
                        onDelete = { installedModels.delete(spec) }
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.search_settings_close))
            }
        }
    }
}

@Composable
private fun PrivacyBanner(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = DrawViewIcons.Shield,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun IndexingStatusCard(progress: IndexingProgress) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!progress.isIndexing) {
            Icon(
                imageVector = DrawViewIcons.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = stringResource(Res.string.search_index_ready),
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
            val statusText = if (progress.unembeddedChunks > 0 && progress.indexedDocuments >= progress.totalDocuments) {
                stringResource(Res.string.search_embedding_status, progress.unembeddedChunks)
            } else {
                stringResource(Res.string.search_indexing_status, progress.indexedDocuments, progress.totalDocuments)
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun ModelCard(
    spec: ModelSpec,
    installed: Boolean,
    progress: DownloadProgress?,
    failure: String?,
    isActive: Boolean?,
    onSelect: (() -> Unit)?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = spec.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = formatBytes(spec.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (installed) {
                LabelBadge(
                    text = if (isActive == true) {
                        stringResource(Res.string.search_model_use)
                    } else {
                        stringResource(Res.string.search_model_installed)
                    },
                    tone = if (isActive == true) BadgeTone.Blue else BadgeTone.Green
                )
            }
        }

        if (progress != null) {
            val pct = (progress.fraction * 100f).roundToInt()
            Text(
                text = stringResource(
                    Res.string.search_model_downloading,
                    formatBytes(progress.downloadedBytes),
                    formatBytes(progress.totalBytes),
                    pct
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.fillMaxWidth(),
                gapSize = 0.dp,
                drawStopIndicator = {}
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                OutlineButton(onClick = onCancel) {
                    Text(stringResource(Res.string.search_model_cancel))
                }
            }
        } else {
            if (failure != null) {
                Text(
                    text = stringResource(Res.string.search_model_failed, failure),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (installed) {
                    if (onSelect != null) {
                        TonalButton(onClick = onSelect) {
                            Text(stringResource(Res.string.search_model_select))
                        }
                    }
                    OutlineButton(onClick = onDelete) {
                        Icon(DrawViewIcons.Trash, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.search_model_delete))
                    }
                } else {
                    TonalButton(onClick = onDownload) {
                        Icon(DrawViewIcons.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.search_model_download, formatBytes(spec.sizeBytes)))
                    }
                }
            }
        }
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes >= 1_000_000_000L) {
        val tenths = ((bytes.toDouble() / 1_000_000_000.0) * 10.0).roundToInt()
        return "${tenths / 10}.${tenths % 10} GB"
    }
    val mb = (bytes.toDouble() / 1_000_000.0).roundToInt().coerceAtLeast(1)
    return "$mb MB"
}
