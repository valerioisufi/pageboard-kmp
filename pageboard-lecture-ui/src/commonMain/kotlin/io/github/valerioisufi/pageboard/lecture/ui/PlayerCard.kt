package io.github.valerioisufi.pageboard.lecture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import io.github.valerioisufi.pageboard.lecture.Lecture
import io.github.valerioisufi.pageboard.lecture.audio.RecordingPlayer
import io.github.valerioisufi.pageboard.lecture.formatLectureTime
import io.github.valerioisufi.pageboard.lecture.ui.generated.resources.*
import io.github.valerioisufi.pageboard.ui.components.DesignIconButton
import io.github.valerioisufi.pageboard.ui.components.DesignSlider
import io.github.valerioisufi.pageboard.ui.theme.DrawViewIcons
import org.jetbrains.compose.resources.stringResource

/**
 * Plays the recording of [lecture] on its card: the position (to drag) with the time and the
 * length, the speed, 10 s back, play and pause, 10 s forward. Shown when the platform can play and
 * the lecture has audio.
 */
@Composable
internal fun PlayerCard(lecture: Lecture) {
    val player = lecture.player ?: return
    // The recording grows while recording
    LaunchedEffect(lecture.elapsedMs) { player.refresh() }
    if (player.durationMs <= 0L) return
    // While the position is dragged, the bar follows the pointer, not the player
    var dragged by remember(player) { mutableStateOf<Float?>(null) }

    PanelCard {
        DesignSlider(
            value = (dragged ?: player.positionMs.toFloat()).coerceIn(0f, player.durationMs.toFloat()),
            onValueChange = { dragged = it },
            onValueChangeFinished = {
                dragged?.let { player.seek(it.toLong()) }
                dragged = null
            },
            valueRange = 0f..player.durationMs.toFloat(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            val shownMs = dragged?.toLong() ?: player.positionMs
            Text(formatLectureTime(shownMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(formatLectureTime(player.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            TextButton(
                onClick = { player.nextSpeed() },
                modifier = Modifier.align(Alignment.CenterStart),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Text(formatSpeed(player.speed), style = MaterialTheme.typography.labelLarge)
            }
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                DesignIconButton(DrawViewIcons.RotateCcw, stringResource(Res.string.lecture_player_back), onClick = { player.skip(-SKIP_MS) }, tint = MaterialTheme.colorScheme.onSurface)
                FilledIconButton(onClick = { player.toggle() }, modifier = Modifier.size(52.dp)) {
                    Icon(
                        if (player.isPlaying) DrawViewIcons.Pause else DrawViewIcons.Play,
                        contentDescription = stringResource(if (player.isPlaying) Res.string.lecture_player_pause else Res.string.lecture_player_play),
                        modifier = Modifier.size(24.dp)
                    )
                }
                DesignIconButton(DrawViewIcons.RotateCw, stringResource(Res.string.lecture_player_forward), onClick = { player.skip(SKIP_MS) }, tint = MaterialTheme.colorScheme.onSurface)
            }
        }
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
