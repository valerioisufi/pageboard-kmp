package io.github.valerioisufi.pageboard.model.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LatexCommandsTest {

    @Test
    fun matchesPrefixInsideFormulaAndIgnoresOutside() {
        val text = "Text \\al inside \$\\al\$"
        // Outside formula range (`17 until 20` covers `\al` between `$...$`)
        assertNull(LatexCommands.match(text, caret = 8, formulaRange = 17 until 20))

        val match = assertNotNull(LatexCommands.match(text, caret = 20, formulaRange = 17 until 20))
        assertEquals("al", match.prefix)
        assertEquals(17, match.tokenStart)
        assertEquals(20, match.tokenEnd)
        assertEquals("alpha", match.candidates.first().name)
    }

    @Test
    fun bareBackslashAndDoubleBackslashLineBreakDoNotTrigger() {
        assertNull(LatexCommands.match("\$\\\$", caret = 2, formulaRange = 1 until 2))
        assertNull(LatexCommands.match("\$\\\\al\$", caret = 5, formulaRange = 1 until 5))
    }

    @Test
    fun soleExactSimpleCommandReturnsNullWhileStructureCommandsRemain() {
        val exactAlpha = "\$\\alpha\$"
        assertNull(LatexCommands.match(exactAlpha, caret = 7, formulaRange = 1 until 7))

        val exactFrac = "\$\\frac\$"
        val match = assertNotNull(LatexCommands.match(exactFrac, caret = 6, formulaRange = 1 until 6))
        assertEquals("frac", match.candidates.single().name)
    }

    @Test
    fun applyInsertsBracesAndPlacesCaretInsideFirstArgument() {
        val text = "\$x + \\fr\$"
        val match = assertNotNull(LatexCommands.match(text, caret = 8, formulaRange = 1 until 8))
        val frac = match.candidates.first { it.name == "frac" }
        val (updated, caret) = LatexCommands.apply(text, match, frac)
        assertEquals("\$x + \\frac{}{}\$", updated)
        assertEquals(11, caret) // inside first `{}` after `\frac{`

        val sqrtText = "\$\\sq\$"
        val sqrtMatch = assertNotNull(LatexCommands.match(sqrtText, caret = 4, formulaRange = 1 until 4))
        val (sqrtUpdated, sqrtCaret) = LatexCommands.apply(sqrtText, sqrtMatch, sqrtMatch.candidates.first { it.name == "sqrt" })
        assertEquals("\$\\sqrt{}\$", sqrtUpdated)
        assertEquals(7, sqrtCaret)
        assertTrue(sqrtMatch.candidates.size <= 8)
    }
}
