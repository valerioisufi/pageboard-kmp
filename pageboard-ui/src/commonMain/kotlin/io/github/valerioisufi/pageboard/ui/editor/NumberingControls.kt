package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.canvas.controller.TextFormatTarget
import io.github.valerioisufi.pageboard.model.text.ListMarker
import io.github.valerioisufi.pageboard.model.text.ListNumbering
import io.github.valerioisufi.pageboard.model.text.ListNumbering.Numbering
import io.github.valerioisufi.pageboard.model.text.NumberingStyle
import io.github.valerioisufi.pageboard.ui.components.ToolPopover
import io.github.valerioisufi.pageboard.ui.generated.resources.Res
import io.github.valerioisufi.pageboard.ui.generated.resources.numbering_automatic
import io.github.valerioisufi.pageboard.ui.generated.resources.numbering_decimal
import io.github.valerioisufi.pageboard.ui.generated.resources.numbering_delimiter
import io.github.valerioisufi.pageboard.ui.generated.resources.numbering_lower_alpha
import io.github.valerioisufi.pageboard.ui.generated.resources.numbering_lower_roman
import io.github.valerioisufi.pageboard.ui.generated.resources.numbering_start
import io.github.valerioisufi.pageboard.ui.generated.resources.numbering_start_earlier
import io.github.valerioisufi.pageboard.ui.generated.resources.numbering_start_later
import io.github.valerioisufi.pageboard.ui.generated.resources.numbering_upper_alpha
import io.github.valerioisufi.pageboard.ui.generated.resources.numbering_upper_roman
import io.github.valerioisufi.pageboard.ui.command.CommandTooltip
import io.github.valerioisufi.pageboard.ui.screen.EditorCommands
import io.github.valerioisufi.pageboard.ui.theme.PageboardIcons
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The numbered list of the bar: the icon makes the lines a list or takes them out of it, the
 * chevron beside it opens the styles (the same state as the command "Numbering…" of the menu).
 */
@Composable
internal fun NumberedListButton(target: TextFormatTarget, commands: EditorCommands) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        FormatButton(commands.numberedList, PageboardIcons.ListOrdered)
        Box {
            CommandTooltip(commands.numbering) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(width = 22.dp, height = 36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (commands.numberingOpen) colors.primaryContainer else Color.Transparent)
                        .keepsTextFocus()
                        .clickable(enabled = commands.numbering.isEnabled(), role = Role.Button, onClickLabel = commands.numbering.label()) { commands.numberingOpen = true }
                ) {
                    Icon(PageboardIcons.ChevronDown, contentDescription = commands.numbering.label(), tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
            ToolPopover(expanded = commands.numberingOpen, onDismissRequest = { commands.numberingOpen = false }) {
                NumberingPicker(target)
            }
        }
    }
}

/**
 * The styles of the list at the caret: each a preview of how it counts, the current one marked, a
 * choice applied at once with the picker left open to try another. A choice with no list at the
 * caret makes one.
 */
@Composable
private fun NumberingPicker(target: TextFormatTarget) {
    val style = target.numberingStyle
    val start = target.numberingStart
    val delimiter = style?.delimiter ?: '.'
    Column(modifier = Modifier.padding(6.dp).width(PICKER_WIDTH), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AutomaticRow(selected = target.inNumberedList && style == null) { target.setNumbering(null, startFor(null, start)) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((numbering, name) in STYLES) {
                val tile = NumberingStyle(numbering, delimiter)
                StyleTile(tile, name, selected = style == tile) { target.setNumbering(tile, startFor(tile, start)) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.numbering_delimiter), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            for (choice in NumberingStyle.DELIMITERS) {
                val chosen = NumberingStyle(style?.numbering ?: Numbering.DECIMAL, choice)
                Chip("1$choice", selected = style != null && delimiter == choice) { target.setNumbering(chosen, startFor(chosen, start)) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.numbering_start), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            val earlier = nextStart(style, start, -1)
            val later = nextStart(style, start, 1)
            Chip("−", selected = false, enabled = earlier != null, description = stringResource(Res.string.numbering_start_earlier)) { earlier?.let { target.setNumbering(style, it) } }
            Text(
                ListNumbering.format(start, style?.numbering ?: Numbering.DECIMAL),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 10.dp).widthIn(min = 28.dp)
            )
            Chip("+", selected = false, enabled = later != null, description = stringResource(Res.string.numbering_start_later)) { later?.let { target.setNumbering(style, it) } }
        }
    }
}

/** The list counting by its level: `1.`, `a.`, `i.`, as a list with no style is. */
@Composable
private fun AutomaticRow(selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(TILE_SHAPE)
            .background(if (selected) colors.primaryContainer else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp)
    ) {
        Text(stringResource(Res.string.numbering_automatic), style = MaterialTheme.typography.bodyMedium, color = if (selected) colors.onPrimaryContainer else colors.onSurface, modifier = Modifier.weight(1f))
        Text("1. a. i.", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant)
    }
}

/** A style, as three lines of a list counting in it: written by [ListNumbering], as the page writes them. */
@Composable
private fun RowScope.StyleTile(style: NumberingStyle, name: StringResource, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(name)
    Column(
        modifier = Modifier
            .weight(1f)
            .height(72.dp)
            .clip(TILE_SHAPE)
            .background(if (selected) colors.primaryContainer else colors.surface)
            .border(1.dp, if (selected) colors.primary else colors.outlineVariant, TILE_SHAPE)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        for (number in 1..3) {
            Text(
                ListNumbering.format(number, style.numbering) + style.delimiter,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = if (selected) colors.onPrimaryContainer else colors.onSurface
            )
        }
    }
}

/** A small button of the picker, on its own or as a choice. */
@Composable
private fun Chip(text: String, selected: Boolean, enabled: Boolean = true, description: String? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(start = 4.dp)
            .minimumInteractiveComponentSize()
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.primaryContainer else colors.surface)
            .border(1.dp, if (selected) colors.primary else colors.outlineVariant, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (description != null) contentDescription = description }
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace,
            color = if (!enabled) colors.onSurfaceVariant.copy(alpha = 0.38f) else if (selected) colors.onPrimaryContainer else colors.onSurface
        )
    }
}

/** The start [style] takes from a list starting from [start]: the same when it can be written, else 1. */
private fun startFor(style: NumberingStyle?, start: Int): Int = if (ListMarker.canStart(style, start)) start else 1

/** The next number a list in [style] can start from after [start], going by [step]; `null` past the first. */
private fun nextStart(style: NumberingStyle?, start: Int, step: Int): Int? {
    var number = start + step
    while (number in 1..MOST_START) {
        if (ListMarker.canStart(style, number)) return number
        number += step
    }
    return null
}

/** The styles of the tiles, with their names. */
private val STYLES = listOf(
    Numbering.DECIMAL to Res.string.numbering_decimal,
    Numbering.LOWER_ALPHA to Res.string.numbering_lower_alpha,
    Numbering.UPPER_ALPHA to Res.string.numbering_upper_alpha,
    Numbering.LOWER_ROMAN to Res.string.numbering_lower_roman,
    Numbering.UPPER_ROMAN to Res.string.numbering_upper_roman
)

private val TILE_SHAPE = RoundedCornerShape(10.dp)

/** The width of the picker: five tiles with `iii.` in them, on the narrowest phone. */
private val PICKER_WIDTH = 288.dp

/** The largest number the stepper of the picker reaches. */
private const val MOST_START = 999
