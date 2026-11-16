package io.github.valerioisufi.drawview.canvas.controller

/**
 * A text brought into the notebook (a file chosen or dropped, a long text pasted) waiting for the
 * student to say where it goes ([DrawViewController.pendingTextImport]; see `docs/plans/text-import.md`).
 *
 * @param text its content as it came.
 * @param pageIndex the page it was brought on (the page shown, the page pasted on), if any.
 * @param isMarkdown it is already the Markdown of the notebook (a `.md` file, a text that looks
 *   like one): not cleaned by default.
 * @param name the name of its file, if any.
 */
public class TextImport(
    public val text: String,
    public val pageIndex: Int?,
    public val isMarkdown: Boolean,
    public val name: String = ""
)

/** Where a text imported goes (see [TextPlacer.startImport]). */
public enum class TextImportPlace {
    /** Each section next to the pages it is about ([PageTopics]). */
    TOPIC,

    /** The free space of its page, then new pages after it. */
    PAGE,

    /** New pages at the end of the notebook. */
    END
}
