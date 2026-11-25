package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.pageboard.canvas.canvasGesturesOverField
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.canvas.observe
import io.github.valerioisufi.pageboard.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.pageboard.canvas.text.TableEditing
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import io.github.valerioisufi.pageboard.model.geometry.Point
import io.github.valerioisufi.pageboard.model.text.RichTextParser
import io.github.valerioisufi.pageboard.model.text.TextBackground
import io.github.valerioisufi.pageboard.model.text.TextSizes.PT_TO_MM
import io.github.valerioisufi.pageboard.ui.adaptive.currentAdaptiveInfo
import io.github.valerioisufi.pageboard.ui.command.Shortcut
import io.github.valerioisufi.pageboard.ui.extension.LocalTextSuggestions
import io.github.valerioisufi.pageboard.ui.extension.TextBeingWritten
import io.github.valerioisufi.pageboard.ui.generated.resources.Res
import io.github.valerioisufi.pageboard.ui.generated.resources.text_editor_formula_invalid
import io.github.valerioisufi.pageboard.ui.render.CodeBlockLayout
import io.github.valerioisufi.pageboard.ui.render.CodeBlockSource
import io.github.valerioisufi.pageboard.ui.render.CodeTheme
import io.github.valerioisufi.pageboard.ui.render.ComposeTextTileAdapter
import io.github.valerioisufi.pageboard.ui.render.FormulaImages
import io.github.valerioisufi.pageboard.ui.render.LruCache
import io.github.valerioisufi.pageboard.ui.render.TableLayout
import io.github.valerioisufi.pageboard.ui.render.codeChips
import io.github.valerioisufi.pageboard.ui.render.toTextAlign
import io.github.valerioisufi.pageboard.ui.theme.rememberNotesFonts
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.stringResource

/**
 * The field of the text being written, over the canvas where the text is on its page, as the page
 * draws it: laid out as the page lays it out (at its resolution, on the width of the box, presented
 * as the page presents it, see [RichTextPresenter]) and drawn scaled by the zoom at the place of the
 * box (see [pageScale]), so that its lines are those of the page at every zoom; the caret, drawn by
 * the editor ([TextCaret]), reveals the source of what it touches; the formula under the caret
 * shows its source, rendered above it.
 *
 * It shows and changes the state of the [io.github.valerioisufi.pageboard.canvas.controller.TextEditorManager]
 * of [controller]; the height it lays the text out is reported to it. Its frame is the one of a
 * selection, drawn by the canvas (see `SelectionFrame.ofEditedText`).
 *
 * @param onCaret where the caret is, once the text it is in has been laid out.
 * @param barBounds where the formatting bar is on the canvas, which the strip of a hidden source avoids.
 */
