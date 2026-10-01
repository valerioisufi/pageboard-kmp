package io.github.valerioisufi.drawview.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.ui.generated.resources.*
import org.jetbrains.compose.resources.stringResource

val DEFAULT_TEXT_COLORS = listOf(
    Color(0xFF000000), // Nero
    Color(0xFF49454F), // Grigio antracite
    Color(0xFF1A73E8), // Blu
    Color(0xFFD93025), // Rosso
    Color(0xFF188038), // Verde
    Color(0xFFE37400), // Arancione
    Color(0xFF9334E6)  // Viola
)

/**
 * Compact row for quick text color selection with active check indicator and rainbow wheel button.
 */
@Composable
fun TextColorPaletteRow(
    selectedColor: Color,
    onColorSelected: (Color) -> Unit,
    modifier: Modifier = Modifier,
    circleSize: Float = 24f,
    showCustomWheelButton: Boolean = true,
    onCustomWheelClick: () -> Unit = {}
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val selectedArgb = selectedColor.toArgb()

        for (color in DEFAULT_TEXT_COLORS) {
            val isSelected = color.toArgb() == selectedArgb
            Box(
                modifier = Modifier
                    .size(circleSize.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0x33000000),
                        shape = CircleShape
                    )
                    .clickable { onColorSelected(color) },
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    val iconTint = if (color.red * 0.299 + color.green * 0.587 + color.blue * 0.114 > 0.5) Color.Black else Color.White
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size((circleSize * 0.65f).dp)
                    )
                }
            }
        }

        if (showCustomWheelButton) {
            val rainbowBrush = remember {
                Brush.sweepGradient(
                    listOf(
                        Color.Red, Color.Yellow, Color.Green,
                        Color.Cyan, Color.Blue, Color.Magenta, Color.Red
                    )
                )
            }
            Box(
                modifier = Modifier
                    .size(circleSize.dp)
                    .clip(CircleShape)
                    .background(rainbowBrush)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape)
                    .clickable { onCustomWheelClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = stringResource(Res.string.color_picker_title),
                    tint = Color.White,
                    modifier = Modifier.size((circleSize * 0.6f).dp)
                )
            }
        }
    }
}

/**
 * Dialog for advanced color selection using ColorWheel.
 */
@Composable
fun TextColorPickerDialog(
    initialColor: Color,
    onDismissRequest: () -> Unit,
    onColorConfirmed: (Color) -> Unit
) {
    var workingColor by remember(initialColor) { mutableStateOf(initialColor) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = stringResource(Res.string.color_picker_title),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .padding(8.dp)
                ) {
                    ColorWheel(
                        color = workingColor,
                        onColorChanged = { workingColor = it },
                        showAlphaSlider = false
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = stringResource(Res.string.common_label_color) + ":", fontSize = 14.sp)
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(workingColor)
                            .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onColorConfirmed(workingColor)
                onDismissRequest()
            }) {
                Text(stringResource(Res.string.common_button_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(Res.string.common_button_cancel))
            }
        }
    )
}

/**
 * Stepper for controlling font size (- / chip with pt / +).
 */
@Composable
fun FontSizeStepper(
    fontSizePt: Float,
    onFontSizeChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onChipClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        IconButton(
            onClick = { onFontSizeChanged((fontSizePt - 2f).coerceAtLeast(8f)) },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Remove,
                contentDescription = stringResource(Res.string.draw_toolbar_action_decrease_font_size),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier
                .then(if (onChipClick != null) Modifier.clickable { onChipClick() } else Modifier)
                .padding(horizontal = 2.dp)
        ) {
            Text(
                text = "${fontSizePt.toInt()} pt",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }

        IconButton(
            onClick = { onFontSizeChanged((fontSizePt + 2f).coerceAtMost(72f)) },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(Res.string.draw_toolbar_action_increase_font_size),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
