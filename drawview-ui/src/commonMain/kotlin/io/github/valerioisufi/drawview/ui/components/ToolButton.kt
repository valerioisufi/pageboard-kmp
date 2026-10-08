package io.github.valerioisufi.drawview.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.ui.command.Command
import io.github.valerioisufi.drawview.ui.command.CommandTooltip

/**
 * Specialized button for the drawing toolbar supporting click, long-click, and flyout menu.
 * It is drawn 36 dp wide and takes the minimum interactive size of the input around it (48 dp
 * for a finger, 40 dp for a precise pointer, see `AdaptiveRoot`).
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
    dropDownMenu: @Composable () -> Unit = {},
    expanded: Boolean = false,
    onDismissRequest: () -> Unit = {},
    command: Command? = null,
    content: @Composable RowScope.() -> Unit = {}
) {
    Box {
        val selectedModifier = if (selected) {
            modifier.background(MaterialTheme.colorScheme.primaryContainer)
        } else {
            modifier
        }
        val button = @Composable {
            Row(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(36.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = { onClick() },
                        onLongClick = { onLongClick() },
                        enabled = enabled,
                        role = Role.Button,
                    )
                    .then(selectedModifier)
                    .padding(8.dp),
            ) {
                content()
            }
        }
        if (command != null) CommandTooltip(command) { button() } else button()
        DropdownMenu(
            modifier = Modifier.width(300.dp),
            expanded = expanded,
            onDismissRequest = { onDismissRequest() },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp
        ) {
            dropDownMenu()
        }
    }
}
