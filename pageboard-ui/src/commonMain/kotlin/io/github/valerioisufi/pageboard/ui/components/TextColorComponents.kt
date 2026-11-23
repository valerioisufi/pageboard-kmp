package io.github.valerioisufi.pageboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.ui.generated.resources.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import io.github.valerioisufi.pageboard.ui.adaptive.AdaptiveDialog
import org.jetbrains.compose.resources.stringResource

/**
 * Dialog for advanced color selection using ColorWheel: the wheel as large as the window lets it
 * be, up to 280 dp, the buttons in sight (an [AdaptiveDialog]).
 */
@Composable
internal fun TextColorPickerDialog(
    initialColor: Color,
    onDismissRequest: () -> Unit,
    onColorConfirmed: (Color) -> Unit
) {
    var workingColor by remember(initialColor) { mutableStateOf(initialColor) }
    // The room of the window less what the dialog takes around the wheel (its margins, its title,
    // the color and the buttons), so that the wheel is not scrolled out of sight in a low window
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    AdaptiveDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(Res.string.color_picker_title)) },
        text = {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val wheel = minOf(280.dp, maxWidth, windowHeight - WheelChrome).coerceAtLeast(120.dp)
                ColorWheel(
                    modifier = Modifier.size(wheel),
                    color = workingColor,
                    onColorChanged = { workingColor = it },
                    showAlphaSlider = false
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(workingColor).border(1.dp, Color.Black.copy(alpha = 0.08f), CircleShape))
                Text(
                    text = "#" + (workingColor.toArgb() and 0xFFFFFF).toString(16).uppercase().padStart(6, '0'),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismissRequest) { Text(stringResource(Res.string.common_button_cancel)) } },
        confirmButton = {
            Button(onClick = {
                onColorConfirmed(workingColor)
                onDismissRequest()
            }) {
                Text(stringResource(Res.string.common_button_apply))
            }
        }
    )
}

/** What [TextColorPickerDialog] takes in height around its wheel. */
private val WheelChrome = 300.dp
