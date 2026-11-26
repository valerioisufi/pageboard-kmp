package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.canvas.controller.TextFormatTarget
import io.github.valerioisufi.pageboard.model.text.RichTextParser
import io.github.valerioisufi.pageboard.ui.command.CommandTooltip
import io.github.valerioisufi.pageboard.ui.components.ToolPopover
import io.github.valerioisufi.pageboard.ui.generated.resources.Res
import io.github.valerioisufi.pageboard.ui.generated.resources.code_first_line
import io.github.valerioisufi.pageboard.ui.generated.resources.numbering_start_earlier
import io.github.valerioisufi.pageboard.ui.generated.resources.numbering_start_later
import io.github.valerioisufi.pageboard.ui.screen.EditorCommands
import io.github.valerioisufi.pageboard.ui.theme.PageboardIcons
import org.jetbrains.compose.resources.stringResource

/**
 * The numbers of the lines of a block of code in the bar: the icon shows them or takes them off,
 * the chevron beside it, while they are shown, opens the number of the first line (the same state
 * as the command "First line number…" of the menu).
 */
@Composable
internal fun LineNumbersButton(target: TextFormatTarget, commands: EditorCommands) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        FormatButton(commands.lineNumbers, PageboardIcons.Hash)
        if (!commands.codeFirstLine.isEnabled()) return@Row
        Box {
            CommandTooltip(commands.codeFirstLine) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(width = 22.dp, height = 36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (commands.codeFirstLineOpen) colors.primaryContainer else Color.Transparent)
                        .keepsTextFocus()
                        .clickable(role = Role.Button, onClickLabel = commands.codeFirstLine.label()) { commands.codeFirstLineOpen = true }
                ) {
                    Icon(PageboardIcons.ChevronDown, contentDescription = commands.codeFirstLine.label(), tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
            ToolPopover(expanded = commands.codeFirstLineOpen, onDismissRequest = { commands.codeFirstLineOpen = false }) {
                FirstLinePicker(target)
            }
        }
    }
}

/**
 * The number of the first line of the block of code at the caret, by one or by ten, each step
 * written at once (`startFrom="10"` after the fence) with the picker left open.
 */
@Composable
private fun FirstLinePicker(target: TextFormatTarget) {
    val first = target.codeFirstLine
    val earlier = stringResource(Res.string.numbering_start_earlier)
    val later = stringResource(Res.string.numbering_start_later)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(6.dp).width(PICKER_WIDTH)) {
        Text(
            stringResource(Res.string.code_first_line),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Chip("−10", selected = false, enabled = first > 0, description = earlier) { target.setCodeFirstLine(first - STEP) }
        Chip("−", selected = false, enabled = first > 0, description = earlier) { target.setCodeFirstLine(first - 1) }
        Text(
            first.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 6.dp).widthIn(min = 44.dp)
        )
        Chip("+", selected = false, enabled = first < RichTextParser.MAX_FIRST_LINE, description = later) { target.setCodeFirstLine(first + 1) }
        Chip("+10", selected = false, enabled = first < RichTextParser.MAX_FIRST_LINE, description = later) { target.setCodeFirstLine(first + STEP) }
    }
}

private val PICKER_WIDTH = 320.dp

/** The longer step of the picker. */
private const val STEP = 10
