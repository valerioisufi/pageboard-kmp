package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.text.TextSizes.PT_TO_MM
import io.github.valerioisufi.drawview.ui.render.toTextAlign
import io.github.valerioisufi.drawview.ui.theme.rememberNotesFonts
import kotlin.math.roundToInt

/**
 * The field of the text being written, over the canvas where the text is on its page: the size
 * and the place follow the zoom and the camera, the lines wrap on the width of the box as the
 * canvas wraps them, the Markdown delimiters away from the caret are concealed.
 *
 * It shows and changes the state of the [io.github.valerioisufi.drawview.core.controller.TextEditorManager]
 * of [controller]; the height it lays the text out is reported to it. Its frame is the one of a
 * selection, drawn by the canvas (see `SelectionFrame.ofEditedText`).
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
    // Read by the layout callback, which must see the scale of the layout it is given
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

    CompositionLocalProvider(LocalDensity provides unscaledDensity) {
        // The field where the text is on the page, turned with it around its middle; its frame and
        // handles are drawn and handled by the canvas, as those of a selection
        BasicTextField(
            value = editor.value,
            onValueChange = editor::updateValue,
            modifier = Modifier
                .offset { IntOffset(origin.x.roundToInt(), origin.y.roundToInt()) }
                .width(widthDp)
                .graphicsLayer {
                    rotationZ = editor.rotation
                    transformOrigin = TransformOrigin.Center
                }
                .focusRequester(focusRequester),
            textStyle = TextStyle(
                color = textColor,
                fontSize = fontSizeSp,
                fontFamily = notesFonts.text,
                letterSpacing = 0.sp,
                lineHeight = TextUnit.Unspecified,
                textAlign = editor.alignment.toTextAlign()
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
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
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }
}
