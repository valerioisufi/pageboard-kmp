package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.model.text.TextSizes.PT_TO_MM
import io.github.valerioisufi.drawview.ui.generated.resources.Res
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_formula_invalid
import io.github.valerioisufi.drawview.ui.render.CodeBlockLayout
import io.github.valerioisufi.drawview.ui.render.CodeTheme
import io.github.valerioisufi.drawview.ui.render.FormulaImages
import io.github.valerioisufi.drawview.ui.render.LruCache
import io.github.valerioisufi.drawview.ui.render.codeChips
import io.github.valerioisufi.drawview.ui.render.toTextAlign
import io.github.valerioisufi.drawview.ui.theme.rememberNotesFonts
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.stringResource

/**
 * The field of the text being written, over the canvas where the text is on its page, as the page
 * draws it: the size and the place follow the zoom and the camera, the lines wrap on the width of
 * the box, the Markdown delimiters away from the caret are concealed, the formulas away from the
 * caret are drawn (see [MarkdownTextUtil.toEditorText]); the formula under the caret shows its
 * source, rendered above it.
 *
 * It shows and changes the state of the [io.github.valerioisufi.drawview.canvas.controller.TextEditorManager]
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
    val camera = controller.viewport.camera.moveCount
    val mmToScreen = controller.viewport.pageMmToScreen(editor.activeTextPageIndex) ?: return

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
    val fontSizePx = editor.fontSize * PT_TO_MM * scale
    val fontSizeSp = with(unscaledDensity) { fontSizePx.toSp() }
    val textColor = Color(editor.color)
    // Exact width of the box in pixels, the same used to lay out the text on the canvas
    val widthPx = (editor.boxWidth * scale).toInt().coerceAtLeast(1)
    val widthDp = with(unscaledDensity) { widthPx.toDp() }

    // The formulas drawn as the page draws them
    val resolver = LocalFontFamilyResolver.current
    val room = remember(resolver, notesFonts) { fontRoom(resolver, notesFonts.text) }
    val formulas = controller.engines.formulas
    val images = remember(formulas) { FormulaImages(formulas, maxSize = 32) }
    val latexReady by remember(formulas) { formulas?.isReady ?: MutableStateFlow(false) }.collectAsState()
    // The blocks of code laid out as the page lays them out, those not changed kept while typing
    val highlighter = controller.engines.code
    val codeMeasurer = remember(resolver) { TextMeasurer(resolver, Density(1f, 1f), LayoutDirection.Ltr, cacheSize = 0) }
    val codeLayouts = remember(codeMeasurer, notesFonts, highlighter) { LruCache<CodeKey, CodeBlockLayout>(MAX_CODE_LAYOUTS) }
    val value = editor.value
    val shown = remember(value.text, value.selection, fontSizePx, editor.color, widthPx, room, latexReady, codeLayouts) {
        MarkdownTextUtil.toEditorText(
            text = value.text,
            selection = value.selection,
            defaultColor = textColor,
            fontSizePx = fontSizePx,
            density = unscaledDensity.density,
            lineWidthPx = widthPx.toFloat(),
            room = room,
            textFont = notesFonts.text,
            monospace = notesFonts.monospace,
            highlighter = highlighter,
            layoutCodeBlock = { code, language, indent ->
                codeLayouts.getOrPut(CodeKey(code, language, indent, fontSizePx, widthPx, editor.color)) {
                    CodeBlockLayout.layout(codeMeasurer, notesFonts.monospace, code, language, indent, fontSizePx, widthPx.toFloat(), textColor, highlighter)
                }
            }
        ) { latex, sizePx -> renderAtBucket(images, latex, sizePx, editor.color) }
    }
    val codeTheme = remember(editor.color) { CodeTheme.of(textColor) }
    val currentShown by rememberUpdatedState(shown)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    CompositionLocalProvider(LocalDensity provides unscaledDensity) {
        // Tracks Shift+Enter on hardware keyboard to allow regular line breaks without list continuation
        var lastKeyWasShiftEnter by remember { mutableStateOf(false) }

        // The field where the text is on the page, turned with it around its middle; its frame and
        // handles are drawn and handled by the canvas, as those of a selection
        BasicTextField(
            value = value,
            onValueChange = { newValue ->
                if (lastKeyWasShiftEnter) {
                    lastKeyWasShiftEnter = false
                    editor.updateValue(newValue, smartNewline = false)
                } else {
                    editor.updateValue(newValue)
                }
            },
            modifier = Modifier
                .offset { IntOffset(origin.x.roundToInt(), origin.y.roundToInt()) }
                .width(widthDp)
                .graphicsLayer {
                    rotationZ = editor.rotation
                    transformOrigin = TransformOrigin.Center
                }
                .focusRequester(focusRequester)
                // A tap on a block of code puts the caret on the character tapped (the field would
                // put it at an end of the character standing for the block)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val current = layout ?: return@awaitEachGesture
                        val text = currentShown
                        if (current.layoutInput.text.text != text.text.text) return@awaitEachGesture
                        val offset = codeOffsetAt(current, text, down.position) ?: return@awaitEachGesture
                        down.consume()
                        val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                        up.consume()
                        editor.updateValue(editor.value.copy(selection = TextRange(offset)))
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        if (event.key == Key.Tab) {
                            if (event.isShiftPressed) {
                                editor.outdent()
                            } else {
                                editor.indent()
                            }
                            return@onPreviewKeyEvent true
                        }
                        if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
                            if (event.isShiftPressed) {
                                lastKeyWasShiftEnter = true
                                return@onPreviewKeyEvent false
                            }
                            lastKeyWasShiftEnter = false
                            if (editor.handleEnter()) {
                                return@onPreviewKeyEvent true
                            }
                        }
                    }
                    false
                }
                .drawWithContent {
                    val current = layout?.takeIf { it.layoutInput.text.text == shown.text.text }
                    // Under the text: the backgrounds of the code in the line and of the block of the caret
                    if (current != null) {
                        val radius = CornerRadius(CodeBlockLayout.cornerRadius(fontSizePx))
                        for (source in shown.codeSources) {
                            val top = current.getLineTop(current.getLineForOffset(source.first))
                            val bottom = current.getLineBottom(current.getLineForOffset(max(source.first, source.last)))
                            drawRoundRect(codeTheme.background, topLeft = Offset(0f, top), size = Size(size.width, bottom - top), cornerRadius = radius)
                        }
                        for (chip in current.codeChips(shown.inlineCode)) {
                            drawRoundRect(codeTheme.background, topLeft = chip.rect.topLeft, size = chip.rect.size, cornerRadius = CornerRadius(chip.radius))
                        }
                    }
                    drawContent()
                    if (current == null) return@drawWithContent
                    for (block in shown.codeBlocks) {
                        drawIntoCanvas { block.layout.draw(it, 0f, codeBlockTop(current, block)) }
                    }
                    for (formula in shown.formulas) {
                        val line = current.getLineForOffset(formula.offset)
                        val left = if (formula.block) (size.width - formula.widthPx) / 2f else current.getHorizontalPosition(formula.offset, usePrimaryDirection = true)
                        val top = if (formula.block) {
                            (current.getLineTop(line) + current.getLineBottom(line) - formula.heightPx) / 2f
                        } else {
                            current.getLineBaseline(line) - formula.heightPx * formula.baselineRatio
                        }
                        drawImage(
                            formula.image,
                            srcSize = IntSize(formula.image.width, formula.image.height),
                            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                            dstSize = IntSize(formula.widthPx.roundToInt().coerceAtLeast(1), formula.heightPx.roundToInt().coerceAtLeast(1))
                        )
                    }
                },
            textStyle = TextStyle(
                color = textColor,
                fontSize = fontSizeSp,
                fontFamily = notesFonts.text,
                letterSpacing = 0.sp,
                lineHeight = TextUnit.Unspecified,
                textAlign = editor.alignment.toTextAlign()
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = { TransformedText(currentShown.text, currentShown.offsetMapping) },
            onTextLayout = { result ->
                layout = result
                onLayout(result)
                editor.reportLayoutHeight(result.size.height / currentScale)
                // The caret on the character the user touched
                editor.takeCaretRequest()?.let { local ->
                    val point = Offset(local.x * currentScale, local.y * currentScale)
                    val offset = codeOffsetAt(result, currentShown, point)
                        ?: currentShown.offsetMapping.transformedToOriginal(result.getOffsetForPosition(point))
                    editor.updateValue(editor.value.copy(selection = TextRange(offset)))
                }
            }
        )

        // The formula under the caret, rendered above its line (or why it cannot be)
        val atCaret = shown.formulaAtCaret
        val current = layout
        if (atCaret != null && current != null && latexReady && current.layoutInput.text.text == shown.text.text) {
            val start = shown.offsetMapping.originalToTransformed(atCaret.start).coerceIn(0, shown.text.length)
            val anchorX = origin.x + current.getHorizontalPosition(start, usePrimaryDirection = true)
            val anchorY = origin.y + current.getLineTop(current.getLineForOffset(start))
            val previewPx = max(fontSizePx, with(unscaledDensity) { MIN_PREVIEW_TEXT.toPx() })
            val preview = remember(atCaret, value.text, previewPx, editor.color) {
                images.render(MarkdownTextUtil.formulaSource(value.text, atCaret), MarkdownTextUtil.formulaSizePx(atCaret, previewPx), editor.color)
            }
            FormulaPreview(
                image = preview?.bitmap,
                modifier = Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val gap = PREVIEW_GAP.roundToPx()
                    layout(placeable.width, placeable.height) {
                        placeable.place(anchorX.roundToInt(), (anchorY - placeable.height - gap).roundToInt().coerceAtLeast(0))
                    }
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }
}

/** What a block of code is laid out from: the same key, the same layout. */
private data class CodeKey(val code: String, val language: String, val indent: Int, val fontPx: Float, val widthPx: Int, val color: Int)

