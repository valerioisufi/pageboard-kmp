package io.github.valerioisufi.pageboard.html

import kotlin.test.Test
import kotlin.test.assertEquals

/** Subscripts and superscripts pasted keep their shift: in Unicode, else in a formula. */
class ScriptShiftTest {

    private fun paste(html: String) = HtmlToMarkdown().convert(html)

    @Test
    fun aScriptOfCharactersUnicodeShiftsIsWrittenInThem() {
        assertEquals("H₂O and CO₂", paste("<p>H<sub>2</sub>O and CO<sub>2</sub></p>"))
        assertEquals("x² + y⁻¹", paste("<p>x<sup>2</sup> + y<sup>-1</sup></p>"))
        assertEquals("the 1ˢᵗ one", paste("<p>the 1<sup>st</sup> one</p>"))
    }

    @Test
    fun anotherScriptIsAFormula() {
        // A formula opens after a space (see InlineText)
        assertEquals("e \$^{\\text{iπ}}\$", paste("<p>e<sup>iπ</sup></p>"))
        assertEquals("xₘₐₓ is", paste("<p>x<sub>max</sub> is</p>"), "letters with their shift in Unicode")
        assertEquals("x \$_{\\text{big}}\$ is", paste("<p>x<sub>big</sub> is</p>"), "b and g have none")
        assertEquals("a \$^{2q}\$", paste("<p>a<sup>2q</sup></p>"), "not only letters, q with no shift: as it is in the formula")
    }

    @Test
    fun aScriptLaTeXWouldReadIsKeptAsItIs() {
        assertEquals("ab{c}", paste("<p>a<sup>b{c}</sup></p>")?.replace("\\", ""))
    }
}
