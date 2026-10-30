package io.github.valerioisufi.drawview.lecture.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.lecture.ui.generated.resources.*
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** How a notice of the assistant reads: a problem, a warning, what happens elsewhere, a fact. */
internal enum class NoticeTone { Error, Warning, Info, Neutral }

/**
 * A notice of the assistant (the key, the quotas, the network, the microphone, the other devices):
 * its icon and its text on the container of its [tone], its [action] under it.
 */
@Composable
internal fun LectureNotice(icon: ImageVector, text: String, tone: NoticeTone, action: Pair<String, () -> Unit>? = null) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        NoticeTone.Error -> colors.errorContainer to colors.onErrorContainer
        NoticeTone.Warning -> colors.secondaryContainer to colors.onSecondaryContainer
        NoticeTone.Info -> colors.tertiaryContainer to colors.onTertiaryContainer
        NoticeTone.Neutral -> colors.surfaceContainerHigh to colors.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(16.dp))
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = if (action != null) 4.dp else 12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = content, modifier = Modifier.weight(1f))
        }
        action?.let { (label, onClick) ->
            TextButton(onClick = onClick, modifier = Modifier.align(Alignment.End)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = content)
            }
        }
    }
}

/** The shape of a [PanelCard], clipped by a card that is clicked. */
internal val PanelCardShape = RoundedCornerShape(20.dp)

/** A card of the panel: the lowest container rounded 20, outlined when it stands out (a suggestion, the context). */
@Composable
internal fun PanelCard(modifier: Modifier = Modifier, outlined: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val shape = PanelCardShape
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, shape)
            .then(if (outlined) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape) else Modifier)
            .padding(16.dp),
        content = content
    )
}

/** An icon on a rounded square of [container] (a lecture of the list, an import). */
@Composable
internal fun IconTile(icon: ImageVector, container: Color, content: Color, size: Dp = 36.dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .background(container, RoundedCornerShape(10.dp))
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
    }
}

/**
 * The lecture being recorded, on the inverse surface: the live dot, how long, the waveform of the
 * microphone ([level], the last seconds), what the assistant is doing ([status]) and stop. [compact]
 * on a device recording only (a line: what it records, how long, stop).
 */
@Composable
internal fun RecordingCard(elapsed: String, level: Float, status: String, onStop: () -> Unit, compact: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val onCard = colors.inverseOnSurface
    if (compact) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.inverseSurface, RoundedCornerShape(16.dp))
                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LiveDot()
            Text(status, style = MaterialTheme.typography.labelLarge, color = onCard, modifier = Modifier.weight(1f))
            Text(elapsed, style = MaterialTheme.typography.labelLarge, color = onCard.copy(alpha = 0.8f))
            StopButton(onStop)
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.inverseSurface, RoundedCornerShape(24.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiveDot()
            Text(
                stringResource(Res.string.lecture_indicator_recording).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = onCard.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f)
            )
            Text(elapsed, style = MaterialTheme.typography.titleLarge, color = onCard)
        }
        Waveform(level, color = colors.inversePrimary, modifier = Modifier.fillMaxWidth().height(40.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = onCard.copy(alpha = 0.7f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            StopButton(onStop)
        }
    }
}

/** The red dot of a recording, ringed. */
@Composable
private fun LiveDot() {
    Box(
        modifier = Modifier
            .size(14.dp)
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.3f), CircleShape)
            .padding(3.dp)
            .background(MaterialTheme.colorScheme.error, CircleShape)
    )
}

/** Stops the recording: a round button on the color of the text of the card. */
@Composable
private fun StopButton(onStop: () -> Unit) {
    IconButton(
        onClick = onStop,
        modifier = Modifier.size(36.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.inverseOnSurface,
            contentColor = MaterialTheme.colorScheme.inverseSurface
        )
    ) {
        Icon(DrawViewIcons.Stop, contentDescription = stringResource(Res.string.lecture_action_stop), modifier = Modifier.size(18.dp))
    }
}

/** How many bars the waveform keeps, and how often it takes the level of the microphone. */
private const val WAVE_BARS = 40
private const val WAVE_SAMPLE_MS = 120L

/** The level of the microphone in the last seconds, as bars (the newest at the end). */
@Composable
internal fun Waveform(level: Float, color: Color, modifier: Modifier = Modifier) {
    val current = rememberUpdatedState(level)
    val levels = remember { mutableStateListOf<Float>().apply { repeat(WAVE_BARS) { add(0f) } } }
    LaunchedEffect(Unit) {
        while (true) {
            delay(WAVE_SAMPLE_MS)
            levels.removeAt(0)
            levels.add(current.value.coerceIn(0f, 1f))
        }
    }
    Canvas(modifier) {
        val gap = 3.dp.toPx()
        val bar = ((size.width - gap * (WAVE_BARS - 1)) / WAVE_BARS).coerceAtLeast(1f)
        val minHeight = 4.dp.toPx()
        levels.forEachIndexed { index, value ->
            val height = (minHeight + (size.height - minHeight) * value).coerceAtMost(size.height)
            drawRoundRect(
                color = color.copy(alpha = if (value > 0f) 1f else 0.4f),
                topLeft = Offset(index * (bar + gap), (size.height - height) / 2f),
                size = Size(bar, height),
                cornerRadius = CornerRadius(bar / 2f)
            )
        }
    }
}
