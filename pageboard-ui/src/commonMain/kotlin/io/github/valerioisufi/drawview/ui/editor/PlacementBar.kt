package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.ui.command.Command
import io.github.valerioisufi.drawview.ui.command.CommandGroup
import io.github.valerioisufi.drawview.ui.command.RegisterCommands
import io.github.valerioisufi.drawview.ui.command.Shortcut
import io.github.valerioisufi.drawview.ui.generated.resources.*
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import org.jetbrains.compose.resources.stringResource

/**
 * The bar of a pending placement (see
 * [io.github.valerioisufi.drawview.canvas.controller.PlacementManager]): what is placed and where,
 * the places proposed gone through (‹ ›, Tab and the arrows), cancel and confirm (Return); Escape
 * cancels as the system back does.
 */
@Composable
internal fun PlacementBar(controller: DrawViewController, modifier: Modifier = Modifier) {
    val placement = controller.placementManager
    val commands = remember(placement) {
        val active = { placement.isActive }
        val several = { placement.proposals.size > 1 }
        listOf(
            Command(
                id = "placement.next",
                label = { stringResource(Res.string.placement_next) },
                group = CommandGroup.EDIT,
                section = 9,
                icon = { rememberVectorPainter(DrawViewIcons.ChevronRight) },
                shortcuts = listOf(Shortcut(Key.Tab), Shortcut(Key.DirectionRight), Shortcut(Key.DirectionDown)),
                isEnabled = { active() && several() }
            ) { placement.next() },
            Command(
                id = "placement.previous",
                label = { stringResource(Res.string.placement_previous) },
                group = CommandGroup.EDIT,
                section = 9,
                icon = { rememberVectorPainter(DrawViewIcons.ChevronLeft) },
                shortcuts = listOf(Shortcut(Key.Tab, shift = true), Shortcut(Key.DirectionLeft), Shortcut(Key.DirectionUp)),
                isEnabled = { active() && several() }
            ) { placement.previous() },
            Command(
                id = "placement.confirm",
                label = { stringResource(Res.string.placement_confirm) },
                group = CommandGroup.EDIT,
                section = 9,
                icon = { rememberVectorPainter(DrawViewIcons.Check) },
                shortcuts = listOf(Shortcut(Key.Enter), Shortcut(Key.NumPadEnter)),
                isEnabled = { active() && placement.canConfirm }
            ) { placement.confirm() }
        )
    }
    val request = placement.request
    if (request != null) RegisterCommands(commands)

    AnimatedVisibility(
        visible = request != null,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -40 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -40 }),
        modifier = modifier
    ) {
        val current = request ?: return@AnimatedVisibility
        val spot = placement.spots.singleOrNull()
        val proposals = placement.proposals
        ActionBar(modifier = Modifier.padding(top = 16.dp), height = 56) {
            Column(modifier = Modifier.padding(start = 10.dp, end = 8.dp).widthIn(max = 280.dp)) {
                val hint = stringResource(if (spot == null) Res.string.placement_hint_tap else Res.string.placement_hint)
                Text(
                    current.label.ifEmpty { hint },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val where = when {
                    spot == null -> null
                    spot.newPageAfter -> stringResource(Res.string.placement_new_page, spot.pageIndex + 1)
                    proposals.size > 1 && placement.spotIndex >= 0 ->
                        stringResource(Res.string.placement_spot, placement.spotIndex + 1, proposals.size, spot.pageIndex + 1)
                    current.label.isNotEmpty() -> hint
                    else -> null
                }
                if (where != null) {
                    Text(where, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (proposals.size > 1) {
                BarAction(DrawViewIcons.ChevronLeft, stringResource(Res.string.placement_previous), { placement.previous() }, command = commands[1])
                BarAction(DrawViewIcons.ChevronRight, stringResource(Res.string.placement_next), { placement.next() }, command = commands[0])
            }
            BarDivider()
            BarAction(DrawViewIcons.Close, stringResource(Res.string.common_button_cancel), { placement.cancel() })
            if (placement.canConfirm) DoneAction(stringResource(Res.string.placement_confirm), { placement.confirm() }, DrawViewIcons.Check)
        }
    }
}
