package io.github.valerioisufi.drawview.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.model.text.TextSizes
import io.github.valerioisufi.drawview.ui.command.DesignMenu
import io.github.valerioisufi.drawview.ui.command.MenuItem
import io.github.valerioisufi.drawview.ui.generated.resources.Res
import io.github.valerioisufi.drawview.ui.generated.resources.draw_toolbar_action_decrease_font_size
import io.github.valerioisufi.drawview.ui.generated.resources.draw_toolbar_action_increase_font_size
import io.github.valerioisufi.drawview.ui.generated.resources.draw_toolbar_label_font_size
import io.github.valerioisufi.drawview.ui.generated.resources.text_font_size_points
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

/**
 * The size of a text: − and + step through the sizes of [TextSizes.PRESETS]; the value opens them
 * all and a field where any size from [TextSizes.MIN] to [TextSizes.MAX] can be typed.
 */
@Composable
internal fun FontSizeControl(
    fontSizePt: Float,
    onFontSizeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .height(36.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = { onFontSizeChange(TextSizes.smaller(fontSizePt)) },
            enabled = fontSizePt > TextSizes.MIN,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(DrawViewIcons.Minus, contentDescription = stringResource(Res.string.draw_toolbar_action_decrease_font_size), modifier = Modifier.size(16.dp))
        }

        Box {
            val label = stringResource(Res.string.draw_toolbar_label_font_size)
            Text(
                text = formatSize(fontSizePt),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(min = 40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = label) { menuOpen = true }
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            )
            DesignMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, modifier = Modifier, offset = DpOffset.Zero, takesText = true) {
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
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.width(160.dp).padding(horizontal = 4.dp, vertical = 4.dp)
                )
                val check = rememberVectorPainter(DrawViewIcons.Check)
                for (preset in TextSizes.PRESETS) {
                    MenuItem(
                        text = "${formatSize(preset)} pt",
                        onClick = {
                            onFontSizeChange(preset)
                            menuOpen = false
                        },
                        icon = check.takeIf { preset == fontSizePt },
                        selected = preset == fontSizePt,
                        alignWithIcons = true
                    )
                }
            }
        }

        IconButton(
            onClick = { onFontSizeChange(TextSizes.larger(fontSizePt)) },
            enabled = fontSizePt < TextSizes.MAX,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(DrawViewIcons.Plus, contentDescription = stringResource(Res.string.draw_toolbar_action_increase_font_size), modifier = Modifier.size(16.dp))
        }
    }
}

/** A size as it is written: "16", "10.5". */
internal fun formatSize(size: Float): String {
    val tenths = (size * 10f).roundToInt()
    return if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
}
