package io.github.valerioisufi.drawview.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.core.geometry.Measure

/**
 * Compact circular button showing both color and scaled stroke thickness indicator.
 */
@Composable
fun QuickPresetButton(
    color: Color,
    size: Measure,
    valueRange: ClosedFloatingPointRange<Float>,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sizeFraction = ((size.mm - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
    val visualSizeDp = (8f + (sizeFraction * 20f)).dp

    Box(
        modifier = modifier
            .size(40.dp)
            .padding(4.dp)
            .clip(CircleShape)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(visualSizeDp)
                .clip(CircleShape)
                .background(color)
        )
    }
}
