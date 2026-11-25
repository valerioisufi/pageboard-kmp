package io.github.valerioisufi.pageboard.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.max
import androidx.compose.ui.zIndex
import io.github.valerioisufi.pageboard.ui.components.fadingHorizontalScroll
import io.github.valerioisufi.pageboard.ui.components.ScrolledContent
import io.github.valerioisufi.pageboard.ui.components.peekingHorizontalScroll
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.canvas.observe
import io.github.valerioisufi.pageboard.ui.adaptive.HeightClass
import io.github.valerioisufi.pageboard.ui.adaptive.WindowLayout
import io.github.valerioisufi.pageboard.ui.adaptive.currentAdaptiveInfo
import io.github.valerioisufi.pageboard.ui.command.Command
import io.github.valerioisufi.pageboard.ui.command.CommandMenu
import io.github.valerioisufi.pageboard.ui.command.DesignMenu
import io.github.valerioisufi.pageboard.ui.components.DesignIconButton
import io.github.valerioisufi.pageboard.ui.components.DocumentInfoSelector
import io.github.valerioisufi.pageboard.ui.components.IconButtonStyle
import io.github.valerioisufi.pageboard.ui.components.LocalToolSizes
import io.github.valerioisufi.pageboard.ui.components.ToolSizes
import io.github.valerioisufi.pageboard.ui.generated.resources.*
import io.github.valerioisufi.pageboard.ui.theme.PageboardElevation
import io.github.valerioisufi.pageboard.ui.theme.PageboardIcons
import io.github.valerioisufi.pageboard.ui.theme.elevation
import io.github.valerioisufi.pageboard.ui.window.LocalWindowDecorInsets
import io.github.valerioisufi.pageboard.ui.window.WindowDraggable
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Where the tools of the editor go, by the space of the editor. */
internal enum class ToolbarArrangement {
    /** In the top bar: one bar (a wide window). */
    ONE_BAR,

    /** In a bar under the top one. */
    TWO_BARS,

    /** Floating at the bottom of the canvas, within reach of the thumb (a phone, a short window). */
    FLOATING_TOOLS
}

/**
 * Which arrangement of the bars of the editor fits its width, and which buttons of the top bar
 * fit beside the title (the others go to the "more" menu).
 */
internal object ToolbarLayout {
    /** The height of the top bar (and of the bar of the tools under it). */
    val BarHeight: Dp = 64.dp

    /** The height of the top bar of a phone. */
    val CompactBarHeight: Dp = 52.dp

    /** The height of the tools floating at the bottom. */
    val FloatingHeight: Dp = 56.dp

    /** The width the title of the document keeps in the top bar. */
    val TitleMinWidth: Dp = 160.dp

    /** The widest title of the document beside the tools. */
    val TitleMaxWidth: Dp = 280.dp

    /** A tool button and the space after it. */
    val ToolWidth: Dp = 46.dp

    /** The width of a divider between groups of tools. */
    val DividerWidth: Dp = 17.dp

    /** The margins of a bar, and of the group of the tools. */
    val BarPadding: Dp = 24.dp
    val GroupPadding: Dp = 16.dp

    /** The buttons of the top bar besides the title and the extensions: back, page grid, more. */
    const val TopButtons = 3

    /**
     * The width of the top bar without the tools: its buttons, the actions of the extensions, the
     * title, and the controls of the window at its start [decorWidth] wide (the traffic lights of
     * macOS, `LocalWindowDecorInsets`).
     */
    fun topWidth(button: Dp, extensionsWidth: Dp, buttons: Int = TopButtons, decorWidth: Dp = 0.dp): Dp =
        BarPadding + decorWidth + button * buttons + extensionsWidth + TitleMinWidth

    /** The width of the group of [tools] buttons of tools in [groups] groups, buttons [button] wide at least. */
    fun toolsWidth(button: Dp, tools: Int, groups: Int): Dp =
        max(ToolWidth, button + 2.dp) * tools + DividerWidth * (groups - 1) + GroupPadding

    /**
     * The arrangement of the tools in an editor [width] wide, in a window of [heightClass], with
     * buttons [button] wide, the actions of the extensions [extensionsWidth] wide, [tools]
     * buttons of tools in [groups] groups and the controls of the window [decorWidth] wide at the
     * start of the top bar.
     */
    fun arrange(
        width: Dp,
        heightClass: HeightClass,
        button: Dp,
        extensionsWidth: Dp,
        tools: Int,
        groups: Int,
        decorWidth: Dp = 0.dp,
        toolButton: Dp = button
    ): ToolbarArrangement = when {
        width < WindowLayout.MediumWidth || heightClass == HeightClass.COMPACT -> ToolbarArrangement.FLOATING_TOOLS
        topWidth(button, extensionsWidth, decorWidth = decorWidth) + toolsWidth(toolButton, tools, groups) <= width -> ToolbarArrangement.ONE_BAR
        else -> ToolbarArrangement.TWO_BARS
    }

