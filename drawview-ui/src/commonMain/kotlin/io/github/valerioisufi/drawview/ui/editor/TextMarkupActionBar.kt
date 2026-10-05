package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.model.Tool
import io.github.valerioisufi.drawview.ui.components.TextMarkupPresetButton
import io.github.valerioisufi.drawview.ui.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Floating contextual action bar of the text of the PDFs: for the selected text, copy it or mark
 * it with a preset of the text markup tool; for a selected mark, copy its text, change its style
 * to a preset or delete it.
 */
@Composable
fun TextMarkupActionBar(
    controller: DrawViewController,
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
        ElevatedCard(
            modifier = Modifier.padding(top = 24.dp),
            shape = RoundedCornerShape(50),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    manager.selectedText?.takeIf { it.isNotEmpty() }?.let { clipboard.setText(AnnotatedString(it)) }
                    manager.clearSelection()
                }) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(Res.string.common_action_copy),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                VerticalDivider(
                    modifier = Modifier
                        .height(24.dp)
                        .padding(horizontal = 4.dp),
                    thickness = 1.dp
                )

                // The presets of the text markup tool
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

                if (selectedMarkup != null) {
                    VerticalDivider(
                        modifier = Modifier
                            .height(24.dp)
                            .padding(horizontal = 4.dp),
                        thickness = 1.dp
                    )
                    IconButton(onClick = { manager.deleteSelectedMarkup() }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(Res.string.common_action_delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
