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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.core.canvas.SelectionFrame
import io.github.valerioisufi.drawview.core.controller.DrawViewController
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
fun TextEditorOverlay(controller: DrawViewController, commands: EditorCommands) {
    val editor = controller.textEditorManager
    if (!editor.isEditing || !controller.isDrawingMode) return
    // Every editing session starts from a fresh state
    key(editor.sessionId) {
        TextEditorSession(controller, commands)
    }
}

@Composable
private fun TextEditorSession(controller: DrawViewController, commands: EditorCommands) {
    val editor = controller.textEditorManager
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val keyboardController = LocalSoftwareKeyboardController.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // Height of the formatting bar: the edited text is kept above it
    var barHeightPx by remember { mutableIntStateOf(0) }
    val atBottom = currentAdaptiveInfo().window.isCompact || imeBottom > 0

    DisposableEffect(Unit) {
        onDispose { keyboardController?.hide() }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val containerHeightPx = constraints.maxHeight.toFloat()

        TextBoxEditor(controller, onLayout = { layout = it })

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
            modifier = barModifier.onSizeChanged { barHeightPx = it.height }
        )

        // Keeps the edited text above the software keyboard and the formatting bar. Not keyed on
        // the layout itself, which changes at every zoom step: it would fight a pinch
        val lineCount = layout?.lineCount ?: 0
        LaunchedEffect(editor.value.text, lineCount, imeBottom, containerHeightPx, barHeightPx, atBottom) {
            val current = layout ?: return@LaunchedEffect
            if (current.lineCount == 0) return@LaunchedEffect
            val top = controller.pageMmToScreen(editor.activeTextPageIndex)?.mapPoint(editor.boxX, editor.boxY)?.y ?: return@LaunchedEffect
            val textBottom = top + current.getLineBottom(current.lineCount - 1)
            // A bar at the bottom sits 8 dp above the keyboard; the frame extends below the last line
            val marginPx = if (atBottom) {
                maxOf(with(density) { 80.dp.toPx() }, barHeightPx + with(density) { 40.dp.toPx() })
            } else {
                with(density) { 40.dp.toPx() }
            }
            val safeBottom = containerHeightPx - imeBottom - marginPx
            if (textBottom > safeBottom) {
                var previous = 0f
                Animatable(0f).animateTo(textBottom - safeBottom, tween(durationMillis = 250)) {
                    val step = value - previous
                    previous = value
                    controller.panCanvasBy(0f, -step)
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
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
    val height = if (constraints.hasBoundedHeight) constraints.maxHeight else placeable.height
    layout(width, height) {
        // Read while placing: follows the camera and the box without composing again
        @Suppress("UNUSED_VARIABLE") val frameVersion = controller.version
        @Suppress("UNUSED_VARIABLE") val camera = controller.cameraPhysics.moveCount
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
