package io.github.valerioisufi.drawview.html

/** A piece of what a block of text holds, before its spaces are collapsed. */
internal sealed interface InlineRun {
    /** A text as the source has it, in its [format]. */
    data class Text(val text: String, val format: HtmlFormat) : InlineRun

    /** A formula, its LaTeX source [tex]; [display] on a line of its own. */
    data class Formula(val tex: String, val display: Boolean) : InlineRun

    /** A line break. */
    data object Break : InlineRun
}

/**
 * The [InlineRun]s of a block written in the notation: the spaces collapsed as a browser collapses
 * them, the delimiters opened and closed around the text they cover, the spaces out of them (the
 * delimiters of the notation touch their text), the literal characters [escaped][MarkdownEscape].
 */
internal object InlineText {

    /** The most line breaks in a row: more of them only leave a larger hole in a note. */
    private const val MOST_BREAKS = 2

    private const val ZERO_WIDTH_SPACE = '\u200B'

    /** The characters pages put around their words only to break or join lines: not text of a note. */
    private const val INVISIBLE = "\u200B\u2060\uFEFF\u00AD"

    /** A delimiter open in a line. */
    private sealed class Delimiter(val open: String, val close: String) {
        data class Link(val address: String) : Delimiter("[", "]($address)")
        data object Strike : Delimiter("~~", "~~")
        data object Bold : Delimiter("**", "**")
        data object Italic : Delimiter("*", "*")
        data object Code : Delimiter("`", "`")
    }

    /** A text that goes on a line in one format, or a whole formula. */
    private sealed interface Piece {
        data class Words(val text: String, val format: HtmlFormat) : Piece
        data class Math(val written: String) : Piece
    }

    /**
     * The result of [write].
     *
     * @property formatted whether anything was written that plain text could not have said.
     */
    data class Written(val text: String, val formatted: Boolean)

