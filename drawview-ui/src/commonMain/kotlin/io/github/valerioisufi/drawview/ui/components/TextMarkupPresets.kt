package io.github.valerioisufi.drawview.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.core.model.TextMarkupKind
import io.github.valerioisufi.drawview.ui.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Name of a kind of mark of the text of the PDFs. */
@Composable
fun textMarkupKindLabel(kind: TextMarkupKind): String = stringResource(
    when (kind) {
        TextMarkupKind.HIGHLIGHT -> Res.string.text_markup_highlight
        TextMarkupKind.UNDERLINE -> Res.string.text_markup_underline
        TextMarkupKind.STRIKEOUT -> Res.string.text_markup_strikeout
    }
)

/**
 * The letters "ab" marked as [kind] with [color], as a mark of the text of the PDFs looks.
 */
@Composable
fun TextMarkupSample(kind: TextMarkupKind, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = "ab",
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        // The highlights are light colors: dark letters over them, like the text of a PDF
        color = if (kind == TextMarkupKind.HIGHLIGHT) Color.Black else MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .drawBehind {
                if (kind == TextMarkupKind.HIGHLIGHT) drawRect(color)
            }
            .drawWithContent {
                drawContent()
                val thickness = 2.dp.toPx()
                val y = when (kind) {
                    TextMarkupKind.HIGHLIGHT -> return@drawWithContent
                    TextMarkupKind.UNDERLINE -> size.height * 0.86f
                    TextMarkupKind.STRIKEOUT -> size.height * 0.55f
                }
                drawRect(color, topLeft = Offset(0f, y - thickness / 2f), size = Size(size.width, thickness))
            }
            .padding(horizontal = 3.dp)
    )
}

/**
 * Compact circular button of a preset of the text markup tool, showing its kind and color.
 */
@Composable
fun TextMarkupPresetButton(
    kind: TextMarkupKind,
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = textMarkupKindLabel(kind)
    Box(
        modifier = modifier
            .size(40.dp)
            .padding(4.dp)
            .clip(CircleShape)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        TextMarkupSample(kind, color)
    }
}

/**
 * Dropdown menu editing a preset of the text markup tool: kind of the marks and color.
 */
@Composable
fun TextMarkupPresetEditMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    kind: TextMarkupKind,
    color: Color,
    showDeleteOption: Boolean,
    onKindChanged: (TextMarkupKind) -> Unit,
    onColorChanged: (Color) -> Unit,
    onDeleteClicked: () -> Unit
) {
    DropdownMenu(
        modifier = Modifier.width(300.dp),
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(Res.string.draw_menu_settings),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                if (showDeleteOption) {
                    IconButton(onClick = onDeleteClicked) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = stringResource(Res.string.common_action_delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                for (option in TextMarkupKind.entries) {
                    val selected = option == kind
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable(role = Role.RadioButton) { onKindChanged(option) }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        TextMarkupSample(option, color)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = textMarkupKindLabel(option), fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Opaque colors: the highlights multiply the colors of the PDF
            ColorWheel(
                color = color,
                onColorChanged = { onColorChanged(it.copy(alpha = 1f)) },
                showAlphaSlider = false
            )
        }
    }
}
