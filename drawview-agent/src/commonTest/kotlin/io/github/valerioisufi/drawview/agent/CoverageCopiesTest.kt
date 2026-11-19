package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.agent.AgentHarness.Companion.item
import io.github.valerioisufi.drawview.model.DrawImage
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.BoxPlacement
import io.github.valerioisufi.drawview.model.change.PlaceElements
import io.github.valerioisufi.drawview.store.OpenDocument
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Elements and pages copied between documents, PDF files and archives brought in, documents duplicated (`docs/plans/agent-coverage.md`, phase 5). */
class CoverageCopiesTest {

    private class FakeFiles(val archive: (suspend (String?) -> String?)? = null) : AgentFiles {
        val kept = mutableListOf<String>()
        override suspend fun readPdf(path: String): AgentPdf? = if (path.endsWith(".pdf")) AgentPdf(path, "hash", listOf(210f to 297f, 297f to 210f)) else null
        override suspend fun keepPdf(path: String): AgentPdf? = readPdf(path)?.let { AgentPdf("/kept/slides.pdf", it.hash, it.pageSizesMm) }?.also { kept += path }
        override suspend fun importArchive(path: String, parentId: String?): String? = if (path.endsWith(".drawview")) archive?.invoke(parentId) else null
    }

    /** A second document, allowed, with an image and a text on its page. */
    private suspend fun AgentHarness.other(allowed: Boolean = true): Pair<OpenDocument, Pair<DrawImage, DrawText>> {
        val document = store.createDocument("Chemistry", 210f, 297f, PageBackground.Solid())
        if (allowed) agents.access.grant(document.id)
        val open = assertNotNull(documents.open(document.id))
        val added = open.edit { session ->
            val image = DrawImage().apply { resourceId = "picture"; x = 30f; y = 40f; width = 50f; height = 40f }
            val text = DrawText().apply { text = "Moles"; x = 30f; y = 100f; width = 80f; height = 10f; fontSize = 16f }
            session.submit(AddElements.of(session.document.pages[0], listOf(image, text)))
            image to text
        }
        return open to added
    }

    @Test
    fun elementsAreCopiedFromAnotherDocumentUnderNewIds() = runTest {
        val harness = AgentHarness.of(this, backgroundScope)
        val (other, added) = harness.other()
        val (image, text) = added
        val source = other.document.pages[0]
        val refs = harness.refs(image.id, text.id, page = source.id, document = other.document.id)
        val answer = harness.propose(
            item("copy_elements") {
                put("from_document", other.document.id); put("from_page", source.id); put("elements", refs); put("page", harness.page.id); put("dx", 10f)
            }
        )
        assertEquals(listOf("accepted"), harness.outcomes(answer))
        val copied = harness.page.images.single()
        assertNotEquals(image.id, copied.id)
        assertEquals(40f, copied.x, 0.01f)
        assertEquals("Moles", harness.page.texts.single().text)
        // The source is as it was
        assertEquals(listOf(image.id), source.images.map { it.id })
        val status = harness.call("proposal_status", kotlinx.serialization.json.buildJsonObject { put("proposal", answer.getValue("proposal")) })
        assertEquals(2, (status.getValue("changed") as kotlinx.serialization.json.JsonArray).let { (it[0] as kotlinx.serialization.json.JsonArray).size })

        assertEquals(UndoOutcome.UNDONE, harness.undoLast())
        assertTrue(harness.page.images.isEmpty() && harness.page.texts.isEmpty())
    }

    @Test
    fun aCopyNeedsItsDocumentAllowedAndItsElementsAsRead() = runTest {
        val harness = AgentHarness.of(this, backgroundScope, direct = false)
        val (hidden, hiddenAdded) = harness.other(allowed = false)
        val refused = harness.refused(
            item("copy_elements") {
                put("from_document", hidden.document.id); put("from_page", hidden.document.pages[0].id)
                putJsonArray("elements") { add(kotlinx.serialization.json.buildJsonObject { put("id", hiddenAdded.first.id); put("base", "any") }) }
                put("page", harness.page.id)
            }
        )
        assertEquals(AgentErrorCode.NOT_GRANTED, refused.code)

        val (other, added) = harness.other()
        val source = other.document.pages[0]
        val refs = harness.refs(added.first.id, page = source.id, document = other.document.id)
        val id = harness.propose(
            item("copy_elements") { put("from_document", other.document.id); put("from_page", source.id); put("elements", refs); put("page", harness.page.id) }
        ).getValue("proposal").jsonPrimitive.content
        // The user moves it in its document meanwhile
        other.edit { session -> session.submit(PlaceElements(source.id, listOf(BoxPlacement(added.first.id, 60f, 40f, 50f, 40f, 0f)))) }
        harness.agents.decide(id, setOf(0))
        assertEquals(ItemOutcome.STALE, harness.agents.proposals.find(id)!!.entries.single().outcome)
        assertTrue(harness.page.images.isEmpty())

        val outside = harness.refused(
            item("copy_elements") {
                put("from_document", other.document.id); put("from_page", source.id); put("elements", harness.refs(added.first.id, page = source.id, document = other.document.id))
                put("page", harness.page.id); put("dx", 200f)
            }
        )
        assertEquals(AgentErrorCode.REFUSED, outside.code)
    }

