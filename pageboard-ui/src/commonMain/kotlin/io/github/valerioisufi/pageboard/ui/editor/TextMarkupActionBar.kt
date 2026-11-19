package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.canvas.tool.Tool
import io.github.valerioisufi.pageboard.ui.components.TextMarkupPresetButton
import io.github.valerioisufi.pageboard.ui.generated.resources.*
import io.github.valerioisufi.pageboard.ui.theme.PageboardIcons
import org.jetbrains.compose.resources.stringResource

/**
 * Floating contextual action bar of the text of the PDFs: for the selected text, copy it or mark
 * it with a preset of the text markup tool; for a selected mark, copy its text, change its style
 * to a preset or delete it.
 */
@Composable
internal fun TextMarkupActionBar(
    controller: PageboardController,
    modifier: Modifier = Modifier
) {
    val manager = controller.textMarkupManager
    val selection = manager.textSelection
    val selectedMarkup = manager.selectedMarkup
    // The clipboard of every platform (the Clipboard API has no common plain text entry)
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    AnimatedVisibility(
        visible = selection != null || selectedMarkup != null,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -40 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -40 }),
        modifier = modifier
    ) {
        ActionBar(modifier = Modifier.padding(top = 16.dp), height = 52) {
            val copy = stringResource(Res.string.common_action_copy)
            Row(
                modifier = Modifier
                    .height(40.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) {
                        manager.selectedText?.takeIf { it.isNotEmpty() }?.let { clipboard.setText(AnnotatedString(it)) }
                        manager.clearSelection()
                    }
                    .padding(start = 10.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(PageboardIcons.Copy, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Text(copy, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            BarDivider()
            // The presets of the text markup tool
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (preset in controller.toolManager.presetsOf(Tool.TEXT_MARKUP)) {
                    val markup = selectedMarkup?.markup
                    TextMarkupPresetButton(
                        kind = preset.markup,
                        color = Color(preset.color),
                        isSelected = markup != null && markup.kind == preset.markup && markup.color == preset.color,
                        onClick = {
                            if (selectedMarkup != null) manager.restyleSelectedMarkup(preset.markup, preset.color)
                            else manager.markSelection(preset.markup, preset.color)
                        }
                    )
                }
            }
            if (selectedMarkup != null) {
                BarDivider()
                BarAction(PageboardIcons.Trash, stringResource(Res.string.common_action_delete), { manager.deleteSelectedMarkup() }, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
