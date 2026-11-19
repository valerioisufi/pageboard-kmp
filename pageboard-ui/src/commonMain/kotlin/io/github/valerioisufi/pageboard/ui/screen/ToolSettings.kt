package io.github.valerioisufi.pageboard.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.ui.components.fadingHorizontalScroll
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.canvas.controller.TextFormatTarget
import io.github.valerioisufi.pageboard.canvas.tool.LassoMode
import io.github.valerioisufi.pageboard.canvas.tool.Tool
import io.github.valerioisufi.pageboard.model.TextAlignment
import io.github.valerioisufi.pageboard.model.TextMarkupKind
import io.github.valerioisufi.pageboard.model.geometry.mm
import io.github.valerioisufi.pageboard.model.geometry.pt
import io.github.valerioisufi.pageboard.ui.components.AddPresetCard
import io.github.valerioisufi.pageboard.ui.components.ColorSwatches
import io.github.valerioisufi.pageboard.ui.components.DesignIconButton
import io.github.valerioisufi.pageboard.ui.components.EraserPreview
import io.github.valerioisufi.pageboard.ui.components.FontSizeControl
import io.github.valerioisufi.pageboard.ui.components.HybridScale
import io.github.valerioisufi.pageboard.ui.components.OptionCard
import io.github.valerioisufi.pageboard.ui.components.PopoverTitle
import io.github.valerioisufi.pageboard.ui.components.PresetCard
import io.github.valerioisufi.pageboard.ui.components.SettingHint
import io.github.valerioisufi.pageboard.ui.components.SwitchSetting
import io.github.valerioisufi.pageboard.ui.components.SettingLabel
import io.github.valerioisufi.pageboard.ui.components.SizeSetting
import io.github.valerioisufi.pageboard.ui.components.StrokePreview
import io.github.valerioisufi.pageboard.ui.components.TextColorPickerDialog
import io.github.valerioisufi.pageboard.ui.components.TextMarkupPresetButton
import io.github.valerioisufi.pageboard.ui.components.TextMarkupSample
import io.github.valerioisufi.pageboard.ui.components.textMarkupKindLabel
import io.github.valerioisufi.pageboard.ui.editor.TextAlignmentButtons
import io.github.valerioisufi.pageboard.ui.generated.resources.*
import io.github.valerioisufi.pageboard.ui.theme.PageboardIcons
import io.github.valerioisufi.pageboard.ui.theme.InkPalette
import io.github.valerioisufi.pageboard.ui.util.formatDecimal
import org.jetbrains.compose.resources.stringResource

// The settings of the tools: the ink, the eraser, the lasso, the text, the marks of the PDF text

/**
 * The settings of an ink tool (the pen, the highlighter): the size, the presets (tap to choose
 * one, "+" to add one), the inks of [swatches] and the color wheel; they change the preset chosen.
 */
