package io.github.valerioisufi.drawview.lecture.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.lecture.Lecture
import io.github.valerioisufi.drawview.lecture.audio.RecordingPlayer
import io.github.valerioisufi.drawview.lecture.formatLectureTime
import io.github.valerioisufi.drawview.lecture.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Plays the recording of [lecture]: play and pause, 10 s back and forward, the position (to drag)
 * and the speed. Shown when the platform can play and the lecture has audio.
 */
@Composable
internal fun PlayerBar(lecture: Lecture) {
    val player = lecture.player ?: return
    // The recording grows while recording
    LaunchedEffect(lecture.elapsedMs) { player.refresh() }
    if (player.durationMs <= 0L) return
    // While the position is dragged, the bar follows the pointer, not the player
    var dragged by remember(player) { mutableStateOf<Float?>(null) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { player.skip(-SKIP_MS) }) {
                Icon(Icons.Outlined.Replay10, contentDescription = stringResource(Res.string.lecture_player_back))
            }
            IconButton(onClick = { player.toggle() }) {
                Icon(
                    if (player.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = stringResource(if (player.isPlaying) Res.string.lecture_player_pause else Res.string.lecture_player_play)
                )
            }
            IconButton(onClick = { player.skip(SKIP_MS) }) {
                Icon(Icons.Outlined.Forward10, contentDescription = stringResource(Res.string.lecture_player_forward))
            }
            val shownMs = dragged?.toLong() ?: player.positionMs
            Text(
                text = formatLectureTime(shownMs) + " / " + formatLectureTime(player.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            TextButton(onClick = { player.nextSpeed() }) {
                Text(formatSpeed(player.speed), style = MaterialTheme.typography.labelLarge)
            }
        }
        Slider(
            value = (dragged ?: player.positionMs.toFloat()).coerceIn(0f, player.durationMs.toFloat()),
            onValueChange = { dragged = it },
            onValueChangeFinished = {
                dragged?.let { player.seek(it.toLong()) }
                dragged = null
            },
            valueRange = 0f..player.durationMs.toFloat(),
            modifier = Modifier.fillMaxWidth().height(24.dp)
        )
    }
}

/** 1 -> "1×", 1.25 -> "1.25×". */
private fun formatSpeed(speed: Float): String =
    (if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()) + "×"

private const val SKIP_MS = 10_000L

/** The segment of the transcript being played, when the recording plays or is paused inside. */
internal fun playingSegment(player: RecordingPlayer?, starts: List<Long>): Int? {
    if (player == null || (!player.isPlaying && player.positionMs == 0L)) return null
    val index = starts.indexOfLast { it <= player.positionMs }
    return index.takeIf { it >= 0 }
}
