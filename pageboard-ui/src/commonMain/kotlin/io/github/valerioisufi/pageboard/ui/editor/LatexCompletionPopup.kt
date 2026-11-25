package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.pageboard.model.text.LatexCommand
import io.github.valerioisufi.pageboard.model.text.LatexCommands
import kotlin.math.roundToInt

/**
 * Compact completion popup shown while typing a `\command` inside a formula, anchored near
 * [anchor] without stealing focus from the text field.
 */
@Composable
internal fun LatexCompletionPopup(
    match: LatexCommands.Match,
    selectedIndex: Int,
    anchor: () -> Rect?,
    avoid: () -> Rect?,
    monospace: FontFamily,
    onSelect: (LatexCommand) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .layout { measurable, constraints ->
                val gap = POPUP_GAP.roundToPx()
                val stripGap = STRIP_OFFSET.roundToPx()
                val maxW = (constraints.maxWidth - 2 * gap).coerceAtLeast(0)
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = maxW))
                layout(placeable.width, placeable.height) {
                    val at = anchor() ?: return@layout
                    val x = at.left.roundToInt().coerceIn(gap, (constraints.maxWidth - placeable.width - gap).coerceAtLeast(gap))
                    val bar = avoid()
                    val below = (at.bottom + stripGap).roundToInt()
                    val above = (at.top - stripGap - placeable.height).roundToInt()
                    fun fits(y: Int) = y >= gap && y + placeable.height <= constraints.maxHeight - gap &&
                        (bar == null || !Rect(x.toFloat(), y.toFloat(), (x + placeable.width).toFloat(), (y + placeable.height).toFloat()).overlaps(bar))
                    val y = when {
                        fits(below) -> below
                        fits(above) -> above
                        below + placeable.height <= constraints.maxHeight - gap -> below
                        else -> above.coerceAtLeast(gap)
                    }
                    placeable.place(x, y)
                }
            }
            .widthIn(min = 160.dp, max = 260.dp)
            .testTag("latex-completion"),
        shape = RoundedCornerShape(10.dp),
        shadowElevation = 6.dp,
        tonalElevation = 3.dp,
        color = colors.surface
    ) {
        Column(modifier = Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            match.candidates.forEachIndexed { index, cmd ->
                val active = index == selectedIndex
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (active) colors.primaryContainer else Color.Transparent)
                        .keepsTextFocus()
                        .clickable { onSelect(cmd) }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "\\${cmd.name}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = monospace, fontSize = 13.sp),
                        color = if (active) colors.onPrimaryContainer else colors.onSurface
                    )
                    if (cmd.preview.isNotEmpty()) {
                        Text(
                            text = cmd.preview,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }
        }
    }
}

private val POPUP_GAP = 6.dp
private val STRIP_OFFSET = 44.dp
