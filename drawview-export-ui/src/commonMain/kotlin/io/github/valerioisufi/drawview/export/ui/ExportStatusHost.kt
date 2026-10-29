package io.github.valerioisufi.drawview.export.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.export.ExportFailure
import io.github.valerioisufi.drawview.export.ExportJob
import io.github.valerioisufi.drawview.export.ExportJobState
import io.github.valerioisufi.drawview.export.ExportJobs
import io.github.valerioisufi.drawview.export.ui.generated.resources.Res
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_close
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_failed_document_missing
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_failed_no_space
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_failed_not_writable
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_failed_nothing
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_failed_unknown
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_open
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_retry
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_show
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_status_cancelled
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_status_done
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_status_item
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_status_missing
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_status_queued
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_status_running
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_status_skipped
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_status_skipped_in
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_stop
import io.github.valerioisufi.drawview.ui.platform.FileReveal
import io.github.valerioisufi.drawview.ui.platform.fileReveal
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * The exports of [jobs] outside the sheet that asked them (see `docs/export-ux.md`): the one
 * running with its progress and Stop, those waiting, then each outcome with what can be done with
 * the file (show it, open it) or Retry. A clean outcome goes away by itself, the others when they
 * are closed.
 */
@Composable
internal fun ExportStatusHost(jobs: ExportJobs, modifier: Modifier = Modifier, reveal: FileReveal? = fileReveal()) {
    val list by jobs.jobs.collectAsState()
    Column(
        modifier = modifier.widthIn(max = 440.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // The last ones, the newest at the bottom (nearest to where the sheet was)
        for (job in list.takeLast(MAX_SHOWN)) {
            key(job.id) {
                ExportStatusCard(
                    job = job,
                    reveal = reveal,
                    onStop = { jobs.cancel(job.id) },
                    onRetry = { jobs.retry(job.id) },
                    onClose = { jobs.dismiss(job.id) }
                )
                // A clean outcome goes by itself; a failure, or what was left out, waits to be read
                val state = job.state
                val clean = state is ExportJobState.Cancelled ||
                    (state is ExportJobState.Done && state.outcome.skipped == 0 && state.outcome.missing.isEmpty())
                if (clean) {
                    LaunchedEffect(state) {
                        delay(if (state is ExportJobState.Done && reveal != null) 10_000 else 5_000)
                        jobs.dismiss(job.id)
                    }
                }
            }
        }
    }
}

/** How many exports are shown at once. */
private const val MAX_SHOWN = 3

/**
 * The card of [job]: what it is doing or how it went, with Stop while it goes, Show and Open
 * ([reveal]) once done, Retry once failed, and Close.
 */
@Composable
internal fun ExportStatusCard(job: ExportJob, reveal: FileReveal?, onStop: () -> Unit, onRetry: () -> Unit, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val name = job.request.fileName
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.inverseSurface,
        contentColor = colors.inverseOnSurface,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val state = job.state
                val (icon, tint) = when (state) {
                    is ExportJobState.Done -> DrawViewIcons.CheckCircle to colors.inversePrimary
                    is ExportJobState.Failed -> DrawViewIcons.TriangleAlert to colors.errorContainer
                    else -> DrawViewIcons.Share to colors.inverseOnSurface
                }
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        text = when (state) {
                            ExportJobState.Queued -> stringResource(Res.string.export_status_queued, name)
                            is ExportJobState.Running -> stringResource(Res.string.export_status_running, name)
                            is ExportJobState.Done -> stringResource(Res.string.export_status_done, name)
                            ExportJobState.Cancelled -> stringResource(Res.string.export_status_cancelled, name)
                            is ExportJobState.Failed -> stringResource(failureText(state.failure), name)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val details = when (state) {
                        is ExportJobState.Running -> state.progress?.takeIf { it.items > 1 && it.name != null }?.let {
                            stringResource(Res.string.export_status_item, it.name.orEmpty(), it.item, it.items)
                        }
                        is ExportJobState.Done -> listOfNotNull(
                            state.outcome.skipped.takeIf { it > 0 }?.let { skipped ->
                                if (state.outcome.skippedIn.isEmpty()) {
                                    stringResource(Res.string.export_status_skipped, skipped)
                                } else {
                                    stringResource(Res.string.export_status_skipped_in, skipped, state.outcome.skippedIn.joinToString())
                                }
                            },
                            state.outcome.missing.takeIf { it.isNotEmpty() }?.let { stringResource(Res.string.export_status_missing, it.joinToString()) }
                        ).joinToString("\n").ifEmpty { null }
                        else -> null
                    }
                    if (details != null) {
                        Text(details, style = MaterialTheme.typography.bodySmall, color = colors.inverseOnSurface.copy(alpha = 0.8f))
                    }
                }
                if (!job.isActive) {
                    IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                        Icon(DrawViewIcons.Close, contentDescription = stringResource(Res.string.export_close), modifier = Modifier.size(18.dp))
                    }
                }
            }
            val state = job.state
            if (state is ExportJobState.Running) {
                LinearProgressIndicator(
                    progress = { state.progress?.fraction ?: 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp),
                    color = colors.inversePrimary,
                    trackColor = colors.inverseOnSurface.copy(alpha = 0.2f),
                    gapSize = 0.dp,
                    drawStopIndicator = {}
                )
            }
            val actions = buildList<Pair<String, () -> Unit>> {
                when (state) {
                    ExportJobState.Queued, is ExportJobState.Running -> add(stringResource(Res.string.export_stop) to onStop)
                    is ExportJobState.Done -> if (reveal != null) {
                        add(stringResource(Res.string.export_show) to { reveal.show(state.path) })
                        add(stringResource(Res.string.export_open) to { reveal.open(state.path) })
                    }
                    is ExportJobState.Failed, ExportJobState.Cancelled -> add(stringResource(Res.string.export_retry) to onRetry)
                }
            }
            if (actions.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    for ((label, action) in actions) {
                        TextButton(onClick = action, colors = ButtonDefaults.textButtonColors(contentColor = colors.inversePrimary)) { Text(label) }
                    }
                }
            }
        }
    }
}

/** What tells the user why an export failed. */
private fun failureText(failure: ExportFailure) = when (failure) {
    ExportFailure.NO_SPACE -> Res.string.export_failed_no_space
    ExportFailure.NOT_WRITABLE -> Res.string.export_failed_not_writable
    ExportFailure.DOCUMENT_MISSING -> Res.string.export_failed_document_missing
    ExportFailure.NOTHING_TO_EXPORT -> Res.string.export_failed_nothing
    ExportFailure.UNKNOWN -> Res.string.export_failed_unknown
}
