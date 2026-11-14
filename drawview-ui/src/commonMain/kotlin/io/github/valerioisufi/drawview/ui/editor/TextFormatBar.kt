package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.canvas.controller.TextFormatTarget
import io.github.valerioisufi.drawview.ui.generated.resources.Res
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_action_save
import io.github.valerioisufi.drawview.ui.screen.EditorCommands
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import org.jetbrains.compose.resources.stringResource

/**
 * The formatting bar of the texts: for the text being written, the formats of its characters
 * (bold, italic, strikethrough, code, block of code and, in a block, its line numbers, heading,
 * lists, table, formula), then their style (see
 * [TextStyleControls]) and the button closing the editor ([onDone]). The buttons run the
 * [commands] of the editor, whose tooltips they show.
 *
 * @param docked a bar on the whole width above the keyboard (a phone); else a pill floating by the
 *   text. In both the button closing the editor is always in sight.
 */
@Composable
internal fun TextFormatBar(
    target: TextFormatTarget,
    commands: EditorCommands,
    modifier: Modifier = Modifier,
    onDone: (() -> Unit)? = null,
    docked: Boolean = false
) {
    val controls: @Composable RowScope.() -> Unit = {
        if (target.formatsCharacters) {
            FormatButton(commands.bold, DrawViewIcons.Bold)
            FormatButton(commands.italic, DrawViewIcons.Italic)
            FormatButton(commands.strikethrough, DrawViewIcons.Strikethrough)
            FormatButton(commands.code, DrawViewIcons.Code)
            FormatButton(commands.codeBlock, DrawViewIcons.SquareCode)
            if (target.inCodeBlock) FormatButton(commands.lineNumbers, DrawViewIcons.Hash)
            HeadingButton(commands)
            FormatButton(commands.bulletList, DrawViewIcons.List)
            NumberedListButton(target, commands)
            FormatButton(commands.checklist, DrawViewIcons.ListChecks)
            FormatButton(commands.quote, DrawViewIcons.Quote)
            TableButton(target, commands)
            FormatButton(commands.formula, DrawViewIcons.Sigma)
            BarDivider()
        }
        TextStyleControls(target, commands)
    }
    if (docked) {
        Column(modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(modifier = Modifier.height(52.dp).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    content = controls
                )
                if (onDone != null) {
                    BarDivider()
                    DoneAction(stringResource(Res.string.text_editor_action_save), onDone, DrawViewIcons.Check)
                }
            }
        }
    } else {
        // The button closing the editor always in sight: the controls scroll when the bar is too narrow
        ActionBar(modifier = modifier, scrolls = false) {
            Row(
                modifier = Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                content = controls
            )
            if (onDone != null) {
                BarDivider()
                DoneAction(stringResource(Res.string.text_editor_action_save), onDone, DrawViewIcons.Check)
            }
        }
    }
}
