package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

/**
 * Markdown and LaTeX notation of the DrawView text blocks:
 * 1. Obsidian-like live preview for the editor, concealing the delimiters that are not under the
 *    cursor;
 * 2. the formatted text drawn on the canvas, with the `$...$` / `$$...$$` formulas rendered by
 *    MicroTeX (see [MarkdownTextUtil.toCanvasText]);
 * 3. helpers used by the formatting toolbar of the editor.
 */
object MarkdownTextUtil {

    internal data class MatchRange(
        val fullStart: Int,
        val fullEnd: Int,
        val contentStart: Int,
        val contentEnd: Int,
        val type: TokenType
    )

    internal enum class TokenType {
        BOLD, ITALIC, STRIKE, CODE, INLINE_MATH, BLOCK_MATH, HEADING
    }

    /**
     * Builds the [AnnotatedString] shown by the text field of the editor ("conceal" effect):
     * - when the cursor touches a token, its delimiters are shown with a faded color;
     * - otherwise the delimiters are invisible (zero size), showing only the formatted content.
     *
     * The text is never changed, so that the offsets of the field are preserved.
     */
    fun toLiveAnnotatedString(
        text: String,
        selection: TextRange,
        isMarkdownEnabled: Boolean,
        defaultColor: Color,
        baseFontSizeSp: TextUnit = 16.sp
    ): AnnotatedString {
        if (!isMarkdownEnabled || text.isEmpty()) {
            return AnnotatedString(text)
        }

        val tokens = scanTokens(text)
        val selMin = min(selection.start, selection.end)
        val selMax = max(selection.start, selection.end)

        return buildAnnotatedString {
            append(text)

            for (t in tokens) {
                // The cursor is "on the token" when it touches or is inside the delimiters
                val isCursorTouching = (selMin <= t.fullEnd && selMax >= t.fullStart)

                if (isCursorTouching) {
                    // Visible delimiters with a faded, proportional style
                    val syntaxStyle = SpanStyle(
                        color = defaultColor.copy(alpha = 0.45f),
                        fontSize = baseFontSizeSp * 0.9f
                    )
                    addStyle(syntaxStyle, t.fullStart, t.contentStart)
                    addStyle(syntaxStyle, t.contentEnd, t.fullEnd)
                } else {
                    // Conceal: invisible delimiters with an almost null size (same offsets)
                    val concealStyle = SpanStyle(
                        color = Color.Transparent,
                        fontSize = 0.001.sp
                    )
                    addStyle(concealStyle, t.fullStart, t.contentStart)
                    addStyle(concealStyle, t.contentEnd, t.fullEnd)
                }

                addStyle(contentStyle(t.type, defaultColor, baseFontSizeSp), t.contentStart, t.contentEnd)
            }
        }
    }

