package io.github.valerioisufi.drawview.html

import com.fleeksoft.ksoup.Ksoup

/**
 * HTML turned into the Markdown of the texts of a notebook (see `RichTextParser`): what a browser, a
 * word processor or a chat assistant copies. See `docs/html-paste.md`.
 *
 * The HTML comes from another application, so it is untrusted: nothing it names is fetched, and its
 * size and depth are bounded.
 */
public class HtmlToMarkdown {

    /**
     * [html] in the notation, or `null` when plain text should be taken instead: when it is too
     * large or cannot be read, or when it holds no formatting at all, so that nothing is gained and
     * a Markdown copied as text stays Markdown.
     */
    public fun convert(html: String): String? {
        if (html.isBlank() || html.length > MAX_HTML_CHARS) return null
        return try {
            val writer = MarkdownWriter()
            val markdown = writer.write(Ksoup.parse(html).body())
            markdown.takeIf { writer.formatted && it.isNotBlank() }
        } catch (_: Exception) {
            // A parser failing on what another application copied must never fail a paste
            null
        }
    }

    private companion object {
        /** Beyond this size, the plain text. */
        const val MAX_HTML_CHARS = 2_000_000
    }
}
