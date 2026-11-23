package io.github.valerioisufi.pageboard.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.ui.components.DesignIconButton
import io.github.valerioisufi.pageboard.ui.extension.EditorPanel
import io.github.valerioisufi.pageboard.ui.generated.resources.Res
import io.github.valerioisufi.pageboard.ui.generated.resources.common_action_close
import io.github.valerioisufi.pageboard.ui.platform.editorInsets
import io.github.valerioisufi.pageboard.ui.theme.PageboardIcons
import org.jetbrains.compose.resources.stringResource

/** Where the open panels of the editor go, by the width the editor has. */
internal enum class PanelPlacement {
    /** Each beside the canvas, side by side. */
    BESIDE,

    /** Beside the canvas, in tabs: there is room for one only. */
    BESIDE_TABS,

    /** Rising from the bottom over the canvas (in tabs when several): no room beside it. */
    BOTTOM;

    companion object {
        /** The width of a panel beside the canvas. */
        val PanelWidth: Dp = 380.dp

        /** The width the canvas keeps beside the panels: a page still readable, as on a phone. */
        val CanvasMinWidth: Dp = 360.dp

        /** Where [open] panels go in an editor [width] wide (none open: where one would go). */
        fun of(width: Dp, open: Int): PanelPlacement = when {
            width - PanelWidth < CanvasMinWidth -> BOTTOM
            width - PanelWidth * open.coerceAtLeast(1) >= CanvasMinWidth -> BESIDE
            else -> BESIDE_TABS
        }
    }
}

/** The part of the canvas a panel rising from the bottom takes, and when made taller. */
private const val SheetFraction = 0.45f
private const val SheetTallFraction = 0.85f

/** A drag of the handle longer than this (in pixels, at once) moves the sheet. */
private const val SheetDragThreshold = 8f

/** How far the content of the sheet of the panels is dragged past its top, or up, to move the sheet. */
private val SheetContentDragThreshold = 48.dp

/**
 * The frame of the editor under its bars: the canvas and the panels of the extensions, placed by
 * the width the editor has (not the window: the expert of the space is the container, see
 * [PanelPlacement]). Beside the canvas they share its height; otherwise they rise from the bottom
 * over it, the canvas staying visible above them.
 *
 * @param canvas the canvas and what floats over it, given the height the panels take at its
 *   bottom (the floating tools stay above them).
 */
@Composable
internal fun EditorScaffold(
    panels: List<EditorPanel>,
    modifier: Modifier = Modifier,
    canvas: @Composable BoxScope.(bottomInset: Dp) -> Unit
) {
    BoxWithConstraints(modifier = modifier) {
        val open = panels.filter { it.isOpen() }
        val placement = PanelPlacement.of(maxWidth, open.size)
        var tall by remember { mutableStateOf(false) }
        val fraction by animateFloatAsState(if (tall) SheetTallFraction else SheetFraction)
        val sheetShown = placement == PanelPlacement.BOTTOM && open.isNotEmpty()
        val bottomInset by animateDpAsState(if (sheetShown) maxHeight * fraction else 0.dp)
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                canvas(bottomInset)
                if (placement == PanelPlacement.BOTTOM) {
                    PanelSheet(open, fraction, onTallChange = { tall = it }, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
            when (placement) {
                PanelPlacement.BESIDE -> for (panel in panels) {
                    AnimatedVisibility(
                        visible = panel.isOpen(),
                        enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                        exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut()
                    ) {
                        SidePane { PanelFrame(listOf(panel)) }
                    }
                }
                PanelPlacement.BESIDE_TABS -> SidePane { PanelFrame(open) }
                PanelPlacement.BOTTOM -> Unit
            }
        }
    }
}

/** A pane beside the bars and the canvas, as high as the editor, clear of the bars and the cutouts of the system. */
@Composable
private fun SidePane(content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxHeight()) {
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // A surface, so that the texts take the content color of the theme
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .width(PanelPlacement.PanelWidth)
                .fillMaxHeight()
        ) {
            Box(modifier = Modifier.windowInsetsPadding(editorInsets().only(WindowInsetsSides.End + WindowInsetsSides.Vertical))) {
                content()
            }
        }
    }
}

/**
 * The panels rising from the bottom over the canvas: their handle makes them taller or shorter
 * (dragged or tapped), dragged down from short it closes them.
 */
@Composable
private fun PanelSheet(open: List<EditorPanel>, fraction: Float, onTallChange: (Boolean) -> Unit, modifier: Modifier) {
    val tall = fraction > (SheetFraction + SheetTallFraction) / 2
    // The panels stay drawn while the sheet goes down
    val last = remember { LastShown() }
    if (open.isNotEmpty()) last.panels = open
    val shown = last.panels
    // Read by the gesture of the handle, which goes on while the sheet changes
    val currentShown by rememberUpdatedState(shown)
    val currentTall by rememberUpdatedState(tall)
    val setTall by rememberUpdatedState(onTallChange)
    AnimatedVisibility(
        visible = open.isNotEmpty(),
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(fraction)
        ) {
            // The content moves the sheet as the handle does, as in the modal sheets
            val threshold = with(LocalDensity.current) { SheetContentDragThreshold.toPx() }
            val connection = remember(threshold) {
                SheetDragConnection(
                    isTall = { currentTall },
                    onTall = { setTall(it) },
                    onClose = { currentShown.forEach { it.onClose() } },
                    threshold = threshold
                )
            }
            // Above the bar of the system (the home indicator)
            Column(modifier = Modifier.navigationBarsPadding().nestedScroll(connection)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { setTall(!currentTall) }
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, dragAmount ->
                                if (dragAmount < -SheetDragThreshold) setTall(true)
                                if (dragAmount > SheetDragThreshold) {
                                    if (currentTall) setTall(false) else currentShown.forEach { it.onClose() }
                                }
                            }
                        }
                        .padding(vertical = 8.dp)
                ) {
                    Box(
                        Modifier
                            .size(width = 32.dp, height = 4.dp)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                    )
                }
                PanelFrame(shown)
            }
        }
    }
}

/** The panels last shown by a sheet (not a state: read in the same composition). */
private class LastShown {
    var panels: List<EditorPanel> = emptyList()
}

/**
 * [panels] in one frame: the header of the one shown (title, actions, close) and, when there are
 * several, their tabs.
 */
@Composable
private fun PanelFrame(panels: List<EditorPanel>) {
    if (panels.isEmpty()) return
    var selectedId by rememberSaveable { mutableStateOf(panels.last().id) }
    val selected = panels.firstOrNull { it.id == selectedId } ?: panels.last()
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(start = if (selected.icon != null) 16.dp else 20.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            selected.icon?.let { icon ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(32.dp)
                        .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape)
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(18.dp))
                }
            }
            Text(
                text = selected.title(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            selected.actions(this)
            DesignIconButton(PageboardIcons.Close, stringResource(Res.string.common_action_close), selected.onClose, tint = MaterialTheme.colorScheme.onSurface)
        }
        if (panels.size > 1) {
            PrimaryTabRow(selectedTabIndex = panels.indexOf(selected)) {
                for (panel in panels) {
                    Tab(
                        selected = panel == selected,
                        onClick = { selectedId = panel.id },
                        text = { Text(panel.title(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
        }
        selected.content(this)
    }
}
