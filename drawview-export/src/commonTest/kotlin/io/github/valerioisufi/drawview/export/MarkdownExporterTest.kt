package io.github.valerioisufi.drawview.export

import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** A note written as Markdown (see `docs/markdown-export.md`). */
class MarkdownExporterTest {

    private fun text(source: String, x: Float, y: Float) = DrawText().apply { text = source; this.x = x; this.y = y; width = 80f; height = 10f; fontSize = 12f }

    private fun note(): DrawDocument = DrawDocument(title = "Analisi").apply {
        addPage()
        addPage()
        // Read from the top, then from the left: the second column after the first on its line
        pages[0].add(text("Right", x = 110f, y = 21f))
        pages[0].add(text("# Limiti", x = 10f, y = 10f))
        pages[0].add(text("Left", x = 10f, y = 20f))
        // A page with no text is left out
        pages[2].add(text("\$x^2 = 1\$", x = 10f, y = 10f))
    }

    @Test
    fun theTextsOfThePagesInTheOrderTheyAreReadARuleBetweenTwoPages() = runTest {
        val content = DocumentContent.of(note())
        val markdown = MarkdownExporter().write(content, "id")
        assertEquals(
            "# Analisi\n\n# Limiti\n\nLeft\n\nRight\n\n---\n\n\$x^2 = 1\$\n",
            markdown
        )
    }

    @Test
    fun whatTheApplicationAddsComesAfterTheTexts() = runTest {
        val appendix = MarkdownAppendix { id -> if (id == "id") "## Lectures\n\nA summary" else "" }
        val markdown = MarkdownExporter(appendix).write(DocumentContent.of(note()), "id")
        assertTrue(markdown.endsWith("= 1\$\n\n## Lectures\n\nA summary\n"), markdown)
    }

    @Test
    fun aNoteWithoutTextHasNothingToWriteUnlessTheApplicationAddsSomething() = runTest {
        val empty = DocumentContent.of(DrawDocument(title = "Vuota"))
        assertFalse(MarkdownExporter().hasText(empty, "id"))
        assertTrue(MarkdownExporter { "## Lectures" }.hasText(empty, "id"))
        assertEquals("# Vuota\n\n## Lectures\n", MarkdownExporter { "## Lectures" }.write(empty, "id"))
    }

    @Test
    fun markdownIsOfferedForOneNoteOnly() {
        assertTrue(ExportFormat.MARKDOWN in ExportScope.Pages("id").formats)
        assertFalse(ExportFormat.MARKDOWN in ExportScope.Folder(null).formats)
        assertEquals("md", ExportFormat.MARKDOWN.extension(3))
        assertEquals("text/markdown", ExportFormat.MARKDOWN.mimeType(3))
    }
}
