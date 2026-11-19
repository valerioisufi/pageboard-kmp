package io.github.valerioisufi.pageboard.lecture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.lecture.AssistantSettings
import io.github.valerioisufi.pageboard.lecture.SharedData
import io.github.valerioisufi.pageboard.lecture.ui.generated.resources.*
import io.github.valerioisufi.pageboard.platform.currentTimeMillis
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

// What the sections of the lecture panel share: texts, times, chips

/** The margins of the content of the panel. */
internal val PanelPadding = 20.dp

/** Something under way: a spinner and what it is. */
@Composable
internal fun Progress(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, trackColor = MaterialTheme.colorScheme.primaryContainer)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** How long a lecture lasted: "1 h 28 min", "42 min". */
internal fun formatDuration(ms: Long): String {
    val minutes = (ms / 60_000).coerceAtLeast(0)
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

/** A wait of the API: "7 h 39 min", "12 min", "1 min". */
internal fun formatWait(ms: Long): String {
    val minutes = ((ms + 59_999) / 60_000).coerceAtLeast(1)
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

/** A time of the lecture on a chip: the high container, the primary color when it is the one playing. */
@Composable
internal fun TimeChip(text: String, active: Boolean = false, onClick: (() -> Unit)? = null) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

// ==========================================================
// Common
// ==========================================================

@Composable
internal fun MutedText(text: String, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/** A sentence of the panel: what happens, what to do. */
@Composable
internal fun BodyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** What leaves this computer with the current settings, before recording. */
@Composable
internal fun privacyNote(settings: AssistantSettings): String {
    val shared = settings.sentToGoogle
    if (shared.isEmpty()) return stringResource(Res.string.lecture_privacy_local)
    val parts = SharedData.entries.filter { it in shared }.map { data ->
        stringResource(
            when (data) {
                SharedData.TRANSCRIPT -> Res.string.lecture_privacy_transcript
                SharedData.NOTEBOOK -> Res.string.lecture_privacy_notebook
            }
        )
    }
    return stringResource(Res.string.lecture_privacy_online, parts.joinToString(", "))
}

/** "04/10/2026 14:30" in the local time zone. */
@OptIn(ExperimentalTime::class)
internal fun formatLectureDate(epochMs: Long): String {
    val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    fun two(value: Int) = value.toString().padStart(2, '0')
    return "${two(t.day)}/${two(t.month.ordinal + 1)}/${t.year} ${two(t.hour)}:${two(t.minute)}"
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

/** Now, again every few seconds (for what depends on the time passing). */
@Composable
internal fun rememberNow(): Long {
    var now by remember { mutableStateOf(currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = currentTimeMillis()
        }
    }
    return now
}
