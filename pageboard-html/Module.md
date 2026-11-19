# Module pageboard-html

HTML turned into the Markdown of the texts of a notebook (`HtmlToMarkdown`): what a browser, a word
processor or a chat assistant puts in the clipboard. It reads the HTML with Ksoup and fetches nothing
it names; the size and the depth of what it reads are bounded.

Headless: no Compose. The clipboard itself is read by `pageboard-ui` (see `docs/plans/html-paste.md`).
