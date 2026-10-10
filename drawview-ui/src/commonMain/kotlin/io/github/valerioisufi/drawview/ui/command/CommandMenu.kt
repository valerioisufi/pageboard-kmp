package io.github.valerioisufi.drawview.ui.command

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpOffset
import io.github.valerioisufi.drawview.ui.adaptive.currentAdaptiveInfo
import kotlin.jvm.JvmName

/**
 * A menu of [commands] (a context menu): their icon, label and shortcut, the enabled ones
 * clickable, the states checked; the sections of the commands separated. A command chosen closes
 * the menu.
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
    val sections = commands.fold(mutableListOf<MutableList<Command>>()) { sections, command ->
        if (sections.lastOrNull()?.last()?.section != command.section) sections += mutableListOf<Command>()
        sections.last() += command
        sections
    }
    CommandMenu(sections, expanded, onDismissRequest, modifier, offset)
}

/**
 * A menu of the commands of [sections], separated (e.g. the "more" menu of a bar, gathering
 * commands of several menus). The shortcuts are shown where there is a keyboard.
 */
@Composable
@JvmName("CommandMenuOfSections")
fun CommandMenu(
    sections: List<List<Command>>,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero
) {
    val adaptive = currentAdaptiveInfo()
    val conventions = adaptive.conventions.takeIf { adaptive.input.hasKeyboard }
    DesignMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier, offset = offset) {
        sections.filter { it.isNotEmpty() }.forEachIndexed { index, section ->
            if (index > 0) MenuDivider()
            for (command in section) {
                val checked = command.isChecked?.invoke()
                MenuItem(
                    text = command.label(),
                    onClick = {
                        onDismissRequest()
                        command.performIfEnabled()
                    },
                    enabled = command.isEnabled(),
                    icon = when {
                        command.icon != null -> command.icon.invoke()
                        checked == true -> rememberVectorPainter(DrawViewIcons.Check)
                        else -> null
                    },
                    shortcut = conventions?.let { command.shortcutLabel(it) },
                    destructive = command.destructive,
                    checked = checked == true && command.icon != null
                )
            }
        }
    }
}

/**
 * A menu of the design: the container of the surfaces rounded 16 dp, its items 40 dp high and
 * rounded 12 dp ([MenuItem]), its sections apart ([MenuDivider]).
 */
@Composable
fun DesignMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.padding(horizontal = 8.dp),
        offset = offset,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 8.dp,
        content = content
    )
}

/**
 * An item of a [DesignMenu]: its [icon] (a check for a state that is on), its [text], its
 * [shortcut]; [selected] in the primary container, [destructive] in the color of the errors,
 * [checked] (a state that is on, beside its icon) with a check at the end. [alignWithIcons] keeps
 * the place of an icon when it has none (beside items with one).
 */
@Composable
fun MenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: Painter? = null,
    shortcut: String? = null,
    selected: Boolean = false,
    destructive: Boolean = false,
    alignWithIcons: Boolean = false,
    checked: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    val content = when {
        destructive -> colors.error
        selected -> colors.onPrimaryContainer
        else -> colors.onSurface
    }.let { if (enabled) it else it.copy(alpha = 0.38f) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(min = 200.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.primaryContainer else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (destructive || selected) content else colors.onSurfaceVariant.copy(alpha = content.alpha), modifier = Modifier.size(20.dp))
        } else if (alignWithIcons) {
            Spacer(modifier = Modifier.size(20.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1, modifier = Modifier.weight(1f))
        if (shortcut != null) Text(shortcut, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        if (checked) Icon(DrawViewIcons.Check, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
    }
}

/** The line between two sections of a [DesignMenu]. */
@Composable
fun MenuDivider() {
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** The label of a group of items of a [DesignMenu] (e.g. "Sort by"). */
@Composable
fun MenuLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
    )
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
