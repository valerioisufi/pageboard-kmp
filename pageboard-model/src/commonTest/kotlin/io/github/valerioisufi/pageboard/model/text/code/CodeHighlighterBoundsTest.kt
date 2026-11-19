package io.github.valerioisufi.pageboard.model.text.code

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * The lexer on code written against it, as another device may send it: 256 KiB (four times the
 * code highlighted) of every pattern that opens a form without closing it read in linear time by
 * every grammar (a quadratic time would take minutes), random code never making it throw, its
 * tokens always in the code, sorted and apart.
 */
class CodeHighlighterBoundsTest {

    @Test
    fun formsNeverClosedTakeLinearTime() {
        for ((name, grammar) in GRAMMARS) {
            for (pattern in PATTERNS) {
                val code = pattern.repeat(LENGTH / pattern.length)
                val time = measureTime { CodeLexer(grammar, code).tokens() }
                assertTrue(time < BOUND, "$name, ${pattern.replace("\n", "\\n")} repeated up to 256 KiB: $time")
            }
        }
    }

    @Test
    fun randomCodeNeverMakesItThrow() {
        val random = Random(20261024)
        for ((name, grammar) in GRAMMARS) {
            repeat(1_000) {
                val code = buildString { repeat(random.nextInt(0, 80)) { append(PARTS[random.nextInt(PARTS.size)]) } }
                val tokens = CodeLexer(grammar, code).tokens()
                var last = 0
                for (token in tokens) {
                    assertTrue(token.start >= last && token.end > token.start && token.end <= code.length, "$name: $token of $tokens in $code")
                    last = token.end
                }
            }
        }
    }

    private companion object {
        const val LENGTH = 256 * 1024
        val BOUND = 10.seconds

        /** Every grammar once, by one of its names. */
        val GRAMMARS = CodeGrammars.byName.entries.distinctBy { it.value }.map { it.key to it.value }

        /** Openings of strings, comments, characters, raw strings, attributes, variables, keys, numbers, calls. */
        val PATTERNS = listOf(
            "\"", "'", "`", "\"\"\"", "'''", "/*", "*/", "/* /*", "//", "#", "--", "\\", "'\\", "'a", "r#", "br##",
            "#\"", "##", "R\"", "R\"abc", "#[", "#![", "\${", "\$'", "\$", "@", "@a.", "&", "!", "- ", "- a", "a: ", "a",
            "a (", "a\n", "0x", "1e+", "1.", ".1", "#include <", "\n#include <", "u8", "rb", "f'", "@\"", "\$@\"", "<",
            "#if ", "a!", "'\\u{", "\"a\": ", " # ", "\n- a: b #"
        )

        /** The parts of the random code: the characters of the forms often, letters, digits, spaces, Unicode. */
        val PARTS = listOf(
            "\"", "'", "`", "/", "*", "#", "-", "\\", "\$", "{", "}", "[", "]", "(", ")", "@", "&", "!", ":", "<", ">",
            "r", "b", "R", "u8", "a", "Z", "_", "0", "x", "1", "e", "+", ".", " ", "\t", "\n", "é", "😀", "\uD800", "\"\"\""
        )
    }
}
