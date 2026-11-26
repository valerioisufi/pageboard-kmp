package io.github.valerioisufi.pageboard.html

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element

/**
 * The rich text (RTF) an application with no HTML on the clipboard puts there, in the notation of
 * the texts (see `docs/plans/import-and-clipboard.md`): its paragraphs and line breaks, bold, italic,
 * strikethrough, subscripts and superscripts, its characters in Unicode and in the code page of
 * Windows (1252, the one of nearly every RTF); the tables, the pictures and the rest are their text,
 * or nothing.
 *
 * It is read into the tree of HTML the paste writes ([MarkdownWriter]). What is read comes from
 * anywhere: the groups nest at most [MAX_DEPTH] deep, at most [MAX_CHARS] are read, and what cannot
 * be read gives `null`, never a failure of the application.
 */
public class RtfToMarkdown {

    /** The format of a group, and whether what it holds is left out (a font table, a picture). */
    private data class State(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val shift: String? = null,
        val skipped: Boolean = false,
        val unicodeSkip: Int = 1
    )

    /** [rtf] in the notation; `null` when it is no RTF, cannot be read, or holds no formatting. */
    public fun convert(rtf: String): String? {
        if (!rtf.startsWith("{\\rtf") || rtf.length > MAX_CHARS) return null
        return try {
            val body = Ksoup.parse("").body()
            read(rtf, body)
            val writer = MarkdownWriter()
            writer.write(body).takeIf { writer.formatted && it.isNotBlank() && MarkdownWriter.keepsTextOf(body, it) }
        } catch (_: Exception) {
            // What another application copied must never fail a paste
            null
        }
    }

