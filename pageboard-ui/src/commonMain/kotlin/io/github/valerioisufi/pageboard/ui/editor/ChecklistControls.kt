package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.canvas.controller.TextFormatTarget
import io.github.valerioisufi.pageboard.ui.command.Command
import io.github.valerioisufi.pageboard.ui.command.CommandTooltip
import io.github.valerioisufi.pageboard.ui.components.SwitchRow
import io.github.valerioisufi.pageboard.ui.components.ToolPopover
import io.github.valerioisufi.pageboard.ui.generated.resources.Res
import io.github.valerioisufi.pageboard.ui.generated.resources.checklist_keep_completed_below
import io.github.valerioisufi.pageboard.ui.generated.resources.checklist_keep_completed_below_hint
import io.github.valerioisufi.pageboard.ui.generated.resources.checklist_progress
import io.github.valerioisufi.pageboard.ui.screen.EditorCommands
import io.github.valerioisufi.pageboard.ui.theme.PageboardIcons
import org.jetbrains.compose.resources.stringResource

/**
 * The checklist of the bar: the icon makes the lines a checklist or takes them out of it; in a
 * checklist, how many of its items are done (`2/5`); the chevron beside it opens its options (the
 * same state as the command "Checklist options…" of the menu).
 */
@Composable
internal fun ChecklistButton(target: TextFormatTarget, commands: EditorCommands) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        FormatButton(commands.checklist, PageboardIcons.ListChecks)
        val (done, total) = target.checklistProgress
        if (target.inChecklist && total > 0) {
            val description = stringResource(Res.string.checklist_progress, done, total)
            Text(
                "$done/$total",
                style = MaterialTheme.typography.labelSmall,
                color = if (done == total) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp).semantics { contentDescription = description }
            )
        }
        Box {
            CommandTooltip(commands.checklistOptions) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(width = 22.dp, height = 36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (commands.checklistOptionsOpen) colors.primaryContainer else Color.Transparent)
                        .keepsTextFocus()
                        .clickable(
                            enabled = commands.checklistOptions.isEnabled(),
                            role = Role.Button,
                            onClickLabel = commands.checklistOptions.label()
                        ) { commands.checklistOptionsOpen = true }
                ) {
                    Icon(PageboardIcons.ChevronDown, contentDescription = commands.checklistOptions.label(), tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
            ToolPopover(expanded = commands.checklistOptionsOpen, onDismissRequest = { commands.checklistOptionsOpen = false }) {
                ChecklistOptions(target, commands)
            }
        }
    }
}

/**
 * The options of the checklist at the caret: numbered or not, its items done moved below the
 * others now, and whether they go there each time one is checked. Applied at once, the popover left
 * open.
 */
@Composable
private fun ChecklistOptions(target: TextFormatTarget, commands: EditorCommands) {
    Column(modifier = Modifier.padding(6.dp).width(OPTIONS_WIDTH), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OptionRow(commands.numberedChecklist, PageboardIcons.ListOrdered)
        OptionRow(commands.moveCompletedChecklists, PageboardIcons.ArrowDownToLine)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))
        SwitchRow(
            text = stringResource(Res.string.checklist_keep_completed_below),
            hint = stringResource(Res.string.checklist_keep_completed_below_hint),
            checked = target.moveCompletedToBottom,
            onCheckedChange = target::setMoveCompletedToBottom,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
    }
}

/** A command of the options as a row: its icon and its name, marked when it is checked. */
@Composable
private fun OptionRow(command: Command, icon: ImageVector) {
    val colors = MaterialTheme.colorScheme
    val enabled = command.isEnabled()
    val checked = command.isChecked?.invoke() == true
    val content = when {
        !enabled -> colors.onSurface.copy(alpha = 0.38f)
        checked -> colors.onPrimaryContainer
        else -> colors.onSurface
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (checked) colors.primaryContainer else Color.Transparent)
            .keepsTextFocus()
            .clickable(enabled = enabled, role = Role.Button) { command.performIfEnabled() }
            .padding(horizontal = 10.dp, vertical = 12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        Text(command.label(), style = MaterialTheme.typography.bodyMedium, color = content)
    }
}

/** The width of the options: their longest name on one line, on the narrowest phone. */
private val OPTIONS_WIDTH = 288.dp
