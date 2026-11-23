package io.github.valerioisufi.pageboard.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import io.github.valerioisufi.pageboard.ui.command.Command
import io.github.valerioisufi.pageboard.ui.command.CommandTooltip
import io.github.valerioisufi.pageboard.ui.adaptive.windowHeightLimit
import io.github.valerioisufi.pageboard.ui.platform.UnfocusedPopupGuard
import io.github.valerioisufi.pageboard.ui.platform.PopupSystemBarsEffect
import io.github.valerioisufi.pageboard.ui.platform.popupTakesFocus

/** The shape of a tool button. */
private val ToolShape = RoundedCornerShape(16.dp)

/**
 * The sizes of the buttons of the tools of the editor: the [button] drawn, the [icon] of a tool
 * and the [barIcon] of a button of the bar among them (undo, redo, the imports).
 */
internal data class ToolSizes(val button: Dp, val icon: Dp, val barIcon: Dp) {
    internal companion object {
        /** For a thumb: the tools floating on a phone, and the bars of a window without touch. */
        val Thumb: ToolSizes = ToolSizes(44.dp, 24.dp, 22.dp)

        /** For a pen: the bars of a tablet, their targets [PenTarget] wide. */
        val Pen: ToolSizes = ToolSizes(40.dp, 20.dp, 20.dp)

        /** The target of a tool in the bars of a tablet: the minimum of Apple for a finger. */
        val PenTarget: Dp = 44.dp
    }
}

/** The sizes of the buttons of the tools around (see `ToolGroup`). */
internal val LocalToolSizes = staticCompositionLocalOf { ToolSizes.Thumb }

/**
 * A button of the tools of the editor: 44 dp ([LocalToolSizes]), rounded 16 dp, the primary container when [selected],
 * the dot of its [ink] (the color the tool draws with). It supports a long click and the popover
 * of its settings ([dropDownMenu], shown while [expanded]); it takes the minimum interactive size
 * of the input around it (48 dp for a finger, see `AdaptiveRoot`). Once [selected], it is brought
 * into view in a row that scrolls (the tools floating on a phone), whole and past the fade of
 * the row ([ScrollFadeLength]).
 *
 * @param command the command the button shows: its label and shortcut in a tooltip where a
 *   pointer hovers. The click is [onClick] (a tool opens its settings when chosen again).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ToolButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit) = {},
    selected: Boolean = false,
    enabled: Boolean = true,
    dropDownMenu: @Composable ColumnScope.() -> Unit = {},
    expanded: Boolean = false,
    onDismissRequest: () -> Unit = {},
    command: Command? = null,
    ink: Color? = null,
    content: @Composable RowScope.() -> Unit = {}
) {
    Box {
        val colors = MaterialTheme.colorScheme
        val sizes = LocalToolSizes.current
        val inView = remember { BringIntoViewRequester() }
        var buttonSize by remember { mutableStateOf(IntSize.Zero) }
        val density = LocalDensity.current
        LaunchedEffect(selected) {
            if (!selected) return@LaunchedEffect
            // The button and a margin as long as the fade on each side, so it is not shown faded
            val margin = with(density) { ScrollFadeLength.toPx() }
            inView.bringIntoView(Rect(-margin, 0f, buttonSize.width + margin, buttonSize.height.toFloat()))
        }
        val button = @Composable {
            Box(
                contentAlignment = Alignment.Center,
                modifier = modifier
                    .bringIntoViewRequester(inView)
                    .onSizeChanged { buttonSize = it }
                    .minimumInteractiveComponentSize()
                    .size(sizes.button)
                    .clip(ToolShape)
                    .background(if (selected) colors.primaryContainer else Color.Transparent)
                    .combinedClickable(
                        onClick = { onClick() },
                        onLongClick = { onLongClick() },
                        enabled = enabled,
                        role = Role.Button,
                    )
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides when {
                        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.38f)
                        selected -> colors.onPrimaryContainer
                        else -> colors.onSurfaceVariant
                    }
                ) {
                    Row(modifier = Modifier.size(sizes.icon), verticalAlignment = Alignment.CenterVertically) { content() }
                }
                if (ink != null && selected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-6).dp, y = 6.dp)
                            .size(8.dp)
                            .background(ink, CircleShape)
                            .border(1.5.dp, colors.primaryContainer, CircleShape)
                    )
                }
            }
        }
        if (command != null) CommandTooltip(command) { button() } else button()
        ToolPopover(expanded = expanded, onDismissRequest = onDismissRequest, content = dropDownMenu)
    }
}

/**
 * The popover of the settings of a tool, under its button: 300 dp wide, the high container rounded
 * 24 dp, the content with its margins. With a text field ([takesText]) it takes the focus where
 * the others do not (see `popupTakesFocus`).
 */
@Composable
internal fun ToolPopover(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    offset: DpOffset = DpOffset(0.dp, 6.dp),
    takesText: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val focusable = takesText || popupTakesFocus()
    if (expanded && !focusable) UnfocusedPopupGuard(onDismissRequest)
    DropdownMenu(
        modifier = Modifier.windowHeightLimit().width(300.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = offset,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
        properties = PopupProperties(focusable = focusable)
    ) {
        if (focusable) PopupSystemBarsEffect()
        content()
    }
}
