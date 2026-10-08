package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.core.controller.TextFormatTarget
import io.github.valerioisufi.drawview.ui.generated.resources.Res
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_action_save
import io.github.valerioisufi.drawview.ui.screen.EditorCommands
import org.jetbrains.compose.resources.stringResource

/**
 * The formatting bar of the texts: their style (see [TextStyleControls]) and, for the text being
 * written, the formats of its characters (bold, italic, strikethrough, code, heading, formula)
 * and the button closing the editor ([onDone]). The buttons run the [commands] of the editor, whose
 * tooltips they show.
 */
@Composable
internal fun TextFormatBar(
    target: TextFormatTarget,
    commands: EditorCommands,
    modifier: Modifier = Modifier,
    onDone: (() -> Unit)? = null
) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            TextStyleControls(target, commands)
            if (target.formatsCharacters) {
                VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp), thickness = 1.dp)
                FormatButton(commands.bold, Icons.Default.FormatBold)
                FormatButton(commands.italic, Icons.Default.FormatItalic)
                FormatButton(commands.strikethrough, Icons.Default.FormatStrikethrough)
                FormatButton(commands.code, Icons.Default.Code)
                FormatButton(commands.heading, Icons.Default.Title)
                FormatButton(commands.formula, Icons.Default.Functions)
            }
            if (onDone != null) {
                VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp), thickness = 1.dp)
                IconButton(onClick = onDone) {
                    Icon(Icons.Default.Check, contentDescription = stringResource(Res.string.text_editor_action_save), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
