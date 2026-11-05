package io.github.valerioisufi.drawview.html

import io.github.valerioisufi.drawview.model.text.RichTextParser.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the applications copy: Word, Google Docs, the pages with formulas; and what a hostile page may copy. */
class SourcesTest {

    private fun convert(html: String): String? = HtmlToMarkdown().convert(html)

    @Test
    fun aFormulaCopiedAsItsSourceIsAFormula() {
        // As Gemini copies it from Chrome: the source between dollars, as text
        val gemini = "<ul><li><b>Pag. 18 (dopo la Slide 14,</b> <b><i>layer \$l\$</i></b><b>):</b> compare \$\\sigma_{fwd}^2\$ invece di \$\\sigma_{bwd}^2\$:" +
            "<br>\$\$\\sigma^2 = \\frac{2}{n_{IN} + n_{OUT}}\$\$</li><li><i>a \$\\lambda\$</i></li></ul>"
        val markdown = convert(gemini).orEmpty()
        assertTrue("\\\$" !in markdown && "\\_" !in markdown, markdown)
        val spans = parsed(markdown)
        val formulas = spans.filter { it.kind == Kind.FORMULA }
        assertEquals(5, formulas.size, markdown)
        assertEquals(1, formulas.count { it.display }, markdown)
        assertEquals(2, spans.count { it.kind == Kind.LIST_ITEM }, markdown)
    }

    @Test
    fun aPriceIsNotAFormula() {
        assertEquals("**a** costa \\\$5 e \\\$10", convert("<b>a</b> costa \$5 e \$10"))
        assertEquals("**a** \\\$un testo qualunque\\\$", convert("<b>a</b> \$un testo qualunque\$"))
        assertEquals("**a** \\_b\\_ \$x_1\$ \\*", convert("<b>a</b> _b_ \$x_1\$ *"))
    }

    @Test
    fun theListsOfWordBecomeLists() {
        val word = """
            <p class=MsoNormal><b>Titolo<o:p></o:p></b></p>
            <p class=MsoListParagraphCxSpFirst style='text-indent:-18.0pt;mso-list:l0 level1 lfo1'><![if !supportLists]><span style='font-family:Symbol;mso-fareast-font-family:Symbol'><span style='mso-list:Ignore'>·<span style='font:7.0pt "Times New Roman"'>&nbsp;&nbsp;</span></span></span><![endif]>Primo<o:p></o:p></p>
            <p class=MsoListParagraphCxSpMiddle style='margin-left:72.0pt;mso-list:l0 level2 lfo1'><![if !supportLists]><span style='font-family:"Courier New"'><span style='mso-list:Ignore'>o<span style='font:7.0pt "Times New Roman"'>&nbsp;&nbsp;</span></span></span><![endif]>Sotto <i>corsivo</i><o:p></o:p></p>
            <p class=MsoListParagraphCxSpLast style='mso-list:l0 level1 lfo1'><![if !supportLists]><span style='font-family:Symbol'><span style='mso-list:Ignore'>·<span>&nbsp;</span></span></span><![endif]>Secondo<o:p></o:p></p>
            <p class=MsoNormal>Dopo<o:p></o:p></p>
        """.trimIndent()
        assertEquals("**Titolo**\n\n- Primo\n  - Sotto *corsivo*\n- Secondo\n\nDopo", convert(word))
    }

    @Test
    fun theNumbersOfWordAreKept() {
        val word = """
            <p class=MsoListParagraph style='mso-list:l1 level1 lfo2'><![if !supportLists]><span><span style='mso-list:Ignore'>3.<span>&nbsp;</span></span></span><![endif]>Tre</p>
            <p class=MsoListParagraph style='mso-list:l1 level1 lfo2'><![if !supportLists]><span><span style='mso-list:Ignore'>4.<span>&nbsp;</span></span></span><![endif]>Quattro</p>
            <p class=MsoListParagraph style='mso-list:l2 level1 lfo3'><![if !supportLists]><span><span style='mso-list:Ignore'>a)<span>&nbsp;</span></span></span><![endif]>Lettera</p>
        """.trimIndent()
        assertEquals("3. Tre\n4. Quattro\n\na.  Lettera", convert(word))
    }

    @Test
    fun theTitlesOfWordAreHeadings() {
        assertEquals("# Grande\n\n## Sotto", convert("<p class=MsoTitle>Grande</p><p class=MsoSubtitle>Sotto</p>"))
        assertEquals("### Terzo", convert("<h3><span style='mso-fareast-font-family:Arial'>Terzo<o:p></o:p></span></h3>"))
    }

    @Test
    fun anEquationOfWordLeavesAPlaceholder() {
        val word = """<p class=MsoNormal><b>x</b> vale <!--[if gte msEquation 12]><m:oMath><m:r><m:t>a</m:t></m:r></m:oMath><![endif]--><![if !msEquation]><img src="file:///C:/tmp/image001.png" alt=""><![endif]></p>"""
        assertEquals("**x** vale [equation]", convert(word))
        assertEquals("**x** vale [equazione]", HtmlToMarkdown("[equazione]").convert(word))
    }

    @Test
    fun aTableOfWordIsATable() {
        val word = """<table class=MsoTableGrid border=1><tr><td width=100><p class=MsoNormal><b>A<o:p></o:p></b></p></td><td><p class=MsoNormal><b>B<o:p></o:p></b></p></td></tr><tr><td><p class=MsoNormal>1</p></td><td><p class=MsoNormal>2</p></td></tr></table>"""
        assertEquals("| **A** | **B** |\n| --- | --- |\n| 1 | 2 |", convert(word))
    }