    private fun contentStyle(type: TokenType, defaultColor: Color, baseFontSize: TextUnit): SpanStyle = when (type) {
        TokenType.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        TokenType.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        TokenType.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        TokenType.CODE -> SpanStyle(
            fontFamily = FontFamily.Monospace,
            background = defaultColor.copy(alpha = 0.1f)
        )
        TokenType.INLINE_MATH -> SpanStyle(
            fontStyle = FontStyle.Italic,
            fontFamily = FontFamily.Serif,
            color = Color(0xFF1E88E5)
        )
        TokenType.BLOCK_MATH -> SpanStyle(
            fontStyle = FontStyle.Italic,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF1565C0)
        )
        TokenType.HEADING -> SpanStyle(
            fontWeight = FontWeight.Bold,
            fontSize = baseFontSize * 1.25f
        )
    }

    /**
     * A formula of a text block drawn on the canvas.
     *
     * @property placeholderIndex index (in [CanvasText.placeholders]) of the placeholder that
     * reserves the room of the formula above the baseline.
     * @property baselineRatio position of the formula baseline (0 = top, 1 = bottom).
     */
    class InlineFormula<T>(
        val image: T,
        val widthPx: Float,
        val heightPx: Float,
        val baselineRatio: Float,
        val placeholderIndex: Int
    )

    /**
     * Text of a block laid out on the canvas: the Markdown delimiters are removed and each
     * formula is replaced by placeholders.
     */
    class CanvasText<T>(
        val text: AnnotatedString,
        /** Placeholder ranges: per formula, the body (above the baseline) and the descent. */
        val placeholders: List<PlaceholderSpec>,
        val formulas: List<InlineFormula<T>>
    )

    /**
     * A placeholder of [CanvasText] in pixels.
     *
     * @property aboveBaseline `true` for the body of the formula (bottom on the baseline), `false`
     * for the zero-width placeholder that reserves the descent (top on the top of the font).
     */
    class PlaceholderSpec(val start: Int, val end: Int, val widthPx: Float, val heightPx: Float, val aboveBaseline: Boolean)

    /**
     * Rendered formula used by [toCanvasText].
     */
    class RenderedFormula<T>(val image: T, val widthPx: Float, val heightPx: Float, val baselineRatio: Float)

    /**
     * Converts a Markdown text into the text drawn on the canvas (the equivalent of the Android
     * `Spanned` of the original app): delimiters are removed, the formulas are rendered by
     * [renderFormula] and placed inline, aligned on the baseline of the line. Invalid formulas
     * are shown as red italic source.
     *
     * @param fontAscentPx ascent of the base font, used to reserve the descent of the formulas.
     */
    fun <T> toCanvasText(
        text: String,
        isMarkdownEnabled: Boolean,
        baseColor: Color,
        fontSizePx: Float,
        fontAscentPx: Float,
        renderFormula: (latex: String, fontSizePx: Float) -> RenderedFormula<T>?
    ): CanvasText<T> {
        if (!isMarkdownEnabled || text.isEmpty()) {
            return CanvasText(
                buildAnnotatedString {
                    append(text)
                    addStyle(SpanStyle(color = baseColor), 0, text.length)
                },
                emptyList(),
                emptyList()
            )
        }

        val tokens = scanTokens(text)
        val placeholders = mutableListOf<PlaceholderSpec>()
        val formulas = mutableListOf<InlineFormula<T>>()
        val annotated = buildAnnotatedString {
            var lastIdx = 0
            val errorRanges = mutableListOf<Pair<Int, Int>>()

            for (t in tokens) {
                if (t.fullStart > lastIdx) append(text.substring(lastIdx, t.fullStart))
                val content = text.substring(t.contentStart, t.contentEnd)
                val startSpan = length

                when (t.type) {
                    TokenType.INLINE_MATH, TokenType.BLOCK_MATH -> {
                        val size = if (t.type == TokenType.BLOCK_MATH) fontSizePx * 1.4f else fontSizePx * 1.1f
                        val rendered = renderFormula(content, size)
                        if (rendered != null && rendered.widthPx > 0f && rendered.heightPx > 0f) {
                            val ascent = rendered.heightPx * rendered.baselineRatio
                            val descent = rendered.heightPx - ascent
                            // Body of the formula: bottom on the baseline
                            val bodyStart = length
                            append(OBJECT_REPLACEMENT)
                            placeholders.add(PlaceholderSpec(bodyStart, bodyStart + 1, rendered.widthPx, ascent.coerceAtLeast(1f), true))
                            formulas.add(
                                InlineFormula(rendered.image, rendered.widthPx, rendered.heightPx, rendered.baselineRatio, placeholders.size - 1)
                            )
                            if (descent > 0.5f) {
                                // Zero-width placeholder hanging from the top of the font down to the
                                // descent of the formula (a word joiner prevents a line break)
                                append(WORD_JOINER)
                                val descentStart = length
                                append(OBJECT_REPLACEMENT)
                                placeholders.add(
                                    PlaceholderSpec(descentStart, descentStart + 1, 0.01f, fontAscentPx + descent, false)
                                )
                            }
                        } else {
                            // Original formula highlighted in red italic when the syntax is invalid
                            val raw = if (t.type == TokenType.BLOCK_MATH) "$$$content$$" else "$$content$"
                            val errorStart = length
                            append(raw)
                            errorRanges.add(errorStart to length)
                        }
                    }
                    else -> {
                        append(content)
                        val endSpan = length
                        when (t.type) {
                            TokenType.BOLD -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), startSpan, endSpan)
                            TokenType.ITALIC -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), startSpan, endSpan)
                            TokenType.STRIKE -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), startSpan, endSpan)
                            TokenType.CODE -> addStyle(SpanStyle(fontFamily = FontFamily.Monospace), startSpan, endSpan)
                            TokenType.HEADING -> addStyle(
                                SpanStyle(fontWeight = FontWeight.Bold, fontSize = (fontSizePx * 1.25f).sp),
                                startSpan,
                                endSpan
                            )
                            TokenType.INLINE_MATH, TokenType.BLOCK_MATH -> {}
                        }
                    }
                }
                lastIdx = t.fullEnd
            }
            if (lastIdx < text.length) append(text.substring(lastIdx))

            addStyle(SpanStyle(color = baseColor), 0, length)
            for ((errStart, errEnd) in errorRanges) {
                addStyle(SpanStyle(color = ERROR_COLOR, fontStyle = FontStyle.Italic), errStart, errEnd)
            }
        }
        return CanvasText(annotated, placeholders, formulas)
    }

    /**
     * Scans the Markdown tokens (bold, italic, strikethrough, code, inline/block math, headings),
     * keeping the outermost tokens when they overlap.
     */
    internal fun scanTokens(text: String): List<MatchRange> {
        val tokens = mutableListOf<MatchRange>()

        // 1. Block math $$...$$
        findRegexMatches(BLOCK_MATH_REGEX, text, TokenType.BLOCK_MATH, 2, 2, tokens)
        // 2. Inline math $...$ (not $$)
        findRegexMatches(INLINE_MATH_REGEX, text, TokenType.INLINE_MATH, 1, 1, tokens)
        // 3. Bold **...** or __...__
        findRegexMatches(BOLD_STAR_REGEX, text, TokenType.BOLD, 2, 2, tokens)
        findRegexMatches(BOLD_UNDERSCORE_REGEX, text, TokenType.BOLD, 2, 2, tokens)
        // 4. Strikethrough ~~...~~
        findRegexMatches(STRIKE_REGEX, text, TokenType.STRIKE, 2, 2, tokens)
        // 5. Italic *...* or _..._
        findRegexMatches(ITALIC_STAR_REGEX, text, TokenType.ITALIC, 1, 1, tokens)
        findRegexMatches(ITALIC_UNDERSCORE_REGEX, text, TokenType.ITALIC, 1, 1, tokens)
        // 6. Inline code `...`
        findRegexMatches(CODE_REGEX, text, TokenType.CODE, 1, 1, tokens)
        // 7. Headings "# Title"
        findRegexMatches(HEADING_REGEX, text, TokenType.HEADING, 2, 0, tokens, contentFromGroup = true)

        // Sort by start and drop the overlapping tokens (the outer/prioritary ones are kept)
        tokens.sortBy { it.fullStart }
        val nonOverlapping = mutableListOf<MatchRange>()
        var lastEnd = -1
        for (t in tokens) {
            if (t.fullStart >= lastEnd && t.fullEnd <= text.length) {
                nonOverlapping.add(t)
                lastEnd = t.fullEnd
            }
        }
        return nonOverlapping
    }

    private fun findRegexMatches(
        regex: Regex,
        text: String,
        type: TokenType,
        prefixLen: Int,
        suffixLen: Int,
        outList: MutableList<MatchRange>,
        contentFromGroup: Boolean = false
    ) {
        for (match in regex.findAll(text)) {
            val start = match.range.first
            val end = match.range.last + 1
            if (end - start > prefixLen + suffixLen) {
                // Headings: the content starts after every '#' and space ("## Title")
                val contentStart = if (contentFromGroup) {
                    var i = start
                    while (i < end && text[i] == '#') i++
                    while (i < end && text[i].isWhitespace()) i++
                    i
                } else {
                    start + prefixLen
                }
                outList.add(
                    MatchRange(
                        fullStart = start,
                        fullEnd = end,
                        contentStart = contentStart,
                        contentEnd = end - suffixLen,
                        type = type
                    )
                )
            }
        }
    }

    // --- Editing helpers (formatting toolbar) ---

    /**
     * Adds or removes the Markdown delimiters around the selection, or inserts them at the
     * cursor (placing the cursor between them).
     */
    fun toggleDelimiters(currentValue: TextFieldValue, prefix: String, suffix: String): TextFieldValue {
        val text = currentValue.text
        val sel = currentValue.selection

        val start = min(sel.start, sel.end)
        val end = max(sel.start, sel.end)

        if (start != end) {
            val selected = text.substring(start, end)
            val pLen = prefix.length
            val sLen = suffix.length
            // Already wrapped: remove the delimiters
            if (start >= pLen && end + sLen <= text.length &&
                text.substring(start - pLen, start) == prefix &&
                text.substring(end, end + sLen) == suffix
            ) {
                val newText = text.substring(0, start - pLen) + selected + text.substring(end + sLen)
                return currentValue.copy(
                    text = newText,
                    selection = TextRange(start - pLen, end - pLen)
                )
            } else {
                val newText = text.substring(0, start) + prefix + selected + suffix + text.substring(end)
                return currentValue.copy(
                    text = newText,
                    selection = TextRange(start + pLen, end + pLen)
                )
            }
        } else {
            val newText = text.substring(0, start) + prefix + suffix + text.substring(start)
            val newCursor = start + prefix.length
            return currentValue.copy(
                text = newText,
                selection = TextRange(newCursor, newCursor)
            )
        }
    }

    /**
     * Inserts a math template (e.g. `\frac{}{}`) replacing the selection, placing the cursor
     * [cursorOffsetInside] characters after its start.
     */
    fun insertTemplate(currentValue: TextFieldValue, template: String, cursorOffsetInside: Int = template.length): TextFieldValue {
        val text = currentValue.text
        val sel = currentValue.selection
        val start = min(sel.start, sel.end)
        val end = max(sel.start, sel.end)

        val newText = text.substring(0, start) + template + text.substring(end)
        val newCursor = start + cursorOffsetInside
        return currentValue.copy(
            text = newText,
            selection = TextRange(newCursor, newCursor)
        )
    }

    internal const val OBJECT_REPLACEMENT = "￼"
    internal const val WORD_JOINER = "⁠"
    private val ERROR_COLOR = Color(0xFFD32F2F)

    // [\s\S]: any character, line breaks included (DOT_MATCHES_ALL is not multiplatform)
    private val BLOCK_MATH_REGEX = Regex("""\$\$([\s\S]+?)\$\$""")
    private val INLINE_MATH_REGEX = Regex("""(?<!\$)\$(?!\$)(.+?)(?<!\$)\$(?!\$)""")
    private val BOLD_STAR_REGEX = Regex("""\*\*(.+?)\*\*""")
    private val BOLD_UNDERSCORE_REGEX = Regex("""__(.+?)__""")
    private val STRIKE_REGEX = Regex("""~~(.+?)~~""")
    private val ITALIC_STAR_REGEX = Regex("""(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)""")
    private val ITALIC_UNDERSCORE_REGEX = Regex("""(?<!_)_(?!_)(.+?)(?<!_)_(?!_)""")
    private val CODE_REGEX = Regex("""`(.+?)`""")
    // Inline flags such as (?m) are not supported by the JavaScript regular expressions
    private val HEADING_REGEX = Regex("""^#+\s+(.+)$""", RegexOption.MULTILINE)
}
