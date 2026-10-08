package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.text.TextSizes.PT_TO_MM
import io.github.valerioisufi.drawview.ui.generated.resources.Res
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_action_move
import io.github.valerioisufi.drawview.ui.render.toTextAlign
import io.github.valerioisufi.drawview.ui.theme.rememberNotesFonts
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The field of the text being written, over the canvas where the text is on its page: the size
 * and the place follow the zoom and the camera, the lines wrap on the width of the box as the
 * canvas wraps them, the Markdown delimiters away from the caret are concealed.
 *
 * It shows and changes the state of the [io.github.valerioisufi.drawview.core.controller.TextEditorManager]
 * of [controller]; the height it lays the text out is reported to it.
 *
 * @param onLayout the layout of the text, in pixels of the screen from the top of the box.
 */
@Composable
internal fun TextBoxEditor(controller: DrawViewController, onLayout: (TextLayoutResult) -> Unit) {
    val editor = controller.textEditorManager
    // Edited with the fonts the canvas draws it with
    val notesFonts = rememberNotesFonts()
    // Recomposes on every camera movement (fling and bounce included) and page change
    @Suppress("UNUSED_VARIABLE")
    val frame = controller.version
    @Suppress("UNUSED_VARIABLE")
    val camera = controller.cameraPhysics.moveCount
    val mmToScreen = controller.pageMmToScreen(editor.activeTextPageIndex) ?: return

    // Scale and position anchored to the page (page millimeters -> screen pixels)
    val scale = mmToScreen.scaleFactor.coerceAtLeast(0.01f)
    val origin = mmToScreen.mapPoint(editor.boxX, editor.boxY)
    // Read by the drag handlers, which must not restart when the canvas moves during a drag
    val currentScale by rememberUpdatedState(scale)

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    // Font sizes follow the page, not the accessibility font scale
    val unscaledDensity = remember(density.density) { Density(density = density.density, fontScale = 1.0f) }
    val fontSizeSp = with(unscaledDensity) { (editor.fontSize * PT_TO_MM * scale).toSp() }
    val textColor = Color(editor.color)
    // Exact width of the box in pixels, the same used to lay out the text on the canvas
    val widthDp = with(unscaledDensity) { (editor.boxWidth * scale).toInt().coerceAtLeast(1).toDp() }
    val primary = MaterialTheme.colorScheme.primary
    // Outer frame: does not move the sub-pixel origin of the glyphs
    val borderPaddingPx = with(unscaledDensity) { BORDER_PADDING.toPx() }

    CompositionLocalProvider(LocalDensity provides unscaledDensity) {
        // Text box with the selection frame and the word-wrap handles
        Box(
            modifier = Modifier
                .offset { IntOffset((origin.x - borderPaddingPx).roundToInt(), (origin.y - borderPaddingPx).roundToInt()) }
                .width(widthDp + BORDER_PADDING * 2)
                .border(width = 1.dp, color = primary.copy(alpha = 0.6f), shape = RoundedCornerShape(4.dp))
                .padding(BORDER_PADDING)
        ) {
            BasicTextField(
                value = editor.value,
                onValueChange = editor::updateValue,
                modifier = Modifier
                    .width(widthDp)
                    .focusRequester(focusRequester),
                textStyle = TextStyle(
                    color = textColor,
                    fontSize = fontSizeSp,
                    fontFamily = notesFonts.text,
                    letterSpacing = 0.sp,
                    lineHeight = TextUnit.Unspecified,
                    textAlign = editor.alignment.toTextAlign()
                ),
                cursorBrush = SolidColor(primary),
                visualTransformation = { annotated ->
                    TransformedText(
                        MarkdownTextUtil.toLiveAnnotatedString(
                            text = annotated.text,
                            selection = editor.value.selection,
                            defaultColor = textColor,
                            baseFontSizeSp = fontSizeSp,
                            monospace = notesFonts.monospace
                        ),
                        OffsetMapping.Identity
                    )
                },
                onTextLayout = { result ->
                    onLayout(result)
                    editor.reportLayoutHeight(result.size.height / currentScale)
                    // The caret on the character the user touched
                    editor.takeCaretRequest()?.let { local ->
                        val offset = result.getOffsetForPosition(Offset(local.x * currentScale, local.y * currentScale))
                        editor.updateValue(editor.value.copy(selection = TextRange(offset)))
                    }
                }
            )

            // Right word-wrap handle
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = 10.dp)
                    .size(width = 12.dp, height = 28.dp)
                    .clip(CircleShape)
                    .background(primary)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            editor.resizeBy(dragAmount.x / currentScale, leftEdge = false)
                        }
                    }
            )

            // Left word-wrap handle
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (-10).dp)
                    .size(width = 12.dp, height = 28.dp)
                    .clip(CircleShape)
                    .background(primary)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            editor.resizeBy(dragAmount.x / currentScale, leftEdge = true)
                        }
                    }
            )

            // Move handle: drags the box on the page while its text is being edited
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = -MOVE_HANDLE_SIZE - 6.dp)
                    .size(MOVE_HANDLE_SIZE)
                    .clip(CircleShape)
                    .background(primary)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            editor.moveBy(dragAmount.x / currentScale, dragAmount.y / currentScale)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.OpenWith,
                    contentDescription = stringResource(Res.string.text_editor_action_move),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }
}

private val BORDER_PADDING = 4.dp
private val MOVE_HANDLE_SIZE = 28.dp
