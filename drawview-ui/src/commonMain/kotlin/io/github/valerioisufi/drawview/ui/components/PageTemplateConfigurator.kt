package io.github.valerioisufi.drawview.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.core.geometry.Dimension
import io.github.valerioisufi.drawview.core.geometry.mm
import io.github.valerioisufi.drawview.core.model.PageBackground
import io.github.valerioisufi.drawview.ui.command.DesignMenu
import io.github.valerioisufi.drawview.ui.command.MenuItem
import io.github.valerioisufi.drawview.ui.generated.resources.*
import io.github.valerioisufi.drawview.ui.theme.DrawViewElevation
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import io.github.valerioisufi.drawview.ui.theme.elevation
import io.github.valerioisufi.drawview.ui.util.formatDecimal
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Defines the minimum allowed size for a page dimension in millimeters to ensure render stability.
 */
private const val MIN_PAGE_SIZE = 50f

/**
 * Defines the maximum allowed total area for a page in square millimeters to prevent OutOfMemory exceptions.
 */
private const val MAX_PAGE_AREA = 250000f

/**
 * Represents the available background patterns that can be applied to a page document.
 *
 * @property label The localized string representation of the pattern type for UI display.
 */
private enum class BgType(val labelRes: StringResource) {
    SOLID(Res.string.page_config_bg_solid),
    RULED(Res.string.page_config_bg_ruled),
    GRID(Res.string.page_config_bg_grid),
    DOTTED(Res.string.page_config_bg_dotted)
}

private enum class PageFormat(val id: String, val labelRes: StringResource?) {
    A4("A4", null),
    A3("A3", null),
    A5("A5", null),
    CUSTOM("CUSTOM", Res.string.page_config_format_custom)
}

