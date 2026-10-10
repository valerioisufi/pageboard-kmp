package io.github.valerioisufi.drawview.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.ui.command.HoverTooltip
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons

/** The colors of a badge: the containers of the palette. */
enum class BadgeTone { Green, Blue, Red, Yellow }

/** A small label of an item (e.g. "Lecture", "Shared", "PDF"): 22 dp, the container of its [tone]. */
@Composable
fun LabelBadge(text: String, tone: BadgeTone, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        BadgeTone.Green -> colors.primaryContainer to colors.onPrimaryContainer
        BadgeTone.Blue -> colors.tertiaryContainer to colors.onTertiaryContainer
        BadgeTone.Red -> colors.errorContainer to colors.onErrorContainer
        BadgeTone.Yellow -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    Row(
        modifier = modifier
            .height(22.dp)
            .background(container, CircleShape)
            .padding(start = if (icon != null) 6.dp else 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(13.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = content, maxLines = 1)
    }
}

/** The look of a [DesignIconButton]. */
enum class IconButtonStyle {
    /** Only the icon (a primary container when selected). */
    Standard,

    /** On a high container (the secondary container when selected). */
    Tonal,

    /** On the primary color. */
    Filled
}

/**
 * A round button of an icon, 40 dp, with the tooltip [label] where a pointer hovers.
 *
 * @param selected a toggle that is on.
 */
@Composable
fun DesignIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: IconButtonStyle = IconButtonStyle.Standard,
    selected: Boolean = false,
    enabled: Boolean = true,
    shortcut: String? = null,
    tint: Color = Color.Unspecified,
    iconSize: Dp = 22.dp
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when {
        style == IconButtonStyle.Filled -> colors.primary to colors.onPrimary
        style == IconButtonStyle.Tonal && selected -> colors.secondaryContainer to colors.onSecondaryContainer
        style == IconButtonStyle.Tonal -> colors.surfaceContainerHigh to colors.onSurfaceVariant
        selected -> colors.primaryContainer to colors.onPrimaryContainer
        else -> Color.Transparent to colors.onSurfaceVariant
    }
    HoverTooltip(label, shortcut = shortcut) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = container,
                contentColor = if (tint.isSpecified) tint else content
            )
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(iconSize))
        }
    }
}

private val Color.isSpecified: Boolean get() = this != Color.Unspecified

/**
 * A chip of a filter: outlined, or in the primary container with a check when [selected]; 36 dp,
 * fully rounded.
 */
@Composable
fun DesignFilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) colors.primaryContainer else Color.Transparent,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
        border = if (selected) null else BorderStroke(1.dp, colors.outlineVariant),
        modifier = modifier.height(36.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = if (selected) 12.dp else 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (selected) Icon(DrawViewIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** A row of chips, scrolled when it does not fit. */
@Composable
fun ChipRow(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(), content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/**
 * The field of a search: 48 dp, fully rounded on the high container, the icon of the search, the
 * button clearing it once something is written, the hint of its [shortcut] where there is a
 * keyboard.
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    shortcut: String? = null,
    clearLabel: String = "",
    focusRequester: FocusRequester? = null
) {
    val colors = MaterialTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    BasicTextField(
        value = query,
        interactionSource = interactions,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(),
        modifier = modifier
            .height(48.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        decorationBox = { field ->
            Row(
                modifier = Modifier
                    .background(colors.surfaceContainerHigh, CircleShape)
                    .then(if (focused) Modifier.border(1.5.dp, colors.primary, CircleShape) else Modifier)
                    .padding(start = 16.dp, end = if (query.isEmpty()) 16.dp else 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(DrawViewIcons.Search, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    field()
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(DrawViewIcons.Close, contentDescription = clearLabel, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                } else if (shortcut != null) {
                    Text(
                        shortcut,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier
                            .background(colors.surfaceContainerHighest, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
    )
}

/**
 * An item of a navigation pane: 48 dp, fully rounded, its icon, its label and the count of what
 * it holds; the primary container when [selected].
 */
@Composable
fun NavigationItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null
) {
    val colors = MaterialTheme.colorScheme
    val content = if (selected) colors.onPrimaryContainer else colors.onSurface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(if (selected) colors.primaryContainer else Color.Transparent, CircleShape)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) content else colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (count != null) {
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = if (selected) content else colors.onSurfaceVariant)
        }
    }
}

/** [content] in the [color] of the content (the icons and texts of a bar). */
@Composable
fun WithContentColor(color: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides color, content = content)
}

/** A message of the design: the inverse surface rounded 12 dp, its action in the inverse primary. */
@Composable
fun DesignSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    Snackbar(
        snackbarData = data,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        containerColor = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        actionColor = MaterialTheme.colorScheme.inversePrimary
    )
}
