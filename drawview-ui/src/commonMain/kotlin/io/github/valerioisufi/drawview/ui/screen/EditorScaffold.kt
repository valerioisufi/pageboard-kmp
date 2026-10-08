package io.github.valerioisufi.drawview.ui.screen

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.ui.command.HoverTooltip
import io.github.valerioisufi.drawview.ui.generated.resources.Res
import io.github.valerioisufi.drawview.ui.generated.resources.common_action_close
import io.github.valerioisufi.drawview.ui.session.EditorPanel
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

/**
 * The frame of the editor under its bars: the canvas and the panels of the extensions, placed by
 * the width the editor has (not the window: the expert of the space is the container, see
 * [PanelPlacement]). Beside the canvas they share its height; otherwise they rise from the bottom
 * over it, the canvas staying visible above them.
 */
@Composable
internal fun EditorScaffold(
    panels: List<EditorPanel>,
    modifier: Modifier = Modifier,
    canvas: @Composable BoxScope.() -> Unit
) {
    BoxWithConstraints(modifier = modifier) {
        val open = panels.filter { it.isOpen() }
        val placement = PanelPlacement.of(maxWidth, open.size)
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                canvas()
                if (placement == PanelPlacement.BOTTOM) {
                    PanelSheet(open, Modifier.align(Alignment.BottomCenter))
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

/** A pane beside the canvas, as high as it, clear of the bars and the cutouts of the system. */
@Composable
private fun SidePane(content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxHeight()) {
        VerticalDivider()
        // A surface, so that the texts take the content color of the theme
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .width(PanelPlacement.PanelWidth)
                .fillMaxHeight()
        ) {
            Box(modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom))) {
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
private fun PanelSheet(open: List<EditorPanel>, modifier: Modifier) {
    var tall by remember { mutableStateOf(false) }
    val fraction by animateFloatAsState(if (tall) SheetTallFraction else SheetFraction)
    // The panels stay drawn while the sheet goes down
    val last = remember { LastShown() }
    if (open.isNotEmpty()) last.panels = open
    val shown = last.panels
    AnimatedVisibility(
        visible = open.isNotEmpty(),
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(fraction)
        ) {
            // Above the bar of the system (the home indicator)
            Column(modifier = Modifier.navigationBarsPadding()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { tall = !tall }
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, dragAmount ->
                                if (dragAmount < -SheetDragThreshold) tall = true
                                if (dragAmount > SheetDragThreshold) {
                                    if (tall) tall = false else shown.forEach { it.onClose() }
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
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = selected.title(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            selected.actions(this)
            val close = stringResource(Res.string.common_action_close)
            HoverTooltip(close) {
                IconButton(onClick = selected.onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = close)
                }
            }
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
