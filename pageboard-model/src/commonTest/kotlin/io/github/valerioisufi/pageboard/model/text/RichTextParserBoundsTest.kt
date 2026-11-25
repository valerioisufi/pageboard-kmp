package io.github.valerioisufi.pageboard.model.text

import io.github.valerioisufi.pageboard.model.text.RichTextParser.Kind
import io.github.valerioisufi.pageboard.model.text.RichTextParser.Span
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * The parser on texts written against it, as another device may send them: texts of 1 MiB parsed
 * in linear time, random texts never making it throw, and its scans finding what the regular
 * expressions they replace found.
 */
class RichTextParserBoundsTest {

    /** Parses [pattern] repeated up to 1 MiB, within the bound. */
    private fun assertLinear(pattern: String) {
        val text = pattern.repeat(MIB / pattern.length)
        val time = measureTime { RichTextParser.parse(text) }
        assertTrue(time < BOUND, "${pattern.replace("\n", "\\n")} repeated up to 1 MiB: $time")
    }

    @Test
    fun delimitersNeverClosedTakeLinearTime() {
        for (pattern in listOf("**a ", "*a ", "***a ", "*a_b", "__a ", "_a ", "~~a ", "**a *b __c _d ~~e ", "**a\n*b\n__c\n_d\n~~e\n")) {
            assertLinear(pattern)
        }
    }

    @Test
    fun manySpansTakeLinearTime() {
        val patterns = listOf(
            "*a* ", "**a** ", "~~a~~ ", "\\*", "`a` ", "# a b\n", "- a\n", "  1. a\n", "\$a\$ ",
            // Formulas never closed on one line, display formulas on one line and on lines of their own
            "\$a ", "\$\$a\$\$", "\$\$a\$\$\n",
            "- **a** *b* ~~c~~ `d` \$e\$ \\* \$f\n## g \$\$h\$\$\n",
            // Items with their continuation lines
            "- a\n  b\n", "1. a\n   b\n   c\n", "- a\n  b\n  - c\n    d\n",
            // Tables, and lines with pipes that are not
            "| a | b |\n|---|---|\n| 1 | 2 |\n\n", "a | b\n", "| a |\n|-|\n| `x | y` |\n"
        )
        for (pattern in patterns) assertLinear(pattern)
        // One item over the whole text
        val long = "- a\n" + "  b\n".repeat(MIB / 4)
        assertTrue(measureTime { ListNumbering.items(long, RichTextParser.parse(long)) } < BOUND)
    }

    @Test
    fun blocksOfCodeTakeLinearTime() {
        val patterns = listOf(
            // Blocks closed, a fence never closed, fences of backticks that are not (a backtick after them)
            "```\na\n```\n", "~~~~\na\n~~~\n", "```a\n", "``` `\n", "```` ``\n", "   ```\n",
            // Display formulas and emphasis meeting blocks
            "\$\$\n```\n", "\$\$a\n```\n```\n", "**a\n```\n```\n", "*a ```\n",
            // Blocks under list items, nested, closed by a line less indented
            "- a\n  ```\n  x\n  ```\n", "- a\n  - b\n    ```\n", "1. a\n   ```\nx\n", "- a\n" + "  ".repeat(8) + "- b\n"
        )
        for (pattern in patterns) assertLinear(pattern)
    }

    @Test
    fun randomTextsNeverMakeItThrow() {
        val random = Random(20261022)
        repeat(2_000) {
            val text = randomText(random, random.nextInt(0, 120))
            assertWellFormed(text, RichTextParser.parse(text))
        }
    }

    @Test
    fun randomEmphasisStaysWellFormedAndMatchesStrikethroughReference() {
        val random = Random(1022)
        repeat(10_000) {
            val text = randomText(random, random.nextInt(0, 40))
            // Some characters masked, as the code and the formulas are
            val masked = text.map { if (random.nextInt(12) == 0) MASK else it }.joinToString("")
            val nested = RichTextParser.nest(RichTextParser.emphasis(masked, text))
            assertWellFormed(text, nested)
            val strikeOnly = RichTextParser.emphasis(masked, text).filter { it.kind == Kind.STRIKE }
            assertEquals(referenceStrike(masked), strikeOnly, "masked: $masked, text: $text")
        }
    }

    @Test
    fun theInlineFormulasCloseWhereTheWalkOfEachFound() {
        val random = Random(2210)
        repeat(10_000) {
            val text = randomText(random, random.nextInt(0, 40))
            val closings = RichTextParser.InlineClosings(text)
            for (open in text.indices) {
                if (text[open] == '$') assertEquals(referenceClosing(text, open), closings.closing(open), "the $ at $open of $text")
            }
        }
    }

