package io.github.valerioisufi.drawview.ui.command

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpOffset
import io.github.valerioisufi.drawview.ui.adaptive.currentAdaptiveInfo

/**
 * A menu of [commands] (a context menu): their icon, label and shortcut, the enabled ones
 * clickable, the states checked; the sections separated. A command chosen closes the menu.
 *
 * @param offset where the menu opens, from the bottom left corner of its anchor.
 */
@Composable
fun CommandMenu(
    commands: List<Command>,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero
) {
    val conventions = currentAdaptiveInfo().conventions
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier, offset = offset) {
        commands.forEachIndexed { index, command ->
            if (index > 0 && commands[index - 1].section != command.section) HorizontalDivider()
            val checked = command.isChecked?.invoke()
            DropdownMenuItem(
                text = { Text(command.label()) },
                onClick = {
                    onDismissRequest()
                    command.performIfEnabled()
                },
                enabled = command.isEnabled(),
                leadingIcon = when {
                    checked == true -> ({ Icon(Icons.Default.Check, contentDescription = null) })
                    command.icon != null -> ({ Icon(command.icon.invoke(), contentDescription = null) })
                    else -> null
                },
                trailingIcon = command.shortcutLabel(conventions)?.let { shortcut ->
                    { Text(shortcut, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            )
        }
    }
}

/**
 * Calls [onClick] with the position of a click of the secondary button of a mouse (the right
 * button, a two-finger click of a trackpad): where the context menus of the pointers open.
 */
fun Modifier.onSecondaryClick(onClick: (Offset) -> Unit): Modifier = pointerInput(onClick) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                val change = event.changes.firstOrNull() ?: continue
                change.consume()
                onClick(change.position)
            }
        }
    }
}
