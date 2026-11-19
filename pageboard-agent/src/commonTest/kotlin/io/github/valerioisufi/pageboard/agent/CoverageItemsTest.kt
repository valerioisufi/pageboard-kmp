package io.github.valerioisufi.pageboard.agent

import io.github.valerioisufi.pageboard.agent.AgentHarness.Companion.item
import io.github.valerioisufi.pageboard.model.ImageElement
import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.FractionalIndex
import io.github.valerioisufi.pageboard.model.PageBackground
import io.github.valerioisufi.pageboard.model.TextAlignment
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.AddPage
import io.github.valerioisufi.pageboard.model.change.RemovePage
import io.github.valerioisufi.pageboard.model.change.SetPageFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The small items of `docs/plans/agent-coverage.md` (phase 2): rotation, another page, auto width, alignment, defaults, pages put back. */
class CoverageItemsTest {

    private suspend fun AgentHarness.addImage(x: Float = 30f, y: Float = 200f): ImageElement = open.edit { session ->
        val image = ImageElement().apply { resourceId = "picture"; this.x = x; this.y = y; width = 50f; height = 40f }
        session.submit(AddElements.of(session.document.pages[0], listOf(image)))
        image
    }

    private suspend fun AgentHarness.addText(content: String = "Gauss"): TextElement = open.edit { session ->
        val text = TextElement().apply { text = content; x = 10f; y = 20f; width = 100f; height = 10f; fontSize = 16f; alignment = TextAlignment.CENTER }
        session.submit(AddElements.of(session.document.pages[0], listOf(text)))
        text
    }

    private suspend fun AgentHarness.addPage(widthMm: Float = 210f): Page = open.edit { session ->
        val page = Page(widthMm = widthMm, heightMm = 297f, orderKey = FractionalIndex.between(session.document.pages.last().orderKey, null))
        session.submit(AddPage.of(page))
        session.document.pages.last()
    }

    @Test
    fun elementsAreTurnedAroundTheCenterOfWhatTheyHold() = runTest {
        val harness = AgentHarness.of(this, backgroundScope)
        val image = harness.addImage(x = 30f, y = 200f)
        val answer = harness.propose(item("place_elements") { put("page", harness.page.id); put("elements", harness.refs(image.id)); put("rotation", 90f) })
        assertEquals(listOf("accepted"), harness.outcomes(answer))
        assertEquals(90f, image.rotation, 0.01f)
        assertEquals(55f, image.centerX, 0.01f)
        assertEquals(220f, image.centerY, 0.01f)
        assertEquals(UndoOutcome.UNDONE, harness.undoLast())
        assertEquals(0f, image.rotation)
    }

    @Test
    fun elementsGoOntoAnotherPageAtTheSamePlace() = runTest {
        val harness = AgentHarness.of(this, backgroundScope)
        val image = harness.addImage()
        val second = harness.addPage()
        val other = harness.converging {
            harness.propose(item("place_elements") { put("page", harness.page.id); put("elements", harness.refs(image.id)); put("dx", 5f); put("to_page", second.id) })
        }
        assertTrue(harness.page.images.none { it.removed == null })
        val moved = second.images.single()
        assertEquals(image.id, moved.id)
        assertEquals(35f, moved.x, 0.01f)
        assertEquals(image.id, other.document.pages[1].images.single().id)
        assertTrue(other.document.pages[0].images.none { it.removed == null })

        assertEquals(UndoOutcome.UNDONE, harness.undoLast())
        assertEquals(image.id, harness.page.images.single().id)
        assertTrue(second.images.isEmpty())
    }

    @Test
    fun elementsDoNotGoOutsideTheOtherPage() = runTest {
        val harness = AgentHarness.of(this, backgroundScope)
        val image = harness.addImage(x = 150f, y = 20f)
        val small = harness.addPage(widthMm = 100f)
        val refused = harness.refused(item("place_elements") { put("page", harness.page.id); put("elements", harness.refs(image.id)); put("to_page", small.id) })
        assertEquals(AgentErrorCode.REFUSED, refused.code)
        val missing = harness.refused(item("place_elements") { put("page", harness.page.id); put("elements", harness.refs(image.id)); put("to_page", "elsewhere") })
        assertEquals(AgentErrorCode.NOT_FOUND, missing.code)
        val turned = harness.refused(item("place_elements") { put("page", harness.page.id); put("elements", harness.refs(image.id)); put("rotation", 1000f) })
        assertEquals(AgentErrorCode.REFUSED, turned.code)
    }

    @Test
    fun aTextIsTurnedAndGivenItsWidthBack() = runTest {
        val harness = AgentHarness.of(this, backgroundScope)
        val text = harness.addText()
        val base = harness.bases().getValue(text.id)
        harness.propose(item("update_text") { put("text", text.id); put("base", base); put("rotation", -30f); put("auto_width", true) })
        assertEquals(-30f, text.rotation, 0.01f)
        assertTrue(text.autoWidth)

        val both = harness.refused(item("update_text") { put("text", text.id); put("base", harness.bases().getValue(text.id)); put("width", 50f); put("auto_width", true) })
        assertEquals(AgentErrorCode.REFUSED, both.code)
    }