@Composable
internal fun ColumnScope.InkSettings(controller: PageboardController, tool: Tool, title: String, swatches: List<Color>, scale: HybridScale) {
    val toolManager = controller.toolManager
    val presets = toolManager.presetsOf(tool)
    val index = toolManager.selectedPresetIndex(tool)
    val active = toolManager.activePreset(tool) ?: return
    val color = Color(active.color)
    var wheelOpen by remember { mutableStateOf(false) }
    fun update(transform: (io.github.valerioisufi.pageboard.canvas.controller.ToolPreset) -> io.github.valerioisufi.pageboard.canvas.controller.ToolPreset) {
        toolManager.presetsOf(tool).getOrNull(index)?.let { toolManager.updatePreset(tool, index, transform(it)) }
    }
    val sizePt = active.sizeMm.mm.pt
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        PopoverTitle(title) { StrokePreview(color.copy(alpha = maxOf(color.alpha, 0.6f)), width = (sizePt * 1.2f).coerceIn(1.5f, 14f).dp) }
        SizeSetting(
            label = stringResource(Res.string.tool_settings_size),
            value = sizePt,
            unit = "pt",
            scale = scale,
            onValueChange = { newPt -> update { it.copy(sizeMm = newPt.pt.mm) } }
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingLabel(stringResource(Res.string.tool_settings_presets), modifier = Modifier.weight(1f))
                if (presets.size > 1) {
                    DesignIconButton(
                        icon = PageboardIcons.Trash,
                        label = stringResource(Res.string.tool_settings_delete_preset),
                        onClick = { toolManager.removePreset(tool, index) },
                        iconSize = 18.dp
                    )
                }
            }
            // Up to three presets share the width with "+", more are scrolled
            val fits = presets.size <= 3
            Row(
                modifier = if (fits) Modifier.fillMaxWidth() else Modifier.fadingHorizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEachIndexed { i, preset ->
                    val presetPt = preset.sizeMm.mm.pt
                    PresetCard(
                        color = Color(preset.color).let { it.copy(alpha = maxOf(it.alpha, 0.6f)) },
                        strokeWidth = (presetPt * 1.2f).coerceIn(1.5f, 10f).dp,
                        label = "${formatDecimal(presetPt, 1)} pt",
                        selected = i == index,
                        onClick = { controller.selectTool(tool, i) },
                        modifier = if (fits) Modifier.weight(1f) else Modifier.width(78.dp)
                    )
                }
                AddPresetCard(
                    label = stringResource(Res.string.draw_toolbar_action_add_preset),
                    onClick = {
                        val newIndex = toolManager.addPreset(tool, active.copy())
                        controller.selectTool(tool, newIndex)
                    },
                    modifier = Modifier.width(44.dp)
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingLabel(stringResource(Res.string.tool_settings_ink))
            // The highlighters keep their transparency
            ColorSwatches(
                colors = swatches,
                selected = color.copy(alpha = 1f),
                onSelect = { swatch -> update { it.copy(color = swatch.copy(alpha = color.alpha).toArgb()) } },
                onCustom = { wheelOpen = true }
            )
        }
        SwitchSetting(
            title = stringResource(Res.string.command_shapes),
            description = stringResource(Res.string.tool_settings_shapes_hint),
            checked = controller.shapeSnapper.isEnabled,
            onCheckedChange = controller.shapeSnapper::updateEnabled
        )
    }
    if (wheelOpen) {
        TextColorPickerDialog(
            initialColor = color.copy(alpha = 1f),
            onDismissRequest = { wheelOpen = false },
            onColorConfirmed = { picked -> update { it.copy(color = picked.copy(alpha = color.alpha).toArgb()) } }
        )
    }
}

/** The settings of the eraser: its size (in millimeters) and what it erases. */
@Composable
internal fun ColumnScope.EraserSettings(controller: PageboardController) {
    val toolManager = controller.toolManager
    val index = toolManager.selectedPresetIndex(Tool.ERASER)
    val preset = toolManager.activePreset(Tool.ERASER)
    val sizeMm = preset?.sizeMm ?: 8f
    // Large erasers, linear precision only for small corrections
    val scale = remember { HybridScale(2f..50f, linearThreshold = 10f, linearProportion = 0.25f) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        PopoverTitle(stringResource(Res.string.tool_eraser)) { EraserPreview(scale.position(sizeMm)) }
        SizeSetting(
            label = stringResource(Res.string.draw_toolbar_label_eraser_size),
            value = sizeMm,
            unit = "mm",
            scale = scale,
            onValueChange = { newMm -> preset?.let { toolManager.updatePreset(Tool.ERASER, index, it.copy(sizeMm = newMm)) } }
        )
        SettingHint(stringResource(Res.string.tool_settings_eraser_hint))
    }
}

/** The settings of the lasso: what it selects. */
@Composable
internal fun ColumnScope.LassoSettings(controller: PageboardController) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        PopoverTitle(stringResource(Res.string.draw_toolbar_title_lasso_mode))
        OptionCard(
            icon = PageboardIcons.Lasso,
            title = stringResource(Res.string.draw_toolbar_option_select_all),
            description = stringResource(Res.string.tool_settings_lasso_all_hint),
            selected = controller.lassoMode == LassoMode.ALL,
            onClick = { controller.lassoMode = LassoMode.ALL }
        )
        OptionCard(
            icon = PageboardIcons.Image,
            title = stringResource(Res.string.draw_toolbar_option_images_only),
            description = stringResource(Res.string.tool_settings_lasso_images_hint),
            selected = controller.lassoMode == LassoMode.IMAGES_ONLY,
            onClick = { controller.lassoMode = LassoMode.IMAGES_ONLY }
        )
    }
}

/**
 * The settings of the text tool, the style of a new text (size, color, alignment), with the
 * controls of the formatting bar.
 */
