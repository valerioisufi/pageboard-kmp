package io.github.valerioisufi.drawview.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.core.text.TextSizes
import io.github.valerioisufi.drawview.ui.generated.resources.Res
import io.github.valerioisufi.drawview.ui.generated.resources.draw_toolbar_action_decrease_font_size
import io.github.valerioisufi.drawview.ui.generated.resources.draw_toolbar_action_increase_font_size
import io.github.valerioisufi.drawview.ui.generated.resources.draw_toolbar_label_font_size
import io.github.valerioisufi.drawview.ui.generated.resources.text_font_size_points
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The size of a text: − and + step through the sizes of [TextSizes.PRESETS]; the value opens them
 * all and a field where any size from [TextSizes.MIN] to [TextSizes.MAX] can be typed.
 */
@Composable
fun FontSizeControl(
    fontSizePt: Float,
    onFontSizeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        IconButton(
            onClick = { onFontSizeChange(TextSizes.smaller(fontSizePt)) },
            enabled = fontSizePt > TextSizes.MIN,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(Res.string.draw_toolbar_action_decrease_font_size), modifier = Modifier.size(16.dp))
        }

        Box {
            val label = stringResource(Res.string.draw_toolbar_label_font_size)
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.clickable(onClickLabel = label) { menuOpen = true }
            ) {
                Text(
                    text = "${formatSize(fontSizePt)} pt",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                var typed by remember(fontSizePt) { mutableStateOf(formatSize(fontSizePt)) }
                fun apply() {
                    typed.replace(',', '.').toFloatOrNull()?.let { onFontSizeChange(TextSizes.clamp(it)) }
                    menuOpen = false
                }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(5) },
                    label = { Text(stringResource(Res.string.text_font_size_points)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { apply() }),
                    modifier = Modifier.width(140.dp).padding(horizontal = 12.dp, vertical = 4.dp)
                )
                for (preset in TextSizes.PRESETS) {
                    DropdownMenuItem(
                        text = { Text("${formatSize(preset)} pt") },
                        onClick = {
                            onFontSizeChange(preset)
                            menuOpen = false
                        },
                        trailingIcon = if (preset == fontSizePt) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null
                    )
                }
            }
        }

        IconButton(
            onClick = { onFontSizeChange(TextSizes.larger(fontSizePt)) },
            enabled = fontSizePt < TextSizes.MAX,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(Res.string.draw_toolbar_action_increase_font_size), modifier = Modifier.size(16.dp))
        }
    }
}

/** A size as it is written: "16", "10.5". */
internal fun formatSize(size: Float): String {
    val tenths = (size * 10f).roundToInt()
    return if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
}
