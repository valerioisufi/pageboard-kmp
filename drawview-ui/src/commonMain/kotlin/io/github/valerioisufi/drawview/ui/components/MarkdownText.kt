package io.github.valerioisufi.drawview.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.canvas.render.DrawViewEngines
import io.github.valerioisufi.drawview.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.drawview.model.text.RichTextParser
import io.github.valerioisufi.drawview.ui.editor.MarkdownTextUtil
import io.github.valerioisufi.drawview.ui.render.CodeBlockLayout
import io.github.valerioisufi.drawview.ui.render.CodeTheme
import io.github.valerioisufi.drawview.ui.render.LocalDrawViewEngines
import io.github.valerioisufi.drawview.ui.render.codeChips
import io.github.valerioisufi.drawview.ui.theme.NotesFonts
import io.github.valerioisufi.drawview.ui.theme.rememberNotesFonts
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The Markdown of a text of the notes shown out of the page, as the page draws it: its delimiters
 * hidden, its formulas rendered by the engine of [LocalDrawViewEngines] once it is ready, its
 * blocks of code laid out at the width given to it in the colors of their language, its code in
 * the line on a background. Until its formulas can be rendered, a text with formulas shows its
 * notation styled (see [MarkdownTextUtil.toLiveAnnotatedString]).
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
            } ?: MarkdownContent(MarkdownTextUtil.toLiveAnnotatedString(markdown, TextRange(markdown.length + 1), color), emptyMap(), emptyList())
        }
        val chipColor = remember(color) { CodeTheme.of(color).background }
        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
        Text(
            content.text,
            inlineContent = content.inline,
            style = style,
            color = color,
            onTextLayout = { layout = it },
            modifier = Modifier.drawBehind {
                val current = layout?.takeIf { it.layoutInput.text == content.text } ?: return@drawBehind
                for (chip in current.codeChips(content.inlineCode)) {
                    drawRoundRect(chipColor, topLeft = chip.rect.topLeft, size = chip.rect.size, cornerRadius = CornerRadius(chip.radius))
                }
            }
        )
    }
}

/** The text shown, the content of its placeholders (formulas, blocks of code) and its code in the line. */
private class MarkdownContent(
    val text: AnnotatedString,
    val inline: Map<String, InlineTextContent>,
    val inlineCode: List<MarkdownTextUtil.InlineCode>
)

/**
 * [markdown] as the canvas lays it out ([MarkdownTextUtil.toCanvasText]) at the size of [style],
 * its placeholders filled by the formulas of [engines] and the blocks of code laid out on
 * [widthPx]; `null` when it has formulas that cannot be rendered yet.
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
): MarkdownContent? {
    val formulas = engines.formulas
    if ((formulas == null || !latexReady) && RichTextParser.parse(markdown).any { it.kind == RichTextParser.Kind.FORMULA }) return null
    val fontSize = if (style.fontSize.isSpecified) style.fontSize else DEFAULT_SIZE
    val fontPx = with(density) { fontSize.toPx() }
    val prefixStyle = style.copy(fontSize = fontPx.sp, letterSpacing = 0.sp)
    val canvasText = MarkdownTextUtil.toCanvasText(
        text = markdown,
        baseColor = color,
        fontSizePx = fontPx,
        fontAscentPx = fontPx * 0.8f,
        lineWidthPx = widthPx,
        monospace = fonts.monospace,
        layoutCodeBlock = { source -> CodeBlockLayout.layout(measurer, fonts.monospace, source, fontPx, color, engines.code, TextTileAdapter.NO_PAPER) },
        listPrefixWidth = { prefix ->
            measurer.measure(AnnotatedString(prefix), prefixStyle, softWrap = false, skipCache = true).getHorizontalPosition(prefix.length, usePrimaryDirection = true)
        },
        density = density.density
    ) { latex, sizePx ->
        formulas?.render(formulas.sanitize(latex), sizePx, color.toArgb())?.let {
            MarkdownTextUtil.RenderedFormula(it.bitmap, it.bitmap.width.toFloat(), it.bitmap.height.toFloat(), it.baselineRatio)
        }
    }
    val formulaAt = canvasText.formulas.associateBy { it.placeholderIndex }
    val blockAt = canvasText.codeBlocks.associateBy { it.placeholderIndex }
    val inline = HashMap<String, InlineTextContent>()
    val text = buildAnnotatedString {
        var last = 0
        canvasText.placeholders.forEachIndexed { index, placeholder ->
            append(canvasText.text.subSequence(last, placeholder.start))
            val id = "placeholder$index"
            appendInlineContent(id)
            last = placeholder.end
            val formula = formulaAt[index]
            val block = blockAt[index]
            inline[id] = InlineTextContent(
                with(density) {
                    Placeholder(width = placeholder.widthPx.toSp(), height = placeholder.heightPx.toSp(), placeholderVerticalAlign = placeholder.verticalAlign)
                }
            ) {
                when {
                    // The body placeholder holds the ascent: the descent hangs below it
                    formula != null -> Image(
                        bitmap = formula.image,
                        contentDescription = null,
                        modifier = Modifier
                            .wrapContentSize(Alignment.TopStart, unbounded = true)
                            .requiredSize(with(density) { formula.widthPx.toDp() }, with(density) { formula.heightPx.toDp() })
                    )
                    block != null -> Canvas(Modifier.fillMaxSize()) {
                        drawIntoCanvas { block.layout.draw(it, block.leftPx, 0f) }
                    }
                }
            }
        }
        append(canvasText.text.subSequence(last, canvasText.text.length))
    }
    return MarkdownContent(text, inline, canvasText.inlineCode)
}

/** The size of a text whose style has none. */
private val DEFAULT_SIZE = 14.sp