    /**
     * The widest title beside the tools in one bar: the room they leave in a bar [width] wide, up
     * to [TitleMaxWidth], so that a long title never pushes the buttons after the tools out of
     * the bar.
     */
    fun titleMaxWidth(width: Dp, button: Dp, extensionsWidth: Dp, tools: Int, groups: Int, decorWidth: Dp = 0.dp, toolButton: Dp = button): Dp =
        (width - topWidth(button, extensionsWidth, decorWidth = decorWidth) - toolsWidth(toolButton, tools, groups) + TitleMinWidth)
            .coerceIn(TitleMinWidth, TitleMaxWidth)

    /**
     * Whether the actions of the extensions, [extensionsWidth] wide, keep their place in the top
     * bar beside a title of [TitleMinWidth] and [buttons] other buttons (else they are behind one
     * button).
     */
    fun extensionsFit(width: Dp, button: Dp, extensionsWidth: Dp, buttons: Int, decorWidth: Dp = 0.dp): Boolean =
        topWidth(button, extensionsWidth, buttons, decorWidth) <= width

    /** Whether the page grid keeps its button in the top bar (else it goes to the "more" menu). */
    fun pageGridFits(width: Dp, button: Dp, extensionsWidth: Dp, decorWidth: Dp = 0.dp): Boolean =
        topWidth(button, extensionsWidth, decorWidth = decorWidth) <= width
}

/**
 * The top bar of the editor: back and the page grid (when it fits), the title of the document and
 * what it holds, the tools in their group when they fit in one bar, the actions of the extensions
 * and the "more" menu with the commands left out of the bars.
 *
 * @param toolsMaxWidth the widest group of the tools, or "Read only", in the top bar.
 * @param titleMaxWidth the widest title beside the tools in the top bar.
 * @param location where the document is (its folder), shown under its title.
 * @param extensionsInBar whether the actions of the extensions are in the bar, else behind one
 *   button that shows them in a popover.
 * @param onExtensionsWidth the width the actions of the extensions take, measured.
 * @param readOnlyNote why the document may only be read, beside "Read only" where the tools would be.
 * @param renamer renames the document from its title (none: the title is not changed there).
 */
@Composable
internal fun EditorTopBar(
    controller: PageboardController,
    commands: EditorCommands,
    isLoading: Boolean,
    arrangement: ToolbarArrangement,
    showPageGrid: Boolean,
    toolsMaxWidth: Dp,
    titleMaxWidth: Dp,
    toolbarActions: @Composable RowScope.() -> Unit,
    onExtensionsWidth: (Dp) -> Unit,
    extensionsInBar: Boolean = true,
    location: String? = null,
    readOnlyNote: @Composable () -> String? = { null },
    renamer: DocumentRenamer? = null
) {
    val density = LocalDensity.current
    val compact = arrangement == ToolbarArrangement.FLOATING_TOOLS
    val decorStartPad = LocalWindowDecorInsets.current.calculateStartPadding(LocalLayoutDirection.current)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(1f)
            .height(if (compact) ToolbarLayout.CompactBarHeight else ToolbarLayout.BarHeight)
            .padding(
                start = if (compact) 4.dp + decorStartPad else 12.dp + decorStartPad,
                end = if (compact) 4.dp else 12.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 4.dp)
    ) {
        BarButton(commands.close, PageboardIcons.ArrowLeft, label = stringResource(Res.string.common_action_back))
        if (showPageGrid) {
            BarButton(commands.pageGrid, PageboardIcons.LayoutGrid, selected = controller.isPageGridVisible)
        }

        val subtitle = documentSubtitle(controller, location)
        val toolsInBar = arrangement == ToolbarArrangement.ONE_BAR && controller.isDrawingMode
        // A document read only: why there are no tools, where they would be
        val readOnlyInBar = !compact && controller.isReadOnly && !isLoading
        DocumentInfoSelector(
            document = if (isLoading) null else controller.document,
            subtitle = subtitle,
            location = location,
            renamer = renamer.takeIf { !controller.isReadOnly },
            renameRequests = commands.renameRequests,
            modifier = when {
                toolsInBar -> Modifier.widthIn(max = titleMaxWidth)
                readOnlyInBar -> Modifier.widthIn(max = ToolbarLayout.TitleMaxWidth)
                else -> Modifier.weight(1f)
            }
        )

        if (toolsInBar) {
            WindowDraggable(modifier = Modifier.weight(1f)) { Spacer(modifier = Modifier.fillMaxWidth()) }
            ToolGroup(modifier = Modifier.widthIn(max = toolsMaxWidth)) {
                EditorTools(controller, commands, withImports = true)
            }
            WindowDraggable(modifier = Modifier.weight(1f)) { Spacer(modifier = Modifier.fillMaxWidth()) }
        } else if (readOnlyInBar) {
            WindowDraggable(modifier = Modifier.weight(1f)) { Spacer(modifier = Modifier.fillMaxWidth()) }
            ReadOnlyBadge(readOnlyNote(), floating = false, modifier = Modifier.widthIn(max = toolsMaxWidth))
            WindowDraggable(modifier = Modifier.weight(1f)) { Spacer(modifier = Modifier.fillMaxWidth()) }
        }

        if (extensionsInBar) {
            Row(
                modifier = Modifier.onSizeChanged { with(density) { onExtensionsWidth(it.width.toDp()) } },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                toolbarActions()
            }
        } else {
            ExtensionsButton(toolbarActions, onExtensionsWidth)
        }

        MoreMenu(commands, arrangement, showPageGrid, renamable = renamer != null && !controller.isReadOnly)
    }
}

