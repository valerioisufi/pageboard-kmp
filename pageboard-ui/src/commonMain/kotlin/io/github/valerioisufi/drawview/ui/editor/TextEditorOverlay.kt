package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.canvas.SelectionFrame
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import io.github.valerioisufi.drawview.ui.adaptive.currentAdaptiveInfo
import io.github.valerioisufi.drawview.ui.screen.EditorCommands
import kotlin.math.roundToInt

/**
 * The editor of the text being written (see `TextEditorManager`): its field over the canvas and
 * its formatting bar, the text kept above the keyboard and the bar.
 *
 * The bar is where the hands are: above the software keyboard on a phone or when the keyboard is
 * shown, by the frame of the text elsewhere (a tablet with a keyboard, a desktop), above it or
 * below it when there is no room above.
 *
 * No pointer input of its own: outside the field and the bar the canvas keeps its gestures (a tap
 * outside saves the text).
 */
@Composable
internal fun TextEditorOverlay(controller: DrawViewController, commands: EditorCommands) {
    val editor = controller.textEditorManager
    if (!editor.isEditing || !controller.isDrawingMode) return
    TextEditorSession(controller, commands)
}

@Composable
private fun TextEditorSession(controller: DrawViewController, commands: EditorCommands) {
    val editor = controller.textEditorManager
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val keyboardController = LocalSoftwareKeyboardController.current
    var caret by remember { mutableStateOf<CaretPlace?>(null) }
    // Height of the formatting bar: the edited text is kept above it
    var barHeightPx by remember { mutableIntStateOf(0) }
    // Where the bar is on the canvas: the strip of a hidden source avoids it
    var barBounds by remember { mutableStateOf<Rect?>(null) }
    val atBottom = currentAdaptiveInfo().window.isCompact || imeBottom > 0

    DisposableEffect(Unit) {
        onDispose { keyboardController?.hide() }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val containerWidthPx = constraints.maxWidth.toFloat()
        val containerHeightPx = constraints.maxHeight.toFloat()

        // A field for each text written, with its own steps to undo; what is around it stays, the
        // keyboard with it, when a text goes on into another box
        key(editor.sessionId) {
            TextBoxEditor(controller, onCaret = { caret = it }, barBounds = { barBounds })
        }

        // On a phone the bar lies on the keyboard, the whole width
        val docked = currentAdaptiveInfo().window.isCompact
        val barModifier = if (atBottom) {
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = with(density) { imeBottom.toDp() } + if (docked) 0.dp else 8.dp)
        } else {
            Modifier.besideEditedText(controller)
        }
        TextFormatBar(
            target = editor,
            commands = commands,
            onDone = editor::finishEditing,
            docked = docked && atBottom,
            modifier = barModifier.onSizeChanged { barHeightPx = it.height }.onGloballyPositioned { barBounds = it.boundsInParent() }
        )

        // Keeps the caret in sight, above the software keyboard and the formatting bar. Keyed on
        // the text and the caret, not on their place, which changes at every movement of the
        // camera: it would fight a pan or a pinch
        val currentCaret by rememberUpdatedState(caret)
        var textRevealed by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(caret?.text, caret?.selection, imeBottom, containerWidthPx, containerHeightPx, barHeightPx, atBottom) {
            val place = currentCaret ?: return@LaunchedEffect
            // A drag selecting is not followed, its text not changing
            val selecting = !place.selection.collapsed && place.text == textRevealed
            textRevealed = place.text
            if (selecting) return@LaunchedEffect
            val origin = controller.viewport.pageMmToScreen(editor.activeTextPageIndex)?.mapPoint(editor.boxX, editor.boxY) ?: return@LaunchedEffect
            val turned = Matrix2D().postRotate(editor.rotation)
            val start = turned.mapPoint(place.rect.left, place.rect.top)
            val end = turned.mapPoint(place.rect.right, place.rect.bottom)
            val onScreen = Rect(
                origin.x + minOf(start.x, end.x), origin.y + minOf(start.y, end.y),
                origin.x + maxOf(start.x, end.x), origin.y + maxOf(start.y, end.y)
            )
            // A bar at the bottom sits 8 dp above the keyboard; one by the frame comes to the bottom
            // of the canvas when the frame takes it all
            val below = with(density) { if (atBottom) maxOf(80.dp.toPx(), barHeightPx + 40.dp.toPx()) else barHeightPx + 24.dp.toPx() }
            val visible = Rect(0f, 0f, containerWidthPx, containerHeightPx - imeBottom - below)
            val pan = with(density) { CaretReveal.panFor(onScreen, visible, marginX = CARET_MARGIN_X.toPx(), marginY = CARET_MARGIN_Y.toPx()) }
            if (pan != Offset.Zero) {
                var previous = 0f
                Animatable(0f).animateTo(1f, tween(durationMillis = 250)) {
                    val step = value - previous
                    previous = value
                    controller.viewport.panBy(pan.x * step, pan.y * step)
                }
            }
        }
    }
}

/**
 * Places the element by the frame of the text being written: centered on it, above it, or below
 * it when there is no room above; always inside the canvas.
 */
private fun Modifier.besideEditedText(controller: DrawViewController): Modifier = layout { measurable, constraints ->
    // At most the width of the canvas less a gap on each side
    val narrowest = if (constraints.hasBoundedWidth) (constraints.maxWidth - 2 * BAR_GAP.roundToPx()).coerceAtLeast(0) else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = narrowest))
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
    val height = if (constraints.hasBoundedHeight) constraints.maxHeight else placeable.height
    layout(width, height) {
        // Read while placing: follows the camera and the box without composing again
        @Suppress("UNUSED_VARIABLE") val frameVersion = controller.version
        @Suppress("UNUSED_VARIABLE") val camera = controller.viewport.camera.moveCount
        val frame = SelectionFrame.ofEditedText(controller)
        val gap = BAR_GAP.roundToPx()
        if (frame == null) {
            placeable.place((width - placeable.width) / 2, gap)
            return@layout
        }
        val top = frame.corners.minOf { it.y }
        val bottom = frame.corners.maxOf { it.y }
        val centerX = frame.center.x
        val x = (centerX - placeable.width / 2f).roundToInt().coerceIn(gap, (width - placeable.width - gap).coerceAtLeast(gap))
        val above = (top - gap - placeable.height).roundToInt()
        val y = if (above >= gap) above else (bottom + gap).roundToInt().coerceAtMost((height - placeable.height - gap).coerceAtLeast(gap))
        placeable.place(x, y)
    }
}

private val BAR_GAP = 12.dp

/** The room kept between the caret and the edges of what is visible of the canvas. */
private val CARET_MARGIN_X = 24.dp
private val CARET_MARGIN_Y = 16.dp
