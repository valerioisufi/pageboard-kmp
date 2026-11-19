package io.github.valerioisufi.drawview.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.ui.generated.resources.Res
import io.github.valerioisufi.drawview.ui.generated.resources.color_picker_title
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import io.github.valerioisufi.drawview.ui.util.formatDecimal
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round
import org.jetbrains.compose.resources.stringResource

/** The title of a popover of a tool, with what it shows beside (e.g. the stroke of the pen). */
@Composable
internal fun PopoverTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier = modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** The label of a group of a popover ("Presets", "Ink"), with its value at the end, if any. */
@Composable
internal fun SettingLabel(text: String, modifier: Modifier = Modifier, value: String? = null) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * A size on a scale linear up to [linearThreshold] (on [linearProportion] of the slider), then
 * exponential up to the end of [valueRange]: the small sizes precise, the large ones reachable.
 */
internal class HybridScale(
    valueRange: ClosedFloatingPointRange<Float>,
    linearThreshold: Float,
    private val linearProportion: Float
) {
    private val min = valueRange.start.coerceAtLeast(0.001f)
    private val max = valueRange.endInclusive.coerceAtLeast(min + 0.001f)
    private val threshold = linearThreshold.coerceIn(min + 0.001f, max)

    /** Where [value] is on the slider, from 0 to 1. */
    fun position(value: Float): Float = when {
        max <= threshold -> (value - min) / (max - min)
        value <= threshold -> (value - min) / (threshold - min) * linearProportion
        else -> linearProportion + ln(value / threshold) / ln(max / threshold) * (1f - linearProportion)
    }.coerceIn(0f, 1f)

    /** The value at [position] of the slider, rounded to the tenth. */
    fun value(position: Float): Float {
        val raw = when {
            max <= threshold -> min + position * (max - min)
            position <= linearProportion -> min + position / linearProportion * (threshold - min)
            else -> threshold * (max / threshold).pow((position - linearProportion) / (1f - linearProportion))
        }
        return round(raw * 10f) / 10f
    }
}

/**
 * The size of a tool: its [label] and its value with its [unit] above a slider of the [scale].
 */
@Composable
internal fun SizeSetting(label: String, value: Float, unit: String, scale: HybridScale, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingLabel(label, value = "${formatDecimal(value, 1)} $unit")
        DesignSlider(value = scale.position(value), onValueChange = { onValueChange(scale.value(it)) })
    }
}

/** A slider of the design: a thin track of the outline, filled in the primary color, a round thumb. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun DesignSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier.height(24.dp),
        valueRange = valueRange,
        steps = steps,
        thumb = {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(colors.primary, CircleShape)
                    .border(3.dp, colors.surfaceContainerHigh, CircleShape)
            )
        },
        track = { state ->
            val fraction = ((state.value - state.valueRange.start) / (state.valueRange.endInclusive - state.valueRange.start)).coerceIn(0f, 1f)
            Canvas(modifier = Modifier.fillMaxWidth().height(4.dp)) {
                val y = size.height / 2f
                drawLine(colors.outlineVariant, Offset(0f, y), Offset(size.width, y), strokeWidth = size.height, cap = StrokeCap.Round)
                drawLine(colors.primary, Offset(0f, y), Offset(size.width * fraction, y), strokeWidth = size.height, cap = StrokeCap.Round)
            }
        }
    )
}

/**
 * The swatches of the inks: [colors] in circles, the [selected] one ringed in the primary color;
 * the button of the color wheel at the end ([onCustom]), ringed when the color is none of them.
 */
@Composable
internal fun ColorSwatches(colors: List<Color>, selected: Color, onSelect: (Color) -> Unit, modifier: Modifier = Modifier, onCustom: (() -> Unit)? = null) {
    val selectedArgb = selected.toArgb()
    val known = colors.any { it.toArgb() == selectedArgb }
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (color in colors) {
            Swatch(selected = color.toArgb() == selectedArgb, onClick = { onSelect(color) }) {
                Box(modifier = Modifier.size(26.dp).background(color, CircleShape).border(1.dp, Color.Black.copy(alpha = 0.08f), CircleShape))
            }
        }
        if (onCustom != null) {
            val label = stringResource(Res.string.color_picker_title)
            Swatch(selected = !known, onClick = onCustom, modifier = Modifier.semantics { contentDescription = label }) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(28.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                ) {
                    Icon(DrawViewIcons.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** A swatch: 34 dp, ringed in the primary color when [selected]. */
@Composable
private fun Swatch(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
    ) {
        content()
    }
}

/** The stroke of a pen of [color], as thick as [width] (a wave). */
@Composable
internal fun StrokePreview(color: Color, width: Dp, modifier: Modifier = Modifier.width(84.dp).height(24.dp)) {
    Canvas(modifier = modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.04f, size.height * 0.62f)
            cubicTo(size.width * 0.3f, size.height * 0.05f, size.width * 0.55f, size.height * 1.0f, size.width * 0.96f, size.height * 0.3f)
        }
        drawPath(path, color, style = Stroke(width = width.toPx().coerceIn(1f, size.height * 0.6f), cap = StrokeCap.Round))
    }
}

/**
 * A card of a preset of a tool: the stroke of its ink and its size; the primary container and a
 * ring when [selected].
 */
@Composable
internal fun PresetCard(color: Color, strokeWidth: Dp, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) colors.primaryContainer else colors.surfaceContainerLowest,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant),
        modifier = modifier.height(62.dp).semantics { this.selected = selected }
    ) {
        Column(
            modifier = Modifier.padding(top = 10.dp, bottom = 8.dp, start = 8.dp, end = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StrokePreview(color, strokeWidth, modifier = Modifier.width(52.dp).height(18.dp))
            Text(
                label,
                style = if (selected) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/** The card adding a preset: a "+" in a dashed outline. */
@Composable
internal fun AddPresetCard(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(62.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp))
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
    ) {
        Icon(DrawViewIcons.Plus, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

/**
 * An option of a tool as a card (e.g. a mode of the lasso): its icon, its title and what it does;
 * the primary container and a check when [selected].
 */
@Composable
internal fun OptionCard(icon: ImageVector, title: String, description: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val content = if (selected) colors.onPrimaryContainer else colors.onSurface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) colors.primaryContainer else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = content)
            Text(description, style = MaterialTheme.typography.bodySmall, color = if (selected) content else colors.onSurfaceVariant)
        }
        if (selected) Icon(DrawViewIcons.Check, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
    }
}

/**
 * A setting of a tool that is on or off: its [title] and what it does ([description]), a switch;
 * the whole row toggles it.
 */
@Composable
internal fun SwitchSetting(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A hint of a popover, under its controls. */
@Composable
internal fun SettingHint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** The ring of an eraser as large as it erases (relative to [fraction] of its largest size). */
@Composable
internal fun EraserPreview(fraction: Float, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = modifier.size(28.dp)) {
        val radius = (size.minDimension / 2f) * (0.3f + 0.7f * fraction.coerceIn(0f, 1f)) - 1.dp.toPx()
        drawCircle(color, radius = radius, center = Offset(size.width / 2, size.height / 2), style = Stroke(width = 1.5.dp.toPx()))
    }
}