@Composable
internal fun ColumnScope.TextToolSettings(controller: PageboardController) {
    val defaults = remember(controller) { TextDefaultsTarget(controller) }
    var wheelOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        PopoverTitle(stringResource(Res.string.draw_toolbar_title_text_settings))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingLabel(stringResource(Res.string.draw_toolbar_label_font_size), modifier = Modifier.weight(1f))
            FontSizeControl(fontSizePt = defaults.fontSize, onFontSizeChange = defaults::changeFontSize)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingLabel(stringResource(Res.string.draw_toolbar_label_text_color))
            ColorSwatches(
                colors = InkPalette.inks.take(5),
                selected = Color(defaults.color),
                onSelect = { defaults.changeColor(it.toArgb()) },
                onCustom = { wheelOpen = true }
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingLabel(stringResource(Res.string.text_editor_action_alignment), modifier = Modifier.weight(1f))
            TextAlignmentButtons(defaults, commands = null)
        }
        SettingHint(stringResource(Res.string.tool_settings_text_hint))
    }
    if (wheelOpen) {
        TextColorPickerDialog(
            initialColor = Color(defaults.color),
            onDismissRequest = { wheelOpen = false },
            onColorConfirmed = { defaults.changeColor(it.toArgb()) }
        )
    }
}

/** The style of a new text, as a target of the formatting controls. */
private class TextDefaultsTarget(private val controller: PageboardController) : TextFormatTarget {
    override val fontSize: Float get() = controller.textDefaults.fontSize
    override val color: Int get() = controller.textDefaults.color
    override val alignment: TextAlignment get() = controller.textDefaults.alignment
    override fun changeFontSize(size: Float) = controller.updateTextDefaults(controller.textDefaults.copy(fontSize = size))
    override fun changeColor(argb: Int) = controller.updateTextDefaults(controller.textDefaults.copy(color = argb))
    override fun changeAlignment(newAlignment: TextAlignment) = controller.updateTextDefaults(controller.textDefaults.copy(alignment = newAlignment))
}

/**
 * The settings of the marks of the text of the PDFs: the presets (tap one to mark with it, "+" to
 * add one), the kind and the color of the one chosen.
 */
@Composable
internal fun ColumnScope.MarkupSettings(controller: PageboardController) {
    val toolManager = controller.toolManager
    val tool = Tool.TEXT_MARKUP
    val presets = toolManager.presetsOf(tool)
    val index = toolManager.selectedPresetIndex(tool)
    val active = toolManager.activePreset(tool) ?: return
    fun update(kind: TextMarkupKind = active.markup, color: Color = Color(active.color)) {
        toolManager.updatePreset(tool, index, active.copy(markup = kind, color = color.copy(alpha = 1f).toArgb()))
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        PopoverTitle(stringResource(Res.string.draw_toolbar_action_text_markup)) {
            if (presets.size > 1) {
                DesignIconButton(
                    icon = PageboardIcons.Trash,
                    label = stringResource(Res.string.tool_settings_delete_preset),
                    onClick = { toolManager.removePreset(tool, index) },
                    iconSize = 18.dp
                )
            }
        }
        SettingHint(stringResource(Res.string.tool_settings_markup_hint))
        Row(modifier = Modifier.fadingHorizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            presets.forEachIndexed { i, preset ->
                TextMarkupPresetButton(kind = preset.markup, color = Color(preset.color), isSelected = i == index, onClick = { controller.selectTool(tool, i) })
            }
            val add = stringResource(Res.string.draw_toolbar_action_add_preset)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable(role = Role.Button) { controller.selectTool(tool, toolManager.addPreset(tool, active.copy())) }
                    .semantics { contentDescription = add }
            ) {
                Icon(PageboardIcons.Plus, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (kind in TextMarkupKind.entries) {
                    val selected = kind == active.markup
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable(role = Role.RadioButton) { update(kind = kind) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(30.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        ) {
                            TextMarkupSample(kind, Color(active.color))
                        }
                        Text(
                            textMarkupKindLabel(kind),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) Icon(PageboardIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                    }
                }
                ColorSwatches(
                    colors = if (active.markup == TextMarkupKind.HIGHLIGHT) InkPalette.highlights else InkPalette.inks.take(5),
                    selected = Color(active.color),
                    onSelect = { update(color = it) },
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                )
            }
        }
    }
}
