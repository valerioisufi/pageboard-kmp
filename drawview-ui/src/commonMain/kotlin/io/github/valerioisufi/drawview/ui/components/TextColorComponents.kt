package io.github.valerioisufi.drawview.ui.components

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
import io.github.valerioisufi.drawview.ui.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Dialog for advanced color selection using ColorWheel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TextColorPickerDialog(
    initialColor: Color,
    onDismissRequest: () -> Unit,
    onColorConfirmed: (Color) -> Unit
) {
    var workingColor by remember(initialColor) { mutableStateOf(initialColor) }
    BasicAlertDialog(onDismissRequest = onDismissRequest) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(Res.string.color_picker_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))
                Box(modifier = Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
                    ColorWheel(
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
                Spacer(modifier = Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onDismissRequest) { Text(stringResource(Res.string.common_button_cancel)) }
                    Button(onClick = {
                        onColorConfirmed(workingColor)
                        onDismissRequest()
                    }) {
                        Text(stringResource(Res.string.common_button_apply))
                    }
                }
            }
        }
    }
}
