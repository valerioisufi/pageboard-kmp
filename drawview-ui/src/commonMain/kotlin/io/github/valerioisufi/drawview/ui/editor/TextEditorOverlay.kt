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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.core.controller.DrawViewController

/**
 * The editor of the text being written (see `TextEditorManager`): its field over the canvas and
 * its formatting bar above the software keyboard; the text is kept above both.
 *
 * No pointer input of its own: outside the field and the bar the canvas keeps its gestures (a tap
 * outside saves the text).
 */
@Composable
fun TextEditorOverlay(controller: DrawViewController) {
    val editor = controller.textEditorManager
    if (!editor.isEditing || !controller.isDrawingMode) return
    // Every editing session starts from a fresh state
    key(editor.sessionId) {
        TextEditorSession(controller)
    }
}

@Composable
private fun TextEditorSession(controller: DrawViewController) {
    val editor = controller.textEditorManager
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val keyboardController = LocalSoftwareKeyboardController.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // Height of the formatting bar: the edited text is kept above it
    var barHeightPx by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        onDispose { keyboardController?.hide() }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val containerHeightPx = constraints.maxHeight.toFloat()

        TextBoxEditor(controller, onLayout = { layout = it })

        TextFormatBar(
            editor,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = with(density) { imeBottom.toDp() } + 8.dp)
                .onSizeChanged { barHeightPx = it.height }
        )

        // Keeps the edited text above the software keyboard and the formatting bar. Not keyed on
        // the layout itself, which changes at every zoom step: it would fight a pinch
        val lineCount = layout?.lineCount ?: 0
        LaunchedEffect(editor.value.text, lineCount, imeBottom, containerHeightPx, barHeightPx) {
            val current = layout ?: return@LaunchedEffect
            if (current.lineCount == 0) return@LaunchedEffect
            val top = controller.pageMmToScreen(editor.activeTextPageIndex)?.mapPoint(editor.boxX, editor.boxY)?.y ?: return@LaunchedEffect
            val textBottom = top + current.getLineBottom(current.lineCount - 1)
            // The bar sits 8 dp above the keyboard; the box border and its handles extend below the
            // last line: 32 dp of free space above the bar
            val marginPx = maxOf(with(density) { 80.dp.toPx() }, barHeightPx + with(density) { 40.dp.toPx() })
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
