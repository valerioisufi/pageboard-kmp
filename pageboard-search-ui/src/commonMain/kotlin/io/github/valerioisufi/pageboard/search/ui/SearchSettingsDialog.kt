package io.github.valerioisufi.pageboard.search.ui

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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.ml.DownloadProgress
import io.github.valerioisufi.pageboard.ml.InstalledModels
import io.github.valerioisufi.pageboard.ml.ModelCatalog
import io.github.valerioisufi.pageboard.ml.ModelDownloads
import io.github.valerioisufi.pageboard.ml.ModelSpec
import io.github.valerioisufi.pageboard.search.HandwritingSearch
import io.github.valerioisufi.pageboard.search.IndexingProgress
import io.github.valerioisufi.pageboard.handwriting.InkLanguageModel
import io.github.valerioisufi.pageboard.ui.components.SwitchRow
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_handwriting_description
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_handwriting_model
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_handwriting_model_hint
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_handwriting_no_reader
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_handwriting_read
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_handwriting_title
import androidx.compose.runtime.rememberCoroutineScope
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_handwriting_language_de
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_handwriting_language_en
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_handwriting_language_es
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_handwriting_language_fr
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_handwriting_language_it
import kotlinx.coroutines.launch
import io.github.valerioisufi.pageboard.search.ui.generated.resources.Res
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_embedding_status
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_index_ready
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_indexing_status
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_model_cancel
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_model_delete
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_model_delete_confirm
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_model_delete_title
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_model_download
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_model_downloading
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_model_failed
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_model_installed
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_model_select
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_model_use
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_runtime_unavailable
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_settings_action
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_settings_answer_description
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_settings_answer_title
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_settings_close
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_settings_privacy
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_settings_semantic_description
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_settings_semantic_title
import io.github.valerioisufi.pageboard.search.ui.generated.resources.search_settings_title
import io.github.valerioisufi.pageboard.ui.adaptive.AdaptiveSheet
import io.github.valerioisufi.pageboard.ui.adaptive.SheetBody
import io.github.valerioisufi.pageboard.ui.components.BadgeTone
import io.github.valerioisufi.pageboard.ui.components.LabelBadge
import io.github.valerioisufi.pageboard.ui.components.OutlineButton
import io.github.valerioisufi.pageboard.ui.components.TonalButton
import io.github.valerioisufi.pageboard.ui.extension.LibraryAction
import io.github.valerioisufi.pageboard.ui.components.DesignIconButton
import io.github.valerioisufi.pageboard.ui.theme.PageboardIcons
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

/**
 * [LibraryAction] opening [SearchSettingsDialog] in the document library.
 */
public fun ContentSearch.settingsAction(): LibraryAction = LibraryAction(
    icon = PageboardIcons.Search,
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
    AdaptiveSheet(body = SheetBody.Fixed, onDismissRequest = onDismiss, maxWidth = 580.dp) {
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
        // The close button in sight above the content, as in the other dialogs: at the end of a long
        // list of models it was below the edge of the dialog
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.search_settings_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            DesignIconButton(PageboardIcons.Close, stringResource(Res.string.search_settings_close), onDismiss)
        }

        PrivacyBanner(text = stringResource(Res.string.search_settings_privacy))

        IndexingStatusCard(progress = indexingProgress)

        search.handwriting?.let { HandwritingSection(it) }

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
    }
}

/** The reading of the handwriting: on or off, and the model of its language when the reader downloads one. */
@Composable
private fun HandwritingSection(handwriting: HandwritingSearch) {
    val enabled by handwriting.isEnabled.collectAsState()
    val language by handwriting.language.collectAsState()
    val languageName = languageName(language)
    val models = handwriting.reading.engines.languageModels
    val scope = rememberCoroutineScope()
    var readsLanguage by remember { mutableStateOf<Boolean?>(null) }
    var model by remember { mutableStateOf<InkLanguageModel?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(language, refresh) {
        readsLanguage = handwriting.reading.readsText(language)
        model = models?.models(listOf(language))?.firstOrNull()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(Res.string.search_handwriting_title), style = MaterialTheme.typography.titleMedium)
        SwitchRow(
            text = stringResource(Res.string.search_handwriting_read),
            hint = stringResource(Res.string.search_handwriting_description),
            checked = enabled,
            onCheckedChange = handwriting::setEnabled
        )
        val current = model
        if (current != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(Res.string.search_handwriting_model, languageName), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(Res.string.search_handwriting_model_hint, formatBytes(current.sizeBytes ?: 0L)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else if (current.isDownloaded) {
                    TextButton(onClick = {
                        busy = true
                        scope.launch {
                            runCatching { models?.delete(current.language) }
                            handwriting.readersChanged()
                            busy = false
                            refresh++
                        }
                    }) { Text(stringResource(Res.string.search_model_delete)) }
                } else {
                    TextButton(onClick = {
                        busy = true
                        failure = null
                        scope.launch {
                            runCatching { models?.download(current.language) }.onFailure { failure = it.message ?: it.toString() }
                            handwriting.readersChanged()
                            busy = false
                            refresh++
                        }
                    }) { Text(stringResource(Res.string.search_model_download, formatBytes(current.sizeBytes ?: 0L))) }
                }
            }
            failure?.let {
                Text(stringResource(Res.string.search_model_failed, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        } else if (readsLanguage == false) {
            Text(
                stringResource(Res.string.search_handwriting_no_reader, languageName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The name of the language [tag] in the language of the interface, or the tag for a language without one here. */
@Composable
private fun languageName(tag: String): String = when (tag.substringBefore('-').lowercase()) {
    "it" -> stringResource(Res.string.search_handwriting_language_it)
    "en" -> stringResource(Res.string.search_handwriting_language_en)
    "fr" -> stringResource(Res.string.search_handwriting_language_fr)
    "de" -> stringResource(Res.string.search_handwriting_language_de)
    "es" -> stringResource(Res.string.search_handwriting_language_es)
    else -> tag
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
            imageVector = PageboardIcons.Shield,
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
                imageVector = PageboardIcons.CheckCircle,
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
    // A model removed is downloaded again whole (gigabytes): the removal is confirmed first
    var confirmingDelete by remember { mutableStateOf(false) }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(Res.string.search_model_delete_title, spec.name)) },
            text = { Text(stringResource(Res.string.search_model_delete_confirm, formatBytes(spec.sizeBytes))) },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; onDelete() }) {
                    Text(stringResource(Res.string.search_model_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(Res.string.search_model_cancel))
                }
            }
        )
    }
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
                    OutlineButton(onClick = { confirmingDelete = true }) {
                        Icon(PageboardIcons.Trash, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.search_model_delete))
                    }
                } else {
                    TonalButton(onClick = onDownload) {
                        Icon(PageboardIcons.Download, contentDescription = null, modifier = Modifier.size(16.dp))
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
