package io.github.valerioisufi.pageboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import io.github.valerioisufi.pageboard.model.TextMarkupKind
import io.github.valerioisufi.pageboard.ui.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Name of a kind of mark of the text of the PDFs. */
@Composable
internal fun textMarkupKindLabel(kind: TextMarkupKind): String = stringResource(
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
internal fun TextMarkupSample(kind: TextMarkupKind, color: Color, modifier: Modifier = Modifier) {
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
internal fun TextMarkupPresetButton(
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
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(
                width = if (isSelected) 2.5.dp else 1.dp,
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