/**
 * A Compose UI component that provides an interface for configuring the physical and visual attributes of a document page.
 * It allows the user to define page dimensions (standard or custom formats), background patterns, and colors, providing real-time visual feedback via a preview canvas.
 *
 * @param modifier The modifier to be applied to the top-level layout of the configurator.
 * @param initialDimension The starting physical dimensions of the page. Defaults to standard A4 size.
 * @param initialBackground The starting background pattern and styling configuration of the page. Defaults to a solid background.
 * @param onApply Callback invoked when the user confirms their configuration. Passes the validated [Dimension] and [PageBackground].
 * @param onCancel Callback invoked when the user aborts the configuration process.
 * @param subtitle what is configured (a page of a document, its pages), under the title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageTemplateConfigurator(
    modifier: Modifier = Modifier,
    initialDimension: Dimension = Dimension.A4(),
    initialBackground: PageBackground = PageBackground.Solid(),
    onApply: (Dimension, PageBackground) -> Unit,
    onCancel: () -> Unit,
    subtitle: String? = null
) {
    var customWidth by remember { mutableStateOf(formatMillimeters(initialDimension.width.mm)) }
    var customHeight by remember { mutableStateOf(formatMillimeters(initialDimension.height.mm)) }

    // Capiamo il formato iniziale basandoci sulle dimensioni reali
    val initialFormat = remember {
        val w = initialDimension.width.mm.roundToInt()
        val h = initialDimension.height.mm.roundToInt()
        when {
            (w == 210 && h == 297) || (w == 297 && h == 210) -> PageFormat.A4
            (w == 297 && h == 420) || (w == 420 && h == 297) -> PageFormat.A3
            (w == 148 && h == 210) || (w == 210 && h == 148) -> PageFormat.A5
            else -> PageFormat.CUSTOM
        }
    }

    // Ora lo stato contiene l'Enum, non una stringa
    var selectedFormat by remember { mutableStateOf(initialFormat) }
    var formatExpanded by remember { mutableStateOf(false) }

    // Funzione helper per ottenere la stringa da mostrare nell'UI
    val getFormatLabel: @Composable (PageFormat) -> String = { format ->
        format.labelRes?.let { stringResource(it) } ?: format.id
    }

    var selectedType by remember {
        mutableStateOf(
            when (initialBackground) {
                is PageBackground.Solid -> BgType.SOLID
                is PageBackground.Ruled -> BgType.RULED
                is PageBackground.Grid -> BgType.GRID
                is PageBackground.Dotted -> BgType.DOTTED
            }
        )
    }

    var spacingMm by remember {
        mutableFloatStateOf(
            when (initialBackground) {
                is PageBackground.Ruled -> initialBackground.spacingMm
                is PageBackground.Grid -> initialBackground.spacingMm
                is PageBackground.Dotted -> initialBackground.spacingMm
                else -> 8f
            }
        )
    }

    var thicknessMm by remember {
        mutableFloatStateOf(
            when (initialBackground) {
                is PageBackground.Ruled -> initialBackground.thicknessMm
                is PageBackground.Grid -> initialBackground.thicknessMm
                is PageBackground.Dotted -> initialBackground.dotRadiusMm
                else -> 0.2f
            }
        )
    }

    val paperColors = listOf(Color.White, Color(0xFFFFFDD0), Color(0xFF2C2C2C))
    var paperColor by remember { mutableStateOf(Color(initialBackground.backgroundColor)) }

    val lineColors = listOf(Color(0xFFB0BEC5), Color(0xFF64B5F6), Color(0xFFE57373))
    var lineColor by remember {
        mutableStateOf(
            when (initialBackground) {
                is PageBackground.Ruled -> Color(initialBackground.lineColor).copy(alpha = 1f)
                is PageBackground.Grid -> Color(initialBackground.lineColor).copy(alpha = 1f)
                is PageBackground.Dotted -> Color(initialBackground.dotColor).copy(alpha = 1f)
                else -> lineColors[1]
            }
        )
    }

    var showColorPickerFor by remember { mutableStateOf<String?>(null) }

    val wValue = customWidth.toFloatOrNull() ?: 0f
    val hValue = customHeight.toFloatOrNull() ?: 0f
    val currentArea = wValue * hValue

    val isWidthError = wValue < MIN_PAGE_SIZE
    val isHeightError = hValue < MIN_PAGE_SIZE
    val isAreaError = currentArea > MAX_PAGE_AREA

    val isDimensionInvalid = isWidthError || isHeightError || isAreaError

    val apply = {
        val bgInt = paperColor.toArgb()
        val lineInt = lineColor.copy(alpha = 0.4f).toArgb()
        val finalBackground = when (selectedType) {
            BgType.SOLID -> PageBackground.Solid(bgInt)
            BgType.RULED -> PageBackground.Ruled(bgInt, lineInt, spacingMm, thicknessMm)
            BgType.GRID -> PageBackground.Grid(bgInt, lineInt, spacingMm, thicknessMm)
            BgType.DOTTED -> PageBackground.Dotted(bgInt, lineInt, spacingMm, thicknessMm)
        }
        onApply(Dimension(wValue.mm, hValue.mm), finalBackground)
    }
    val preview: @Composable () -> Unit = {
        if (isDimensionInvalid) {
            Text(stringResource(Res.string.page_config_preview_invalid_size), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } else {
            PagePreview(
                widthMm = wValue,
                heightMm = hValue,
                bgType = selectedType,
                paperColor = paperColor,
                lineColor = lineColor.copy(alpha = 0.4f),
                spacingMm = spacingMm,
                thicknessMm = thicknessMm
            )
        }
    }
    val formatText: @Composable (PageFormat) -> String = { format ->
        if (format == PageFormat.CUSTOM) getFormatLabel(format) else "${getFormatLabel(format)} · ${customWidth} × ${customHeight} mm"
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val wide = maxWidth >= 600.dp
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            // The title, what it changes and (narrow) the preview beside it
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(Res.string.page_config_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (wide) {
                    DesignIconButton(DrawViewIcons.Close, stringResource(Res.string.common_button_cancel), onCancel, tint = MaterialTheme.colorScheme.onSurface)
                } else if (!isDimensionInvalid) {
                    Box(modifier = Modifier.height(48.dp).aspectRatio(0.72f), contentAlignment = Alignment.Center) { preview() }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                if (wide) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .width(260.dp)
                            .height(360.dp)
                            .background(MaterialTheme.colorScheme.surfaceDim.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                            .padding(24.dp)
                    ) { preview() }
                }
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Format
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingLabel(stringResource(Res.string.page_config_label_format).trimEnd(':', '：'))
                        Box {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                                    .clickable { formatExpanded = true }
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(formatText(selectedFormat), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                Icon(DrawViewIcons.ChevronDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            }
                            DesignMenu(expanded = formatExpanded, onDismissRequest = { formatExpanded = false }) {
                                PageFormat.entries.forEach { formatOption ->
                                    MenuItem(
                                        text = getFormatLabel(formatOption),
                                        selected = formatOption == selectedFormat,
                                        onClick = {
                                            selectedFormat = formatOption
                                            formatExpanded = false
                                            // The sizes change only with a format
                                            when (formatOption) {
                                                PageFormat.A4 -> { customWidth = "210"; customHeight = "297" }
                                                PageFormat.A3 -> { customWidth = "297"; customHeight = "420" }
                                                PageFormat.A5 -> { customWidth = "148"; customHeight = "210" }
                                                PageFormat.CUSTOM -> Unit
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        if (selectedFormat == PageFormat.CUSTOM) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = customWidth,
                                    onValueChange = { if (it.length <= 5) customWidth = it },
                                    label = { Text(stringResource(Res.string.page_config_label_width)) },
                                    isError = isWidthError || isAreaError,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = customHeight,
                                    onValueChange = { if (it.length <= 5) customHeight = it },
                                    label = { Text(stringResource(Res.string.page_config_label_height)) },
                                    isError = isHeightError || isAreaError,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                            }
                            if (isWidthError || isHeightError) {
                                Text(
                                    text = stringResource(Res.string.page_config_error_min_size, formatDecimal(MIN_PAGE_SIZE, 0)),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.End
                                )
                            } else if (isAreaError) {
                                Text(
                                    text = stringResource(Res.string.page_config_error_max_area),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.End
                                )
                            }
                        }
                    }
                    // Pattern
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingLabel(stringResource(Res.string.page_config_label_bg_pattern).trimEnd(':', '：'))
                        SegmentedChoice(BgType.entries, selectedType, label = { stringResource(it.labelRes) }, onSelect = { selectedType = it })
                    }
                    // Colors
                    ColorRow(stringResource(Res.string.page_config_label_paper_color).trimEnd(':', '：'), paperColors, paperColor, { paperColor = it }) { showColorPickerFor = "PAPER" }
                    if (selectedType != BgType.SOLID) {
                        ColorRow(stringResource(Res.string.page_config_label_line_color).trimEnd(':', '：'), lineColors, lineColor, { lineColor = it }) { showColorPickerFor = "LINE" }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SettingLabel(stringResource(Res.string.page_config_label_spacing).trimEnd(':', '：'), value = stringResource(Res.string.page_config_value_spacing, spacingMm.roundToInt()))
                            DesignSlider(value = spacingMm, onValueChange = { spacingMm = it }, valueRange = 4f..20f, steps = 15)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SettingLabel(stringResource(Res.string.page_config_label_thickness).trimEnd(':', '：'), value = stringResource(Res.string.page_config_value_thickness, formatDecimal(thicknessMm, 1)))
                            DesignSlider(value = thicknessMm, onValueChange = { thicknessMm = it }, valueRange = 0.1f..1.5f, steps = 13)
                        }
                    }
                    // The buttons: at the end of the controls wide, the whole width on a phone
                    if (wide) {
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            TextButton(onClick = onCancel) { Text(stringResource(Res.string.common_button_cancel)) }
                            Button(enabled = !isDimensionInvalid, onClick = apply) { Text(stringResource(Res.string.common_button_apply)) }
                        }
                    } else {
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlineButton(onClick = onCancel, modifier = Modifier.weight(1f).height(52.dp)) { Text(stringResource(Res.string.common_button_cancel)) }
                            Button(enabled = !isDimensionInvalid, onClick = apply, modifier = Modifier.weight(1f).height(52.dp)) { Text(stringResource(Res.string.common_button_apply)) }
                        }
                    }
                }
            }
        }
    }

    if (showColorPickerFor != null) {
        val initialColor = if (showColorPickerFor == "PAPER") paperColor else lineColor
        TextColorPickerDialog(
            initialColor = initialColor,
            onColorConfirmed = { color ->
                if (showColorPickerFor == "PAPER") paperColor = color else lineColor = color
            },
            onDismissRequest = { showColorPickerFor = null }
        )
    }
}

/**
 * A Compose Canvas implementation that renders a dynamically scaled visual preview of the document page.
 *
 * @param widthMm The target width of the page in millimeters.
 * @param heightMm The target height of the page in millimeters.
 * @param bgType The background pattern type to be rendered onto the canvas.
 * @param paperColor The solid fill color of the page.
 * @param lineColor The color utilized for rendering background pattern elements (lines, grids, or dots).
 * @param spacingMm The distance between pattern elements in millimeters.
 * @param thicknessMm The stroke width or radius size of the pattern elements in millimeters.
 */
