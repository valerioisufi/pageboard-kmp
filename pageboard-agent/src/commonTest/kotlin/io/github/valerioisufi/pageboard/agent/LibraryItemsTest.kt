package io.github.valerioisufi.pageboard.agent

import io.github.valerioisufi.pageboard.model.DrawText
import io.github.valerioisufi.pageboard.model.PageBackground
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.store.InMemoryKeyValueStorage
import io.github.valerioisufi.pageboard.store.KeyValueDocumentStore
import io.github.valerioisufi.pageboard.store.OpenDocuments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The items of a proposal on the library: documents and folders, the trash, and their undo. */
class LibraryItemsTest {

    private class Setup(val store: KeyValueDocumentStore, val documents: OpenDocuments, val agents: ExternalAgents, val connection: String, val documentId: String)

    private suspend fun setup(test: TestScope, scope: CoroutineScope, all: Boolean = true, direct: Boolean = true): Setup {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
        val documents = OpenDocuments(store, StandardTestDispatcher(test.testScheduler), scope)
        val agents = ExternalAgents(documents, AgentEngines(), store)
        val document = store.createDocument("Physics", 210f, 297f, PageBackground.Solid())
        agents.access.setEnabled(true)
        if (all) agents.access.setAllDocuments(true) else agents.access.grant(document.id)
        agents.access.setApplyDirectly(direct)
        return Setup(store, documents, agents, agents.access.connect("test").id, document.id)
    }

    private suspend fun Setup.call(name: String, arguments: JsonObject = JsonObject(emptyMap())): JsonObject =
        assertIs<ToolResult.Value>(agents.tools.call(name, arguments, connection)).json.jsonObject

    private suspend fun Setup.propose(vararg items: JsonObject): JsonObject = call(
        "propose_library",
        buildJsonObject {
            put("title", "Tidy up")
            put("items", JsonArray(items.toList()))
        }
    )

    private suspend fun Setup.refused(vararg items: JsonObject): AgentToolException = assertFailsWith<AgentToolException> { propose(*items) }

    private fun JsonObject.outcomes(): List<String> = getValue("items").jsonArray.map { it.jsonPrimitive.content }

    private inline fun item(kind: String, build: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("kind", kind)
        build()
    }

    private suspend fun Setup.folderNamed(name: String): String = store.folders.first().first { it.name == name }.id

    private suspend fun Setup.folderOf(documentId: String): String? = store.documents.first().first { it.id == documentId }.folderId

    private suspend fun Setup.inTrash(id: String): Boolean = store.trash.first().any { it.id == id }

    private suspend fun Setup.undoLast(): UndoOutcome = agents.undo(agents.activity.batches.value.last().id)

    @Test
    fun aDocumentIsCreatedAllowedAndUndoneToTheTrash() = runTest {
        val setup = setup(this, backgroundScope, all = false)
        val answer = setup.propose(item("create_document") { put("title", "Chemistry") })
        assertEquals(listOf("accepted"), answer.outcomes())
        val created = answer.getValue("created").jsonArray.single().jsonPrimitive.content
        assertEquals("Chemistry", setup.store.documents.first().first { it.id == created }.name)
        assertTrue(setup.agents.access.allows(created))
        assertEquals(null, setup.agents.activity.batches.value.single().documentId)

        assertEquals(UndoOutcome.UNDONE, setup.undoLast())
        assertTrue(setup.inTrash(created))
    }

    @Test
    fun aDocumentWrittenInSinceItWasCreatedIsNotUndone() = runTest {
        val setup = setup(this, backgroundScope)
        val created = setup.propose(item("create_document") { put("title", "Chemistry") }).getValue("created").jsonArray.single().jsonPrimitive.content
        val open = assertNotNull(setup.documents.open(created))
        testScheduler.advanceTimeBy(5)
        open.edit { session ->
            val text = DrawText().apply { text = "Written by the user"; x = 10f; y = 20f; width = 100f; height = 10f; fontSize = 16f }
            session.submit(AddElements.of(session.document.pages[0], listOf(text)))
        }
        open.flush()
        setup.documents.close(open)
        assertEquals(UndoOutcome.CHANGED, setup.undoLast())
        assertTrue(!setup.inTrash(created))
    }

    @Test
    fun aDocumentIsMovedTrashedAndRestoredAndEachIsUndone() = runTest {
        val setup = setup(this, backgroundScope)
        setup.propose(item("create_folder") { put("name", "Year 1") })
        val folder = setup.folderNamed("Year 1")

        setup.propose(item("move_document") { put("document", setup.documentId); put("folder", folder) })
        assertEquals(folder, setup.folderOf(setup.documentId))
        assertEquals(UndoOutcome.UNDONE, setup.undoLast())
        assertEquals(null, setup.folderOf(setup.documentId))

        setup.propose(item("trash_document") { put("document", setup.documentId) })
        assertTrue(setup.inTrash(setup.documentId))
        val listed = setup.call("list_trash").getValue("items").jsonArray.single().jsonObject
        assertEquals("document", listed.getValue("kind").jsonPrimitive.content)
        assertEquals(UndoOutcome.UNDONE, setup.undoLast())
        assertTrue(!setup.inTrash(setup.documentId))

        setup.propose(item("trash_document") { put("document", setup.documentId) })
        setup.propose(item("restore") { put("item", setup.documentId) })
        assertTrue(!setup.inTrash(setup.documentId))
        assertEquals(UndoOutcome.UNDONE, setup.undoLast())
        assertTrue(setup.inTrash(setup.documentId))
    }