    @Test
    fun displayFormulasAreAloneOnTheirLinesAsBefore() {
        val random = Random(221)
        repeat(10_000) {
            val text = randomText(random, random.nextInt(0, 40))
            val start = random.nextInt(0, text.length + 1)
            val end = random.nextInt(start, text.length + 1)
            assertEquals(referenceAlone(text, start, end), RichTextParser.isAloneOnItsLines(text, start, end), "$start .. $end of $text")
        }
    }

    @Test
    fun theNestingIsWhatComparingWithEverySpanGave() {
        val random = Random(22)
        repeat(10_000) {
            val candidates = List(random.nextInt(0, 12)) {
                val start = random.nextInt(0, 30)
                val end = random.nextInt(start + 1, start + 16)
                val contentStart = random.nextInt(start, end + 1)
                val contentEnd = random.nextInt(contentStart, end + 1)
                Span(Kind.entries[random.nextInt(Kind.entries.size)], start, end, contentStart, contentEnd)
            }
            assertEquals(referenceNest(candidates), RichTextParser.nest(candidates), "$candidates")
        }
    }

    /** The spans of [text] lie in it, sorted, nested in the content of each other or apart. */
    private fun assertWellFormed(text: String, spans: List<Span>) {
        for (span in spans) {
            assertTrue(span.start <= span.contentStart && span.contentStart <= span.contentEnd && span.contentEnd <= span.end, "$span")
            assertTrue(span.start >= 0 && span.end <= text.length, "$span in a text of ${text.length}")
        }
        assertEquals(spans.sortedBy { it.start }, spans)
        for (a in spans) for (b in spans) {
            if (a === b) continue
            val apart = a.end <= b.start || b.end <= a.start
            val nested = (a.start >= b.contentStart && a.end <= b.contentEnd) || (b.start >= a.contentStart && b.end <= a.contentEnd)
            assertTrue(apart || nested, "$a and $b in $text")
        }
    }

    /** A text of [length] parts, each a character of the notation, a letter, a space or a line break. */
    private fun randomText(random: Random, length: Int) = buildString { repeat(length) { append(PARTS[random.nextInt(PARTS.size)]) } }

    /** The strikethrough emphasis as the regular expression found it before the scan. */
    private fun referenceStrike(masked: String): List<Span> =
        STRIKE_REFERENCE.findAll(masked).map { match ->
            Span(Kind.STRIKE, match.range.first, match.range.last + 1, match.range.first + 2, match.range.last - 1)
        }.toList()

    /** The `$` closing the formula opened at [open], walked from it as before [RichTextParser.InlineClosings]. */
    private fun referenceClosing(text: String, open: Int): Int {
        val first = text.getOrNull(open + 1) ?: return -1
        if (first.isWhitespace() || first == '$') return -1
        var j = open + 1
        while (j < text.length) {
            val c = text[j]
            if (c == '\n') return -1
            if (c == '\\') {
                j += 2
                continue
            }
            if (c == '$') {
                val closes = !text[j - 1].isWhitespace() && text.getOrNull(j + 1)?.isDigit() != true && text.getOrNull(j + 1) != '$'
                if (closes) return j
            }
            j++
        }
        return -1
    }

    /** Whether only spaces are around [start] .. [end] on their lines, their whole lines read. */
    private fun referenceAlone(text: String, start: Int, end: Int): Boolean {
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        return text.substring(lineStart, start).isBlank() && text.substring(end, lineEnd).isBlank()
    }

    /** The nesting comparing each span with every span kept before it (see [RichTextParser.nest]). */
    private fun referenceNest(candidates: List<Span>): List<Span> {
        val sorted = candidates.sortedWith(compareBy<Span> { it.start }.thenByDescending { it.end })
        val kept = mutableListOf<Span>()
        for (span in sorted) {
            val fits = kept.all { other ->
                span.end <= other.start || span.start >= other.end ||
                    (other.kind != Kind.CODE && other.kind != Kind.CODE_BLOCK && other.kind != Kind.FORMULA && other.kind != Kind.ESCAPE &&
                        span.start >= other.contentStart && span.end <= other.contentEnd)
            }
            if (fits) kept += span
        }
        return kept
    }

    private companion object {
        const val MIB = 1 shl 20
        val BOUND = 10.seconds
        const val MASK = ''

        /** The parts of the random texts: the delimiters often, fences, an emoji, a lone surrogate, spaces of Unicode. */
        val PARTS = listOf(
            "*", "*", "*", "_", "_", "_", "~", "~", "`", "$", "$", "\\", "#", "-", "+", "1", ".",
            "a", "b", "é", " ", " ", "\t", "\n", " ", "😀", "\uD800", "```", "\n```", "~~~"
        )

        // The content touches its delimiters; [\s\S]: any character, the line breaks included
        val STRIKE_REFERENCE = Regex("""~~(?=\S)([\s\S]+?)(?<=\S)~~""")
    }
}
