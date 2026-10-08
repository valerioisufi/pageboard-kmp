package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.core.controller.TextEditorManager
import io.github.valerioisufi.drawview.core.model.TextAlignment
import io.github.valerioisufi.drawview.core.text.RichTextEditing.Format
import io.github.valerioisufi.drawview.ui.components.FontSizeStepper
import io.github.valerioisufi.drawview.ui.components.TextColorPaletteRow
import io.github.valerioisufi.drawview.ui.components.TextColorPickerDialog
import io.github.valerioisufi.drawview.ui.generated.resources.Res
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_action_bold
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_action_code
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_action_heading
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_action_italic
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_action_math
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_action_save
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_action_strikethrough
import org.jetbrains.compose.resources.stringResource

/**
 * The formatting bar of the text being written: size, color, bold, italic, strikethrough, code,
 * heading, alignment, formula, and the button closing the editor.
 */
@Composable
internal fun TextFormatBar(editor: TextEditorManager, modifier: Modifier = Modifier) {
    var showColorPickerRow by remember { mutableStateOf(false) }
    var showColorWheelDialog by remember { mutableStateOf(false) }
    var showFontSizeSliderRow by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    val textColor = Color(editor.color)

    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            // Size, color and formatting
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                FontSizeStepper(
                    fontSizePt = editor.fontSize,
                    onFontSizeChanged = editor::changeFontSize,
                    onChipClick = {
                        showFontSizeSliderRow = !showFontSizeSliderRow
                        showColorPickerRow = false
                    }
                )

                // Text color
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(textColor)
                        .border(
                            width = 2.dp,
                            color = if (showColorPickerRow) primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                            shape = CircleShape
                        )
                        .clickable {
                            showColorPickerRow = !showColorPickerRow
                            showFontSizeSliderRow = false
                        }
                )

                VerticalDivider(modifier = Modifier.height(20.dp).padding(horizontal = 2.dp), thickness = 1.dp)

                IconButton(onClick = { editor.toggle(Format.BOLD) }) {
                    Icon(Icons.Default.FormatBold, contentDescription = stringResource(Res.string.text_editor_action_bold))
                }
                IconButton(onClick = { editor.toggle(Format.ITALIC) }) {
                    Icon(Icons.Default.FormatItalic, contentDescription = stringResource(Res.string.text_editor_action_italic))
                }
                IconButton(onClick = { editor.toggle(Format.STRIKETHROUGH) }) {
                    Icon(Icons.Default.FormatStrikethrough, contentDescription = stringResource(Res.string.text_editor_action_strikethrough))
                }
                IconButton(onClick = { editor.toggle(Format.CODE) }) {
                    Icon(Icons.Default.Code, contentDescription = stringResource(Res.string.text_editor_action_code))
                }
                IconButton(onClick = { editor.toggleHeading() }) {
                    Icon(Icons.Default.Title, contentDescription = stringResource(Res.string.text_editor_action_heading))
                }

                // Alignment (start, center, end)
                TextButton(onClick = { editor.changeAlignment(TextAlignment.entries[(editor.alignment.ordinal + 1) % TextAlignment.entries.size]) }) {
                    Text(
                        text = when (editor.alignment) {
                            TextAlignment.CENTER -> "≡"
                            TextAlignment.END -> "⇥"
                            TextAlignment.START -> "⇤"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = primary
                    )
                }

                // A formula in the line: $…$ around the selection
                IconButton(onClick = { editor.toggle(Format.FORMULA) }) {
                    Icon(Icons.Default.Functions, contentDescription = stringResource(Res.string.text_editor_action_math))
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Save & close
                IconButton(onClick = { editor.finishEditing() }) {
                    Icon(Icons.Default.Check, contentDescription = stringResource(Res.string.text_editor_action_save), tint = primary)
                }
            }

            // Quick color palette
            AnimatedVisibility(visible = showColorPickerRow) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    TextColorPaletteRow(
                        selectedColor = textColor,
                        onColorSelected = {
                            editor.changeColor(it.toArgb())
                            showColorPickerRow = false
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        onCustomWheelClick = {
                            showColorPickerRow = false
                            showColorWheelDialog = true
                        }
                    )
                }
            }

            // Continuous font size slider
            AnimatedVisibility(visible = showFontSizeSliderRow) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                        Text(
                            text = "${editor.fontSize.toInt()} pt",
                            style = MaterialTheme.typography.labelMedium,
                            color = primary,
                            modifier = Modifier.width(44.dp)
                        )
                        Slider(
                            value = editor.fontSize,
                            onValueChange = editor::changeFontSize,
                            valueRange = 10f..60f,
                            steps = 24,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    if (showColorWheelDialog) {
        TextColorPickerDialog(
            initialColor = textColor,
            onDismissRequest = { showColorWheelDialog = false },
            onColorConfirmed = { editor.changeColor(it.toArgb()) }
        )
    }
}