/** Where [block] is drawn in its line of [layout]: centered in it, as the page draws it. */
private fun codeBlockTop(layout: TextLayoutResult, block: MarkdownTextUtil.EditorCodeBlock): Float {
    val line = layout.getLineForOffset(block.offset)
    return (layout.getLineTop(line) + layout.getLineBottom(line) - block.layout.heightPx) / 2f
}

/**
 * The offset of the text written under [point] of the field when it is on a block of code drawn
 * away from the caret, or `null`.
 */
private fun codeOffsetAt(layout: TextLayoutResult, shown: MarkdownTextUtil.EditorText<*>, point: Offset): Int? {
    for (block in shown.codeBlocks) {
        val top = codeBlockTop(layout, block)
        if (point.y < top || point.y > top + block.layout.heightPx || point.x < 0f || point.x > block.layout.widthPx) continue
        return block.codeStart + block.layout.codeOffsetAt(Offset(point.x, point.y - top))
    }
    return null
}

/** The formula under the caret: its [image], or a word saying it cannot be read. */
@Composable
private fun FormulaPreview(image: ImageBitmap?, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        if (image != null) {
            Image(image, contentDescription = null, modifier = Modifier.padding(8.dp))
        } else {
            Text(
                stringResource(Res.string.text_editor_formula_invalid),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * [latex] rendered by [images] at the size of the steps of [SIZE_STEP] above [sizePx] (fewer
 * images while the zoom changes), reported at [sizePx].
 */
private fun renderAtBucket(images: FormulaImages, latex: String, sizePx: Float, color: Int): MarkdownTextUtil.RenderedFormula<ImageBitmap>? {
    if (sizePx <= 0f) return null
    val bucket = SIZE_STEP.pow(ceil(ln(sizePx) / ln(SIZE_STEP)))
    val image = images.render(latex, bucket, color) ?: return null
    val k = sizePx / bucket
    return MarkdownTextUtil.RenderedFormula(image.bitmap, image.bitmap.width * k, image.bitmap.height * k, image.baselineRatio)
}

/** The font [font] per pixel of its size (see [MarkdownTextUtil.FontRoom]). */
private fun fontRoom(resolver: FontFamily.Resolver, font: FontFamily): MarkdownTextUtil.FontRoom {
    val measurer = TextMeasurer(resolver, Density(1f, 1f), LayoutDirection.Ltr, cacheSize = 0)
    val size = 1000f
    val line = measurer.measure(AnnotatedString(MarkdownTextUtil.OBJECT_REPLACEMENT), TextStyle(fontSize = size.sp, fontFamily = font, letterSpacing = 0.sp))
    val ascent = line.firstBaseline
    return MarkdownTextUtil.FontRoom(ascent / size, (line.getLineBottom(0) - ascent) / size, line.getBoundingBox(0).width / size)
}

/** The blocks of code laid out kept by the editor. */
private const val MAX_CODE_LAYOUTS = 16

/** Ratio between the sizes the formulas of the editor are rendered at. */
private const val SIZE_STEP = 1.08f

/** The least size of the text of the formula shown above the caret. */
private val MIN_PREVIEW_TEXT = 18.dp
private val PREVIEW_GAP = 6.dp
