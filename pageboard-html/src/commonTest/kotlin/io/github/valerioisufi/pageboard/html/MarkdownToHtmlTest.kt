package io.github.valerioisufi.pageboard.html

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A text in the notation written as HTML, for the applications it is copied to. */
class MarkdownToHtmlTest {

    @Test
    fun theFormatsAreWritten() {
        assertEquals(
            "<p>A <strong>bold</strong>, <em>italic</em>, <s>gone</s>, <code>x &lt; 1</code> and <a href=\"https://example.org\">link</a></p>",
            MarkdownToHtml.convert("A **bold**, *italic*, ~~gone~~, `x < 1` and [link](https://example.org)")
        )
    }

    @Test
    fun theBlocksAreWritten() {
        val html = MarkdownToHtml.convert("# Title\n\nFirst line\nsecond line\n\n> quoted\n\n```kotlin\nval a = 1\n```")
        assertEquals(
            "<h1>Title</h1><p>First line<br>second line</p><blockquote><p>quoted</p></blockquote><pre><code class=\"language-kotlin\">val a = 1</code></pre>",
            html
        )
    }

    @Test
    fun theListsNestAndTheChecklistsKeepTheirBoxes() {
        val html = MarkdownToHtml.convert("1. one\n   - inner\n2. two\n\n- [x] done\n- [ ] to do")
        assertTrue(html.startsWith("<ol><li>one</li><ul><li>inner</li></ul><li>two</li></ol>"), html)
        assertTrue(html.endsWith("<ul><li>☑ done</li><li>☐ to do</li></ul>"), html)
    }

    @Test
    fun aTableAndAFormulaAreWritten() {
        val html = MarkdownToHtml.convert("| a | b |\n|---|:-:|\n| 1 | \$x^2\$ |")
        assertEquals("<table><tr><th>a</th><th style=\"text-align:center\">b</th></tr><tr><td>1</td><td style=\"text-align:center\">\$x^2\$</td></tr></table>", html)
    }
}
