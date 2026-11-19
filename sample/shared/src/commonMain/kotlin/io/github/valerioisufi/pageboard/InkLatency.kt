package io.github.valerioisufi.pageboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.ui.extension.EditorExtension
import io.github.valerioisufi.pageboard.ui.screen.EditorActionButton
import io.github.valerioisufi.pageboard.ui.theme.PageboardIcons

/**
 * Measures the latency of the ink (see `docs/plans/ink-latency.md`): a button of the toolbar switches the
 * measure on, and the report of the last stroke is shown over the canvas and printed. A measure of
 * the editor, not of an extension: the sample reads it from the editor of its session.
 */
fun inkLatencyExtension(): EditorExtension = EditorExtension {
    toolbarActions { context ->
        val controller = SampleData.sessions.find(context.documentId)?.controller ?: return@toolbarActions
        EditorActionButton(
            PageboardIcons.Clock,
            "Measure the latency of the ink",
            onClick = { controller.isInkLatencyMeasured = !controller.isInkLatencyMeasured },
            active = controller.isInkLatencyMeasured
        )
    }
    canvasOverlay { context ->
        val controller = SampleData.sessions.find(context.documentId)?.controller ?: return@canvasOverlay
        val report by controller.inkLatency.collectAsState()
        LaunchedEffect(report) { report?.let { println("Pageboard $it") } }
        val shown = report
        if (controller.isInkLatencyMeasured && shown != null) {
            Text(
                shown.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    // Above the indicator of the page
                    .padding(start = 12.dp, end = 12.dp, bottom = 72.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), MaterialTheme.shapes.small)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
