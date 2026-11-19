package io.github.valerioisufi.pageboard.agent

import io.github.valerioisufi.pageboard.agent.AgentHarness.Companion.item
import io.github.valerioisufi.pageboard.model.DocumentResource
import io.github.valerioisufi.pageboard.model.PageContentSnapshot
import io.github.valerioisufi.pageboard.model.PdfTextLayout
import io.github.valerioisufi.pageboard.model.TextMarkupKind
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.AddResource
import io.github.valerioisufi.pageboard.model.change.CompositeChange
import io.github.valerioisufi.pageboard.model.change.PdfData
import io.github.valerioisufi.pageboard.model.geometry.Rect
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The marks of the text of a PDF page made, changed and removed by a model (`docs/plans/agent-coverage.md`, phase 4). */
class CoverageMarkupsTest {

    /** A PDF page of two lines, 6 points a character, the spaces and the line break without a box. */
    private class FakePages(lines: List<String>) : PageEngine {
        val layout: PdfTextLayout

        init {
            val text = lines.joinToString("\n")
            val boxes = FloatArray(text.length * 4)
            var line = 0
            var column = 0
            text.forEachIndexed { index, char ->
                if (char == '\n') {
                    line++
                    column = 0
                    return@forEachIndexed
                }
                if (char != ' ') {
                    val left = 72f + column * 6f
                    val top = 100f + line * 20f
                    boxes[index * 4] = left; boxes[index * 4 + 1] = top; boxes[index * 4 + 2] = left + 6f; boxes[index * 4 + 3] = top + 12f
                }
                column++
            }
            layout = PdfTextLayout(595f, 842f, IntArray(text.length) { text[it].code }, boxes)
        }

        override fun textHeight(markdown: String, widthMm: Float, fontSize: Float): Float? = null
        override fun pdfLayout(path: String, pageIndex: Int): PdfTextLayout? = layout.takeIf { path == "/slides.pdf" }
        override suspend fun renderPage(resources: List<DocumentResource>, page: PageContentSnapshot, maxSidePx: Int, area: Rect?, marks: List<PageMark>): ByteArray? = null
    }

    private val pages = FakePages(listOf("The law of Gauss holds", "for every surface of Gauss"))

    private suspend fun harness(test: kotlinx.coroutines.test.TestScope, scope: kotlinx.coroutines.CoroutineScope): AgentHarness {
        val harness = AgentHarness.of(test, scope, AgentEngines(pages = { pages }))
        harness.open.edit { session ->
            session.submit(
                CompositeChange(
                    listOf(
                        AddResource.of("slides", DocumentResource.ResourceType.PDF, "/slides.pdf", ""),
                        AddElements(session.document.pages[0].id, listOf(PdfData("pdf", "slides", 0)))
                    )
                )
            )
        }
        return harness
    }

    private fun AgentHarness.marks() = page.markups.filter { it.removed == null }

    @Test
    fun aPassageIsMarkedAsTheEditorMarksIt() = runTest {
        val harness = harness(this, backgroundScope)
        val answer = harness.propose(item("add_markup") { put("page", harness.page.id); put("quote", "Gauss holds  for every"); put("markup_kind", "highlight") })
        assertEquals(listOf("accepted"), harness.outcomes(answer))
        val mark = harness.marks().single()
        assertEquals(TextMarkupKind.HIGHLIGHT, mark.kind)
        assertEquals(MarkupItems.HIGHLIGHT_COLOR, mark.color)
        assertEquals("Gauss holds\nfor every", mark.text)
        assertEquals(11, mark.startIndex)
        // One box a line
        assertEquals(2, mark.rects.size)
        assertEquals(pages.layout.lineRects(mark.startIndex, mark.endIndex), mark.rects)
        val read = harness.readPage().getValue("markups").jsonArray.single().jsonObject
        assertEquals("highlight", read.getValue("kind").jsonPrimitive.content)

        assertEquals(UndoOutcome.UNDONE, harness.undoLast())
        assertTrue(harness.marks().isEmpty())
    }

