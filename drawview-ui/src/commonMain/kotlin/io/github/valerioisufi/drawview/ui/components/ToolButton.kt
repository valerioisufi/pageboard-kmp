package io.github.valerioisufi.drawview.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.ui.command.Command
import io.github.valerioisufi.drawview.ui.command.CommandTooltip

/** The shape of a tool button. */
private val ToolShape = RoundedCornerShape(16.dp)

/**
 * A button of the tools of the editor: 44 dp, rounded 16 dp, the primary container when [selected],
 * the dot of its [ink] (the color the tool draws with). It supports a long click and the popover
 * of its settings ([dropDownMenu], shown while [expanded]); it takes the minimum interactive size
 * of the input around it (48 dp for a finger, see `AdaptiveRoot`).
 *
 * @param command the command the button shows: its label and shortcut in a tooltip where a
 *   pointer hovers. The click is [onClick] (a tool opens its settings when chosen again).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToolButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit) = {},
    selected: Boolean = false,
    enabled: Boolean = true,
    dropDownMenu: @Composable ColumnScope.() -> Unit = {},
    expanded: Boolean = false,
    onDismissRequest: () -> Unit = {},
    command: Command? = null,
    ink: Color? = null,
    content: @Composable RowScope.() -> Unit = {}
) {
    Box {
        val colors = MaterialTheme.colorScheme
        val button = @Composable {
            Box(
                contentAlignment = Alignment.Center,
                modifier = modifier
                    .minimumInteractiveComponentSize()
                    .size(44.dp)
                    .clip(ToolShape)
                    .background(if (selected) colors.primaryContainer else Color.Transparent)
                    .combinedClickable(
                        onClick = { onClick() },
                        onLongClick = { onLongClick() },
                        enabled = enabled,
                        role = Role.Button,
                    )
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides when {
                        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.38f)
                        selected -> colors.onPrimaryContainer
                        else -> colors.onSurfaceVariant
                    }
                ) {
                    Row(modifier = Modifier.size(24.dp), verticalAlignment = Alignment.CenterVertically) { content() }
                }
                if (ink != null && selected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-6).dp, y = 6.dp)
                            .size(8.dp)
                            .background(ink, CircleShape)
                            .border(1.5.dp, colors.primaryContainer, CircleShape)
                    )
                }
            }
        }
        if (command != null) CommandTooltip(command) { button() } else button()
        ToolPopover(expanded = expanded, onDismissRequest = onDismissRequest, content = dropDownMenu)
    }
}

/**
 * The popover of the settings of a tool, under its button: 300 dp wide, the high container rounded
 * 24 dp, the content with its margins.
 */
@Composable
fun ToolPopover(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    offset: DpOffset = DpOffset(0.dp, 6.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenu(
        modifier = Modifier.width(300.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = offset,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
        content = content
    )
}
