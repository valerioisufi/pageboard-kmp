package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.ui.components.fadingHorizontalScroll
import io.github.valerioisufi.pageboard.ui.command.Command
import io.github.valerioisufi.pageboard.ui.command.CommandTooltip
import io.github.valerioisufi.pageboard.ui.theme.PageboardElevation
import io.github.valerioisufi.pageboard.ui.theme.elevation

/**
 * A bar floating over the canvas (the formatting of a text, the selection, the text of the PDFs):
 * the high container rounded 24 dp, level 2, its actions in a row scrolled when they do not fit
 * (unless [scrolls] is `false`: the content scrolls a part of its own, see [TextFormatBar]).
 */
@Composable
internal fun ActionBar(modifier: Modifier = Modifier, height: Int = 48, scrolls: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape((height / 2).dp)
    Row(
        modifier = modifier
            .elevation(PageboardElevation.Level2, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .height(height.dp)
            .then(if (scrolls) Modifier.fadingHorizontalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        content = content
    )
}

/** The line between two groups of a bar. */
@Composable
internal fun BarDivider() {
    Box(modifier = Modifier.width(9.dp), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

/** A round action of a bar, 40 dp, its icon 20 dp; the tooltip of [command] when given. */
@Composable
internal fun BarAction(icon: ImageVector, label: String, onClick: () -> Unit, command: Command? = null, tint: Color = Color.Unspecified, enabled: Boolean = true) {
    val button = @Composable {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.keepsTextFocus(),
            colors = IconButtonDefaults.iconButtonColors(contentColor = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint)
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
        }
    }
    if (command == null) button() else CommandTooltip(command) { button() }
}

/** The action closing what a bar edits (a text): a check on the primary color. */
@Composable
internal fun DoneAction(label: String, onClick: () -> Unit, icon: ImageVector) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
        ) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
        }
    }
}