    @Test
    fun theOccurrenceAskedIsMarked() = runTest {
        val harness = harness(this, backgroundScope)
        harness.propose(item("add_markup") { put("page", harness.page.id); put("quote", "Gauss"); put("markup_kind", "underline"); put("occurrence", 2); put("color", "#C0392B") })
        val mark = harness.marks().single()
        assertEquals(TextMarkupKind.UNDERLINE, mark.kind)
        assertEquals(0xFFC0392B.toInt(), mark.color)
        // "for every surface of Gauss" begins at 23
        assertEquals(23 + 21, mark.startIndex)
        val third = runCatching { harness.propose(item("add_markup") { put("page", harness.page.id); put("quote", "Gauss"); put("markup_kind", "underline"); put("occurrence", 3) }) }
        assertEquals(AgentErrorCode.NOT_FOUND, (third.exceptionOrNull() as AgentToolException).code)
    }

    @Test
    fun aPassageNotInTheTextOrAPageWithoutPdfIsRefused() = runTest {
        val harness = harness(this, backgroundScope)
        assertEquals(AgentErrorCode.NOT_FOUND, harness.refused(item("add_markup") { put("page", harness.page.id); put("quote", "Faraday"); put("markup_kind", "highlight") }).code)
        assertEquals(AgentErrorCode.REFUSED, harness.refused(item("add_markup") { put("page", harness.page.id); put("quote", "Gauss"); put("markup_kind", "circle") }).code)

        val plain = AgentHarness.of(this, backgroundScope, AgentEngines(pages = { pages }))
        assertEquals(AgentErrorCode.REFUSED, plain.refused(item("add_markup") { put("page", plain.page.id); put("quote", "Gauss"); put("markup_kind", "highlight") }).code)
    }

    @Test
    fun marksAreRestyledAndRemoved() = runTest {
        val harness = harness(this, backgroundScope)
        harness.propose(
            item("add_markup") { put("page", harness.page.id); put("quote", "law"); put("markup_kind", "highlight") },
            item("add_markup") { put("page", harness.page.id); put("quote", "surface"); put("markup_kind", "highlight") }
        )
        val (first, second) = harness.marks()
        val other = harness.converging {
            harness.propose(item("restyle_markups") { put("page", harness.page.id); put("markups", harness.refs(first.id, second.id)); put("markup_kind", "strikeout"); put("color", "#000000") })
        }
        assertTrue(harness.marks().all { it.kind == TextMarkupKind.STRIKEOUT && it.color == 0xFF000000.toInt() })
        assertTrue(other.document.pages[0].markups.all { it.kind == TextMarkupKind.STRIKEOUT })

        harness.propose(item("remove_elements") { put("page", harness.page.id); put("elements", harness.refs(first.id)) })
        assertEquals(listOf(second.id), harness.marks().map { it.id })
        assertEquals(first.id, harness.page.removedElements.single().id)

        // A mark is not placed, and restyle_markups takes marks only
        assertEquals(AgentErrorCode.REFUSED, harness.refused(item("place_elements") { put("page", harness.page.id); put("elements", harness.refs(second.id)); put("dx", 5f) }).code)
        assertEquals(AgentErrorCode.REFUSED, harness.refused(item("restyle_markups") { put("page", harness.page.id); put("markups", harness.refs("pdf")); put("color", "#000000") }).code)
    }

    @Test
    fun aMarkChangedSinceItWasReadIsStale() = runTest {
        val harness = harness(this, backgroundScope)
        harness.propose(item("add_markup") { put("page", harness.page.id); put("quote", "law"); put("markup_kind", "highlight") })
        val mark = harness.marks().single()
        val refs = harness.refs(mark.id)
        harness.propose(item("restyle_markups") { put("page", harness.page.id); put("markups", harness.refs(mark.id)); put("color", "#00FF00") })
        val answer = harness.propose(item("restyle_markups") { put("page", harness.page.id); put("markups", refs); put("color", "#0000FF") })
        assertEquals(listOf("stale"), harness.outcomes(answer))
        assertEquals(0xFF00FF00.toInt(), mark.color)
        assertNull(harness.page.markups.firstOrNull { it.removed != null })
        assertEquals(1, (harness.readPage().getValue("markups") as JsonArray).size)
    }
}
