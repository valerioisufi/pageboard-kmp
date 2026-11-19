package io.github.valerioisufi.pageboard.explorer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.explorer.generated.resources.Res
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_dialog_new_note
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_fab_item_folder
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_fab_item_note
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_fab_state_collapsed
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_fab_state_expanded
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_fab_toggle_menu
import io.github.valerioisufi.pageboard.ui.theme.DrawViewElevation
import io.github.valerioisufi.pageboard.ui.theme.DrawViewIcons
import io.github.valerioisufi.pageboard.ui.theme.elevation
import org.jetbrains.compose.resources.stringResource

/** The shape of the floating action button. */
private val FabShape = RoundedCornerShape(20.dp)

/**
 * The floating button making a new note: extended ("New note") where there is room, an icon on a
 * phone. With [withMenu] it opens the choice of a note or a folder ([expanded]), the button
 * becoming the one closing it; without, it makes a note (a wide library makes the folders from its
 * pane).
 */
@Composable
internal fun NewItemButton(
    commands: LibraryCommands,
    extended: Boolean,
    withMenu: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AnimatedVisibility(visible = expanded, enter = fadeIn() + scaleIn(transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)), exit = fadeOut() + scaleOut(transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f))) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(end = 4.dp)) {
                MenuEntry(DrawViewIcons.FolderPlus, stringResource(Res.string.explorer_fab_item_folder), MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface) {
                    onExpandedChange(false)
                    commands.newFolder.perform()
                }
                MenuEntry(DrawViewIcons.FilePlus, stringResource(Res.string.explorer_fab_item_note), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer) {
                    onExpandedChange(false)
                    commands.newNote.perform()
                }
            }
        }
        val label = stringResource(Res.string.explorer_dialog_new_note)
        val state = stringResource(if (expanded) Res.string.explorer_fab_state_expanded else Res.string.explorer_fab_state_collapsed)
        val toggle = stringResource(Res.string.explorer_fab_toggle_menu)
        Surface(
            onClick = { if (withMenu) onExpandedChange(!expanded) else commands.newNote.perform() },
            shape = FabShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier
                .elevation(DrawViewElevation.Level2, FabShape)
                .semantics {
                    if (withMenu) {
                        contentDescription = toggle
                        stateDescription = state
                    } else {
                        contentDescription = label
                    }
                }
        ) {
            if (extended && !expanded) {
                Row(
                    modifier = Modifier.height(56.dp).padding(start = 20.dp, end = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(DrawViewIcons.Plus, contentDescription = null, modifier = Modifier.size(24.dp))
                    Text(label, style = MaterialTheme.typography.titleSmall)
                }
            } else {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
                    Icon(if (expanded) DrawViewIcons.Close else DrawViewIcons.Plus, contentDescription = null, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

/** An entry of the menu of the floating button: its label beside its button. */
@Composable
private fun MenuEntry(icon: ImageVector, label: String, container: Color, content: Color, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .elevation(DrawViewElevation.Level1, RoundedCornerShape(16.dp))
                .background(container, RoundedCornerShape(16.dp))
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
        }
    }
}

/** What the open menu of the floating button lays over the library: a scrim closing it when tapped. */
@Composable
internal fun NewItemScrim(expanded: Boolean, onDismiss: () -> Unit) {
    AnimatedVisibility(visible = expanded, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.24f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
        )
    }
}
