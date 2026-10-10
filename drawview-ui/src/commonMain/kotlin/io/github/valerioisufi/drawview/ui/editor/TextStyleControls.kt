package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.core.controller.TextFormatTarget
import io.github.valerioisufi.drawview.model.TextAlignment
import io.github.valerioisufi.drawview.ui.command.Command
import io.github.valerioisufi.drawview.ui.command.CommandTooltip
import io.github.valerioisufi.drawview.ui.components.ColorSwatches
import io.github.valerioisufi.drawview.ui.components.FontSizeControl
import io.github.valerioisufi.drawview.ui.components.TextColorPickerDialog
import io.github.valerioisufi.drawview.ui.components.ToolPopover
import io.github.valerioisufi.drawview.ui.generated.resources.Res
import io.github.valerioisufi.drawview.ui.generated.resources.draw_toolbar_label_text_color
import io.github.valerioisufi.drawview.ui.generated.resources.text_align_center
import io.github.valerioisufi.drawview.ui.generated.resources.text_align_end
import io.github.valerioisufi.drawview.ui.generated.resources.text_align_start
import io.github.valerioisufi.drawview.ui.screen.EditorCommands
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import io.github.valerioisufi.drawview.ui.theme.InkPalette
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The style of the texts of [target] (the text being written, the texts selected, the defaults of
 * the tool): size, color, alignment. The same controls in the formatting bar of the editor and in
 * the bar of the selection.
 *
 * @param commands the commands of the editor, whose tooltips the buttons show (none for the
 *   defaults of the tool, which the commands do not change).
 */
@Composable
internal fun RowScope.TextStyleControls(target: TextFormatTarget, commands: EditorCommands?) {
    FontSizeControl(fontSizePt = target.fontSize, onFontSizeChange = target::changeFontSize)
    TextColorButton(target)
    TextAlignmentButtons(target, commands)
}

/** The color of the texts of [target]: a swatch opening the palette and the color wheel. */
@Composable
internal fun TextColorButton(target: TextFormatTarget) {
    var paletteOpen by remember { mutableStateOf(false) }
    var wheelOpen by remember { mutableStateOf(false) }
    val color = Color(target.color)
    val label = stringResource(Res.string.draw_toolbar_label_text_color)
    Box {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = label) { paletteOpen = true }
                .semantics { contentDescription = label }
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(28.dp).border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            ) {
                Box(modifier = Modifier.size(20.dp).clip(CircleShape).background(color))
            }
        }
        ToolPopover(expanded = paletteOpen, onDismissRequest = { paletteOpen = false }) {
            ColorSwatches(
                colors = InkPalette.inks,
                selected = color,
                onSelect = {
                    target.changeColor(it.toArgb())
                    paletteOpen = false
                },
                onCustom = {
                    paletteOpen = false
                    wheelOpen = true
                }
            )
        }
    }
    if (wheelOpen) {
        TextColorPickerDialog(
            initialColor = color,
            onDismissRequest = { wheelOpen = false },
            onColorConfirmed = { target.changeColor(it.toArgb()) }
        )
    }
}

/** Left, center, right: the alignment of the lines of the texts of [target]. */
@Composable
internal fun TextAlignmentButtons(target: TextFormatTarget, commands: EditorCommands?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        for ((alignment, button) in alignmentButtons(commands)) {
            FormatToggle(
                command = button.command,
                checked = target.alignment == alignment,
                icon = button.icon,
                description = button.description,
                onClick = { target.changeAlignment(alignment) }
            )
        }
    }
}

private class AlignmentButton(val command: Command?, val icon: ImageVector, val description: StringResource)

private fun alignmentButtons(commands: EditorCommands?) = listOf(
    TextAlignment.START to AlignmentButton(commands?.alignStart, DrawViewIcons.AlignLeft, Res.string.text_align_start),
    TextAlignment.CENTER to AlignmentButton(commands?.alignCenter, DrawViewIcons.AlignCenter, Res.string.text_align_center),
    TextAlignment.END to AlignmentButton(commands?.alignEnd, DrawViewIcons.AlignRight, Res.string.text_align_end)
)

/** A button of a format that is on or off, with the tooltip of [command] when given. */
@Composable
internal fun FormatToggle(command: Command?, checked: Boolean, icon: ImageVector, description: StringResource, onClick: () -> Unit) {
    val button = @Composable {
        val colors = MaterialTheme.colorScheme
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (checked) colors.primaryContainer else Color.Transparent)
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onClick() })
        ) {
            Icon(icon, contentDescription = stringResource(description), tint = if (checked) colors.onPrimaryContainer else colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
    if (command == null) button() else CommandTooltip(command) { button() }
}

/**
 * A button running [command] (its icon, its label and its tooltip), as a format toggle: 36 dp
 * rounded 10, the primary container while the command is on.
 */
@Composable
internal fun FormatButton(command: Command, icon: ImageVector) {
    val colors = MaterialTheme.colorScheme
    val checked = command.isChecked?.invoke() == true
    val enabled = command.isEnabled()
    CommandTooltip(command) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (checked) colors.primaryContainer else Color.Transparent)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = command.label()) { command.performIfEnabled() }
        ) {
            Icon(
                icon,
                contentDescription = command.label(),
                tint = when {
                    !enabled -> colors.onSurfaceVariant.copy(alpha = 0.38f)
                    checked -> colors.onPrimaryContainer
                    else -> colors.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