    /**
     * [runs] in the notation. With [oneLine] (a heading, a cell of a table) the line breaks are
     * spaces and a formula stays in the line.
     *
     * @param inTable it goes in a cell of a table.
     */
    fun write(runs: List<InlineRun>, oneLine: Boolean = false, inTable: Boolean = false): Written {
        var formatted = false
        val lines = linesOf(runs, oneLine).map { line ->
            // A line of a heading or of a cell has its marker before it: what follows is no marker
            val written = writeLine(line, inTable, startsBlock = !oneLine)
            formatted = formatted || written.formatted
            written.text
        }
        val text = lines.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }.joinToString("\n")
        return Written(text, formatted)
    }

    /** [runs] by lines, each with its spaces collapsed; an empty line stands for a break repeated. */
    private fun linesOf(runs: List<InlineRun>, oneLine: Boolean): List<List<Piece>> {
        val lines = mutableListOf(mutableListOf<Piece>())
        var pendingSpace = false
        var breaks = 0
        fun lineBreak() {
            if (breaks < MOST_BREAKS) lines += mutableListOf<Piece>()
            breaks++
            pendingSpace = false
        }
        fun add(piece: Piece) {
            val line = lines.last()
            val last = line.lastOrNull()
            if (piece is Piece.Words && last is Piece.Words && last.format == piece.format) {
                line[line.lastIndex] = Piece.Words(last.text + piece.text, last.format)
            } else {
                line += piece
            }
            breaks = 0
        }
        // A space goes in the format of what is around it, whose emphasis it stays out of, unless
        // the text goes on in the same format
        fun flushSpace(format: HtmlFormat?) {
            if (!pendingSpace) return
            pendingSpace = false
            val line = lines.last()
            if (line.isEmpty()) return
            val last = line.last()
            when {
                last is Piece.Words && last.format == format -> add(Piece.Words(" ", last.format))
                last is Piece.Words && format != null && last.format.link != null && last.format.link == format.link ->
                    add(Piece.Words(" ", HtmlFormat(link = format.link)))
                else -> add(Piece.Words(" ", HtmlFormat()))
            }
        }
        for (run in runs) {
            when (run) {
                InlineRun.Break -> if (oneLine) pendingSpace = true else lineBreak()
                is InlineRun.Formula -> {
                    val alone = run.display && !oneLine
                    if (alone && lines.last().isNotEmpty()) lineBreak()
                    flushSpace(null)
                    add(Piece.Math(if (alone) "$$${run.tex}$$" else "$${run.tex}$"))
                    if (alone) lineBreak()
                }
                is InlineRun.Text -> {
                    val word = StringBuilder()
                    fun flushWord() {
                        if (word.isEmpty()) return
                        add(Piece.Words(word.toString(), run.format))
                        word.clear()
                    }
                    for (c in run.text) {
                        if (c in INVISIBLE) continue
                        if (c.isWhitespace() || c == '\u00A0') {
                            flushWord()
                            pendingSpace = true
                        } else {
                            if (pendingSpace) flushSpace(run.format)
                            word.append(c)
                        }
                    }
                    flushWord()
                }
            }
        }
        return lines
    }

    private fun delimitersOf(format: HtmlFormat): List<Delimiter> = buildList {
        format.link?.let { add(Delimiter.Link(it)) }
        if (format.strike) add(Delimiter.Strike)
        if (format.bold) add(Delimiter.Bold)
        if (format.italic) add(Delimiter.Italic)
        if (format.code) add(Delimiter.Code)
    }

    /** A line of [pieces], with the delimiters they ask for. */
    private fun writeLine(pieces: List<Piece>, inTable: Boolean, startsBlock: Boolean): Written {
        val out = StringBuilder()
        val open = ArrayDeque<Delimiter>()
        val closers = ArrayDeque<String>()
        var formatted = false
        var afterFormula = false
        fun closeTo(depth: Int) {
            while (open.size > depth) {
                open.removeLast()
                out.append(closers.removeLast())
            }
        }
        for (piece in pieces) {
            when (piece) {
                is Piece.Math -> {
                    closeTo(0)
                    if (out.lastOrNull()?.isLetterOrDigit() == true) out.append(' ')
                    out.append(piece.written)
                    afterFormula = true
                    formatted = true
                }
                is Piece.Words -> {
                    // A browser may lose the space after a formula it copies; a formula followed by a digit would not read as one
                    if (afterFormula && piece.text.first().isLetterOrDigit()) out.append(' ')
                    afterFormula = false
                    // A code holding a backtick has no way of being written as code
                    val format = if (piece.format.code && '`' in piece.text) piece.format.copy(code = false) else piece.format
                    val wanted = delimitersOf(format)
                    var common = 0
                    while (common < open.size && common < wanted.size && open[common] == wanted[common]) common++
                    // An emphasis changing in the middle of a word does not nest: `**a*b***`
                    val emphasisChanges = (open.drop(common) + wanted.drop(common)).any { it.open.startsWith('*') }
                    if (emphasisChanges && out.isNotEmpty() && !out.last().isWhitespace()) {
                        while (common > 0 && open[common - 1].open.startsWith('*')) common--
                    }
                    val closing = open.size > common
                    closeTo(common)
                    // `**a***b*` would not read as two emphases: nothing visible between them
                    if (closing && out.endsWith('*') && wanted.getOrNull(common)?.open?.startsWith('*') == true) out.append(ZERO_WIDTH_SPACE)
                    for (delimiter in wanted.drop(common)) {
                        // A single `*` does not touch another one: `**_a_**`, as `_` does not work inside a word it is the second choice
                        val underscore = delimiter === Delimiter.Italic && out.endsWith('*')
                        out.append(if (underscore) "_" else delimiter.open)
                        closers.addLast(if (underscore) "_" else delimiter.close)
                        open.addLast(delimiter)
                        formatted = true
                    }
                    if (format.code) {
                        out.append(piece.text)
                    } else {
                        val startsLine = startsBlock && out.isEmpty()
                        var at = 0
                        for (formula in MarkdownEscape.formulasIn(piece.text)) {
                            if (formula.first > at) {
                                out.append(MarkdownEscape.escape(piece.text.substring(at, formula.first), startsLine && at == 0, inLink = format.link != null, inTable = inTable))
                            }
                            out.append(piece.text, formula.first, formula.last + 1)
                            at = formula.last + 1
                            formatted = true
                        }
                        if (at < piece.text.length) {
                            out.append(MarkdownEscape.escape(piece.text.substring(at), startsLine && at == 0, inLink = format.link != null, inTable = inTable))
                        }
                    }
                }
            }
        }
        closeTo(0)
        return Written(out.toString().trimEnd(), formatted)
    }
}