@Composable
internal fun TextBoxEditor(controller: PageboardController, onCaret: (CaretPlace) -> Unit, barBounds: () -> Rect? = { null }) {
    val editor = controller.textEditorManager
    // Edited with the fonts the canvas draws it with
    val notesFonts = rememberNotesFonts()
    // The scale of the page (page millimeters -> screen pixels), the field placed where the box is
    // (editedBoxOrigin): a zoom scales the drawing of the field, never its layout
    val pageScale by remember(controller) {
        derivedStateOf {
            controller.version
            controller.viewport.camera.moveCount
            controller.viewport.pageMmToScreen(editor.activeTextPageIndex)?.scaleFactor
        }
    }
    if (pageScale == null) return
    // Pixels of the screen per pixel of the layout: read where it is drawn, not composed
    val screenPerLayout = { (pageScale ?: LAYOUT_SCALE).coerceAtLeast(0.01f) / LAYOUT_SCALE }

    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    // Font sizes follow the page, not the accessibility font scale
    val unscaledDensity = remember(density.density) { Density(density = density.density, fontScale = 1.0f) }
    // Laid out at the resolution of the page, as the page lays it out (ComposeTextTileAdapter):
    // the same font size and width in pixels, so the same lines
    val fontSizePx = editor.fontSize * PT_TO_MM * LAYOUT_SCALE
    val fontSizeSp = with(unscaledDensity) { fontSizePx.toSp() }
    val textColor = Color(editor.color)
    val widthPx = (editor.boxWidth * LAYOUT_SCALE).toInt().coerceAtLeast(1)

    // The formulas in the room the page gives them, drawn by images at the size they have on the screen
    val resolver = LocalFontFamilyResolver.current
    val codeMeasurer = remember(resolver) { TextMeasurer(resolver, Density(1f, 1f), LayoutDirection.Ltr, cacheSize = 0) }
    val struts = remember(codeMeasurer, notesFonts) { notesFonts.struts(codeMeasurer) }
    val formulas = controller.engines.formulas
    val roomImages = remember(formulas) { FormulaImages(formulas, maxSize = 32) }
    val screenImages = remember(formulas) { FormulaImages(formulas, maxSize = 32) }
    // The size each image of the room was rendered at, to render it again at its size on the screen
    val formulaSizes = remember(formulas) { HashMap<ImageBitmap, Float>() }
    val latexReady by remember(formulas) { formulas?.isReady ?: MutableStateFlow(false) }.collectAsState()
    // The blocks of code laid out as the page lays them out, those not changed kept while typing
    val highlighter = controller.engines.code
    val codeLayouts = remember(codeMeasurer, notesFonts, highlighter) { LruCache<CodeKey, CodeBlockLayout>(MAX_CODE_LAYOUTS) }
    // The code on the paper of its page, as the page draws it
    val paper by controller.document.observe { pages.getOrNull(editor.activeTextPageIndex)?.background?.backgroundColor ?: TextTileAdapter.NO_PAPER }
    val value = editor.value
    // The suggestion of the extensions after the end of the text (docs/plans/text-box-hook.md)
    val suggestions = LocalTextSuggestions.current?.takeIf { controller.areTextSuggestionsEnabled }
    val suggestionScope = rememberCoroutineScope()
    val suggestionSession = remember(suggestions, suggestionScope) {
        suggestions?.let { s -> SuggestionSession(suggestionScope) { writing -> s.suggester.suggest(s.context, writing) } }
    }
    val asksSuggestion = suggestionSession != null && value.selection.collapsed && value.selection.start == value.text.length &&
        value.text.isNotBlank() && !editor.inTable && !editor.inCodeBlock
    LaunchedEffect(suggestionSession, value.text, value.selection, asksSuggestion) {
        suggestionSession?.changed(
            if (asksSuggestion) TextBeingWritten(value.text, value.selection.start, editor.activeTextPageIndex, editor.activeTextId) else null
        )
    }
    DisposableEffect(suggestionSession) { onDispose { suggestionSession?.drop() } }
    val suggestion = suggestionSession?.suggestionFor(value.text, value.selection.start)?.takeIf { asksSuggestion }
    // The formula the caret is in: drawn in its place while its source is written, its last image
    // kept while what is typed cannot be rendered
    val writing = remember { FormulaBeingWritten() }
    val bodyFont = remember(notesFonts, editor.fontFamily) { notesFonts.familyFor(editor.fontFamily) }
    val bareShown = remember(value.text, value.selection, fontSizePx, editor.color, widthPx, struts, latexReady, codeLayouts, paper, bodyFont) {
        formulaSizes.clear()
        val selection = value.selection
        val atCaret = RichTextParser.parse(value.text).firstOrNull { it.kind == RichTextParser.Kind.FORMULA && selection.min > it.start && selection.max < it.end }
        val caretLatex = atCaret?.let { MarkdownTextUtil.formulaSource(value.text, it) }
        if (atCaret?.start != writing.start) writing.image = null
        writing.start = atCaret?.start ?: -1
        writing.invalid = false
        fun room(latex: String, sizePx: Float): MarkdownTextUtil.RenderedFormula<ImageBitmap>? {
            val rendered = roomImages.formula(latex, sizePx, editor.color)
            val kept = if (latex == caretLatex) {
                if (rendered != null) writing.image = rendered else writing.invalid = latexReady
                rendered ?: writing.image
            } else {
                rendered
            }
            return kept?.also { formulaSizes[it.image] = sizePx }
        }
        val presentation = Presentation(
            fontSizePx = fontSizePx,
            color = textColor,
            struts = struts,
            density = unscaledDensity.density,
            lineWidthPx = widthPx.toFloat(),
            monospace = notesFonts.monospace,
            symbols = notesFonts.symbols,
            highlighter = highlighter,
            layoutCodeBlock = { source ->
                codeLayouts.getOrPut(CodeKey(source, fontSizePx, editor.color, paper)) {
                    CodeBlockLayout.layout(codeMeasurer, notesFonts.monospace, source, fontSizePx, textColor, highlighter, paper, notesFonts.text)
                }
            },
            // The list items and their blocks where the page draws them
            measurePrefix = { prefix ->
                codeMeasurer.measure(AnnotatedString(prefix), TextStyle(fontSize = fontSizePx.sp, fontFamily = bodyFont, letterSpacing = 0.sp), softWrap = false, skipCache = true)
                    .getHorizontalPosition(prefix.length, usePrimaryDirection = true)
            },
            // The tables laid out as the page lays them out
            measureText = { cell, cellWidth, alignment ->
                TableLayout.measureCell(codeMeasurer, cell, TextStyle(color = textColor, fontSize = fontSizePx.sp, fontFamily = bodyFont, letterSpacing = 0.sp), cellWidth, alignment)
            },
            paperColor = paper
        ) { latex, sizePx -> room(latex, sizePx) }
        RichTextPresenter.present(value.text, presentation, reveal = value.selection)
    }
    // The suggestion after the end, lighter, not part of the text
    val shown = remember(bareShown, suggestion, textColor) { if (suggestion == null) bareShown else GhostText.append(bareShown, suggestion, textColor) }
    /** Inserts the suggestion shown at the caret (the end), as if typed. */
    fun acceptSuggestion(): Boolean {
        val accepted = suggestion ?: return false
        val current = editor.value
        suggestionSession?.drop()
        editor.updateValue(TextFieldValue(current.text + accepted, TextRange(current.text.length + accepted.length)))
        return true
    }
    // The presented text is what the field shows: a new one (a new size, the formulas ready) is
    // shown at once, even with the same value
    val transformation = remember(shown) { VisualTransformation { TransformedText(shown.text, shown.mapping) } }
    val caretShown by TextCaret.rememberShown(focused, value.selection, value.text)
    val codeTheme = remember(editor.color, paper) { CodeTheme.of(textColor, paper) }
    // The cell of the caret when it is in a table, and the color of the caret
    val tableCaret = editor.tableCaret
    val accent = MaterialTheme.colorScheme.primary
    val selectionColor = LocalTextSelectionColors.current.backgroundColor
    val currentShown by rememberUpdatedState(shown)
    // The source the shown text presents
    val shownSource by rememberUpdatedState(value.text)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // Whether a button of a mouse is down on the field: what the field selects then is where the pointer is
    val mouseDown = remember { booleanArrayOf(false) }
    /**
     * Gives the editor what the user did: a move of the caret off what is not shown (see
     * [CaretStops]), as the text is shown now. [pointed] tells that a pointer gave the selection.
     */
    fun change(newValue: TextFieldValue, smartNewline: Boolean = true, pointed: Boolean = false) {
        val current = editor.value
        if (current.text != shownSource) {
            editor.updateValue(newValue, smartNewline)
            return
        }
        // Off the fences of the blocks of code, then off what is not shown
        val offFences = CodeBlockCaret.moved(current, newValue, currentShown) ?: newValue
        val stopped = CaretStops.moved(current, offFences, currentShown.mapping, pointed || mouseDown[0]) ?: offFences
        editor.updateValue(stopped, smartNewline)
    }
    /** The offset of the source under a point of the layout of the field, as it is shown now. */
    fun offsetAt(point: Offset): Int? {
        val current = layout ?: return null
        val text = currentShown
        if (current.layoutInput.text.text != text.text.text) return null
        return codeOffsetAt(current, text, point) ?: text.mapping.transformedToOriginal(current.getOffsetForPosition(point))
    }
    // The selection by a finger or a pen, its handles and its toolbar drawn by the editor
    val touch = remember { TouchSelection() }
    SideEffect { touch.valueChanged(editor.value) }

    // What the field pastes: the formatted text of the clipboard in the notation, out of the blocks of code
    val systemClipboard = LocalClipboard.current
    val clipboardText = rememberClipboardText()
    val fieldClipboard = remember(systemClipboard, clipboardText, editor) {
        FormattedPasteClipboard(
            systemClipboard,
            formattedText = { clipboardText.markdown().takeUnless { editor.inCodeBlock } },
            onFormatted = { editor.formattedPaste = it }
        )
    }
    // "Paste as plain text" is not a paste of the field
    @Suppress("DEPRECATION")
    val plainClipboard = LocalClipboardManager.current
    val conventions = currentAdaptiveInfo().conventions
    val uriHandler = LocalUriHandler.current
    fun openLink(url: String) {
        RichTextParser.openableUrl(url)?.let { safe -> runCatching { uriHandler.openUri(safe) } }
    }

    CompositionLocalProvider(LocalDensity provides unscaledDensity, LocalClipboard provides fieldClipboard) {
        // The session this field was made for
        val session = remember { editor.sessionId }
        // The text this field has: its own, also after it stopped being the one written
        val fieldText = remember { arrayOf(value.text) }
        // Tracks Shift+Enter on a hardware keyboard out of the list items: a plain line break, no list continued
        var lastKeyWasShiftEnter by remember { mutableStateOf(false) }

        // The field where the text is on the page, turned with it around its middle; its frame and
        // handles are drawn and handled by the canvas, as those of a selection
        BasicTextField(
            value = value,
            onValueChange = { newValue ->
                // A field of a session that ended (the text flowed into another box of its chain)
                // may still send what it had, with the keys of the same frame: only those are for
                // the box written now
                val previous = fieldText[0]
                fieldText[0] = newValue.text
                if (editor.sessionId != session) {
                    editor.typedBeforeFlow(session, previous, newValue)
                    return@BasicTextField
                }
                if (lastKeyWasShiftEnter) {
                    lastKeyWasShiftEnter = false
                    change(newValue, smartNewline = false)
                } else {
                    change(newValue)
                }
            },
            modifier = Modifier
                // As large as the box, also when it is larger than the canvas: the lines wrap as on
                // the page, and a field cut by the canvas would scroll inside itself
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .graphicsLayer {
                    // At the exact place of the box, as the page draws it (not on whole pixels)
                    val origin = controller.editedBoxOrigin()
                    translationX = origin.x
                    translationY = origin.y
                    // A text turns around its top-left corner (see TextElement)
                    rotationZ = editor.rotation
                    transformOrigin = TransformOrigin(0f, 0f)
                }
                // The fingers, a wheel and the middle button move the canvas over the field too
                .canvasGesturesOverField(controller)
                // Laid out as the page lays it out, drawn at the zoom; what follows is in the pixels of the layout
                .pageScale(widthPx, screenPerLayout)
                .onFocusChanged { focused = it.hasFocus }
                .focusRequester(focusRequester)
                // A tap on the suggestion takes it
                .pointerInput(suggestion) {
                    if (suggestion == null) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val current = layout ?: return@awaitEachGesture
                        val text = currentShown
                        if (current.layoutInput.text.text != text.text.text) return@awaitEachGesture
                        if (current.getOffsetForPosition(down.position) !in GhostText.rangeIn(text, suggestion)) return@awaitEachGesture
                        down.consume()
                        val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                        up.consume()
                        acceptSuggestion()
                    }
                }
                // A tap on the box of an item of a checklist checks it or unchecks it, the caret where it was
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val current = layout ?: return@awaitEachGesture
                        val box = current.boxAt(currentShown, down.position) ?: return@awaitEachGesture
                        down.consume()
                        val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                        up.consume()
                        if (current.boxAt(currentShown, up.position) === box) editor.toggleCheckedAt(box.itemStart)
                    }
                }
                // Cmd/Ctrl+click on a link opens it without altering selection
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val down = event.changes.firstOrNull { it.changedToDownIgnoreConsumed() } ?: continue
                            val modifiers = event.keyboardModifiers
                            if (!modifiers.isCtrlPressed && !modifiers.isMetaPressed) continue
                            val current = layout ?: continue
                            val text = currentShown
                            if (current.layoutInput.text.text != text.text.text) continue
                            val offset = current.getOffsetForPosition(down.position)
                            val link = text.links.firstOrNull { offset in it.shownStart until it.shownEnd } ?: continue
                            down.consume()
                            val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: continue
                            up.consume()
                            val upOffset = layout?.takeIf { it.layoutInput.text.text == currentShown.text.text }?.getOffsetForPosition(up.position)
                            if (upOffset != null && upOffset in link.shownStart until link.shownEnd) {
                                openLink(link.url)
                            }
                        }
                    }
                }
                // A finger and a pen select as the page shows the text, the handles where it is
                .touchSelectionGestures(
                    touch,
                    offsetAt = ::offsetAt,
                    value = { editor.value },
                    onValue = { change(it, pointed = true) },
                    onTap = {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    }
                )
                // A mouse with a button down selects where it is, until the field has taken its release
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.none { it.type == PointerType.Mouse }) continue
                            if (event.changes.any { it.pressed }) {
                                mouseDown[0] = true
                            } else if (mouseDown[0]) {
                                awaitPointerEvent(PointerEventPass.Final)
                                mouseDown[0] = false
                            }
                        }
                    }
                }
                // A click on an object (a block of code, a formula, a cell) puts the caret on the
                // character clicked, and a drag from it selects through it (the field would take the
                // object for one character)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (down.type != PointerType.Mouse || !down.pressed) return@awaitEachGesture
                        val current = layout ?: return@awaitEachGesture
                        val text = currentShown
                        if (current.layoutInput.text.text != text.text.text) return@awaitEachGesture
                        val anchor = codeOffsetAt(current, text, down.position) ?: return@awaitEachGesture
                        down.consume()
                        change(editor.value.copy(selection = TextRange(anchor)))
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            val end = offsetAt(change.position) ?: continue
                            editor.updateValue(editor.value.copy(selection = TextRange(anchor, end)))
                        }
                    }
                }
                .onPreviewKeyEvent { event ->
                    // A suggestion shown: Tab and the right arrow take it, Escape drops it
                    if (suggestion != null && event.type == KeyEventType.KeyDown && !event.isShiftPressed) {
                        when (event.key) {
                            Key.Tab, Key.DirectionRight -> if (acceptSuggestion()) return@onPreviewKeyEvent true
                            Key.Escape -> {
                                suggestionSession?.drop()
                                return@onPreviewKeyEvent true
                            }
                            else -> {}
                        }
                    }
                    if (event.type == KeyEventType.KeyDown && PASTE_PLAIN.matches(event, conventions)) {
                        plainClipboard.getText()?.text?.let { editor.pasteAtCaret(it) }
                        return@onPreviewKeyEvent true
                    }
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
                    if (event.type == KeyEventType.KeyDown && current.selection.collapsed && !event.isShiftPressed && current.text == shownSource) {
                        // Out of a block of code that ends or starts the text: onto a line added
                        val leaving = when (event.key) {
                            Key.DirectionRight -> CodeBlockCaret.leftAtTheEnds(current, currentShown, forward = true, vertical = false)
                            Key.DirectionDown -> CodeBlockCaret.leftAtTheEnds(current, currentShown, forward = true, vertical = true)
                            Key.DirectionLeft -> CodeBlockCaret.leftAtTheEnds(current, currentShown, forward = false, vertical = false)
                            Key.DirectionUp -> CodeBlockCaret.leftAtTheEnds(current, currentShown, forward = false, vertical = true)
                            else -> null
                        }
                        if (leaving != null) {
                            editor.updateValue(leaving, smartNewline = false)
                            return@onPreviewKeyEvent true
                        }
                        // Through the lines of a block of code and into it, as the block lays them out
                        val laidOut = layout?.takeIf { it.layoutInput.text.text == currentShown.text.text }
                        val target = laidOut?.let {
                            when (event.key) {
                                Key.DirectionUp -> CodeBlockCaret.vertical(it, currentShown, current.selection.start, up = true, ::offsetAt)
                                Key.DirectionDown -> CodeBlockCaret.vertical(it, currentShown, current.selection.start, up = false, ::offsetAt)
                                Key.MoveHome -> CodeBlockCaret.lineEnd(currentShown, current.selection.start, start = true)
                                Key.MoveEnd -> CodeBlockCaret.lineEnd(currentShown, current.selection.start, start = false)
                                else -> null
                            }
                        }
                        if (target != null) {
                            // A move of the caret ends the word the keyboard composes, as the field's own moves do
                            change(current.copy(selection = TextRange(target), composition = null))
                            return@onPreviewKeyEvent true
                        }
                    }
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
                    // What is as wide on the screen at every zoom, in the pixels of the layout
                    val screenPx = 1f / screenPerLayout()
                    editor.background?.let { fill ->
                        val outset = TextBackground.outsetMm(editor.fontSize) * LAYOUT_SCALE
                        val radius = TextBackground.radiusMm(editor.fontSize) * LAYOUT_SCALE
                        drawRoundRect(
                            Color(fill),
                            topLeft = Offset(-outset, -outset),
                            size = Size(size.width + 2 * outset, size.height + 2 * outset),
                            cornerRadius = CornerRadius(radius)
                        )
                    }
                    // Under the text: the backgrounds of the code in the line and the bars of the quotes
                    if (current != null) {
                        for (bar in current.quoteBars(shown.quotes, fontSizePx)) {
                            drawRoundRect(textColor.copy(alpha = textColor.alpha * QUOTE_BAR_ALPHA), topLeft = bar.rect.topLeft, size = bar.rect.size, cornerRadius = CornerRadius(bar.radius))
                        }
                        for (chip in current.codeChips(shown.inlineCode)) {
                            drawRoundRect(codeTheme.background, topLeft = chip.rect.topLeft, size = chip.rect.size, cornerRadius = CornerRadius(chip.radius))
                        }
                    }
                    drawContent()
                    if (current == null) return@drawWithContent
                    val selection = value.selection
                    for (block in shown.codeBlocks) {
                        val origin = current.codeBlockOrigin(block)
                        drawIntoCanvas { block.layout.draw(it, origin.x, origin.y) }
                        // The selection in its code, and the caret in it
                        val from = max(selection.min, block.codeStart) - block.codeStart
                        val to = minOf(selection.max, block.codeEnd) - block.codeStart
                        if (to > from) translate(origin.x, origin.y) { drawPath(block.layout.selectionPath(from, to), selectionColor) }
                        if (selection.collapsed && caretShown && block.holds(selection.start) && tableCaret == null) {
                            val rect = block.layout.cursorRect(selection.start - block.codeStart).translate(origin)
                            with(TextCaret) { drawCaret(rect, TextCaret.CARET_WIDTH.toPx() * screenPx, accent) }
                        }
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
                            style = Stroke(width = 2.dp.toPx() * screenPx)
                        )
                        val length = cell.source.last + 1 - cell.source.first
                        fun inCell(offset: Int) = cell.presented.mapping.originalToTransformed((offset - cell.source.first).coerceIn(0, length))
                        if (selection.collapsed) {
                            // In the source of a formula of the cell, the caret is in the strip
                            if (caretShown && cell.presented.hiddenAtCaret?.caretIn != true) {
                                val rect = cell.layout.getCursorRect(inCell(selection.start))
                                with(TextCaret) { drawCaret(rect.translate(origin), TextCaret.CARET_WIDTH.toPx() * screenPx, accent) }
                            }
                        } else {
                            val path = cell.layout.getPathForRange(inCell(selection.min), inCell(selection.max))
                            translate(origin.x, origin.y) { drawPath(path, accent.copy(alpha = 0.3f)) }
                        }
                    } else if (caretShown && value.selection.collapsed && shown.hiddenAtCaret?.caretIn != true && shown.codeBlocks.none { it.holds(value.selection.start) }) {
                        // In a hidden source (a formula, the address of a link, a fence) the caret is in
                        // the strip, in a block of code in the block; elsewhere the caret of the text,
                        // as wide on the screen at every zoom
                        val rect = current.getCursorRect(shown.mapping.originalToTransformed(value.selection.start).coerceIn(0, current.layoutInput.text.length))
                        with(TextCaret) { drawCaret(rect, TextCaret.CARET_WIDTH.toPx() * screenPx, accent) }
                    }
                    for ((formula, box) in shown.formulas.map { it to current.formulaRect(it) } + current.tableFormulas(shown.tables)) {
                        // Rendered again at the size it has on the screen, sharp at every zoom
                        val image = formulaSizes[formula.image]
                            ?.let { screenImages.render(formula.latex, onScreen(it / screenPx), editor.color)?.bitmap }
                            ?: formula.image
                        drawImage(
                            image,
                            srcSize = IntSize(image.width, image.height),
                            dstOffset = IntOffset(box.left.roundToInt(), box.top.roundToInt()),
                            dstSize = IntSize(formula.widthPx.roundToInt().coerceAtLeast(1), formula.heightPx.roundToInt().coerceAtLeast(1)),
                            filterQuality = FilterQuality.Medium
                        )
                    }
                },
            textStyle = TextStyle(
                color = textColor,
                fontSize = fontSizeSp,
                fontFamily = bodyFont,
                letterSpacing = 0.sp,
                lineHeight = TextUnit.Unspecified,
                textAlign = editor.alignment.toTextAlign()
            ),
            // The caret is drawn by the editor (see TextCaret), in the text or in the cell of a table
            cursorBrush = SolidColor(Color.Transparent),
            visualTransformation = transformation,
            onTextLayout = { result ->
                layout = result
                editor.reportLayoutHeight(result.size.height / LAYOUT_SCALE)
                // The caret on the character the user touched
                editor.takeCaretRequest()?.let { local ->
                    val point = Offset(local.x * LAYOUT_SCALE, local.y * LAYOUT_SCALE)
                    // A tap on the page on the box of an item of a checklist: the item checked or unchecked
                    result.boxAt(currentShown, point)?.let { box -> editor.toggleCheckedAt(box.itemStart) }
                    val offset = codeOffsetAt(result, currentShown, point)
                        ?: currentShown.mapping.transformedToOriginal(result.getOffsetForPosition(point))
                    change(editor.value.copy(selection = TextRange(offset)), pointed = true)
                }
            }
        )

        // The handles of a selection by touch, where the text is on the screen
        fun toScreen(point: Offset): Offset {
            val k = screenPerLayout()
            val origin = controller.editedBoxOrigin()
            val turned = Matrix2D().postRotate(editor.rotation).mapPoint(point.x * k, point.y * k)
            return Offset(origin.x + turned.x, origin.y + turned.y)
        }
        fun toLayout(point: Offset): Offset {
            val k = screenPerLayout()
            val origin = controller.editedBoxOrigin()
            val straight = Matrix2D().postRotate(-editor.rotation).mapPoint(point.x - origin.x, point.y - origin.y)
            return Offset(straight.x / k, straight.y / k)
        }
        fun caretAt(offset: Int): Rect? = layout?.takeIf { it.layoutInput.text.text == currentShown.text.text }
            ?.let { caretRect(it, currentShown, editor.tableCaret, offset) }

        // The source the page hides of what the caret is in, in a strip by it: the text does not move
        val cellAtCaret = tableCaret?.let { c ->
            shown.tables.firstOrNull { it.span.start == c.span.start }?.layout?.cells?.firstOrNull { it.row == c.row && it.column == c.column }
        }
        val hidden = shown.hiddenAtCaret ?: cellAtCaret?.presented?.hiddenAtCaret
        val linkAtCaret = shown.linkAtCaret ?: cellAtCaret?.presented?.linkAtCaret
        if (hidden != null && layout?.layoutInput?.text?.text == shown.text.text) {
            // In a cell, its offsets are those of the cell
            val from = hidden.source.first + if (shown.hiddenAtCaret == null && cellAtCaret != null) cellAtCaret.source.first else 0
            val length = hidden.length
            fun inSource(range: TextRange) = TextRange((range.start - from).coerceIn(0, length), (range.end - from).coerceIn(0, length))
            SourceStrip(
                source = value.text.substring(from, (from + length).coerceAtMost(value.text.length)),
                selection = if (hidden.caretIn) inSource(value.selection) else null,
                composition = value.composition?.takeIf { hidden.caretIn }?.let(::inSource),
                caretShown = caretShown,
                anchor = { hiddenAnchor(layout, currentShown, editor.tableCaret)?.let { rect -> onScreen(rect, ::toScreen) } },
                avoid = barBounds,
                error = if (hidden.kind == RichTextParser.Kind.FORMULA && writing.invalid) stringResource(Res.string.text_editor_formula_invalid) else null,
                onCaret = { change(editor.value.copy(selection = TextRange(from + it))) },
                monospace = notesFonts.monospace,
                onOpenLink = linkAtCaret?.let { url -> { openLink(url) } }
            )
        }
        // No handle on the text while the caret is in the strip: it would stand at the start of the formula
        if (hidden?.caretIn != true) {
            TouchHandles(
                touch,
                value = { editor.value },
                caretRect = ::caretAt,
                toScreen = ::toScreen,
                toLayout = ::toLayout,
                offsetAt = ::offsetAt,
                onValue = { change(it, pointed = true) },
                color = LocalTextSelectionColors.current.handleColor
            )
        }

        // The toolbar of the platform for a selection by touch (copy, cut, paste, select all)
        val textToolbar = LocalTextToolbar.current
        var parentInRoot by remember { mutableStateOf(Offset.Zero) }
        var toolbarShown by remember { mutableStateOf(false) }
        Box(Modifier.onGloballyPositioned { parentInRoot = it.positionInRoot() })
        val selected = value.selection
        val wantsToolbar = touch.toolbar && !touch.dragging
        val start = if (wantsToolbar) caretAt(selected.min) else null
        val end = if (wantsToolbar) caretAt(selected.max) else null
        SideEffect {
            if (start != null && end != null) {
                val corners = listOf(start.topLeft, start.bottomLeft, end.topRight, end.bottomRight).map { toScreen(it) + parentInRoot }
                val rect = Rect(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
                fun copy() {
                    val now = editor.value
                    plainClipboard.setText(AnnotatedString(now.text.substring(now.selection.min, now.selection.max)))
                }
                textToolbar.showMenu(
                    rect,
                    onCopyRequested = if (selected.collapsed) null else ({
                        copy()
                        touch.toolbar = false
                    }),
                    onPasteRequested = {
                        val formatted = clipboardText.markdown().takeUnless { editor.inCodeBlock }
                        val now = editor.value
                        if (formatted != null) {
                            editor.formattedPaste = formatted
                            editor.updateValue(TextFieldValue(now.text.replaceRange(now.selection.min, now.selection.max, formatted), TextRange(now.selection.min + formatted.length)))
                        } else {
                            plainClipboard.getText()?.text?.let { editor.pasteAtCaret(it) }
                        }
                        touch.toolbar = false
                    },
                    onCutRequested = if (selected.collapsed) null else ({
                        copy()
                        val now = editor.value
                        editor.updateValue(TextFieldValue(now.text.removeRange(now.selection.min, now.selection.max), TextRange(now.selection.min)))
                        touch.toolbar = false
                    }),
                    onSelectAllRequested = if (selected.min == 0 && selected.max == value.text.length) null else ({
                        editor.updateValue(editor.value.copy(selection = TextRange(0, editor.value.text.length)))
                        touch.touch(editor.value, TouchSelection.Handles.SELECTION, toolbar = true)
                    })
                )
                toolbarShown = true
            } else if (toolbarShown) {
                if (textToolbar.status == TextToolbarStatus.Shown) textToolbar.hide()
                toolbarShown = false
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    val laidOut = layout?.takeIf { it.layoutInput.text.text == shown.text.text }
    if (laidOut != null) {
        // On the screen, from the corner of the box
        val k = screenPerLayout()
        // In a hidden source, the caret followed is what it belongs to (the source is in the strip)
        val inHidden = (shown.hiddenAtCaret ?: cellHidden(shown, tableCaret))?.caretIn == true
        val inLayout = (if (inHidden) hiddenAnchor(laidOut, shown, tableCaret) else null) ?: caretRect(laidOut, shown, tableCaret, value.selection.end)
        val rect = Rect(inLayout.left * k, inLayout.top * k, inLayout.right * k, inLayout.bottom * k)
        SideEffect { onCaret(CaretPlace(value.text, value.selection, rect)) }
    }
}

/** The resolution the field lays its text out at: the page's (see [ComposeTextTileAdapter]). */
private const val LAYOUT_SCALE = ComposeTextTileAdapter.LAYOUT_PX_PER_MM

/** The size [sizePx] on the screen a formula is rendered at, in steps of [SIZE_STEP] (fewer images while the zoom changes). */
private fun onScreen(sizePx: Float): Float = SIZE_STEP.pow(ceil(ln(sizePx.coerceAtLeast(1f)) / ln(SIZE_STEP)))

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
    // In the code of a block: in the block
    shown.codeBlocks.firstOrNull { it.holds(offset) }?.let { block ->
        return block.layout.cursorRect(offset - block.codeStart).translate(layout.codeBlockOrigin(block))
    }
    return layout.getCursorRect(shown.mapping.originalToTransformed(offset).coerceIn(0, layout.layoutInput.text.length))
}

/**
 * Where the box of the text being written is on the screen. Read while placing, it follows the
 * camera and the pages without composing the field again.
 */
private fun PageboardController.editedBoxOrigin(): Point {
    @Suppress("UNUSED_VARIABLE") val frame = version
    @Suppress("UNUSED_VARIABLE") val camera = viewport.camera.moveCount
    val editor = textEditorManager
    return viewport.pageMmToScreen(editor.activeTextPageIndex)?.mapPoint(editor.boxX, editor.boxY) ?: Point(0f, 0f)
}

/** What a block of code is laid out from: the same key, the same layout. */
private data class CodeKey(val source: CodeBlockSource, val fontPx: Float, val color: Int, val paper: Int)

/**
 * The offset of the text written under [point] of the field when it is on an object: on a formula,
 * the end of its source (opened in the strip); on a block of code drawn away from the caret or on a
 * cell of a table, the character there; else `null`.
 */
private fun codeOffsetAt(layout: TextLayoutResult, shown: PresentedText<*>, point: Offset): Int? {
    shown.formulas.firstOrNull { it.sourceEnd >= 0 && layout.formulaRect(it).contains(point) }?.let { return it.sourceEnd }
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
        val inBlock = Offset(point.x - left, point.y - top)
        // Its label: its opening fence, written in the strip
        if (block.layout.labelRect?.contains(inBlock) == true && block.codeStart > block.start) return block.codeStart - 1
        return block.codeStart + block.layout.codeOffsetAt(inBlock)
    }
    return null
}

