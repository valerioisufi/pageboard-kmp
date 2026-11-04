package io.github.valerioisufi.drawview.model.text

/**
 * A plain text written elsewhere (copied from a PDF, a web page, a chat) turned into the Markdown of
 * the notebook, by rules, the same on every device (see `docs/text-import.md`):
 * - the lines of a paragraph broken by the width of the source joined, a word cut by a hyphen at
 *   the end of a line joined again;
 * - the bullets ("•", "◦", "–", "*", "·", …) as "-", the numbered items ("1)") as "1.", the lines
 *   wrapped under an item joined to it;
 * - a short line alone, without a final sign, before a paragraph, as a heading; a numbered title
 *   ("2.3 Limits") as a heading of its level; a short line in capitals as a heading;
 * - the fenced code blocks, the display formulas between `$$` and the lines already Markdown
 *   (headings, quotes, tables) left as they are.
 */
public object TextCleanup {

    /** The longest line taken for a heading. */
    public const val MAX_HEADING_CHARS: Int = 60

    /**
     * Whether [text] already looks like Markdown (headings, lists, emphasis, code, formulas, quotes):
     * then it is not cleaned by default.
     */
    public fun looksLikeMarkdown(text: String): Boolean {
        val lines = text.lines().map { it.trimStart() }
        return lines.any { MARKDOWN_LINE.containsMatchIn(it) } || text.contains("**") || INLINE_FORMULA.containsMatchIn(text)
    }

    /** [text] as the Markdown of the notebook. */
    public fun toMarkdown(text: String): String {
        val blocks = mutableListOf<String>()
        val paragraph = mutableListOf<String>()
        fun closeParagraph() {
            if (paragraph.isNotEmpty()) blocks += cleanParagraph(paragraph)
            paragraph.clear()
        }
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').replace(' ', ' ').lines()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()
            // A fenced code block or a display formula, as it is
            val fence = when {
                trimmed.startsWith("```") -> "```"
                trimmed.startsWith("~~~") -> "~~~"
                trimmed == "$$" || (trimmed.startsWith("$$") && trimmed.indexOf("$$", 2) < 0) -> "$$"
                else -> null
            }
            if (fence != null) {
                closeParagraph()
                val start = index
                index++
                while (index < lines.size && !lines[index].trim().let { if (fence == "$$") it.contains("$$") else it.startsWith(fence) }) index++
                blocks += lines.subList(start, minOf(index + 1, lines.size)).joinToString("\n")
                index++
                continue
            }
            if (trimmed.isEmpty()) closeParagraph() else paragraph += line
            index++
        }
        closeParagraph()
        // A short line alone before a paragraph is its heading
        return blocks.mapIndexed { i, block ->
            val next = blocks.getOrNull(i + 1)
            if (next != null && isHeadingLike(block) && !isHeadingLike(next)) "## $block" else block
        }.joinToString("\n\n")
    }

    /** The lines of a paragraph joined, its items one per line, its headings marked. */
    private fun cleanParagraph(lines: List<String>): String {
        val out = mutableListOf<String>()
        for (raw in lines) {
            val line = raw.trim()
            val item = itemOf(line)
            val previous = out.lastOrNull()
            when {
                isMarkdownLine(line) || item != null || previous == null || isMarkdownLine(previous) && !isItem(previous) -> out += item ?: line
                // A line wrapped under the previous one (or under an item): joined, a word cut by a hyphen joined again
                else -> out[out.lastIndex] = joined(previous, line)
            }
        }
        if (out.size == 1) {
            val single = out.single()
            numberedHeading(single)?.let { return it }
            if (isCapitalsHeading(single)) return "## " + single.lowercase().replaceFirstChar { it.uppercase() }
        }
        return out.joinToString("\n")
    }

    /** [line] wrapped under [previous] joined to it, a word cut by a hyphen joined again. */
    internal fun joined(previous: String, line: String): String =
        if (previous.endsWith("-") && previous.length > 1 && previous[previous.length - 2].isLetter() && line.firstOrNull()?.isLowerCase() == true) {
            previous.dropLast(1) + line
        } else {
            "$previous $line"
        }

    /** [line] as an item of a list ("- …", "1. …"), or `null` when it is not one. */
    internal fun itemOf(line: String): String? {
        BULLET.matchEntire(line)?.let { return "- " + it.groupValues[1] }
        NUMBERED_ITEM.matchEntire(line)?.let { return it.groupValues[1] + ". " + it.groupValues[2] }
        return null
    }

    private fun isItem(line: String) = line.startsWith("- ") || NUMBERED_ITEM.matches(line)

    /** A line of Markdown that is not joined to the others: a heading, a quote, a row of a table. */
    private fun isMarkdownLine(line: String) = line.startsWith("#") || line.startsWith(">") || line.startsWith("|")

    /** A numbered title ("2.3 Limits", "4 Series") as a heading of its level, or `null`. */
    private fun numberedHeading(line: String): String? {
        val match = NUMBERED_TITLE.matchEntire(line) ?: return null
        if (line.length > MAX_HEADING_CHARS || line.last() in ENDING_SIGNS) return null
        val depth = match.groupValues[1].count { it == '.' } + 1
        return "#".repeat((depth + 1).coerceAtMost(4)) + " " + line
    }

    /** A short line in capitals ("INTRODUCTION"). */
    private fun isCapitalsHeading(line: String): Boolean {
        val letters = line.filter { it.isLetter() }
        return line.length <= MAX_HEADING_CHARS && letters.length >= 3 && letters.all { it.isUpperCase() }
    }

    /** A block that is one short line, without a final sign, that is no item nor Markdown. */
    private fun isHeadingLike(block: String): Boolean =
        '\n' !in block && block.length <= MAX_HEADING_CHARS && block.isNotEmpty() && block.last() !in ENDING_SIGNS &&
            !isMarkdownLine(block) && !isItem(block) && !block.startsWith("```") && !block.startsWith("$$") && block.any { it.isLetter() }

    private val MARKDOWN_LINE = Regex("""^(#{1,6}\s|- |\* |\d+\.\s|```|~~~|>\s|\|)""")
    private val INLINE_FORMULA = Regex("""\$[^\s$][^$\n]*\$""")
    private val BULLET = Regex("""^[•◦▪‣·–—*\-]\s+(.+)$""")
    private val NUMBERED_ITEM = Regex("""^(\d{1,3})[).]\s+(.+)$""")
    private val NUMBERED_TITLE = Regex("""^(\d{1,2}(?:\.\d{1,2})+|\d{1,2})\s+\p{Lu}.*$""")
    private const val ENDING_SIGNS = ".,;:!?"
}
