package io.github.valerioisufi.drawview.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * An interactive toolbar button that expands horizontally to reveal quick preset actions.
 */
@Composable
fun ExpandableToolButton(
    modifier: Modifier = Modifier,
    isExpanded: Boolean,
    isSelected: Boolean,
    mainIcon: @Composable () -> Unit,
    expandedContent: @Composable () -> Unit
) {
    val buttonShape = RoundedCornerShape(18.dp)

    Row(
        modifier = modifier
            .clip(buttonShape)
            .border(
                width = if (isExpanded) 1.dp else 0.dp,
                color = if (isExpanded) MaterialTheme.colorScheme.outlineVariant else Color.Transparent,
                shape = buttonShape
            )
            .background(
                if (isSelected && !isExpanded) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        mainIcon()

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandHorizontally(
                animationSpec = tween(durationMillis = 300),
                expandFrom = Alignment.Start
            ) + fadeIn(animationSpec = tween(durationMillis = 300)),
            exit = shrinkHorizontally(
                animationSpec = tween(durationMillis = 250),
                shrinkTowards = Alignment.Start
            ) + fadeOut(animationSpec = tween(durationMillis = 250))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp, end = 4.dp)
            ) {
                expandedContent()
            }
        }
    }
}