    private fun read(rtf: String, body: Element) {
        val states = ArrayDeque<State>()
        var state = State()
        var paragraph = Element("p")
        var pendingSkip = 0
        val text = StringBuilder()

        // The text read so far written into the paragraph, in the format of the group it was in
        fun flush() {
            if (text.isEmpty()) return
            var target = paragraph
            fun wrap(tag: String) {
                val element = Element(tag)
                target.appendChild(element)
                target = element
            }
            if (state.bold) wrap("strong")
            if (state.italic) wrap("em")
            if (state.strike) wrap("s")
            state.shift?.let(::wrap)
            target.appendText(text.toString())
            text.clear()
        }

        fun endParagraph() {
            flush()
            if (paragraph.childNodeSize() > 0) body.appendChild(paragraph)
            paragraph = Element("p")
        }

        fun addText(chars: String) {
            if (state.skipped) return
            if (pendingSkip > 0) {
                // The characters standing for a character of Unicode for the readers that know none
                val skip = minOf(pendingSkip, chars.length)
                pendingSkip -= skip
                text.append(chars, skip, chars.length)
                return
            }
            text.append(chars)
        }

        fun change(next: State) {
            if (next.copy(skipped = state.skipped, unicodeSkip = state.unicodeSkip) != state.copy(skipped = state.skipped, unicodeSkip = state.unicodeSkip)) flush()
            state = next
        }

        var i = 0
        // Whether the group just opened may be a destination (its first control word)
        var groupStart = false
        while (i < rtf.length) {
            when (val c = rtf[i]) {
                '{' -> {
                    flush()
                    if (states.size >= MAX_DEPTH) throw IllegalStateException("nested too deep")
                    states.addLast(state)
                    groupStart = true
                    i++
                }
                '}' -> {
                    flush()
                    state = states.removeLastOrNull() ?: State()
                    pendingSkip = 0
                    groupStart = false
                    i++
                }
                '\\' -> {
                    if (i + 1 >= rtf.length) break
                    val next = rtf[i + 1]
                    when {
                        next == '\\' || next == '{' || next == '}' -> {
                            addText(next.toString())
                            i += 2
                        }
                        next == '~' -> {
                            addText(" ")
                            i += 2
                        }
                        next == '_' -> {
                            addText("‑")
                            i += 2
                        }
                        next == '-' -> i += 2
                        // An ignorable destination: left out when it is not known
                        next == '*' -> {
                            if (groupStart) state = state.copy(skipped = true)
                            i += 2
                        }
                        next == '\'' -> {
                            val hex = rtf.substring(i + 2, minOf(i + 4, rtf.length)).toIntOrNull(16)
                            if (hex != null) addText(cp1252(hex).toString())
                            i += 4
                        }
                        next == '\n' || next == '\r' -> {
                            endParagraph()
                            i += 2
                        }
                        next.isLetter() -> {
                            var end = i + 1
                            while (end < rtf.length && end - i <= MAX_WORD && rtf[end].isLetter()) end++
                            val word = rtf.substring(i + 1, end)
                            var paramEnd = end
                            if (paramEnd < rtf.length && rtf[paramEnd] == '-') paramEnd++
                            while (paramEnd < rtf.length && paramEnd - end <= MAX_PARAMETER && rtf[paramEnd].isDigit()) paramEnd++
                            val parameter = rtf.substring(end, paramEnd).toIntOrNull()
                            // A space after a control word belongs to it
                            i = if (paramEnd < rtf.length && rtf[paramEnd] == ' ') paramEnd + 1 else paramEnd
                            val atStart = groupStart
                            groupStart = false
                            when (word) {
                                "par", "sect", "page" -> endParagraph()
                                "line" -> {
                                    flush()
                                    paragraph.appendChild(Element("br"))
                                }
                                "tab" -> addText("\t")
                                "cell" -> addText(" ")
                                "row" -> endParagraph()
                                "emdash" -> addText("—")
                                "endash" -> addText("–")
                                "bullet" -> addText("•")
                                "lquote" -> addText("‘")
                                "rquote" -> addText("’")
                                "ldblquote" -> addText("“")
                                "rdblquote" -> addText("”")
                                "b" -> change(state.copy(bold = parameter != 0))
                                "i" -> change(state.copy(italic = parameter != 0))
                                "strike", "striked" -> change(state.copy(strike = parameter != 0))
                                "super" -> change(state.copy(shift = "sup"))
                                "sub" -> change(state.copy(shift = "sub"))
                                "nosupersub" -> change(state.copy(shift = null))
                                "plain" -> change(state.copy(bold = false, italic = false, strike = false, shift = null))
                                "uc" -> state = state.copy(unicodeSkip = (parameter ?: 1).coerceIn(0, MAX_UNICODE_SKIP))
                                "u" -> if (parameter != null) {
                                    val code = if (parameter < 0) parameter + 65536 else parameter
                                    addText(code.toChar().toString())
                                    pendingSkip = state.unicodeSkip
                                }
                                else -> if (atStart && word in SKIPPED) state = state.copy(skipped = true)
                            }
                            continue
                        }
                        else -> i += 2
                    }
                    groupStart = false
                }
                '\r', '\n' -> i++
                else -> {
                    var end = i
                    while (end < rtf.length && rtf[end] != '\\' && rtf[end] != '{' && rtf[end] != '}' && rtf[end] != '\r' && rtf[end] != '\n') end++
                    addText(rtf.substring(i, end))
                    groupStart = false
                    i = end.coerceAtLeast(i + 1)
                    if (c == '\u0000') break
                }
            }
        }
        endParagraph()
    }

    /** The character of the code page 1252 of Windows of [byte]. */
    private fun cp1252(byte: Int): Char = if (byte in 0x80..0x9F) CP1252_HIGH[byte - 0x80] else byte.toChar()

    private companion object {
        /** The longest RTF read, in characters. */
        const val MAX_CHARS = 4_000_000

        /** How deep the groups may nest. */
        const val MAX_DEPTH = 256

        /** The longest control word and parameter read. */
        const val MAX_WORD = 32
        const val MAX_PARAMETER = 10

        /** The most characters standing for one of Unicode. */
        const val MAX_UNICODE_SKIP = 8

        /** The destinations whose text is not the document's. */
        val SKIPPED = setOf(
            "fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "headerl", "headerr", "headerf",
            "footerl", "footerr", "footerf", "fldinst", "object", "themedata", "colorschememapping", "latentstyles",
            "datastore", "xmlnstbl", "listtable", "listoverridetable", "rsidtbl", "generator", "filetbl", "revtbl",
            "pgdsctbl", "footnote", "annotation", "bkmkstart", "bkmkend", "shppict", "nonshppict", "listtext", "pntext"
        )

        /** The characters 0x80 to 0x9F of the code page 1252. */
        const val CP1252_HIGH = "€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008DŽ\u008F\u0090‘’“”•–—˜™š›œ\u009DžŸ"
    }
}