    @Test
    fun aTextOfGoogleDocsKeepsItsFormats() {
        val docs = """<meta charset='utf-8'><b style="font-weight:normal;" id="docs-internal-guid-abc"><h1 dir="ltr"><span style="font-size:20pt;font-weight:400;">Titolo</span></h1>
            <p dir="ltr"><span style="font-weight:400;">Testo </span><span style="font-weight:700;">grassetto</span><span style="font-weight:400;"> e </span><span style="font-weight:400;font-style:italic;">corsivo</span></p>
            <ul style="margin-top:0"><li dir="ltr" style="list-style-type:disc;" aria-level="1"><p dir="ltr" role="presentation"><span style="font-weight:400;">Voce</span></p></li></ul></b>"""
        assertEquals("# Titolo\n\nTesto **grassetto** e *corsivo*\n\n- Voce", convert(docs))
    }

    @Test
    fun aFormulaOfKatexIsAFormula() {
        val inline = """<p><b>Sia</b> <span class="katex"><span class="katex-mathml"><math xmlns="http://www.w3.org/1998/Math/MathML"><semantics><mrow><msup><mi>x</mi><mn>2</mn></msup></mrow><annotation encoding="application/x-tex">x^2</annotation></semantics></math></span><span class="katex-html" aria-hidden="true"><span class="base">x2</span></span></span> dato.</p>"""
        assertEquals("**Sia** \$x^2\$ dato.", convert(inline))
        val display = """<p>Prima</p><span class="katex-display"><span class="katex"><span class="katex-mathml"><math display="block"><semantics><mrow><mi>y</mi></mrow><annotation encoding="application/x-tex">\frac{a}{b}</annotation></semantics></math></span><span class="katex-html" aria-hidden="true">ab</span></span></span><p>Dopo</p>"""
        val markdown = convert(display)
        assertEquals("Prima\n\n\$\$\\frac{a}{b}\$\$\n\nDopo", markdown)
        assertTrue(parsed(markdown).any { it.kind == Kind.FORMULA && it.display })
    }

    @Test
    fun aFormulaOfWikipediaIsAFormula() {
        val page = """<p>Sia <span class="mwe-math-element"><span class="mwe-math-mathml-inline mwe-math-mathml-a11y" style="display: none;"><math alttext="{\displaystyle x}"><semantics><mi>x</mi><annotation encoding="application/x-tex">{\displaystyle x}</annotation></semantics></math></span><img src="https://wikimedia.org/x.svg" class="mwe-math-fallback-image-inline" alt="{\displaystyle x}"></span> un numero</p>"""
        assertEquals("Sia \${\\displaystyle x}\$ un numero", convert(page))
    }

    @Test
    fun aFormulaOfMathJaxIsAFormula() {
        val version2 = """<p>Sia <span class="MathJax_Preview">x</span><span class="MathJax" id="MathJax-Element-1-Frame">x</span><script type="math/tex" id="MathJax-Element-1">x+1</script> un numero</p>"""
        assertEquals("Sia \$x+1\$ un numero", convert(version2))
        val display2 = """<div class="MathJax_Display">y</div><script type="math/tex; mode=display">y=2</script>"""
        assertEquals("\$\$y=2\$\$", convert(display2))
        val version3 = """<mjx-container class="MathJax" display="true"><svg></svg><mjx-assistive-mml><math><semantics><mi>z</mi><annotation encoding="application/x-tex">z</annotation></semantics></math></mjx-assistive-mml></mjx-container>"""
        assertEquals("\$\$z\$\$", convert(version3))
    }

    @Test
    fun aFormulaWithoutItsSourceKeepsItsText() {
        assertEquals("**a** x2", convert("""<b>a</b> <math><msup><mi>x</mi><mn>2</mn></msup></math>"""))
    }

    @Test
    fun aFormulaInACellOrAHeadingStaysInTheLine() {
        assertEquals("# Sia \$x\$", convert("""<h1>Sia <span class="katex"><annotation encoding="application/x-tex">x</annotation></span></h1>"""))
        val table = """<table><tr><td>a</td><td>b</td></tr><tr><td><math display="block" alttext="|x|"></math></td><td>c</td></tr></table>"""
        assertEquals("| a | b |\n| --- | --- |\n| \$|x|\$ | c |", convert(table))
    }

    @Test
    fun theCaptionOfATableIsKept() {
        assertEquals("Didascalia\n\n| a | b |\n| --- | --- |\n| 1 | 2 |", convert("<table><caption>Didascalia</caption><tr><td>a</td><td>b</td></tr><tr><td>1</td><td>2</td></tr></table>"))
    }

    @Test
    fun aConversionLosingWordsLeavesThePlainText() {
        // The text directly in a list has no item to go in
        assertNull(convert("<ul>parola persa<li><b>a</b></li></ul>"))
    }

    @Test
    fun aHtmlTooLargeLeavesThePlainText() {
        assertNull(convert("<b>a</b>" + " ".repeat(2_000_001)))
    }

    @Test
    fun aHtmlDeeplyNestedIsBoundedAndKeepsItsText() {
        val depth = 100_000
        for (tag in listOf("div", "span", "ul", "blockquote")) {
            val html = "<b>a</b>" + "<$tag>".repeat(depth) + "fondo" + "</$tag>".repeat(depth)
            val markdown = convert(html)
            assertTrue(markdown == null || "fondo" in markdown, tag)
        }
    }

    @Test
    fun aTableAskingForManyColumnsIsBounded() {
        val markdown = convert("""<table><tr><td colspan="1000000000">a</td></tr><tr><td>b</td><td>c</td></tr></table>""")
        assertTrue(markdown != null && markdown.length < 1000)
    }
}
