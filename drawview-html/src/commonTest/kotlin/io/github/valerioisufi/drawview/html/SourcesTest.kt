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
    fun anEquationOfWordThatCannotBeWrittenLeavesAPlaceholder() {
        val word = """<p class=MsoNormal><b>x</b> vale <!--[if gte msEquation 12]><m:oMath><m:nuovo><m:r>a</m:r></m:nuovo></m:oMath><![endif]--><![if !msEquation]><img src="file:///C:/tmp/image001.png" alt=""><![endif]></p>"""
        assertEquals("**x** vale [equation]", convert(word))
        assertEquals("**x** vale [equazione]", HtmlToMarkdown("[equazione]").convert(word))
    }

    @Test
    fun anEquationOfWordInALineIsAFormula() {
        // As Word copied it, its styles left out
        val word = """<p class=MsoNormal><b>Testo</b> con <!--[if gte msEquation 12]><m:oMath><m:sSubSup><m:sSubSupPr><span style='font-family:"Cambria Math",serif'><m:ctrlPr></m:ctrlPr></span></m:sSubSupPr><m:e><i><span><m:r>σ</m:r></span></i></m:e><m:sub><i><span><m:r>fwd</m:r></span></i></m:sub><m:sup><i><span><m:r>2</m:r></span></i></m:sup></m:sSubSup></m:oMath><![endif]--><![if !msEquation]><span style='font-size:12.0pt'><!--[if gte vml 1]><v:shape id="_x0000_i1025"><v:imagedata src="file:///C:/tmp/a.png" o:title=""/></v:shape><![endif]--><![if !vml]><img width=33 height=25 src="file:///C:/tmp/a.png" v:shapes="_x0000_i1025"><![endif]></span><![endif]><span>&nbsp;</span>e<o:p></o:p></p>"""
        val markdown = convert(word)
        assertEquals("**Testo** con \$\\sigma_{fwd}^{2}\$ e", markdown)
        assertEquals(1, parsed(markdown).count { it.kind == Kind.FORMULA })
    }

    @Test
    fun anEquationOfWordOnItsLineStaysInTheItemItGoesOn() {
        val equation = """<!--[if gte msEquation 12]><m:oMathPara><m:oMath><m:f><m:fPr><span><m:ctrlPr></m:ctrlPr></span></m:fPr><m:num><span><m:r><m:rPr><m:scr m:val="roman"/><m:sty m:val="p"/></m:rPr>2</m:r></span></m:num><m:den><i><span><m:r>n</m:r></span></i></m:den></m:f></m:oMath></m:oMathPara><![endif]--><![if !msEquation]><img src="file:///C:/tmp/a.png"><![endif]>"""
        val item = { text: String -> """<p class=MsoListParagraph style='mso-list:l0 level1 lfo1'><![if !supportLists]><span><span style='mso-list:Ignore'>·<span>&nbsp;</span></span></span><![endif]>$text<o:p></o:p></p>""" }
        val expected = "- uno\n  \$\$\\frac{2}{n}\$\$\n- due"
        // In a paragraph of the list, without its marker
        assertEquals(expected, convert(item("uno") + "<p class=MsoListParagraphCxSpMiddle>$equation<o:p></o:p></p>" + item("due")))
        // Centered in a normal paragraph, between two items
        assertEquals(expected, convert(item("uno") + "<p class=MsoNormal align=center>$equation<o:p></o:p></p>" + item("due")))
        // After the last item it is a paragraph of its own
        assertEquals("- uno\n\n\$\$\\frac{2}{n}\$\$\n\nfine", convert(item("uno") + "<p class=MsoNormal>$equation<o:p></o:p></p><p class=MsoNormal>fine</p>"))
        val markdown = convert(item("uno") + "<p class=MsoListParagraphCxSpMiddle>$equation</p>" + item("due"))
        assertEquals(2, parsed(markdown).count { it.kind == Kind.LIST_ITEM })
        assertEquals(1, parsed(markdown).count { it.kind == Kind.FORMULA && it.display })
    }

    @Test
    fun theStructuresOfAnEquationOfWordAreWritten() {
        fun tex(omml: String) = OmmlToLatex.convert("[if gte msEquation 12]><m:oMath>$omml</m:oMath><![endif]")?.tex
        fun r(text: String) = "<i><span><m:r>$text</m:r></span></i>"
        val plain = """<m:rPr><m:scr m:val="roman"/><m:sty m:val="p"/></m:rPr>"""
        val noProps = "<span><m:ctrlPr></m:ctrlPr></span>"

        assertEquals("\\sqrt{a+b}", tex("""<m:rad><m:radPr><m:degHide m:val="on"/>$noProps</m:radPr><m:deg></m:deg><m:e>${r("a")}<span><m:r>+</m:r></span>${r("b")}</m:e></m:rad>"""))
        assertEquals("\\sqrt[3]{x}", tex("<m:rad><m:radPr>$noProps</m:radPr><m:deg>${r("3")}</m:deg><m:e>${r("x")}</m:e></m:rad>"))
        assertEquals("\\sum_{i=1}^{n}x_{i}", tex("""<m:nary><m:naryPr><m:chr m:val="∑"/>$noProps</m:naryPr><m:sub>${r("i=1")}</m:sub><m:sup>${r("n")}</m:sup><m:e><m:sSub><m:sSubPr>$noProps</m:sSubPr><m:e>${r("x")}</m:e><m:sub>${r("i")}</m:sub></m:sSub></m:e></m:nary>"""))
        assertEquals("\\int_{0}^{\\infty}e^{-x}dx", tex("<m:nary><m:naryPr>$noProps</m:naryPr><m:sub>${r("0")}</m:sub><m:sup>${r("∞")}</m:sup><m:e><m:sSup><m:sSupPr>$noProps</m:sSupPr><m:e>${r("e")}</m:e><m:sup>${r("-x")}</m:sup></m:sSup>${r("dx")}</m:e></m:nary>"))
        val matrix = "<m:m><m:mPr><m:mcs><m:mc><m:mcPr><m:count m:val=\"2\"/></m:mcPr></m:mc></m:mcs>$noProps</m:mPr><m:mr><m:e>${r("a")}</m:e><m:e>${r("b")}</m:e></m:mr><m:mr><m:e>${r("c")}</m:e><m:e>${r("d")}</m:e></m:mr></m:m>"
        assertEquals("\\begin{matrix}a & b \\\\ c & d\\end{matrix}", tex(matrix))
        assertEquals("\\left( \\begin{matrix}a & b \\\\ c & d\\end{matrix} \\right)", tex("<m:d><m:dPr>$noProps</m:dPr><m:e>$matrix</m:e></m:d>"))
        assertEquals("\\left[ a | b \\right)", tex("""<m:d><m:dPr><m:begChr m:val="["/></m:dPr><m:e>${r("a")}</m:e><m:e>${r("b")}</m:e></m:d>"""))
        assertEquals("\\left\\{ a \\right.", tex("""<m:d><m:dPr><m:begChr m:val="{"/><m:endChr m:val=""/></m:dPr><m:e>${r("a")}</m:e></m:d>"""))
        assertEquals("\\hat{x}+\\overline{y}", tex("""<m:acc><m:accPr>$noProps</m:accPr><m:e>${r("x")}</m:e></m:acc>${r("+")}<m:acc><m:accPr><m:chr m:val="&#x305;"/></m:accPr><m:e>${r("y")}</m:e></m:acc>"""))
        assertEquals("\\overline{x}", tex("""<m:bar><m:barPr><m:pos m:val="top"/></m:barPr><m:e>${r("x")}</m:e></m:bar>"""))
        val limit = "<m:limLow><m:e><span><m:r>$plain" + "lim</m:r></span></m:e><m:lim>${r("x→0")}</m:lim></m:limLow>"
        assertEquals("\\lim_{x\\to0}{\\sin{x}}", tex("<m:func><m:fName>$limit</m:fName><m:e><m:func><m:fName><span><m:r>$plain" + "sin</m:r></span></m:fName><m:e>${r("x")}</m:e></m:func></m:e></m:func>"))
        assertEquals("\\mathrm{abc}", tex("<span><m:r>$plain" + "abc</m:r></span>"))
        assertEquals("\\text{per ogni}x", tex("""<m:r><m:rPr><m:nor/></m:rPr>per ogni</m:r>${r("x")}"""))
        assertEquals("\\alpha x\\leq\\sigma\\{\\}", tex(r("αx≤σ{}")))
        assertEquals("ab", tex("<m:r>\uD835\uDC4E\uD835\uDC4F</m:r>"))
        assertEquals("{n \\atop k}", tex("""<m:f><m:fPr><m:type m:val="noBar"/></m:fPr><m:num>${r("n")}</m:num><m:den>${r("k")}</m:den></m:f>"""))
        assertEquals("a", tex("<m:r><w:rPr><w:rFonts/></w:rPr><m:t>a</m:t></m:r>"))
    }

    @Test
    fun anEquationNestedTooDeepLeavesAPlaceholder() {
        val deep = "<m:rad><m:e>".repeat(200) + "<m:r>x</m:r>" + "</m:e></m:rad>".repeat(200)
        assertEquals("**a** [equation]", convert("<p><b>a</b> <!--[if gte msEquation 12]><m:oMath>$deep</m:oMath><![endif]--></p>"))
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
    fun aFormulaOfKatexCopiedByChromeIsWrittenFromItsMathml() {
        // Chrome leaves out the hidden annotation with the source, and keeps the MathML and the drawing
        val katex = { mathml: String, html: String -> """<span class="katex"><span class="katex-mathml"><math xmlns="http://www.w3.org/1998/Math/MathML"><semantics><mrow>$mathml</mrow></semantics></math></span><span class="katex-html" aria-hidden="true">$html</span></span>""" }
        assertEquals("**a** \$a^{\\prime}\$", convert("<b>a</b> " + katex("""<msup><mi>a</mi><mo mathvariant="normal" lspace="0em" rspace="0em">′</mo></msup>""", "a′")))
        assertEquals("\$\\tilde{a}\$ x", convert(katex("""<mover accent="true"><mi>a</mi><mo>~</mo></mover>""", "a~") + " x"))
        assertEquals("\$\\frac{1}{\\sigma_{i}^{2}}\$", convert(katex("<mfrac><mn>1</mn><msubsup><mi>σ</mi><mi>i</mi><mn>2</mn></msubsup></mfrac>", "1σ")))
        assertEquals("\$\\sum_{i=1}^{n}x\$", convert(katex("""<munderover><mo>∑</mo><mrow><mi>i</mi><mo>=</mo><mn>1</mn></mrow><mi>n</mi></munderover><mi>x</mi>""", "x")))
        assertEquals("\$\\lim_{x\\to0}\\sin x\$", convert(katex("""<munder><mo><mi>lim</mi></mo><mrow><mi>x</mi><mo>→</mo><mn>0</mn></mrow></munder><mi>sin</mi><mi>x</mi>""", "x").replace("<mo><mi>lim</mi></mo>", "<mi>lim</mi>")))
        assertEquals("\$\\left( \\begin{matrix}a & b \\\\ c & d\\end{matrix} \\right)\$", convert(katex("""<mrow><mo fence="true">(</mo><mtable><mtr><mtd><mi>a</mi></mtd><mtd><mi>b</mi></mtd></mtr><mtr><mtd><mi>c</mi></mtd><mtd><mi>d</mi></mtd></mtr></mtable><mo fence="true">)</mo></mrow>""", "ab")))
        assertEquals("\$\\sqrt[3]{x}+\\sqrt{y}\$", convert(katex("<mroot><mi>x</mi><mn>3</mn></mroot><mo>+</mo><msqrt><mi>y</mi></msqrt>", "x")))
        assertEquals("\$\\text{se }x\\mathrm{d}\$".replace("se ", "se"), convert(katex("""<mtext>se </mtext><mi>x</mi><mi mathvariant="normal">d</mi>""", "x")))
    }

    @Test
    fun aFormulaOfKatexThatCannotBeWrittenKeepsOnlyItsDrawing() {
        val katex = """<b>a</b> <span class="katex"><span class="katex-mathml"><math><semantics><mrow><mnuovo>x</mnuovo></mrow></semantics></math></span><span class="katex-html" aria-hidden="true">disegno</span></span>"""
        assertEquals("**a** disegno", convert(katex))
    }

    @Test
    fun aFormulaOfWikipediaCopiedByChromeIsReadFromItsAttribute() {
        val page = """<p>Sia <span class="mwe-math-mathml-a11y" style="display:none"><math class="mwe-math-element" data-mw="{&quot;name&quot;:&quot;math&quot;,&quot;attrs&quot;:{&quot;display&quot;:&quot;inline&quot;},&quot;body&quot;:{&quot;extsrc&quot;:&quot;\\mu=0&quot;}}"><mrow><mi>μ</mi></mrow></math></span><mjx-container><mjx-lazy data-mjx-lazy="25"></mjx-lazy></mjx-container> dato</p>"""
        assertEquals("Sia \$\\mu=0\$ dato", convert(page))
        val display = """<p><span class="mwe-math-mathml-a11y" style="display:none"><math data-mw="{&quot;attrs&quot;:{&quot;display&quot;:&quot;block&quot;},&quot;body&quot;:{&quot;extsrc&quot;:&quot;x&quot;}}"></math></span></p>"""
        assertEquals("\$\$x\$\$", convert(display))
    }

    @Test
    fun theSetsOfWikipediaAreWrittenInLatex() {
        val page = """<p>Sia <span class="mwe-math-mathml-a11y"><math data-mw="{&quot;body&quot;:{&quot;extsrc&quot;:&quot;x\\in\\R, \\Rightarrow&quot;}}"></math></span></p>"""
        assertEquals("Sia \$x\\in\\mathbb{R}, \\Rightarrow\$", convert(page))
    }

    @Test
    fun aFormulaKeepsItsSpaceFromTheWordsAroundIt() {
        val math = """<math alttext="x"></math>"""
        assertEquals("a \$x\$ b", convert("a$math" + "b"))
        assertEquals("(\$x\$), \$x\$ 2", convert("($math), $math" + "2"))
    }

    @Test
    fun aFormulaOfMathJax2CopiedByChromeIsReadFromItsAttribute() {
        val mathml = "&lt;math xmlns=&quot;http://www.w3.org/1998/Math/MathML&quot;&gt;&lt;msup&gt;&lt;mn&gt;10&lt;/mn&gt;&lt;mrow&gt;&lt;mo&gt;&amp;#x2212;&lt;/mo&gt;&lt;mi&gt;k&lt;/mi&gt;&lt;/mrow&gt;&lt;/msup&gt;&lt;/math&gt;"
        val page = """<p>Di <span class="math-container"><span class="MathJax_Preview"></span><span class="MathJax" data-mathml="$mathml" role="presentation"><nobr aria-hidden="true"><span class="math"><span class="mn">10</span><span class="mo">−</span><span class="mi">k</span></span></nobr></span></span> per</p>"""
        assertEquals("Di \${10}^{-k}\$ per", convert(page))
        val display = """<div class="MathJax_Display"><span class="MathJax" data-mathml="$mathml"><nobr>10</nobr></span></div>"""
        assertEquals("\$\${10}^{-k}\$\$", convert(display))
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
    fun aFormulaWithoutItsSourceIsWrittenFromItsMathml() {
        assertEquals("**a** \$x^{2}\$", convert("""<b>a</b> <math><msup><mi>x</mi><mn>2</mn></msup></math>"""))
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
