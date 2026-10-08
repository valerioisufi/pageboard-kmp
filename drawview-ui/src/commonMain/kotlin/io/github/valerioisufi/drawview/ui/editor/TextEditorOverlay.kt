package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.latex.LatexEngine
import io.github.valerioisufi.drawview.ui.components.FontSizeStepper
import io.github.valerioisufi.drawview.ui.components.TextColorPaletteRow
import io.github.valerioisufi.drawview.ui.components.TextColorPickerDialog
import io.github.valerioisufi.drawview.ui.generated.resources.*
import io.github.valerioisufi.drawview.core.text.TextSizes.PT_TO_MM
import io.github.valerioisufi.drawview.ui.theme.rememberNotesFonts
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * On-canvas text editor with Markdown live preview, LaTeX preview, word-wrap handles and a
 * formatting toolbar (above the software keyboard when it is shown).
 *
 * The edited box is anchored in page millimeters, so it follows the page while the canvas is
 * panned and zoomed.
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
    // Edited with the fonts the canvas draws it with
    val notesFonts = rememberNotesFonts()
    val pageIndex = editor.activeTextPageIndex
    // Recomposes on every camera movement (fling and bounce included) and page change
    @Suppress("UNUSED_VARIABLE")
    val frame = controller.version
    @Suppress("UNUSED_VARIABLE")
    val camera = controller.cameraPhysics.moveCount
    val mmToScreen = controller.pageMmToScreen(pageIndex) ?: return

    // Scale and position anchored to the page (page millimeters -> screen pixels)
    val liveScale = mmToScreen.scaleFactor.coerceAtLeast(0.01f)
    val origin = mmToScreen.mapPoint(editor.activeTextMmX, editor.activeTextMmY)
    val liveScreenX = origin.x
    val liveScreenY = origin.y
    // Read by the drag handlers, which must not restart when the canvas moves during a drag
    val currentScale by rememberUpdatedState(liveScale)

    // Raw Markdown text of the block
    val activeItem = editor.activeTextItem
    val initialRawText = activeItem?.text ?: ""
    var textValue by remember {
        mutableStateOf(TextFieldValue(text = initialRawText, selection = TextRange(initialRawText.length)))
    }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    // Font sizes follow the page, not the accessibility font scale
    val unscaledDensity = remember(density.density) { Density(density = density.density, fontScale = 1.0f) }
    val imeBottom = WindowInsets.ime.getBottom(density)
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var actualTextHeightPx by remember { mutableFloatStateOf(10f) }

    // Formatting of the block
    var currentFontSizePt by remember { mutableFloatStateOf(activeItem?.fontSize ?: controller.defaultTextFontSize) }
    var currentColorInt by remember { mutableIntStateOf(activeItem?.color ?: controller.defaultTextColor) }
    var currentAlignment by remember { mutableIntStateOf(activeItem?.alignment ?: 0) }
    var showColorPickerRow by remember { mutableStateOf(false) }
    var showColorWheelDialog by remember { mutableStateOf(false) }
    var showFontSizeSliderRow by remember { mutableStateOf(false) }
    // Height of the formatting toolbar: the edited text is kept above it
    var toolbarHeightPx by remember { mutableIntStateOf(0) }

    val baseFontSizeMm = currentFontSizePt * PT_TO_MM
    val scaledFontSizePx = baseFontSizeMm * liveScale
    val scaledFontSizeSp = with(unscaledDensity) { scaledFontSizePx.toSp() }

    val textColor = Color(currentColorInt)
    val isMarkdownEnabled = editor.isMarkdownLivePreviewEnabled
    var showMathQuickBar by remember { mutableStateOf(editor.isMathMode || activeItem?.isLatex == true) }

    // Exact width of the box in pixels, the same used to lay out the text on the canvas
    val screenWidthPx = (editor.activeBoxWidthMm * liveScale).toInt().coerceAtLeast(1)
    val boxWidthDp = with(unscaledDensity) { screenWidthPx.toDp() }

    val textAlign = when (currentAlignment) {
        1 -> TextAlign.Center
        2 -> TextAlign.End
        else -> TextAlign.Start
    }

    // Outer frame: does not move the sub-pixel origin of the glyphs
    val borderPadding = 4.dp
    val borderPaddingPx = with(unscaledDensity) { borderPadding.toPx() }

    // Live preview of the LaTeX formula
    val latexReady by LatexEngine.isReady.collectAsState()
    val mathPreviewBitmap = remember(textValue.text, currentColorInt, scaledFontSizePx, latexReady, showMathQuickBar) {
        val raw = textValue.text.trim()
        if (latexReady && raw.isNotEmpty() && (editor.isMathMode || showMathQuickBar || raw.contains("$") || raw.startsWith("\\"))) {
            val clean = if (raw.startsWith("$$") && raw.endsWith("$$") && raw.length >= 4) {
                raw.substring(2, raw.length - 2)
            } else if (raw.startsWith("$") && raw.endsWith("$") && raw.length >= 2) {
                raw.substring(1, raw.length - 1)
            } else {
                raw
            }
            LatexEngine.renderToBitmap(clean, scaledFontSizePx * 1.3f, currentColorInt, paddingPx = 8f)
        } else {
            null
        }
    }

    fun saveAndFinish() {
        keyboardController?.hide()
        val isFormula = editor.isMathMode || textValue.text.trim().startsWith("$$")
        val heightMm = (if (isFormula) latexBlockHeightMm(textValue.text, currentFontSizePt, currentColorInt, editor.activeBoxWidthMm) else null)
            ?: (actualTextHeightPx / liveScale)
        editor.finishTextEditing(
            text = textValue.text,
            isLatex = isFormula,
            color = currentColorInt,
            fontSize = currentFontSizePt,
            measuredHeightMm = heightMm,
            alignment = currentAlignment
        )
    }

    // The system back and a tap on the canvas outside the box save the text
    val currentSaveAndFinish by rememberUpdatedState { saveAndFinish() }
    DisposableEffect(editor) {
        editor.finishEditingRequest = { currentSaveAndFinish() }
        onDispose { editor.finishEditingRequest = null }
    }

    // The others working on the document see the text as it is written
    val draftHeightMm = actualTextHeightPx / liveScale
    LaunchedEffect(
        textValue.text, currentColorInt, currentFontSizePt, currentAlignment, isMarkdownEnabled,
        editor.activeBoxWidthMm, editor.activeTextMmX, editor.activeTextMmY, draftHeightMm
    ) {
        val isFormula = editor.isMathMode || textValue.text.trim().startsWith("$$")
        editor.reportDraft(textValue.text, isFormula, currentColorInt, currentFontSizePt, draftHeightMm, currentAlignment)
    }

    // No pointer input of its own: outside the box and the toolbar the canvas keeps its gestures
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val containerHeightPx = constraints.maxHeight.toFloat()
        val primary = MaterialTheme.colorScheme.primary

        CompositionLocalProvider(LocalDensity provides unscaledDensity) {
            // Text box with the selection frame and the word-wrap handles
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (liveScreenX - borderPaddingPx).roundToInt(),
                            (liveScreenY - borderPaddingPx).roundToInt()
                        )
                    }
                    .width(boxWidthDp + borderPadding * 2)
                    .border(
                        width = 1.dp,
                        color = primary.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(borderPadding)
            ) {
                BasicTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    modifier = Modifier
                        .width(boxWidthDp)
                        .focusRequester(focusRequester),
                    textStyle = TextStyle(
                        color = textColor,
                        fontSize = scaledFontSizeSp,
                        fontFamily = notesFonts.text,
                        letterSpacing = 0.sp,
                        lineHeight = TextUnit.Unspecified,
                        textAlign = textAlign
                    ),
                    cursorBrush = SolidColor(primary),
                    visualTransformation = { annotated ->
                        TransformedText(
                            MarkdownTextUtil.toLiveAnnotatedString(
                                text = annotated.text,
                                selection = textValue.selection,
                                isMarkdownEnabled = isMarkdownEnabled,
                                defaultColor = textColor,
                                baseFontSizeSp = scaledFontSizeSp,
                                monospace = notesFonts.monospace
                            ),
                            OffsetMapping.Identity
                        )
                    },
                    onTextLayout = { result ->
                        textLayoutResult = result
                        actualTextHeightPx = result.size.height.toFloat()
                        // The caret on the character the user touched
                        editor.takeCaretRequest()?.let { local ->
                            val offset = result.getOffsetForPosition(Offset(local.x * currentScale, local.y * currentScale))
                            textValue = textValue.copy(selection = TextRange(offset))
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
                                val deltaMm = dragAmount.x / currentScale
                                editor.activeBoxWidthMm = (editor.activeBoxWidthMm + deltaMm).coerceIn(MIN_BOX_WIDTH_MM, MAX_BOX_WIDTH_MM)
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
                                val deltaMm = dragAmount.x / currentScale
                                val newWidth = editor.activeBoxWidthMm - deltaMm
                                if (newWidth >= MIN_BOX_WIDTH_MM) {
                                    editor.activeTextMmX += deltaMm
                                    editor.activeBoxWidthMm = newWidth
                                }
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
                                val page = controller.document.pages.getOrNull(pageIndex) ?: return@detectDragGestures
                                val heightMm = actualTextHeightPx / currentScale
                                // Part of the box always stays on the page
                                editor.activeTextMmX = (editor.activeTextMmX + dragAmount.x / currentScale)
                                    .coerceIn(MIN_VISIBLE_MM - editor.activeBoxWidthMm, page.widthMm - MIN_VISIBLE_MM)
                                editor.activeTextMmY = (editor.activeTextMmY + dragAmount.y / currentScale)
                                    .coerceIn(MIN_VISIBLE_MM - heightMm, page.heightMm - MIN_VISIBLE_MM)
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

        // Formatting toolbar (above the software keyboard)
        AnimatedVisibility(
            visible = true,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = with(density) { imeBottom.toDp() } + 8.dp)
        ) {
            ElevatedCard(
                modifier = Modifier.onSizeChanged { toolbarHeightPx = it.height },
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    // Row 0: live preview of the LaTeX formula
                    if (mathPreviewBitmap != null) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(Res.string.text_editor_label_formula),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Image(
                                    bitmap = mathPreviewBitmap,
                                    contentDescription = stringResource(Res.string.text_editor_desc_latex_preview)
                                )
                            }
                        }
                    }

                    // Row 1: size, color, Markdown and formatting
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        FontSizeStepper(
                            fontSizePt = currentFontSizePt,
                            onFontSizeChanged = { currentFontSizePt = it },
                            onChipClick = {
                                showFontSizeSliderRow = !showFontSizeSliderRow
                                showColorPickerRow = false
                            }
                        )

                        // Text color
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(textColor)
                                .border(
                                    width = 2.dp,
                                    color = if (showColorPickerRow) primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                                    shape = CircleShape
                                )
                                .clickable {
                                    showColorPickerRow = !showColorPickerRow
                                    showFontSizeSliderRow = false
                                },
                            contentAlignment = Alignment.Center
                        ) {}

                        VerticalDivider(
                            modifier = Modifier
                                .height(20.dp)
                                .padding(horizontal = 2.dp),
                            thickness = 1.dp
                        )

                        // Live Markdown toggle
                        TextButton(onClick = { editor.isMarkdownLivePreviewEnabled = !editor.isMarkdownLivePreviewEnabled }) {
                            Text(
                                text = "M↓",
                                fontWeight = FontWeight.Bold,
                                color = if (editor.isMarkdownLivePreviewEnabled) primary else MaterialTheme.colorScheme.outline
                            )
                        }

                        IconButton(onClick = { textValue = MarkdownTextUtil.toggleDelimiters(textValue, "**", "**") }) {
                            Icon(Icons.Default.FormatBold, contentDescription = stringResource(Res.string.text_editor_action_bold))
                        }
                        IconButton(onClick = { textValue = MarkdownTextUtil.toggleDelimiters(textValue, "*", "*") }) {
                            Icon(Icons.Default.FormatItalic, contentDescription = stringResource(Res.string.text_editor_action_italic))
                        }
                        IconButton(onClick = { textValue = MarkdownTextUtil.toggleDelimiters(textValue, "~~", "~~") }) {
                            Icon(Icons.Default.FormatStrikethrough, contentDescription = stringResource(Res.string.text_editor_action_strikethrough))
                        }
                        IconButton(onClick = { textValue = MarkdownTextUtil.toggleDelimiters(textValue, "`", "`") }) {
                            Icon(Icons.Default.Code, contentDescription = stringResource(Res.string.text_editor_action_code))
                        }
                        IconButton(onClick = { textValue = MarkdownTextUtil.toggleHeading(textValue) }) {
                            Icon(Icons.Default.Title, contentDescription = stringResource(Res.string.text_editor_action_heading))
                        }

                        // Alignment (start, center, end)
                        TextButton(onClick = { currentAlignment = (currentAlignment + 1) % 3 }) {
                            Text(
                                text = when (currentAlignment) {
                                    1 -> "≡"
                                    2 -> "⇥"
                                    else -> "⇤"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = primary
                            )
                        }

                        // Math mode ($)
                        IconButton(
                            onClick = {
                                showMathQuickBar = !showMathQuickBar
                                if (showMathQuickBar) {
                                    textValue = MarkdownTextUtil.toggleDelimiters(textValue, "$", "$")
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.Functions,
                                contentDescription = stringResource(Res.string.text_editor_action_math),
                                tint = if (showMathQuickBar) primary else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Save & close
                        IconButton(onClick = { saveAndFinish() }) {
                            Icon(Icons.Default.Check, contentDescription = stringResource(Res.string.text_editor_action_save), tint = primary)
                        }
                    }

                    // Row 1.1: quick color palette
                    AnimatedVisibility(visible = showColorPickerRow) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            TextColorPaletteRow(
                                selectedColor = textColor,
                                onColorSelected = {
                                    currentColorInt = it.toArgb()
                                    showColorPickerRow = false
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                onCustomWheelClick = {
                                    showColorPickerRow = false
                                    showColorWheelDialog = true
                                }
                            )
                        }
                    }

                    // Row 1.2: continuous font size slider
                    AnimatedVisibility(visible = showFontSizeSliderRow) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${currentFontSizePt.toInt()} pt",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = primary,
                                    modifier = Modifier.width(44.dp)
                                )
                                Slider(
                                    value = currentFontSizePt,
                                    onValueChange = { currentFontSizePt = it },
                                    valueRange = 10f..60f,
                                    steps = 24,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // Row 2: math quick bar
                    if (showMathQuickBar) {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for ((label, template) in MATH_SYMBOLS) {
                                ElevatedCard(
                                    onClick = {
                                        textValue = MarkdownTextUtil.insertTemplate(
                                            textValue,
                                            template.first + template.second,
                                            template.first.length
                                        )
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = CardDefaults.elevatedCardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                    modifier = Modifier.padding(horizontal = 2.dp)
                                ) {
                                    Text(
                                        text = label,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showColorWheelDialog) {
            TextColorPickerDialog(
                initialColor = Color(currentColorInt),
                onDismissRequest = { showColorWheelDialog = false },
                onColorConfirmed = { currentColorInt = it.toArgb() }
            )
        }

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }

        // Keeps the edited text above the software keyboard and the formatting toolbar. Not keyed
        // on the layout itself, which changes at every zoom step: it would fight a pinch
        val lineCount = textLayoutResult?.lineCount ?: 0
        LaunchedEffect(textValue.text, lineCount, imeBottom, containerHeightPx, toolbarHeightPx) {
            val layout = textLayoutResult
            if (layout != null && layout.lineCount > 0) {
                val textBottomLocal = layout.getLineBottom(layout.lineCount - 1)
                val absoluteBottomY = liveScreenY + textBottomLocal
                // The toolbar sits 8 dp above the keyboard; the box border and its handles extend
                // below the last line: 32 dp of free space above the toolbar
                val marginPx = maxOf(with(density) { 80.dp.toPx() }, toolbarHeightPx + with(density) { 40.dp.toPx() })
                val safeAreaBottom = containerHeightPx - imeBottom - marginPx
                if (absoluteBottomY > safeAreaBottom) {
                    val deltaY = absoluteBottomY - safeAreaBottom
                    var previous = 0f
                    Animatable(0f).animateTo(deltaY, tween(durationMillis = 250)) {
                        val step = value - previous
                        previous = value
                        controller.panCanvasBy(0f, -step)
                    }
                }
            }
        }
    }
}

/**
 * Height in page millimeters of a LaTeX block [widthMm] wide (the formula is scaled on the width
 * of the block), or `null` when the engine is not available.
 */
private fun latexBlockHeightMm(text: String, fontSizePt: Float, color: Int, widthMm: Float): Float? {
    if (!LatexEngine.isNativeReady()) return null
    val clean = LatexEngine.sanitizeLatexString(text)
    if (clean.isBlank()) return null
    val result = LatexEngine.renderFormula(clean, fontSizePt * 3f, color, paddingPx = 12f) ?: return null
    if (result.width <= 0 || result.height <= 0) return null
    return widthMm * result.height / result.width
}

private const val MIN_BOX_WIDTH_MM = 20f
private const val MAX_BOX_WIDTH_MM = 350f

/** Millimeters of a moved box that always stay on its page. */
private const val MIN_VISIBLE_MM = 5f

private val MOVE_HANDLE_SIZE = 28.dp

private val MATH_SYMBOLS = listOf(
    "a/b" to ("\\frac{" to "}{}"),
    "√x" to ("\\sqrt{" to "}"),
    "x²" to ("^{" to "}"),
    "x_i" to ("_{" to "}"),
    "∑" to ("\\sum_{i=1}^{n} " to ""),
    "∫" to ("\\int_{a}^{b} " to ""),
    "±" to ("\\pm " to ""),
    "≤" to ("\\le " to ""),
    "≥" to ("\\ge " to ""),
    "≠" to ("\\neq " to ""),
    "π" to ("\\pi " to ""),
    "θ" to ("\\theta " to ""),
    "∞" to ("\\infty " to ""),
    "{ }" to ("{" to "}")
)