    @Test
    fun anInsertedTextTakesItsAlignment() = runTest {
        val harness = AgentHarness.of(this, backgroundScope)
        val styled = harness.addText()
        harness.propose(item("insert_text") { put("markdown", "Centered"); put("page", harness.page.id); put("alignment", "end") })
        assertEquals(TextAlignment.END, harness.page.texts.single { it.text == "Centered" }.alignment)
        harness.propose(item("insert_text") { put("markdown", "Like it"); put("style_from", styled.id) })
        assertEquals(TextAlignment.CENTER, harness.page.texts.single { it.text == "Like it" }.alignment)
        val wrong = harness.refused(item("insert_text") { put("markdown", "Wrong"); put("alignment", "justify") })
        assertEquals(AgentErrorCode.REFUSED, wrong.code)
    }

    @Test
    fun theFormatOfNewPagesIsChangedAndUndone() = runTest {
        val harness = AgentHarness.of(this, backgroundScope)
        val custom = harness.addPage()
        harness.open.edit { session -> session.submit(SetPageFormat(custom.id, 210f, 297f, PageBackground.Dotted())) }
        val other = harness.converging {
            harness.propose(item("set_document_defaults") { put("width_mm", 148f); put("height_mm", 210f); put("background", "ruled"); put("spacing_mm", 7f) })
        }
        val document = harness.open.document
        assertEquals(148f, document.defaultWidthMm)
        val ruled = document.defaultBackground as PageBackground.Ruled
        assertEquals(7f, ruled.spacingMm)
        // The pages on the background of the document follow it; one of its own keeps it
        assertTrue(harness.page.background is PageBackground.Ruled)
        assertTrue(custom.background is PageBackground.Dotted)
        assertEquals(148f, other.document.defaultWidthMm)
        val read = harness.call("read_document", kotlinx.serialization.json.buildJsonObject { put("document", harness.documentId) })
        assertEquals(JsonPrimitive("ruled"), (read.getValue("new_page_format") as kotlinx.serialization.json.JsonObject).getValue("background"))

        assertEquals(UndoOutcome.UNDONE, harness.undoLast())
        assertEquals(210f, document.defaultWidthMm)
        assertTrue(harness.page.background is PageBackground.Solid)

        val ofDocument = harness.refused(item("set_document_defaults") { put("background", "document") })
        assertEquals(AgentErrorCode.REFUSED, ofDocument.code)
        val nothing = harness.refused(item("set_document_defaults") {})
        assertEquals(AgentErrorCode.REFUSED, nothing.code)
    }

    @Test
    fun removedPagesArePutBackWhereTheyWere() = runTest {
        val harness = AgentHarness.of(this, backgroundScope)
        val second = harness.addPage()
        val third = harness.addPage()
        harness.open.edit { session -> session.submit(RemovePage(second.id)) }
        assertEquals(2, harness.open.document.pages.size)

        val other = harness.converging {
            val answer = harness.propose(item("restore_pages") { putJsonArray("pages") { add(JsonPrimitive(second.id)) } })
            assertEquals(listOf("accepted"), harness.outcomes(answer))
        }
        assertEquals(listOf(harness.page.id, second.id, third.id), harness.open.document.pages.map { it.id })
        assertEquals(listOf(harness.page.id, second.id, third.id), other.document.pages.map { it.id })

        val shown = harness.refused(item("restore_pages") { putJsonArray("pages") { add(JsonPrimitive(second.id)) } })
        assertEquals(AgentErrorCode.NOT_FOUND, shown.code)
        assertEquals(UndoOutcome.UNDONE, harness.undoLast())
        assertNull(harness.open.document.pageById(second.id))
        assertNotNull(harness.open.document.removedPages[second.id])
    }

    @Test
    fun aDocumentIsCreatedOnTheBackgroundAsked() = runTest {
        val harness = AgentHarness.of(this, backgroundScope)
        val answer = harness.proposeLibrary(item("create_document") { put("title", "Squared"); put("background", "grid"); put("spacing_mm", 4f); put("background_color", "#FFFDE7") })
        assertEquals(listOf("accepted"), harness.outcomes(answer))
        val made = harness.store.documents.first().single { it.name == "Squared" }
        val grid = made.defaultBackground as PageBackground.Grid
        assertEquals(4f, grid.spacingMm)
        assertEquals(0xFFFFFDE7.toInt(), grid.backgroundColor)
        val wrong = kotlin.test.assertFailsWith<AgentToolException> { harness.proposeLibrary(item("create_document") { put("title", "Odd"); put("background", "stripes") }) }
        assertEquals(AgentErrorCode.REFUSED, wrong.code)
        assertFalse(harness.store.documents.first().any { it.name == "Odd" })
    }
}
