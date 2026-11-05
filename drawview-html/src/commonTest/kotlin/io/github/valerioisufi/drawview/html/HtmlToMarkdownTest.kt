package io.github.valerioisufi.drawview.html

import io.github.valerioisufi.drawview.model.text.RichTextParser
import io.github.valerioisufi.drawview.model.text.RichTextParser.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** HTML turned into the notation, the results read back as `RichTextParser` reads them. */
class HtmlToMarkdownTest {

    private fun convert(html: String): String? = HtmlToMarkdown().convert(html)

    private fun kinds(markdown: String?): Set<Kind> = parsed(markdown).map { it.kind }.toSet()

    @Test
    fun aTextWithoutFormattingIsLeftToThePlainText() {
        assertNull(convert("<p>Solo testo</p><p>su due paragrafi</p>"))
        assertNull(convert("<span>**non convertito**</span>"))
        assertNull(convert("   "))
    }

    @Test
    fun theInlineFormatsAreWrittenAroundTheirText() {
        assertEquals("a **b** c", convert("a <b>b</b> c"))
        assertEquals("a *b* c", convert("a <em>b</em> c"))
        assertEquals("a ~~b~~ c", convert("a <del>b</del> c"))
        assertEquals("a `b` c", convert("a <code>b</code> c"))
        assertEquals("**_both_**", convert("<b><i>both</i></b>"))
        assertEquals("[sito](https://example.com/a)", convert("""<a href="https://example.com/a">sito</a>"""))
        assertEquals("[**sito**](https://example.com)", convert("""<a href="https://example.com"><b>sito</b></a>"""))
    }

    @Test
    fun theSpacesStayOutOfTheDelimiters() {
        assertEquals("a **b** c", convert("a<b> b </b>c"))
        assertEquals("**a b**", convert("<b>a</b> <b>b</b>"))
        assertEquals("**a** *b*", convert("<b>a</b> <i>b</i>"))
        assertEquals("**a b**", convert("<b>a </b><b>b</b>"))
        assertEquals("**a**", convert("<b>\n  a\n</b>"))
    }

    @Test
    fun theStylesCountAsTheTagsAndWinOverThem() {
        assertEquals("**a**", convert("""<span style="font-weight:700">a</span>"""))
        assertEquals("**a**", convert("""<span style="FONT-WEIGHT: bold !important">a</span>"""))
        assertEquals("*a*", convert("""<span style="font-style:italic">a</span>"""))
        assertEquals("~~a~~", convert("""<span style="text-decoration:line-through">a</span>"""))
        assertEquals("`a`", convert("""<span style="font-family:'Courier New',monospace">a</span>"""))
        assertNull(convert("""<strong style="font-weight:400">a</strong>"""))
        // The other names of Word are not the same properties
        assertEquals("**a**", convert("""<b style='mso-bidi-font-weight:normal'>a</b>"""))
    }

    @Test
    fun theOuterBoldOfGoogleDocsIsNotBold() {
        val docs = """<b style="font-weight:normal" id="docs-internal-guid-1"><p dir="ltr"><span style="font-weight:400">uno </span><span style="font-weight:700">due</span></p></b>"""
        assertEquals("uno **due**", convert(docs))
    }

    @Test
    fun theHeadingsKeepTheirLevelsAndLoseTheirBold() {
        assertEquals("# Uno\n\n###### Sei", convert("<h1>Uno</h1><h6>Sei</h6>"))
        assertEquals("## Titolo *corsivo*", convert("<h2><b>Titolo</b> <i>corsivo</i></h2>"))
        assertTrue(Kind.HEADING in kinds(convert("<h3>Uno</h3>")))
    }

    @Test
    fun theParagraphsAreSeparatedAndABreakIsALine() {
        assertEquals("**a**\n\nb", convert("<p><b>a</b></p><p>b</p>"))
        assertEquals("**a**\nb", convert("<b>a</b><br>b"))
        assertEquals("**a**\n\nb", convert("<div><b>a</b></div>\n\n<div>b</div>"))
        assertEquals("**a**\n\nb", convert("<b>a</b><br><br><br><br>b"))
    }