/**
 * The actions of the extensions where they do not fit beside the title: one button showing them
 * in a popover, closed once one of them is tapped (what they open is kept by their extension, not
 * by their button). Their width is measured there too, so that they come back to the bar when it
 * has room for them.
 */
@Composable
private fun ExtensionsButton(toolbarActions: @Composable RowScope.() -> Unit, onExtensionsWidth: (Dp) -> Unit) {
    val density = LocalDensity.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        DesignIconButton(
            icon = PageboardIcons.Zap,
            label = stringResource(Res.string.editor_action_extensions),
            onClick = { expanded = true },
            selected = expanded,
            tint = MaterialTheme.colorScheme.onSurface
        )
        DesignMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Row(
                modifier = Modifier
                    .onSizeChanged { with(density) { onExtensionsWidth(it.width.toDp()) } }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(pass = PointerEventPass.Initial)
                            if (waitForUpOrCancellation(pass = PointerEventPass.Initial) != null) expanded = false
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                toolbarActions()
            }
        }
    }
}

/**
 * "Analysis · 12 pages": where the document is and how long it is, on every size; the page looked
 * at is said by the indicator over the canvas.
 */
@Composable
private fun documentSubtitle(controller: PageboardController, location: String?): String {
    val total by controller.document.observe { pages.size }
    return listOfNotNull(location, pluralStringResource(Res.plurals.editor_pages_count, total, total)).joinToString(" · ")
}

/** A button of the top bar running [command] (its label and shortcut in the tooltip), its icon [iconSize] wide. */
@Composable
internal fun BarButton(command: Command, icon: ImageVector, label: String = command.label(), selected: Boolean = false, iconSize: Dp = 22.dp) {
    DesignIconButton(
        iconSize = iconSize,
        icon = icon,
        label = label,
        onClick = { command.performIfEnabled() },
        selected = selected,
        enabled = command.isEnabled(),
        shortcut = command.shortcutLabel(currentAdaptiveInfo().conventions),
        tint = MaterialTheme.colorScheme.onSurface
    )
}

/**
 * The group of the tools in the bars: a pill of the container of the surfaces, the tools scrolled
 * when they do not fit; sized for a pen where there is touch (a tablet), with targets of
 * [ToolSizes.PenTarget], as they are as now with a mouse.
 */
@Composable
internal fun ToolGroup(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    if (currentAdaptiveInfo().input.hasTouch) {
        CompositionLocalProvider(LocalToolSizes provides ToolSizes.Pen, LocalMinimumInteractiveComponentSize provides ToolSizes.PenTarget) {
            ToolGroupRow(modifier, content)
        }
    } else {
        ToolGroupRow(modifier, content)
    }
}

@Composable
private fun ToolGroupRow(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .fadingHorizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        content = content
    )
}

/**
 * A document that may only be read, where the tools would be: "Read only" and why ([note], e.g.
 * who decides who edits); in the top bar on the container of the tools, else [floating] at the
 * bottom of the canvas.
 */
