package io.github.valerioisufi.pageboard.html

import com.fleeksoft.ksoup.Ksoup

/**
 * HTML turned into the Markdown of the texts of a notebook (see `RichTextParser`): what a browser, a
 * word processor or a chat assistant copies. See `docs/plans/html-paste.md`.
 *
 * The HTML comes from another application, so it is untrusted: nothing it names is fetched, and its
 * size and depth are bounded.
 *
 * @param equationPlaceholder what stands for an equation whose source cannot be read (one of Word),
 *   in the language of the application.
 */
public class HtmlToMarkdown(private val equationPlaceholder: String = "[equation]") {

    /**
     * [html] in the notation, or `null` when plain text should be taken instead: when it is too
     * large or cannot be read, when words would be lost, or when it holds no formatting at all, so
     * that nothing is gained and a Markdown copied as text stays Markdown.
     */
    public fun convert(html: String): String? {
        if (html.isBlank() || html.length > MAX_HTML_CHARS) return null
        return try {
            val body = Ksoup.parse(html).body()
            WordHtml(equationPlaceholder).normalize(body)
            val writer = MarkdownWriter()
            val markdown = writer.write(body)
            markdown.takeIf { writer.formatted && it.isNotBlank() && MarkdownWriter.keepsTextOf(body, it) }
        } catch (_: Exception) {
            // A parser failing on what another application copied must never fail a paste
            null
        }
    }

    /**
     * [html], a page of HTML imported as a file, in the notation, or its plain text when it holds no
     * formatting; `null` when it cannot be read or holds no text.
     */
    public fun convertPage(html: String): String? {
        convert(html)?.let { return it }
        if (html.length > MAX_HTML_CHARS) return null
        return try {
            Ksoup.parse(html).body().wholeText().trim().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        /** Beyond this size, the plain text. */
        const val MAX_HTML_CHARS = 2_000_000
    }
}