    @Test
    fun theListsNestAndKeepTheirNumbers() {
        assertEquals("- a\n- b\n  - c\n    - d\n- e", convert("<ul><li>a</li><li>b<ul><li>c<ul><li>d</li></ul></li></ul></li><li>e</li></ul>"))
        assertEquals("3. a\n4. b", convert("""<ol start="3"><li>a</li><li>b</li></ol>"""))
        assertEquals("1. a\n5. b\n6. c", convert("""<ol><li>a</li><li value="5">b</li><li>c</li></ol>"""))
        assertEquals("a.  x\nb.  y", convert("""<ol type="a"><li>x</li><li>y</li></ol>"""))
        assertEquals("i.  x\nii.  y", convert("""<ol style="list-style-type:lower-roman"><li>x</li><li>y</li></ol>"""))
        assertEquals("- a\n  - b", convert("<ul><li>a</li><ul><li>b</li></ul></ul>"))
        assertEquals("- a\n- b", convert("<li>a</li> <li>b</li>"))
        assertEquals("- **a**", convert("<ul><li><p><b>a</b></p></li></ul>"))
        assertEquals(3, parsed(convert("<ol><li>a<ul><li>b</li></ul></li><li>c</li></ol>")).count { it.kind == Kind.LIST_ITEM })
    }

    @Test
    fun aSecondParagraphOfAnItemStaysInTheItem() {
        val markdown = convert("<ul><li><p>a</p><p>b</p></li><li>c</li></ul>")
        assertEquals("- a\n  b\n- c", markdown)
        assertEquals(2, parsed(markdown).count { it.kind == Kind.LIST_ITEM })
    }

    @Test
    fun theQuotesAreMarkedLineByLine() {
        assertEquals("> a\n>\n> b", convert("<blockquote><p>a</p><p>b</p></blockquote>"))
        assertEquals("> - a", convert("<blockquote><ul><li>a</li></ul></blockquote>"))
        assertEquals("> a\n>\n> b", convert("<blockquote>a<blockquote>b</blockquote></blockquote>"))
        assertTrue(Kind.QUOTE in kinds(convert("<blockquote>a</blockquote>")))
    }

    @Test
    fun theBlocksOfCodeKeepTheirLinesAndLanguage() {
        assertEquals("```kotlin\nval a = 1\n\n  val b = *2\n```", convert("<pre><code class=\"language-kotlin\">val a = 1\n\n  val b = *2\n</code></pre>"))
        assertEquals("```python\nx\n```", convert("""<div class="highlight highlight-source-python"><pre>x</pre></div>"""))
        assertEquals("````\na ``` b\n````", convert("<pre>a ``` b</pre>"))
        // The bar of a chat around the code
        assertEquals("```js\nx()\n```", convert("""<pre><div>js<button>Copy</button></div><code class="language-js">x()</code></pre>"""))
        // As an editor copies it
        assertEquals("```\nuno\n\ndue\n```", convert("""<div style="font-family:Consolas,monospace;white-space:pre;"><div><span>uno</span></div><br><div><span>due</span></div></div>"""))
        assertTrue(Kind.CODE_BLOCK in kinds(convert("<pre>x</pre>")))
        val inItem = convert("<ul><li>a<pre>x</pre></li></ul>")
        assertEquals("- a\n  ```\n  x\n  ```", inItem)
        assertTrue(Kind.CODE_BLOCK in kinds(inItem))
    }

    @Test
    fun theTablesHaveAHeaderAndTheirCellsStayOnALine() {
        val html = """<table><thead><tr><th>A</th><th style="text-align:right">B</th></tr></thead>
            <tbody><tr><td><p>uno</p><p>due</p></td><td>x|y</td></tr><tr><td colspan="2"><b>tre</b></td></tr></tbody></table>"""
        val markdown = convert(html)
        assertEquals("| A | B |\n| --- | ---: |\n| uno due | x\\|y |\n| **tre** |  |", markdown)
        assertTrue(Kind.TABLE in kinds(markdown))
    }