@Composable
internal fun ReadOnlyBadge(note: String?, floating: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(22.dp)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .then(if (floating) Modifier.elevation(PageboardElevation.Level2, shape) else Modifier)
            .background(if (floating) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer, shape)
            .height(if (floating) 44.dp else 48.dp)
            .padding(start = 14.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(PageboardIcons.Eye, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(stringResource(Res.string.draw_read_only), style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
        if (note != null) {
            Box(modifier = Modifier.width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Text(note, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The line between two groups of tools. */
@Composable
internal fun ToolDivider() {
    Box(modifier = Modifier.width(ToolbarLayout.DividerWidth - 2.dp), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

/**
 * The "more" button and its menu: the commands left out of the bars (the page grid, the imports
 * under floating tools), the view, the document (its name when [renamable]), the drawing mode and
 * about.
 */
@Composable
private fun MoreMenu(commands: EditorCommands, arrangement: ToolbarArrangement, showPageGrid: Boolean, renamable: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val floating = arrangement == ToolbarArrangement.FLOATING_TOOLS
    Box {
        DesignIconButton(
            icon = PageboardIcons.MoreHorizontal,
            label = stringResource(Res.string.common_action_more_options),
            onClick = { expanded = true },
            tint = MaterialTheme.colorScheme.onSurface
        )
        CommandMenu(
            sections = listOf(
                listOfNotNull(commands.pageGrid.takeIf { !showPageGrid }, commands.addPage) +
                    (if (floating) listOf(commands.importPdf, commands.importImage, commands.importText) else emptyList()),
                listOfNotNull(commands.rename.takeIf { renamable }, commands.fitWidth, commands.stylusOnly, commands.snapping, commands.shapes, commands.ruler, commands.protractor, commands.layers, commands.textSuggestions, commands.documentBackground, commands.drawingMode),
                listOf(commands.about)
            ),
            expanded = expanded,
            onDismissRequest = { expanded = false }
        )
    }
}

/**
 * The tools floating at the bottom of the canvas (a phone, a short window): undo and redo, always
 * in view, then the drawing tools, scrolled when they do not fit; the imports are in the "more"
 * menu.
 */
@Composable
internal fun FloatingTools(controller: PageboardController, commands: EditorCommands, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier = modifier
            .elevation(PageboardElevation.Level2, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .height(ToolbarLayout.FloatingHeight)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        EditorHistory(commands)
        ToolDivider()
        // A tool button and the space after it, to cut the last one shown in its middle: the row
        // holds tool buttons only, all of this size (the dot of the ink is inside the button)
        val target = LocalMinimumInteractiveComponentSize.current.takeIf { it.isSpecified } ?: 0.dp
        val pitch = maxOf(LocalToolSizes.current.button, target) + 2.dp
        val content = remember { ScrolledContent() }
        Row(
            modifier = Modifier.weight(1f, fill = false).peekingHorizontalScroll(rememberScrollState(), pitch, content),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            EditorTools(controller, commands, withImports = false, withHistory = false)
        }
    }
}

/** Where the tools of a phone end: the page and the zoom, "3 / 12 · 100%", on the inverse surface. */
@Composable
internal fun PageIndicator(controller: PageboardController, withZoom: Boolean, modifier: Modifier = Modifier) {
    val state by remember(controller) {
        androidx.compose.runtime.derivedStateOf {
            controller.viewport.camera.moveCount
            controller.version
            val page = (controller.viewport.mostVisiblePageIndex() ?: 0) + 1
            val zoom = kotlin.math.round(controller.viewport.camera.getCurrentScale() * 100f).toInt()
            Triple(page, controller.document.pages.size, zoom)
        }
    }
    val (page, total, zoom) = state
    val shape = CircleShape
    Row(
        modifier = modifier
            .elevation(PageboardElevation.Level2, shape)
            .background(MaterialTheme.colorScheme.inverseSurface, shape)
            .padding(horizontal = if (withZoom) 16.dp else 14.dp, vertical = if (withZoom) 8.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("$page / $total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.inverseOnSurface)
        if (withZoom) {
            Box(modifier = Modifier.size(3.dp).background(MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.6f), CircleShape))
            Text("$zoom%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f))
        }
    }
}

/** An icon button of the tools of an extension (e.g. the assistant), tonal when [active]. */
@Composable
public fun EditorActionButton(icon: ImageVector, label: String, onClick: () -> Unit, active: Boolean = false, highlighted: Boolean = false) {
    DesignIconButton(
        icon = icon,
        label = label,
        onClick = onClick,
        style = if (active || highlighted) IconButtonStyle.Tonal else IconButtonStyle.Standard,
        selected = highlighted,
        tint = if (active || highlighted) Color.Unspecified else MaterialTheme.colorScheme.onSurface
    )
}