    @Test
    fun foldersAreCreatedRenamedMovedAndTrashedWithWhatTheyHold() = runTest {
        val setup = setup(this, backgroundScope)
        setup.propose(item("create_folder") { put("name", "Courses") }, item("create_folder") { put("name", "Physics") })
        val courses = setup.folderNamed("Courses")
        val physics = setup.folderNamed("Physics")
        val folders = setup.call("list_folders").getValue("folders").jsonArray
        assertEquals(2, folders.size)

        setup.propose(item("move_folder") { put("folder", physics); put("parent", courses) }, item("rename_folder") { put("folder", courses); put("name", "Year 1") })
        val moved = setup.store.folders.first()
        assertEquals(courses, moved.first { it.id == physics }.parentId)
        assertEquals("Year 1", moved.first { it.id == courses }.name)
        assertEquals(UndoOutcome.UNDONE, setup.undoLast())
        val back = setup.store.folders.first()
        assertEquals(null, back.first { it.id == physics }.parentId)
        assertEquals("Courses", back.first { it.id == courses }.name)

        setup.propose(item("move_document") { put("document", setup.documentId); put("folder", courses) })
        setup.propose(item("trash_folder") { put("folder", courses) })
        assertTrue(setup.inTrash(courses))
        assertTrue(setup.store.documents.first().none { it.id == setup.documentId })
        assertEquals(UndoOutcome.UNDONE, setup.undoLast())
        assertEquals(courses, setup.folderOf(setup.documentId))
    }

    @Test
    fun aFolderFilledSinceItWasCreatedIsNotUndone() = runTest {
        val setup = setup(this, backgroundScope)
        setup.propose(item("create_folder") { put("name", "Courses") })
        val batch = setup.agents.activity.batches.value.last().id
        setup.store.moveDocument(setup.documentId, setup.folderNamed("Courses"))
        assertEquals(UndoOutcome.CHANGED, setup.agents.undo(batch))
        assertTrue(!setup.inTrash(setup.folderNamed("Courses")))
    }

    @Test
    fun whatCannotBeDoneAsTheLibraryIsIsStale() = runTest {
        val setup = setup(this, backgroundScope)
        val answer = setup.propose(
            item("create_document") { put("title", "Physics") },
            item("create_folder") { put("name", "Courses") },
            item("create_folder") { put("name", "Courses") }
        )
        assertEquals(listOf("stale", "accepted", "stale"), answer.outcomes())
    }

    @Test
    fun foldersNeedEveryDocumentAllowed() = runTest {
        val setup = setup(this, backgroundScope, all = false)
        assertEquals(AgentErrorCode.NOT_GRANTED, assertFailsWith<AgentToolException> { setup.call("list_folders") }.code)
        assertEquals(AgentErrorCode.NOT_GRANTED, setup.refused(item("create_folder") { put("name", "Courses") }).code)
        val other = setup.store.createDocument("Chemistry", 210f, 297f, PageBackground.Solid())
        assertEquals(AgentErrorCode.NOT_GRANTED, setup.refused(item("trash_document") { put("document", other.id) }).code)
        // What is in the trash and not allowed is not listed
        setup.store.trashDocument(other.id)
        assertTrue(setup.call("list_trash").getValue("items").jsonArray.isEmpty())
        assertEquals(AgentErrorCode.NOT_GRANTED, setup.refused(item("restore") { put("item", other.id) }).code)
    }

    @Test
    fun aProposalWaitsForTheReviewUnlessAppliedDirectly() = runTest {
        val setup = setup(this, backgroundScope, direct = false)
        val id = setup.propose(item("trash_document") { put("document", setup.documentId) }, item("create_folder") { put("name", "Courses") })
            .getValue("proposal").jsonPrimitive.content
        assertTrue(setup.agents.library.find(id)!!.isWaiting)
        assertTrue(!setup.inTrash(setup.documentId))

        setup.agents.decideLibrary(id, setOf(1))
        assertEquals(listOf(ItemOutcome.REJECTED, ItemOutcome.ACCEPTED), setup.agents.library.find(id)!!.outcomes)
        assertTrue(!setup.inTrash(setup.documentId))
        assertEquals(1, setup.store.folders.first().size)
        val status = setup.call("proposal_status", buildJsonObject { put("proposal", id) })
        assertEquals(listOf("rejected", "accepted"), status.outcomes())
    }

    @Test
    fun theTrashTakesALimitedNumberOfItems() = runTest {
        val setup = setup(this, backgroundScope)
        val ids = (1..21).map { setup.store.createDocument("Document $it", 210f, 297f, PageBackground.Solid()).id }
        val refused = setup.refused(*ids.map { id -> item("trash_document") { put("document", id) } }.toTypedArray())
        assertEquals(AgentErrorCode.REFUSED, refused.code)
        assertTrue(ids.none { setup.inTrash(it) })

        var now = 0L
        val limiter = TrashLimiter(perBatch = 20, perMinute = 50, clock = { now })
        limiter.take(20)
        limiter.take(20)
        assertFailsWith<AgentToolException> { limiter.take(20) }
        now = 61_000L
        limiter.take(20)
    }

    @Test
    fun nothingIsDeletedForGood() = runTest {
        val setup = setup(this, backgroundScope)
        val names = setup.agents.tools.tools.map { it.name }
        assertTrue(names.none { "delete" in it || "purge" in it || "empty" in it }, "$names")
        assertEquals(
            AgentErrorCode.BAD_REQUEST,
            setup.refused(item("delete_document") { put("document", setup.documentId) }).code
        )
        setup.propose(item("trash_document") { put("document", setup.documentId) })
        assertTrue(setup.store.trash.first().single().documentIds.contains(setup.documentId))
    }
}
