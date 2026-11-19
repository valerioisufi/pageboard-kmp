package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.ui.adaptive.currentAdaptiveInfo
import io.github.valerioisufi.drawview.ui.command.Command
import io.github.valerioisufi.drawview.ui.command.CommandTooltip
import io.github.valerioisufi.drawview.ui.components.ToolPopover
import io.github.valerioisufi.drawview.ui.screen.EditorCommands
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons

/**
 * The heading of the bar: the icon makes the line of the caret a heading of level 1 or text again,
 * the chevron beside it opens the levels (the same state as the command "Heading level" of the menu).
 */
@Composable
internal fun HeadingButton(commands: EditorCommands) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        FormatButton(commands.heading, DrawViewIcons.Heading)
        Box {
            CommandTooltip(commands.headingLevels) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(width = 22.dp, height = 36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (commands.headingLevelsOpen) colors.primaryContainer else Color.Transparent)
                        .keepsTextFocus()
                        .clickable(enabled = commands.headingLevels.isEnabled(), role = Role.Button, onClickLabel = commands.headingLevels.label()) {
                            commands.headingLevelsOpen = true
                        }
                ) {
                    Icon(DrawViewIcons.ChevronDown, contentDescription = commands.headingLevels.label(), tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
            ToolPopover(expanded = commands.headingLevelsOpen, onDismissRequest = { commands.headingLevelsOpen = false }) {
                HeadingLevelPicker(commands)
            }
        }
    }
}

/**
 * The levels of a line: normal text and the headings 1 to 3, each written at its size, the current
 * one marked; a choice applies at once and closes the picker.
 */
@Composable
private fun HeadingLevelPicker(commands: EditorCommands) {
    Column(modifier = Modifier.padding(6.dp).width(PICKER_WIDTH)) {
        LevelRow(commands, commands.normalText, 0)
        LevelRow(commands, commands.heading, 1)
        LevelRow(commands, commands.heading2, 2)
        LevelRow(commands, commands.heading3, 3)
    }
}

/** A level of the picker: its name at the size of its text, its shortcut on a keyboard. */
@Composable
private fun LevelRow(commands: EditorCommands, command: Command, level: Int) {
    val colors = MaterialTheme.colorScheme
    val selected = command.isChecked?.invoke() == true
    val base = MaterialTheme.typography.bodyLarge
    val style = if (level == 0) base else base.copy(fontSize = base.fontSize * MarkdownTextUtil.headingScale(level), fontWeight = FontWeight.Bold)
    val info = currentAdaptiveInfo()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .keepsTextFocus()
            .selectable(selected = selected, role = Role.RadioButton) {
                command.performIfEnabled()
                commands.headingLevelsOpen = false
            }
            .padding(horizontal = 12.dp)
    ) {
        Text(command.label(), style = style, color = colors.onSurface, maxLines = 1, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        if (info.input.hasKeyboard) {
            command.shortcutLabel(info.conventions)?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** The width of the picker of the levels. */
private val PICKER_WIDTH = 220.dp