    @Test
    fun aTableOfLayoutGivesItsContent() {
        assertEquals("**a**\n\nb", convert("<table><tr><td><b>a</b></td></tr><tr><td>b</td></tr></table>"))
        assertEquals("**a**\n\nb", convert("<table><tr><td><b>a</b></td><td>b</td></tr></table>"))
    }

    @Test
    fun whatIsNotTextIsNotRead() {
        assertEquals("**a**", convert("<script>x</script><style>y</style><b>a</b><svg><text>z</text></svg><button>b</button>"))
        assertEquals("**a**", convert("""<b>a</b><span style="display:none">h</span><span hidden>i</span>"""))
        assertEquals("**a** alt", convert("""<b>a</b> <img src="https://example.com/x.png" alt="alt">"""))
        assertEquals("**a**", convert("""<b>a</b><img src="https://example.com/x.png">"""))
    }

    @Test
    fun onlyTheSafeLinksAreKept() {
        assertEquals("**a** b", convert("""<b>a</b> <a href="javascript:alert(1)">b</a>"""))
        assertEquals("**a** b", convert("""<b>a</b> <a href="/relativo">b</a>"""))
        assertEquals("[b](mailto:a@example.com)", convert("""<a href="mailto:a@example.com">b</a>"""))
        assertEquals("[b](https://e.com/a%20b%28c%29)", convert("""<a href="https://e.com/a b(c)">b</a>"""))
        assertTrue(Kind.LINK in kinds(convert("""<a href="https://e.com/a_(b)">b</a>""")))
    }

    @Test
    fun theCharactersOfTheNotationStayCharacters() {
        assertEquals("**a** 2 \\* 3 \\* 4 \\$5 \\`x\\`", convert("<b>a</b> 2 * 3 * 4 \$5 `x`"))
        assertEquals("**a** file_name \\_x\\_ [1] a\\](b)", convert("<b>a</b> file_name _x_ [1] a](b)"))
        assertEquals("**a** ~1 \\~\\~b\\~\\~ C:\\Users", convert("""<b>a</b> ~1 ~~b~~ C:\Users"""))
        assertEquals(
            "**a**\n\n\\# no\n\n\\- no\n\n\\+ no\n\n\\> no\n\n1\\. no\n\n#nope",
            convert("<b>a</b><p># no</p><p>- no</p><p>+ no</p><p>&gt; no</p><p>1. no</p><p>#nope</p>")
        )
        assertEquals(setOf(Kind.BOLD, Kind.ESCAPE), kinds(convert("<b>a</b><p># no</p><p>- no</p><p>&gt; no</p><p>1. no</p>")))
    }

    @Test
    fun aCodeWithABacktickIsWrittenAsText() {
        assertEquals("**a** b\\`c", convert("<b>a</b> <code>b`c</code>"))
    }

    @Test
    fun adjacentFormatsReadBack() {
        for (html in listOf("<b><i>a</i></b>", "<b>a</b><i>b</i>", "<i>a</i><b>b</b>", "<b>a<i>b</i></b>", "<b><i>a</i>b</b>", "<b>a<i>b</i>c</b>", "<b>a <i>b</i></b>", "<b>a <i>b</i> c</b>", "<i>a <b>b</b> c</i>")) {
            val kinds = kinds(convert(html))
            assertTrue(Kind.BOLD in kinds && Kind.ITALIC in kinds, "$html -> ${convert(html)} -> $kinds")
        }
    }

    @Test
    fun aBrokenHtmlIsReadAsABrowserReadsIt() {
        assertEquals("**a**\n\n**b**", convert("<p><b>a<p>b"))
        assertEquals("- a\n- b", convert("<ul><li>a<li>b"))
    }
}

/** The spans of [markdown] as `RichTextParser` reads them, those nested included. */
internal fun parsed(markdown: String?): List<RichTextParser.Span> = RichTextParser.parse(markdown.orEmpty())
