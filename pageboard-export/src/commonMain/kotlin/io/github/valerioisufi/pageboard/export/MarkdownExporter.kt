package io.github.valerioisufi.pageboard.export

import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.PageContentSnapshot

/**
 * What an application adds to the Markdown of a note (see [MarkdownExporter]), e.g. the notes and
 * the summaries of its lectures: Markdown written after the texts of the note, empty for nothing.
 */
public fun interface MarkdownAppendix {
    /** What is added to the Markdown of the note [documentId]. */
    public suspend fun of(documentId: String): String
}

/**
 * Writes a note as Markdown (see `docs/plans/markdown-export.md`): its name as the title, then the texts
 * of its pages as they are written (Markdown with LaTeX formulas between `$`), each page after the
 * one before, a rule between two pages, the texts of a page in the order they are read (from the
 * top, then from the left); the pages without a text left out; then what [appendix] adds. The
 * strokes, the images and the PDF pages are not text: they are not written.
 */
public class MarkdownExporter(private val appendix: MarkdownAppendix? = null) {

    /** [content], the pages of the note [documentId], as Markdown. */
    public suspend fun write(content: DocumentContent, documentId: String): String {
        val pages = content.pages.map { textOf(it) }.filter { it.isNotBlank() }
        val added = appendix?.of(documentId)?.trim().orEmpty()
        return buildString {
            append("# ").append(content.title.ifBlank { "Untitled" }.lines().first()).append("\n\n")
            append(pages.joinToString("\n\n---\n\n"))
            if (added.isNotEmpty()) {
                if (pages.isNotEmpty()) append("\n\n")
                append(added)
            }
        }.trimEnd() + "\n"
    }

    /** Whether there is anything to write of [content]: a text, or what the appendix adds. */
    public suspend fun hasText(content: DocumentContent, documentId: String): Boolean =
        content.pages.any { page -> page.texts.any { it.text.isNotBlank() } } || appendix?.of(documentId)?.isNotBlank() == true

    internal companion object {
        /** A text that is no more than a little below another is on its line, in millimeters. */
        private const val SAME_LINE_MM = 2f

        /** The texts of [page] in the order they are read, a blank line between two. */
        internal fun textOf(page: PageContentSnapshot): String = readingOrder(page.texts)
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")

        /** [texts] from the top, those at the same height from the left. */
        internal fun readingOrder(texts: List<TextElement>): List<TextElement> {
            val byTop = texts.sortedBy { it.y }
            val lines = ArrayList<MutableList<TextElement>>()
            for (text in byTop) {
                val line = lines.lastOrNull()
                if (line != null && text.y - line.first().y <= SAME_LINE_MM) line += text else lines += mutableListOf(text)
            }
            return lines.flatMap { line -> line.sortedBy { it.x } }
        }
    }
}
