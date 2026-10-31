package io.github.valerioisufi.drawview.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.canvas.render.DrawViewEngines
import io.github.valerioisufi.drawview.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.drawview.model.text.RichTextParser
import io.github.valerioisufi.drawview.ui.editor.MarkdownTextUtil
import io.github.valerioisufi.drawview.ui.editor.Presentation
import io.github.valerioisufi.drawview.ui.editor.PresentedText
import io.github.valerioisufi.drawview.ui.editor.RichTextPresenter
import io.github.valerioisufi.drawview.ui.editor.Struts
import io.github.valerioisufi.drawview.ui.editor.FontRoom
import io.github.valerioisufi.drawview.ui.editor.codeBlockOrigin
import io.github.valerioisufi.drawview.ui.editor.formulaRect
import io.github.valerioisufi.drawview.ui.editor.tableFormulas
import io.github.valerioisufi.drawview.ui.editor.tableOrigin
import io.github.valerioisufi.drawview.ui.render.TableLayout
import io.github.valerioisufi.drawview.ui.render.CodeBlockLayout
import io.github.valerioisufi.drawview.ui.render.CodeTheme
import io.github.valerioisufi.drawview.ui.render.LocalDrawViewEngines
import io.github.valerioisufi.drawview.ui.render.codeChips
import io.github.valerioisufi.drawview.ui.theme.NotesFonts
import io.github.valerioisufi.drawview.ui.theme.rememberNotesFonts
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The Markdown of a text of the notes shown out of the page, as the page draws it (see
 * [RichTextPresenter]): its delimiters hidden, its formulas rendered by the engine of
 * [LocalDrawViewEngines] once it is ready, its blocks of code laid out at the width given to it in
 * the colors of their language, its code in the line on a background. Until its formulas can be
 * rendered, a text with formulas shows its notation styled (see [MarkdownTextUtil.toLiveAnnotatedString]).
 */
@Composable
public fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    style: TextStyle = LocalTextStyle.current
) {
    val density = LocalDensity.current
    val engines = LocalDrawViewEngines.current
    val formulas = engines.formulas
    val latexReady by remember(formulas) { formulas?.isReady ?: MutableStateFlow(false) }.collectAsState()
    val resolver = LocalFontFamilyResolver.current
    val fonts = rememberNotesFonts()
    // The blocks of code laid out in pixels, as the canvas lays them out
    val measurer = remember(resolver) { TextMeasurer(resolver, Density(1f, 1f), LayoutDirection.Ltr, cacheSize = 0) }
    BoxWithConstraints(modifier) {
        val widthPx = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else null
        val content = remember(markdown, color, style, density, engines, latexReady, widthPx, fonts) {
            try {
                markdownContent(markdown, color, style, density, engines, latexReady, widthPx, measurer, fonts)
            } catch (_: Throwable) {
                null
            }
        }
        val chipColor = remember(color) { CodeTheme.of(color).background }
        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
        Text(
            content?.text ?: MarkdownTextUtil.toLiveAnnotatedString(markdown, TextRange(markdown.length + 1), color),
            // The lines with formulas or blocks of code take the room of their struts, as on the page:
            // a height of line set by the style would leave them on the text around them
            style = if (content != null && (content.formulas.isNotEmpty() || content.codeBlocks.isNotEmpty() || content.tables.isNotEmpty())) style.copy(lineHeight = TextUnit.Unspecified) else style,
            color = color,
            onTextLayout = { layout = it },
            modifier = Modifier.drawWithContent {
                val current = layout?.takeIf { content != null && it.layoutInput.text == content.text }
                if (current == null || content == null) {
                    drawContent()
                    return@drawWithContent
                }
                for (chip in current.codeChips(content.inlineCode)) {
                    drawRoundRect(chipColor, topLeft = chip.rect.topLeft, size = chip.rect.size, cornerRadius = CornerRadius(chip.radius))
                }
                drawContent()
                for (block in content.codeBlocks) {
                    val origin = current.codeBlockOrigin(block)
                    drawIntoCanvas { block.layout.draw(it, origin.x, origin.y) }
                }
                for (table in content.tables) {
                    val origin = current.tableOrigin(table)
                    drawIntoCanvas { table.layout.draw(it, origin.x, origin.y) }
                }
                for ((formula, box) in content.formulas.map { it to current.formulaRect(it) } + current.tableFormulas(content.tables)) {
                    drawImage(
                        formula.image,
                        srcSize = IntSize(formula.image.width, formula.image.height),
                        dstOffset = IntOffset(box.left.roundToInt(), box.top.roundToInt()),
                        dstSize = IntSize(box.width.roundToInt().coerceAtLeast(1), box.height.roundToInt().coerceAtLeast(1))
                    )
                }
            }
        )
    }
}

/**
 * [markdown] as the canvas presents it at the size of [style], its formulas rendered by [engines]
 * and its blocks of code laid out on [widthPx]; `null` when it has formulas that cannot be rendered
 * yet.
 */
private fun markdownContent(
    markdown: String,
    color: Color,
    style: TextStyle,
    density: Density,
    engines: DrawViewEngines,
    latexReady: Boolean,
    widthPx: Float?,
    measurer: TextMeasurer,
    fonts: NotesFonts
): PresentedText<ImageBitmap>? {
    val formulas = engines.formulas
    if ((formulas == null || !latexReady) && RichTextParser.parse(markdown).any { it.kind == RichTextParser.Kind.FORMULA }) return null
    val fontSize = if (style.fontSize.isSpecified) style.fontSize else DEFAULT_SIZE
    val fontPx = with(density) { fontSize.toPx() }
    val prefixStyle = style.copy(fontSize = fontPx.sp, letterSpacing = 0.sp)
    // The struts and the blocks of code in the font of this text: the room of its lines is the one of its font
    val textFont = style.fontFamily ?: androidx.compose.ui.text.font.FontFamily.Default
    val presentation = Presentation(
        fontSizePx = fontPx,
        color = color,
        struts = Struts(fonts.strutAbove, fonts.strutBelow, textFont, FontRoom.of(measurer, textFont)),
        density = density.density,
        lineWidthPx = widthPx,
        monospace = fonts.monospace,
        highlighter = engines.code,
        layoutCodeBlock = { source -> CodeBlockLayout.layout(measurer, fonts.monospace, source, fontPx, color, engines.code, TextTileAdapter.NO_PAPER, textFont) },
        measurePrefix = { prefix ->
            measurer.measure(AnnotatedString(prefix), prefixStyle, softWrap = false, skipCache = true).getHorizontalPosition(prefix.length, usePrimaryDirection = true)
        },
        measureText = { cell, cellWidth, alignment -> TableLayout.measureCell(measurer, cell, prefixStyle.copy(color = color), cellWidth, alignment) }
    ) { latex, sizePx ->
        formulas?.render(formulas.sanitize(latex), sizePx, color.toArgb())?.let {
            MarkdownTextUtil.RenderedFormula(it.bitmap, it.bitmap.width.toFloat(), it.bitmap.height.toFloat(), it.baselineRatio)
        }
    }
    return RichTextPresenter.present(markdown, presentation)
}

/** The size of a text whose style has none. */
private val DEFAULT_SIZE = 14.sp