/**
 * The formula the caret is in, from its [start]: its last [image] rendered, kept while what is
 * typed in it cannot be rendered ([invalid]), so that its line does not change at every key.
 */
private class FormulaBeingWritten {
    var start: Int = -1
    var image: MarkdownTextUtil.RenderedFormula<ImageBitmap>? = null
    var invalid: Boolean = false
}

/** The hidden source of the cell of the caret in a table ([tableCaret]), if the caret is in one. */
private fun cellHidden(shown: PresentedText<*>, tableCaret: TableEditing.Caret?): HiddenSource? = tableCaret?.let { c ->
    shown.tables.firstOrNull { it.span.start == c.span.start }?.layout?.cells?.firstOrNull { it.row == c.row && it.column == c.column }?.presented?.hiddenAtCaret
}

/**
 * What the hidden source of the caret belongs to, in the pixels of the layout: its formula, the start
 * of its link (its line), the cell of the caret in a table; `null` when not laid out.
 */
private fun hiddenAnchor(layout: TextLayoutResult?, shown: PresentedText<*>, tableCaret: TableEditing.Caret?): Rect? {
    if (layout == null || layout.layoutInput.text.text != shown.text.text) return null
    val hidden = shown.hiddenAtCaret
    if (hidden != null) {
        if (hidden.kind == RichTextParser.Kind.FORMULA) shown.formulas.firstOrNull { it.offset == hidden.anchor }?.let { return layout.formulaRect(it) }
        if (hidden.kind == RichTextParser.Kind.CODE_BLOCK) shown.codeBlocks.firstOrNull { it.offset == hidden.anchor }?.let { block ->
            val origin = layout.codeBlockOrigin(block)
            return Rect(origin.x, origin.y, origin.x + block.layout.widthPx, origin.y + block.layout.heightPx)
        }
        val at = hidden.anchor.coerceIn(0, layout.layoutInput.text.length)
        val line = layout.getLineForOffset(at)
        val x = layout.getHorizontalPosition(at, usePrimaryDirection = true)
        return Rect(x, layout.getLineTop(line), x + 1f, layout.getLineBottom(line))
    }
    val caret = tableCaret ?: return null
    val table = shown.tables.firstOrNull { it.span.start == caret.span.start } ?: return null
    val origin = layout.tableOrigin(table)
    val edges = table.layout
    return Rect(origin.x + edges.columnEdges[caret.column], origin.y + edges.rowEdges[caret.row], origin.x + edges.columnEdges[caret.column + 1], origin.y + edges.rowEdges[caret.row + 1])
}

/** [rect] of the layout of the field on the screen, its corners mapped by [toScreen] (the box may be turned). */
private fun onScreen(rect: Rect, toScreen: (Offset) -> Offset): Rect {
    val corners = listOf(rect.topLeft, rect.topRight, rect.bottomLeft, rect.bottomRight).map(toScreen)
    return Rect(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
}

/** The blocks of code laid out kept by the editor. */
private const val MAX_CODE_LAYOUTS = 16

/** "Paste as plain text" while writing, the shortcut of its command (see `EditorCommands.pastePlain`). */
private val PASTE_PLAIN = Shortcut(Key.V, primary = true, shift = true)

/** Ratio between the sizes the formulas of the editor are rendered at. */
private const val SIZE_STEP = 1.08f
