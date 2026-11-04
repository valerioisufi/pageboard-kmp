package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.canvasGesturesOverField
import io.github.valerioisufi.drawview.canvas.observe
import io.github.valerioisufi.drawview.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.drawview.canvas.text.TableEditing
import io.github.valerioisufi.drawview.model.geometry.Point
import io.github.valerioisufi.drawview.model.text.TextBackground
import io.github.valerioisufi.drawview.model.text.TextSizes.PT_TO_MM
import io.github.valerioisufi.drawview.ui.generated.resources.Res
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_formula_invalid
import io.github.valerioisufi.drawview.ui.render.CodeBlockLayout
import io.github.valerioisufi.drawview.ui.render.CodeBlockSource
import io.github.valerioisufi.drawview.ui.render.CodeTheme
import io.github.valerioisufi.drawview.ui.render.TableLayout
import io.github.valerioisufi.drawview.ui.render.ComposeTextTileAdapter
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
 * the box, the text presented as the page presents it (see [RichTextPresenter]), the caret revealing
 * the source of what it touches; the formula under the caret shows its source, rendered above it.
 *
 * It shows and changes the state of the [io.github.valerioisufi.drawview.canvas.controller.TextEditorManager]
 * of [controller]; the height it lays the text out is reported to it. Its frame is the one of a
 * selection, drawn by the canvas (see `SelectionFrame.ofEditedText`).
 *
 * @param onCaret where the caret is, once the text it is in has been laid out.
 */
