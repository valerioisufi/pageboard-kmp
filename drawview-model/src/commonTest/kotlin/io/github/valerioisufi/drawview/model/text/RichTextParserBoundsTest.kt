package io.github.valerioisufi.drawview.model.text

import io.github.valerioisufi.drawview.model.text.RichTextParser.Kind
import io.github.valerioisufi.drawview.model.text.RichTextParser.Span
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
        for (pattern in listOf("**a ", "*a ", "__a ", "_a ", "~~a ", "**a *b __c _d ~~e ", "**a\n*b\n__c\n_d\n~~e\n")) {
            assertLinear(pattern)
        }
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
    fun theEmphasisIsWhatTheRegularExpressionsFound() {
        val random = Random(1022)
        repeat(10_000) {
            val text = randomText(random, random.nextInt(0, 40))
            // Some characters masked, as the code and the formulas are
            val masked = text.map { if (random.nextInt(12) == 0) MASK else it }.joinToString("")
            assertEquals(referenceEmphasis(masked, text), RichTextParser.emphasis(masked, text), "masked: $masked, text: $text")
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

    /** The emphasis as the regular expressions found it before the scan (see [RichTextParser.emphasis]). */
    private fun referenceEmphasis(masked: String, text: String): List<Span> {
        fun String.isWordAt(index: Int) = getOrNull(index)?.isLetterOrDigit() == true
        val spans = mutableListOf<Span>()
        for ((kind, delimiter, regex, wordBound) in REFERENCE) {
            for (match in regex.findAll(masked)) {
                val start = match.range.first
                val end = match.range.last + 1
                if (wordBound && (text.isWordAt(start - 1) || text.isWordAt(end))) continue
                spans += Span(kind, start, end, start + delimiter, end - delimiter)
            }
        }
        return spans
    }

    private data class Rule(val kind: Kind, val delimiter: Int, val regex: Regex, val wordBound: Boolean = false)

    private companion object {
        const val MIB = 1 shl 20
        val BOUND = 10.seconds
        const val MASK = ''

        /** The parts of the random texts: the delimiters often, an emoji, a lone surrogate, spaces of Unicode. */
        val PARTS = listOf(
            "*", "*", "*", "_", "_", "_", "~", "~", "`", "$", "$", "\\", "#", "-", "+", "1", ".",
            "a", "b", "é", " ", " ", "\t", "\n", " ", "😀", "\uD800"
        )

        // The content touches its delimiters; [\s\S]: any character, the line breaks included
        val REFERENCE = listOf(
            Rule(Kind.BOLD, 2, Regex("""\*\*(?=\S)([\s\S]+?)(?<=\S)\*\*""")),
            Rule(Kind.BOLD, 2, Regex("""__(?=\S)([\s\S]+?)(?<=\S)__"""), wordBound = true),
            Rule(Kind.STRIKE, 2, Regex("""~~(?=\S)([\s\S]+?)(?<=\S)~~""")),
            Rule(Kind.ITALIC, 1, Regex("""(?<!\*)\*(?![\s*])([\s\S]+?)(?<![\s*])\*(?!\*)""")),
            Rule(Kind.ITALIC, 1, Regex("""(?<!_)_(?![\s_])([\s\S]+?)(?<![\s_])_(?!_)"""), wordBound = true)
        )
    }
}