@Composable
private fun PagePreview(
    widthMm: Float,
    heightMm: Float,
    bgType: BgType,
    paperColor: Color,
    lineColor: Color,
    spacingMm: Float,
    thicknessMm: Float
) {
    val safeWidth = widthMm.coerceAtLeast(1f)
    val safeHeight = heightMm.coerceAtLeast(1f)

    val ratio = safeWidth / safeHeight

    Canvas(
        modifier = Modifier
            .aspectRatio(ratio.coerceIn(0.1f, 10f))
            .fillMaxSize()
            .elevation(DrawViewElevation.Level1, RoundedCornerShape(2.dp))
    ) {
        val w = size.width
        val h = size.height

        drawRect(color = paperColor, size = size)

        if (bgType == BgType.SOLID) return@Canvas

        val pixelsPerMm = w / safeWidth

        val spacingPx = (spacingMm * pixelsPerMm).coerceAtLeast(8f)
        val thicknessPx = (thicknessMm * pixelsPerMm).coerceAtLeast(1f)

        when (bgType) {
            BgType.RULED -> {
                var y = spacingPx
                while (y < h) {
                    drawLine(color = lineColor, start = Offset(0f, y), end = Offset(w, y), strokeWidth = thicknessPx)
                    y += spacingPx
                }
            }
            BgType.GRID -> {
                var y = spacingPx
                while (y < h) {
                    drawLine(color = lineColor, start = Offset(0f, y), end = Offset(w, y), strokeWidth = thicknessPx)
                    y += spacingPx
                }
                var x = spacingPx
                while (x < w) {
                    drawLine(color = lineColor, start = Offset(x, 0f), end = Offset(x, h), strokeWidth = thicknessPx)
                    x += spacingPx
                }
            }
            BgType.DOTTED -> {
                var x = spacingPx
                while (x < w) {
                    var y = spacingPx
                    while (y < h) {
                        drawCircle(color = lineColor, radius = thicknessPx, center = Offset(x, y))
                        y += spacingPx
                    }
                    x += spacingPx
                }
            }
            BgType.SOLID -> {}
        }
    }
}

/** A color of the page: its [label], the swatches of [colors] and the color wheel. */
@Composable
private fun ColorRow(label: String, colors: List<Color>, selected: Color, onSelect: (Color) -> Unit, onCustom: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        ColorSwatches(colors = colors, selected = selected, onSelect = onSelect, onCustom = onCustom)
    }
}

/**
 * Text shown in the size fields: integer millimeters without decimals.
 */
private fun formatMillimeters(value: Float): String =
    if (value == value.roundToInt().toFloat()) value.roundToInt().toString() else formatDecimal(value, 1)
