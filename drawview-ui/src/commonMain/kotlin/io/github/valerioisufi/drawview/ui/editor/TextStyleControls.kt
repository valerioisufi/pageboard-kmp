package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.core.controller.TextFormatTarget
import io.github.valerioisufi.drawview.core.model.TextAlignment
import io.github.valerioisufi.drawview.ui.command.Command
import io.github.valerioisufi.drawview.ui.command.CommandTooltip
import io.github.valerioisufi.drawview.ui.components.FontSizeControl
import io.github.valerioisufi.drawview.ui.components.TextColorPaletteRow
import io.github.valerioisufi.drawview.ui.components.TextColorPickerDialog
import io.github.valerioisufi.drawview.ui.generated.resources.Res
import io.github.valerioisufi.drawview.ui.generated.resources.draw_toolbar_label_text_color
import io.github.valerioisufi.drawview.ui.generated.resources.text_align_center
import io.github.valerioisufi.drawview.ui.generated.resources.text_align_end
import io.github.valerioisufi.drawview.ui.generated.resources.text_align_start
import io.github.valerioisufi.drawview.ui.screen.EditorCommands
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
        IconButton(onClick = { paletteOpen = true }, modifier = Modifier.semantics { contentDescription = label }) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(2.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape)
            )
        }
        DropdownMenu(expanded = paletteOpen, onDismissRequest = { paletteOpen = false }, shape = RoundedCornerShape(12.dp)) {
            TextColorPaletteRow(
                selectedColor = color,
                onColorSelected = {
                    target.changeColor(it.toArgb())
                    paletteOpen = false
                },
                modifier = Modifier.padding(8.dp),
                onCustomWheelClick = {
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
    TextAlignment.START to AlignmentButton(commands?.alignStart, Icons.AutoMirrored.Filled.FormatAlignLeft, Res.string.text_align_start),
    TextAlignment.CENTER to AlignmentButton(commands?.alignCenter, Icons.Default.FormatAlignCenter, Res.string.text_align_center),
    TextAlignment.END to AlignmentButton(commands?.alignEnd, Icons.AutoMirrored.Filled.FormatAlignRight, Res.string.text_align_end)
)

/** A button of a format that is on or off, with the tooltip of [command] when given. */
@Composable
internal fun FormatToggle(command: Command?, checked: Boolean, icon: ImageVector, description: StringResource, onClick: () -> Unit) {
    val button = @Composable {
        IconToggleButton(checked = checked, onCheckedChange = { onClick() }) {
            Icon(icon, contentDescription = stringResource(description))
        }
    }
    if (command == null) button() else CommandTooltip(command) { button() }
}

/** A button running [command] (its icon, its label and its tooltip). */
@Composable
internal fun FormatButton(command: Command, icon: ImageVector) {
    CommandTooltip(command) {
        IconButton(onClick = { command.performIfEnabled() }, enabled = command.isEnabled()) {
            Icon(icon, contentDescription = command.label())
        }
    }
}