@Composable
internal fun TextBoxEditor(controller: DrawViewController, onCaret: (CaretPlace) -> Unit) {
    val editor = controller.textEditorManager
    // Edited with the fonts the canvas draws it with
    val notesFonts = rememberNotesFonts()
    // The scale of the page (page millimeters -> screen pixels): composed again by a zoom, not by
    // the other movements of the camera, the field placed where the box is (editedBoxOrigin)
    val pageScale by remember(controller) {
        derivedStateOf {
            controller.version
            controller.viewport.camera.moveCount
            controller.viewport.pageMmToScreen(editor.activeTextPageIndex)?.scaleFactor
        }
    }
    val scale = (pageScale ?: return).coerceAtLeast(0.01f)
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

    // The formulas drawn as the page draws them, in the room the page gives them
    val resolver = LocalFontFamilyResolver.current
    val codeMeasurer = remember(resolver) { TextMeasurer(resolver, Density(1f, 1f), LayoutDirection.Ltr, cacheSize = 0) }
    val struts = remember(codeMeasurer, notesFonts) { notesFonts.struts(codeMeasurer) }
    val formulas = controller.engines.formulas
    val images = remember(formulas) { FormulaImages(formulas, maxSize = 32) }
    val latexReady by remember(formulas) { formulas?.isReady ?: MutableStateFlow(false) }.collectAsState()
    // The blocks of code laid out as the page lays them out, those not changed kept while typing
    val highlighter = controller.engines.code
    val codeLayouts = remember(codeMeasurer, notesFonts, highlighter) { LruCache<CodeKey, CodeBlockLayout>(MAX_CODE_LAYOUTS) }
    // The code on the paper of its page, as the page draws it
    val paper by controller.document.observe { pages.getOrNull(editor.activeTextPageIndex)?.background?.backgroundColor ?: TextTileAdapter.NO_PAPER }
    val value = editor.value
    val shown = remember(value.text, value.selection, fontSizePx, editor.color, widthPx, struts, latexReady, codeLayouts, paper) {
        val presentation = Presentation(
            fontSizePx = fontSizePx,
            color = textColor,
            struts = struts,
            density = unscaledDensity.density,
            lineWidthPx = widthPx.toFloat(),
            monospace = notesFonts.monospace,
            highlighter = highlighter,
            layoutCodeBlock = { source ->
                codeLayouts.getOrPut(CodeKey(source, fontSizePx, editor.color, paper)) {
                    CodeBlockLayout.layout(codeMeasurer, notesFonts.monospace, source, fontSizePx, textColor, highlighter, paper, notesFonts.text)
                }
            },
            // The list items and their blocks where the page draws them
            measurePrefix = { prefix ->
                codeMeasurer.measure(AnnotatedString(prefix), TextStyle(fontSize = fontSizePx.sp, fontFamily = notesFonts.text, letterSpacing = 0.sp), softWrap = false, skipCache = true)
                    .getHorizontalPosition(prefix.length, usePrimaryDirection = true)
            },
            // The tables laid out as the page lays them out
            measureText = { cell, cellWidth, alignment ->
                TableLayout.measureCell(codeMeasurer, cell, TextStyle(color = textColor, fontSize = fontSizePx.sp, fontFamily = notesFonts.text, letterSpacing = 0.sp), cellWidth, alignment)
            },
            paperColor = paper
        ) { latex, sizePx -> renderAtBucket(images, latex, sizePx, editor.color, scale / ComposeTextTileAdapter.LAYOUT_PX_PER_MM) }
        RichTextPresenter.present(value.text, presentation, reveal = value.selection)
    }
    val codeTheme = remember(editor.color, paper) { CodeTheme.of(textColor, paper) }
    // The cell of the caret when it is in a table, and the color of the caret
    val tableCaret = editor.tableCaret
    val accent = MaterialTheme.colorScheme.primary
    val currentShown by rememberUpdatedState(shown)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    CompositionLocalProvider(LocalDensity provides unscaledDensity) {
        // The session this field was made for
        val session = remember { editor.sessionId }
        // Tracks Shift+Enter on a hardware keyboard out of the list items: a plain line break, no list continued
        var lastKeyWasShiftEnter by remember { mutableStateOf(false) }

        // The field where the text is on the page, turned with it around its middle; its frame and
        // handles are drawn and handled by the canvas, as those of a selection
        BasicTextField(
            value = value,
            onValueChange = { newValue ->
                // A field of a session that ended (the text flowed into another box of its chain)
                // may still send what it had: not for the box written now
                if (editor.sessionId != session) return@BasicTextField
                if (lastKeyWasShiftEnter) {
                    lastKeyWasShiftEnter = false
                    editor.updateValue(newValue, smartNewline = false)
                } else {
                    editor.updateValue(newValue)
                }
            },
            modifier = Modifier
                .offset {
                    val origin = controller.editedBoxOrigin()
                    IntOffset(origin.x.roundToInt(), origin.y.roundToInt())
                }
                // As large as the box, also when it is larger than the canvas: the lines wrap as on
                // the page, and a field cut by the canvas would scroll inside itself
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .width(widthDp)
                .graphicsLayer {
                    // A text turns around its top-left corner (see DrawText)
                    rotationZ = editor.rotation
                    transformOrigin = TransformOrigin(0f, 0f)
                }
                .focusRequester(focusRequester)
                // The fingers, a wheel and the middle button move the canvas over the field too
                .canvasGesturesOverField(controller)
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
                    if (event.type == KeyEventType.KeyDown && editor.inTable) {
                        // In a table the caret goes from cell to cell (Tab: see indent)
                        val moved = when (event.key) {
                            Key.DirectionUp -> editor.tableArrow(up = true)
                            Key.DirectionDown -> editor.tableArrow(down = true)
                            Key.DirectionLeft -> !event.isShiftPressed && editor.tableArrow(left = true)
                            Key.DirectionRight -> !event.isShiftPressed && editor.tableArrow()
                            // Shift+Enter too: a cell holds no line break
                            Key.Enter, Key.NumPadEnter -> editor.handleEnter()
                            else -> false
                        }
                        if (moved) return@onPreviewKeyEvent true
                    }
                    val current = editor.value
                    if (event.type == KeyEventType.KeyDown && current.selection.collapsed && !event.isShiftPressed && editor.inChain) {
                        // At the ends of a box of a chain the caret goes on into the box before or after
                        val atStart = current.selection.start == 0
                        val atEnd = current.selection.start == current.text.length
                        val moved = when (event.key) {
                            Key.Backspace -> atStart && editor.backspaceIntoPreviousBox()
                            Key.DirectionUp, Key.DirectionLeft -> atStart && editor.moveInChain(forward = false)
                            Key.DirectionDown, Key.DirectionRight -> atEnd && editor.moveInChain(forward = true)
                            else -> false
                        }
                        if (moved) return@onPreviewKeyEvent true
                    }
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
                                // In a list item, a line break inside it; elsewhere a plain one
                                if (editor.lineBreakInItem()) return@onPreviewKeyEvent true
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
                    // Under everything: the fill of the box, around it as on the page (see TextBackground)
                    editor.background?.let { fill ->
                        val outset = TextBackground.outsetMm(editor.fontSize) * scale
                        val radius = TextBackground.radiusMm(editor.fontSize) * scale
                        drawRoundRect(
                            Color(fill),
                            topLeft = Offset(-outset, -outset),
                            size = Size(size.width + 2 * outset, size.height + 2 * outset),
                            cornerRadius = CornerRadius(radius)
                        )
                    }
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
                        // Around the code being written of a block: the rest of the block
                        for (block in shown.writtenBlocks) {
                            val top = current.getLineTop(current.getLineForOffset(block.offset))
                            drawIntoCanvas { block.layout.drawChrome(it, block.leftPx, top) }
                        }
                    }
                    drawContent()
                    if (current == null) return@drawWithContent
                    for (block in shown.codeBlocks) {
                        val origin = current.codeBlockOrigin(block)
                        drawIntoCanvas { block.layout.draw(it, origin.x, origin.y) }
                    }
                    for (table in shown.tables) {
                        val origin = current.tableOrigin(table)
                        drawIntoCanvas { table.layout.draw(it, origin.x, origin.y) }
                    }
                    // The cell of the caret in a table: its outline, the caret or the selection in it
                    val cellCaret = tableCaret
                    val table = cellCaret?.let { c -> shown.tables.firstOrNull { it.span.start == c.span.start } }
                    val cell = table?.layout?.cells?.firstOrNull { it.row == cellCaret.row && it.column == cellCaret.column }
                    if (table != null && cell != null) {
                        val origin = current.tableOrigin(table) + Offset(cell.left, cell.top)
                        val edges = table.layout
                        val frameLeft = current.tableOrigin(table).x + edges.columnEdges[cell.column]
                        val frameTop = current.tableOrigin(table).y + edges.rowEdges[cell.row]
                        drawRect(
                            accent,
                            topLeft = Offset(frameLeft, frameTop),
                            size = Size(edges.columnEdges[cell.column + 1] - edges.columnEdges[cell.column], edges.rowEdges[cell.row + 1] - edges.rowEdges[cell.row]),
                            style = Stroke(width = 2.dp.toPx())
                        )
                        val length = cell.source.last + 1 - cell.source.first
                        fun inCell(offset: Int) = cell.presented.mapping.originalToTransformed((offset - cell.source.first).coerceIn(0, length))
                        val selection = value.selection
                        if (selection.collapsed) {
                            val rect = cell.layout.getCursorRect(inCell(selection.start))
                            drawRect(accent, topLeft = origin + rect.topLeft, size = Size(2.dp.toPx(), rect.height))
                        } else {
                            val path = cell.layout.getPathForRange(inCell(selection.min), inCell(selection.max))
                            translate(origin.x, origin.y) { drawPath(path, accent.copy(alpha = 0.3f)) }
                        }
                    }
                    for ((formula, box) in shown.formulas.map { it to current.formulaRect(it) } + current.tableFormulas(shown.tables)) {
                        drawImage(
                            formula.image,
                            srcSize = IntSize(formula.image.width, formula.image.height),
                            dstOffset = IntOffset(box.left.roundToInt(), box.top.roundToInt()),
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
            // In a table the caret is drawn in its cell (the table is one object of the field)
            cursorBrush = SolidColor(if (tableCaret != null) Color.Transparent else MaterialTheme.colorScheme.primary),
            visualTransformation = { TransformedText(currentShown.text, currentShown.mapping) },
            onTextLayout = { result ->
                layout = result
                editor.reportLayoutHeight(result.size.height / currentScale)
                // The caret on the character the user touched
                editor.takeCaretRequest()?.let { local ->
                    val point = Offset(local.x * currentScale, local.y * currentScale)
                    val offset = codeOffsetAt(result, currentShown, point)
                        ?: currentShown.mapping.transformedToOriginal(result.getOffsetForPosition(point))
                    editor.updateValue(editor.value.copy(selection = TextRange(offset)))
                }
            }
        )

        // The formula under the caret, rendered above its line (or why it cannot be)
        val atCaret = shown.formulaAtCaret
        val current = layout
        if (atCaret != null && current != null && latexReady && current.layoutInput.text.text == shown.text.text) {
            val start = shown.mapping.originalToTransformed(atCaret.start).coerceIn(0, shown.text.length)
            val anchorX = current.getHorizontalPosition(start, usePrimaryDirection = true)
            val anchorY = current.getLineTop(current.getLineForOffset(start))
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
                        val origin = controller.editedBoxOrigin()
                        placeable.place((origin.x + anchorX).roundToInt(), (origin.y + anchorY - placeable.height - gap).roundToInt().coerceAtLeast(0))
                    }
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    val laidOut = layout?.takeIf { it.layoutInput.text.text == shown.text.text }
    if (laidOut != null) {
        val rect = caretRect(laidOut, shown, tableCaret, value.selection.end)
        SideEffect { onCaret(CaretPlace(value.text, value.selection, rect)) }
    }
}

/**
 * The caret at [offset] of the text written, in pixels from the top-left corner of the field; in a
 * table it is drawn in its cell, of a layout of its own.
 */
private fun caretRect(layout: TextLayoutResult, shown: PresentedText<*>, tableCaret: TableEditing.Caret?, offset: Int): Rect {
    val table = tableCaret?.let { caret -> shown.tables.firstOrNull { it.span.start == caret.span.start } }
    val cell = table?.layout?.cells?.firstOrNull { it.row == tableCaret.row && it.column == tableCaret.column }
    if (table != null && cell != null) {
        val length = cell.source.last + 1 - cell.source.first
        val inCell = cell.presented.mapping.originalToTransformed((offset - cell.source.first).coerceIn(0, length))
        return cell.layout.getCursorRect(inCell).translate(layout.tableOrigin(table) + Offset(cell.left, cell.top))
    }
    return layout.getCursorRect(shown.mapping.originalToTransformed(offset).coerceIn(0, layout.layoutInput.text.length))
}

/**
 * Where the box of the text being written is on the screen. Read while placing, it follows the
 * camera and the pages without composing the field again.
 */
private fun DrawViewController.editedBoxOrigin(): Point {
    @Suppress("UNUSED_VARIABLE") val frame = version
    @Suppress("UNUSED_VARIABLE") val camera = viewport.camera.moveCount
    val editor = textEditorManager
    return viewport.pageMmToScreen(editor.activeTextPageIndex)?.mapPoint(editor.boxX, editor.boxY) ?: Point(0f, 0f)
}

/** What a block of code is laid out from: the same key, the same layout. */
private data class CodeKey(val source: CodeBlockSource, val fontPx: Float, val color: Int, val paper: Int)

/**
 * The offset of the text written under [point] of the field when it is on a block of code drawn
 * away from the caret or on a cell of a table, or `null`.
 */
private fun codeOffsetAt(layout: TextLayoutResult, shown: PresentedText<*>, point: Offset): Int? {
    // A cell of a table: the character tapped in it
    for (table in shown.tables) {
        val origin = layout.tableOrigin(table)
        val cell = table.layout.cellAt(point - origin) ?: continue
        val inCell = point - origin - Offset(cell.left, cell.top)
        val shownOffset = cell.layout.getOffsetForPosition(inCell)
        return cell.source.first + cell.presented.mapping.transformedToOriginal(shownOffset).coerceIn(0, cell.source.last + 1 - cell.source.first)
    }
    for (block in shown.codeBlocks) {
        val (left, top) = layout.codeBlockOrigin(block)
        if (point.y < top || point.y > top + block.layout.heightPx || point.x < left || point.x > left + block.layout.widthPx) continue
        return block.codeStart + block.layout.codeOffsetAt(Offset(point.x - left, point.y - top))
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
 * images while the zoom changes), with the room of the image the canvas renders: its size at the
 * resolution of the canvas, [screenPerCanvas] screen pixels per pixel of the canvas, so that the
 * lines are those of the page.
 */
internal fun renderAtBucket(images: FormulaImages, latex: String, sizePx: Float, color: Int, screenPerCanvas: Float): MarkdownTextUtil.RenderedFormula<ImageBitmap>? {
    if (sizePx <= 0f || screenPerCanvas <= 0f) return null
    val bucket = SIZE_STEP.pow(ceil(ln(sizePx) / ln(SIZE_STEP)))
    val image = images.render(latex, bucket, color) ?: return null
    val page = images.render(latex, sizePx / screenPerCanvas, color) ?: return null
    return MarkdownTextUtil.RenderedFormula(image.bitmap, page.bitmap.width * screenPerCanvas, page.bitmap.height * screenPerCanvas, page.baselineRatio)
}

/** The blocks of code laid out kept by the editor. */
private const val MAX_CODE_LAYOUTS = 16

/** Ratio between the sizes the formulas of the editor are rendered at. */
private const val SIZE_STEP = 1.08f

/** The least size of the text of the formula shown above the caret. */
private val MIN_PREVIEW_TEXT = 18.dp
private val PREVIEW_GAP = 6.dp