    @Test
    fun pagesAreCopiedAfterThePageAsked() = runTest {
        val harness = AgentHarness.of(this, backgroundScope)
        val (other, _) = harness.other()
        val first = harness.page.id
        val other2 = harness.converging {
            harness.propose(item("copy_pages") { put("from_document", other.document.id); putJsonArray("pages") { add(JsonPrimitive(other.document.pages[0].id)) }; put("after", first) })
        }
        val pages = harness.open.document.pages
        assertEquals(2, pages.size)
        assertEquals(first, pages[0].id)
        assertNotEquals(other.document.pages[0].id, pages[1].id)
        assertEquals("Moles", pages[1].texts.single().text)
        assertEquals(2, other2.document.pages.size)
        assertEquals(UndoOutcome.UNDONE, harness.undoLast())
        assertEquals(1, harness.open.document.pages.size)
    }

    @Test
    fun aPdfFileAddsAPageForEachOfItsPages() = runTest {
        val files = FakeFiles()
        val harness = AgentHarness.of(this, backgroundScope, AgentEngines(files = files))
        val answer = harness.propose(item("import_pdf") { put("path", "/home/slides.pdf"); put("after", harness.page.id) })
        assertEquals(listOf("accepted"), harness.outcomes(answer))
        assertEquals(listOf("/home/slides.pdf"), files.kept)
        val pages = harness.open.document.pages
        assertEquals(3, pages.size)
        assertEquals(listOf(0, 1), pages.drop(1).map { it.pdfs.single().pdfPageIndex })
        assertEquals(297f, pages[2].widthMm)
        val resource = assertNotNull(harness.open.document.findResource(pages[1].pdfs.single().resourceId))
        assertEquals("/kept/slides.pdf", resource.path)
        assertEquals(UndoOutcome.UNDONE, harness.undoLast())
        assertEquals(1, harness.open.document.pages.size)

        assertEquals(AgentErrorCode.REFUSED, harness.refused(item("import_pdf") { put("path", "/home/notes.txt") }).code)
        val without = AgentHarness.of(this, backgroundScope)
        assertEquals(AgentErrorCode.UNAVAILABLE, without.refused(item("import_pdf") { put("path", "/home/slides.pdf") }).code)
    }

    @Test
    fun aDocumentIsDuplicatedWithAllItsPagesAndUndoneToTheTrash() = runTest {
        val harness = AgentHarness.of(this, backgroundScope)
        harness.open.edit { session ->
            val text = DrawText().apply { text = "Gauss"; x = 10f; y = 20f; width = 100f; height = 10f; fontSize = 16f }
            session.submit(AddElements.of(session.document.pages[0], listOf(text)))
        }
        harness.open.flush()
        val answer = harness.proposeLibrary(item("duplicate_document") { put("document", harness.documentId) })
        assertEquals(listOf("accepted"), harness.outcomes(answer))
        val copy = harness.store.documents.first().single { it.name == "Physics (2)" }
        val open = assertNotNull(harness.documents.open(copy.id))
        assertEquals(listOf("Gauss"), open.document.pages.single().texts.map { it.text })
        assertNotEquals(harness.page.id, open.document.pages.single().id)
        harness.documents.close(open)
        assertTrue(harness.agents.access.allows(copy.id))

        assertEquals(UndoOutcome.UNDONE, harness.undoLast())
        assertTrue(harness.store.trash.first().any { it.id == copy.id })

        val named = harness.proposeLibrary(item("duplicate_document") { put("document", harness.documentId); put("title", "Physics") })
        assertEquals(listOf("stale"), harness.outcomes(named))
    }

    @Test
    fun anArchiveComesInAFolderOfItsOwnUndoneToTheTrash() = runTest {
        lateinit var harness: AgentHarness
        val files = FakeFiles(archive = { parent ->
            harness.store.createFolder("Archive", parent)
            val folder = harness.store.folders.first().first { it.name == "Archive" && it.parentId == parent }.id
            harness.store.createDocument("From the archive", 210f, 297f, PageBackground.Solid(), folder)
            folder
        })
        harness = AgentHarness.of(this, backgroundScope, AgentEngines(files = files), all = true)
        val answer = harness.proposeLibrary(item("import_archive") { put("path", "/home/notes.drawview") })
        assertEquals(listOf("accepted"), harness.outcomes(answer))
        val made = harness.store.documents.first().single { it.name == "From the archive" }
        assertNotNull(made.folderId)
        assertEquals(UndoOutcome.UNDONE, harness.undoLast())
        assertTrue(harness.store.trash.first().any { it.id == made.folderId })

        val wrong = harness.proposeLibrary(item("import_archive") { put("path", "/home/notes.zip") })
        assertEquals(listOf("stale"), harness.outcomes(wrong))

        val some = AgentHarness.of(this, backgroundScope, AgentEngines(files = files))
        assertEquals(AgentErrorCode.NOT_GRANTED, assertFailsWith<AgentToolException> { some.proposeLibrary(item("import_archive") { put("path", "/home/notes.drawview") }) }.code)
    }
}
