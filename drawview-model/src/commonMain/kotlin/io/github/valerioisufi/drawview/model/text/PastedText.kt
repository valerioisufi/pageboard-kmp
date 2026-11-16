package io.github.valerioisufi.drawview.model.text

/**
 * A text pasted from elsewhere made fit for a text of the notebook, the same on every device, without
 * being rewritten (see `docs/plans/text-box-fixes.md`):
 * - its line endings and its non-breaking spaces always normalized;
 * - unless it already looks like Markdown: at least two bullets ("•", "–", "1)", …) made a list, the
 *   lines wrapped under an item joined to it;
 * - and the lines of a paragraph joined only when they look broken by the width of the source.
 *
 * It never guesses a heading: that is for the import of a long text, whose sheet shows the result
 * (see [TextCleanup]).
 */
public object PastedText {

    /** A paragraph of fewer lines, or of shorter ones, is taken as written (verses, an address, a list of names). */
    private const val MIN_WRAPPED_LINES = 3
    private const val MIN_WRAPPED_CHARS = 40

    /** How short a line broken by the width of the source may be, against the longest one. */
    private const val MIN_WRAPPED_RATIO = 0.7f

    /** The signs ending a line that was meant to end there. */
    private const val SENTENCE_ENDS = ".:;!?"

    /** The start of a line that belongs to a block of the notation. */
    private val BLOCK_LINE = Regex("""^(#{1,6} |- |\d+\. |[0-9A-Za-z]+[.)]  |> |>$|\||```|\$\$)""")

    /** [text] with its line endings and its non-breaking spaces normalized, nothing else. */
    public fun normalized(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n').replace(' ', ' ')

    /** [text] as it goes in a text of the notebook. */
    public fun clean(text: String): String {
        val normal = normalized(text)
        if (TextCleanup.looksLikeMarkdown(normal)) return normal
        val lines = normal.split('\n')
        val items = lines.filter { TextCleanup.itemOf(it.trim()) != null }
        val isList = items.size >= 2
        // The depths of the items, by the indents they have
        val indents = items.map { indentOf(it) }.distinct().sorted()
        val out = mutableListOf<String>()
        var index = 0
        while (index < lines.size) {
            if (lines[index].isBlank()) {
                out += lines[index++]
                continue
            }
            val start = index
            while (index < lines.size && lines[index].isNotBlank()) index++
            val paragraph = lines.subList(start, index)
            out += when {
                isList && paragraph.any { TextCleanup.itemOf(it.trim()) != null } -> listOf(paragraph, indents)
                looksWrapped(paragraph) -> listOf(paragraph.map { it.trim() }.reduce(TextCleanup::joined))
                else -> paragraph
            }
        }
        return out.joinToString("\n")
    }

    /**
     * [markdown] pasted between [before] and [after] of a text: a block (a heading, a list, a table,
     * …) needs a line of its own to be read as one, so a line break is added where the text around
     * it is on the same line. A paragraph joins the line it is pasted in.
     */
    public fun placed(markdown: String, before: String, after: String): String {
        val opens = before.substringAfterLast('\n').isNotBlank() && isBlockLine(markdown.substringBefore('\n'))
        val closes = after.substringBefore('\n').isNotBlank() && isBlockLine(markdown.substringAfterLast('\n'))
        return (if (opens) "\n\n" else "") + markdown + (if (closes) "\n\n" else "")
    }

    private fun isBlockLine(line: String): Boolean = BLOCK_LINE.containsMatchIn(line.trimStart())

    /** The lines of a [paragraph] with items: each item at its depth, the lines under it joined to it. */
    private fun listOf(paragraph: List<String>, indents: List<Int>): List<String> {
        val out = mutableListOf<String>()
        var inItem = false
        for (line in paragraph) {
            val item = TextCleanup.itemOf(line.trim())
            when {
                item != null -> {
                    out += " ".repeat(indents.indexOf(indentOf(line)) * 2) + item
                    inItem = true
                }
                inItem -> out[out.lastIndex] = TextCleanup.joined(out.last(), line.trim())
                else -> out += line
            }
        }
        return out
    }

    private fun indentOf(line: String): Int = line.length - line.trimStart().length

    /** Whether the lines of [paragraph] look broken by the width of the source: long, as long as each other, not ending sentences. */
    private fun looksWrapped(paragraph: List<String>): Boolean {
        if (paragraph.size < MIN_WRAPPED_LINES || paragraph.any { indentOf(it) > 0 }) return false
        val broken = paragraph.dropLast(1)
        val longest = broken.maxOf { it.trimEnd().length }
        return longest >= MIN_WRAPPED_CHARS &&
            broken.all { it.trimEnd().length >= longest * MIN_WRAPPED_RATIO && it.trimEnd().last() !in SENTENCE_ENDS }
    }
}
